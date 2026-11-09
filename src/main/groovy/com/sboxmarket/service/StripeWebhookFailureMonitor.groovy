package com.sboxmarket.service

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.atomic.AtomicLong

/**
 * Observe-only counters for Stripe webhook deliveries that went wrong, plus a
 * staff alert when they pile up.
 *
 * Two outcomes are counted, both decided by {@code StripeWebhookController},
 * which this class never influences (status codes, retries and money handling
 * stay exactly as they are):
 *   - {@link #BAD_SIGNATURE}: rejected with 400. Stripe's docs name the wrong
 *     endpoint secret as the most common cause, which after a key paste looks
 *     exactly like "no events yet" on the admin tile.
 *   - {@link #PERMANENT_ACK}: a permanent failure ACKed with 200 so Stripe
 *     stops retrying. That event will never be delivered again, so a person
 *     has to reconcile it by hand.
 *
 * Counts are exposed as Micrometer counters
 * ({@code skinbox.stripe.webhook.failures}, tag {@code reason}) and on the
 * admin Health tile via {@link #snapshot()}. When a reason reaches its
 * threshold inside the window (defaults: 3 bad signatures or 1 permanent ACK
 * in 15 minutes) one urgent admin alert goes out; the next one for that
 * reason waits at least a full window.
 *
 * Totals are per JVM and reset on restart; the Micrometer counters are the
 * durable view for anyone scraping the private actuator port.
 */
@Service
@Slf4j
class StripeWebhookFailureMonitor {

    static final String BAD_SIGNATURE = 'bad_signature'
    static final String PERMANENT_ACK = 'permanent_ack'
    static final String METER_NAME = 'skinbox.stripe.webhook.failures'
    static final int MAX_TRACKED = 10_000

    @Autowired(required = false) OpsAlertService opsAlertService
    @Autowired(required = false) MeterRegistry meterRegistry

    @Value('${ops.alerts.stripe-webhook.window-ms:900000}')
    long windowMs = 900000L

    @Value('${ops.alerts.stripe-webhook.bad-signature-threshold:3}')
    int badSignatureThreshold = 3

    @Value('${ops.alerts.stripe-webhook.permanent-ack-threshold:1}')
    int permanentAckThreshold = 1

    protected final Map<String, AtomicLong> totals = [(BAD_SIGNATURE): new AtomicLong(), (PERMANENT_ACK): new AtomicLong()]
    protected final Map<String, ArrayDeque<Long>> recent = [(BAD_SIGNATURE): new ArrayDeque<Long>(), (PERMANENT_ACK): new ArrayDeque<Long>()]
    protected final Map<String, Long> lastAlertAt = [:]
    protected final Map<String, Long> lastAt = [:]
    protected volatile String lastPermanentEventId
    protected volatile String lastPermanentEventType

    protected long now() { System.currentTimeMillis() }

    void recordBadSignature() {
        record(BAD_SIGNATURE, null, null)
    }

    /** {@code payload} is the raw body; it already passed signature
     *  verification on this path, and only a well-formed event id and type are
     *  read out of it, for the alert. */
    void recordPermanentAck(String payload) {
        String id = null
        String type = null
        try {
            if (payload != null && payload.length() <= 1_000_000) {
                def parsed = new JsonSlurper().parseText(payload)
                if (parsed instanceof Map) {
                    String rawId = parsed.id instanceof String ? parsed.id : null
                    String rawType = parsed.type instanceof String ? parsed.type : null
                    if (rawId && rawId ==~ /evt_[A-Za-z0-9]{1,64}/) id = rawId
                    if (rawType && rawType ==~ /[a-z0-9_.]{1,80}/) type = rawType
                }
            }
        } catch (Exception ignore) { /* counted without an id */ }
        record(PERMANENT_ACK, id, type)
    }

    protected void record(String reason, String eventId, String eventType) {
        try {
            long t = now()
            totals[reason].incrementAndGet()
            try {
                if (meterRegistry != null) {
                    Counter.builder(METER_NAME).tag('reason', reason)
                            .description('Stripe webhook deliveries rejected (bad signature) or ACKed after a permanent failure')
                            .register(meterRegistry).increment()
                }
            } catch (Exception e) {
                log.debug("Stripe webhook meter update failed: ${e.message}")
            }
            int inWindow
            boolean fire = false
            synchronized (this) {
                lastAt[reason] = t
                if (reason == PERMANENT_ACK) {
                    lastPermanentEventId = eventId
                    lastPermanentEventType = eventType
                }
                def q = recent[reason]
                q.addLast(t)
                // Bounded even under a flood of forged deliveries.
                while (q.size() > MAX_TRACKED) q.removeFirst()
                while (!q.isEmpty() && t - q.peekFirst() > windowMs) q.removeFirst()
                inWindow = q.size()
                int threshold = Math.max(1, reason == BAD_SIGNATURE ? badSignatureThreshold : permanentAckThreshold)
                Long prev = lastAlertAt[reason]
                if (inWindow >= threshold && (prev == null || t - prev >= windowMs)) {
                    lastAlertAt[reason] = t
                    fire = true
                }
            }
            if (fire) alert(reason, inWindow, eventId, eventType)
        } catch (Exception e) {
            log.warn("Stripe webhook failure monitor failed: ${e.message}")
        }
    }

    protected void alert(String reason, int inWindow, String eventId, String eventType) {
        long mins = Math.max(1L, (long) (windowMs / 60000L))
        String title
        String body
        if (reason == BAD_SIGNATURE) {
            title = 'Stripe webhook signature failures'
            body = "${inWindow} Stripe webhook deliveries in the last ${mins} min were rejected for a bad signature. ".toString() +
                    'The most common cause is a wrong webhook signing secret. Check it against the Stripe dashboard; ' +
                    "rejected events show as failed in Stripe's Event deliveries."
        } else {
            title = 'Stripe webhook event dropped'
            body = "${inWindow} Stripe webhook event(s) in the last ${mins} min failed permanently and were acknowledged, ".toString() +
                    'so Stripe will not retry them. ' +
                    (eventId ? "Latest: ${eventId}${eventType ? ' (' + eventType + ')' : ''}. ".toString() : '') +
                    'Reconcile by hand; the server log has the error.'
        }
        log.error("Stripe webhook alert: ${title}: ${inWindow} in ${mins} min")
        opsAlertService?.alertAdmins(OpsAlertService.STRIPE_WEBHOOK_ALERT + ':' + reason,
                OpsAlertService.STRIPE_WEBHOOK_ALERT, title, body, '/admin?tab=health', true)
    }

    long total(String reason) { totals[reason]?.get() ?: 0L }

    /** For the admin Health tile. */
    Map snapshot() {
        long t = now()
        synchronized (this) {
            def count = { String r -> recent[r].count { Long at -> t - at <= windowMs } }
            [
                windowMs:               windowMs,
                badSignatureTotal:      total(BAD_SIGNATURE),
                badSignatureInWindow:   count(BAD_SIGNATURE),
                lastBadSignatureAt:     lastAt[BAD_SIGNATURE],
                permanentAckTotal:      total(PERMANENT_ACK),
                permanentAckInWindow:   count(PERMANENT_ACK),
                lastPermanentAckAt:     lastAt[PERMANENT_ACK],
                lastPermanentEventId:   lastPermanentEventId,
                lastPermanentEventType: lastPermanentEventType
            ]
        }
    }
}
