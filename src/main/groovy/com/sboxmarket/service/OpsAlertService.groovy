package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.ConcurrentHashMap

/**
 * Staff-only operational alerts: the Steam bot going offline / coming back,
 * Stripe webhook failures, and deposits stuck past their Steam hold.
 *
 * Fan-out is the same one the chargeback and fraud-signal paths use:
 * {@code findByRole('ADMIN')}, banned admins dropped, one
 * {@link NotificationService#safePush} bell each, so a bell hiccup never
 * fails the caller.
 *
 * Urgent alerts also go out by email, because a bell only reaches an admin
 * who is signed in and looking. Recipients are ONLY the admin accounts' own
 * verified addresses ({@link EmailService#canSendSecurityTo}), the same gate
 * security alerts use. There is no configured address and no other recipient.
 * Email is throttled to one per alert key per {@link #emailThrottleMs}
 * (default 1 h) and can be switched off with {@code ops.alerts.email-enabled}.
 *
 * Every method swallows its own failures: monitoring must never break the
 * thing it monitors.
 */
@Service
@Slf4j
class OpsAlertService {

    static final String BOT_OFFLINE          = 'OPS_BOT_OFFLINE'
    static final String BOT_ONLINE           = 'OPS_BOT_ONLINE'
    static final String STRIPE_WEBHOOK_ALERT = 'OPS_STRIPE_WEBHOOK'
    static final String STUCK_ESCROW         = 'OPS_STUCK_ESCROW'

    @Autowired(required = false) SteamUserRepository steamUserRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) EmailService emailService

    @Value('${ops.alerts.email-enabled:true}')
    boolean emailEnabled = true

    @Value('${ops.alerts.email-throttle-ms:3600000}')
    long emailThrottleMs = 3600000L

    protected final Map<String, Long> lastEmailAt = new ConcurrentHashMap<>()

    protected long now() { System.currentTimeMillis() }

    /** Active (not banned) ADMIN accounts. Empty on any failure. */
    List<SteamUser> activeAdmins() {
        if (steamUserRepository == null) return []
        try {
            return (steamUserRepository.findByRole('ADMIN') ?: []).findAll { it != null && !Boolean.TRUE.equals(it.banned) }
        } catch (Exception e) {
            log.warn("Ops alert admin lookup failed: ${e.message}")
            return []
        }
    }

    /**
     * Bell every active admin; when {@code urgent}, also email them (throttled
     * per {@code alertKey}). Returns the number of bells that landed, so a
     * caller that dedupes can retry next tick when nothing was delivered.
     */
    int alertAdmins(String alertKey, String kind, String title, String body, String path, boolean urgent) {
        def admins = activeAdmins()
        if (admins.isEmpty()) {
            log.warn("Ops alert ${kind} has no admin to go to: ${title}")
            return 0
        }
        int bells = 0
        if (notificationService != null) {
            admins.each { admin ->
                try {
                    if (notificationService.safePush(admin.id, kind, title, body, null, path) != null) bells++
                } catch (Exception e) {
                    log.warn("Ops alert ${kind} bell failed for admin=${admin.id}: ${e.message}")
                }
            }
        }
        if (urgent) emailTo(alertKey ?: kind, title, body, admins)
        return bells
    }

    /** Email-only path, for alerts that already rang their own bell (the
     *  HIGH fraud sweeper). Same recipients, gate and throttle. Returns the
     *  number of emails handed to {@link EmailService#send}. */
    int emailAdmins(String alertKey, String title, String body) {
        return emailTo(alertKey, title, body, null)
    }

    protected int emailTo(String alertKey, String title, String body, List<SteamUser> knownAdmins) {
        if (!emailEnabled || emailService == null || !alertKey) return 0
        try {
            long t = now()
            // Claim the throttle slot before sending so two threads racing on
            // the same key send once.
            boolean[] claimed = [false] as boolean[]
            lastEmailAt.compute(alertKey) { k, prev ->
                if (prev != null && t - prev < emailThrottleMs) return prev
                claimed[0] = true
                return t
            }
            if (!claimed[0]) return 0
            def admins = knownAdmins != null ? knownAdmins : activeAdmins()
            int sent = 0
            String subject = "[SkinBox ops] ${title ?: alertKey}".toString()
            String text = (body ?: title ?: alertKey) + "\n\nThis is a staff-only operations alert sent to admin accounts. " +
                    "Open the admin panel for details."
            admins.each { admin ->
                try {
                    if (emailService.canSendSecurityTo(admin)) {
                        emailService.send(admin.email, subject, text)
                        sent++
                    }
                } catch (Exception e) {
                    log.warn("Ops alert email failed for admin=${admin?.id}: ${e.message}")
                }
            }
            return sent
        } catch (Exception e) {
            log.warn("Ops alert email path failed for ${alertKey}: ${e.message}")
            return 0
        }
    }
}
