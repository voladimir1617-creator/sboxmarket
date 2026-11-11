package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Bounce and spam-complaint handling for outgoing mail.
 *
 * Provider: Resend (resend.com). Free tier is 3,000 mails a month, it speaks
 * plain SMTP (so EmailService needs no change: SMTP_HOST=smtp.resend.com,
 * SMTP_USERNAME=resend, SMTP_PASSWORD=the API key) and it posts signed
 * webhooks for {@code email.bounced} and {@code email.complained}.
 *
 * Webhooks are signed the Standard Webhooks / Svix way: headers
 * {@code svix-id}, {@code svix-timestamp} (unix seconds) and
 * {@code svix-signature} ("v1,&lt;base64&gt;", space separated when the secret
 * was rotated). The signed text is {@code id.timestamp.body}, HMAC-SHA256 with
 * the base64 part of the {@code whsec_...} secret. A delivery older or newer
 * than five minutes is refused so a captured one can't be replayed later.
 *
 * What a verified event does, per account whose current address matches:
 *  - email.bounced (permanent): {@code emailSuppressed = BOUNCE}, so nothing
 *    is mailed there any more, security alerts included; nobody can read it.
 *  - email.complained: {@code emailSuppressed = COMPLAINT}, so only security
 *    alerts still go out. A complaint never downgrades an earlier bounce.
 * The user gets one bell notification on the change, so they can fix the
 * address. Entering a new address, or verifying this one again, clears it.
 * Any other event type is acknowledged and ignored.
 */
@Service
@Slf4j
class EmailEventService {

    static final long TOLERANCE_SECONDS = 300
    static final String NOTIFICATION_KIND = 'EMAIL_UNDELIVERABLE'

    @Autowired SteamUserRepository steamUserRepository
    @Autowired NotificationService notificationService

    /** RESEND_WEBHOOK_SECRET, the whsec_... value from the Resend webhook page. */
    @Value('${app.email.webhook-secret:}')
    String webhookSecret

    /** Test seam. */
    java.time.Clock clock = java.time.Clock.systemUTC()

    long nowSeconds() { clock.millis().intdiv(1000L) as long }

    boolean isConfigured() { webhookSecret?.trim() }

    /** True only when one of the v1 signatures matches and the timestamp is fresh. */
    boolean verify(String id, String timestamp, String signatureHeader, String body) {
        if (!isConfigured() || !id || !timestamp || !signatureHeader || body == null) return false
        long ts
        try { ts = Long.parseLong(timestamp.trim()) } catch (NumberFormatException ignored) { return false }
        if (Math.abs(nowSeconds() - ts) > TOLERANCE_SECONDS) return false
        byte[] key
        try {
            def raw = webhookSecret.trim()
            key = Base64.decoder.decode(raw.startsWith('whsec_') ? raw.substring(6) : raw)
        } catch (IllegalArgumentException ignored) {
            log.warn('EmailEventService: app.email.webhook-secret is not valid base64; refusing every webhook')
            return false
        }
        def mac = Mac.getInstance('HmacSHA256')
        mac.init(new SecretKeySpec(key, 'HmacSHA256'))
        byte[] expected = mac.doFinal("${id}.${timestamp}.${body}".toString().getBytes(StandardCharsets.UTF_8))
        signatureHeader.trim().split(/\s+/).any { String part ->
            int comma = part.indexOf(',')
            if (comma < 0 || part.substring(0, comma) != 'v1') return false
            byte[] given
            try { given = Base64.decoder.decode(part.substring(comma + 1)) } catch (IllegalArgumentException ignored) { return false }
            MessageDigest.isEqual(expected, given)
        }
    }

    /** Apply one verified event. Returns how many accounts changed. */
    @Transactional
    int handle(String body) {
        def event
        try { event = new JsonSlurper().parseText(body) } catch (Exception ignored) { return 0 }
        if (!(event instanceof Map)) return 0
        String type = event.type as String
        String mark
        if (type == 'email.bounced') {
            // Resend only sends email.bounced for permanent failures, but if a
            // future payload labels one transient, a later retry may land.
            def bounceType = (event.data instanceof Map && event.data.bounce instanceof Map) ? event.data.bounce.type as String : null
            if (bounceType && bounceType.equalsIgnoreCase('Transient')) return 0
            mark = EmailService.EMAIL_SUPPRESSED_BOUNCE
        } else if (type == 'email.complained') {
            mark = EmailService.EMAIL_SUPPRESSED_COMPLAINT
        } else {
            return 0
        }
        def to = event.data instanceof Map ? event.data.to : null
        List<String> addresses = (to instanceof Collection ? to : [to]).findAll { it instanceof String && it.contains('@') }
                .collect { (it as String).trim().toLowerCase() }.unique()
        int changed = 0
        addresses.each { String addr ->
            steamUserRepository.findByEmailIgnoreCase(addr).each { SteamUser u ->
                if (u.emailSuppressed == mark || u.emailSuppressed == EmailService.EMAIL_SUPPRESSED_BOUNCE) return
                u.emailSuppressed = mark
                steamUserRepository.save(u)
                changed++
                log.info('EmailEventService: {} for user {}', mark, u.id)
                if (mark == EmailService.EMAIL_SUPPRESSED_BOUNCE) {
                    notificationService.safePush(u.id, NOTIFICATION_KIND, 'Our emails to you are bouncing',
                        'Your email provider refused our last message, so we stopped emailing that address. ' +
                        'Enter a working address in Profile to get trade and security emails again.', null, '/profile')
                } else {
                    notificationService.safePush(u.id, NOTIFICATION_KIND, 'Emails paused after a spam report',
                        'Our last email to you was marked as spam, so we only send security alerts now. ' +
                        'Save your address again in Profile to turn trade emails back on.', null, '/profile')
                }
            }
        }
        changed
    }
}
