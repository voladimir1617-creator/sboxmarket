package com.sboxmarket.service

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Staff alert for the Steam bot sidecar going offline.
 *
 * Polls the sidecar's {@code /health} through
 * {@link SteamTradeBotService#health()} once a minute while the bot is
 * configured. A check is healthy only when the sidecar answers AND reports
 * {@code loggedIn} and {@code ready}: a sidecar that is up but logged out of
 * Steam (Steam Guard, an expired session) stalls deposits and deliveries just
 * as surely as one that is down.
 *
 * After {@link #failureThreshold} failed checks in a row (default 3, so about
 * three minutes) it logs ERROR and raises ONE urgent admin alert. Nothing more
 * is sent while the outage lasts. The first healthy check after that raises
 * one "back online" alert and re-arms. One outage, two alerts.
 *
 * State is per JVM. The sidecar is bound to 127.0.0.1, so there is one
 * backend beside it. Inert when the bot is unconfigured, so dev and tests do
 * no network I/O.
 */
@Service
@Slf4j
class SteamBotHealthMonitor {

    @Autowired(required = false) SteamTradeBotService steamTradeBotService
    @Autowired(required = false) OpsAlertService opsAlertService

    @Value('${steam.bot.health.enabled:true}')
    boolean monitorEnabled = true

    @Value('${steam.bot.health.failure-threshold:3}')
    int failureThreshold = 3

    // State, read by the admin Health tile through snapshot().
    volatile int consecutiveFailures = 0
    volatile boolean offlineAlerted = false
    volatile Long lastCheckedAt
    volatile Long lastHealthyAt
    volatile Boolean lastLoggedIn
    volatile Boolean lastReady
    volatile String lastError
    volatile Integer lastPendingConfirmations

    protected long now() { System.currentTimeMillis() }

    @Scheduled(initialDelayString = '${steam.bot.health.initial-delay-ms:60000}',
               fixedDelayString = '${steam.bot.health.interval-ms:60000}')
    void checkBotHealth() {
        if (!monitorEnabled) return
        if (steamTradeBotService == null || !steamTradeBotService.enabled) return
        try {
            SteamBotResult r = steamTradeBotService.health()
            long t = now()
            lastCheckedAt = t
            Map raw = r?.raw
            lastLoggedIn = raw?.loggedIn instanceof Boolean ? (Boolean) raw.loggedIn : null
            lastReady = raw?.ready instanceof Boolean ? (Boolean) raw.ready : null
            lastPendingConfirmations = raw?.pendingConfirmations instanceof Number ? ((Number) raw.pendingConfirmations).intValue() : null
            String why = describeFailure(r)
            lastError = (raw?.lastError != null ? String.valueOf(raw.lastError) : (why ?: null))?.take(200)

            if (why == null) {
                lastHealthyAt = t
                int was = consecutiveFailures
                consecutiveFailures = 0
                if (offlineAlerted) {
                    offlineAlerted = false
                    log.warn("Steam bot is back online after ${was} failed health check(s)")
                    opsAlertService?.alertAdmins(OpsAlertService.BOT_ONLINE, OpsAlertService.BOT_ONLINE,
                            'Steam bot back online',
                            'The Steam bot sidecar is answering again and reports logged in and ready. Deposits and deliveries resume on the next poll.',
                            '/admin?tab=health', true)
                }
                return
            }

            consecutiveFailures = consecutiveFailures + 1
            if (consecutiveFailures < Math.max(1, failureThreshold) || offlineAlerted) return
            log.error("Steam bot OFFLINE: ${consecutiveFailures} failed health checks in a row (${why}). " +
                    "Deposits and deliveries are stalled until it recovers.")
            int bells = opsAlertService != null
                    ? opsAlertService.alertAdmins(OpsAlertService.BOT_OFFLINE, OpsAlertService.BOT_OFFLINE,
                        'Steam bot offline',
                        "The Steam bot sidecar has failed ${consecutiveFailures} health checks in a row (${why}). ".toString() +
                        'Deposits and deliveries are stalled. Check the bot process and its Steam login.',
                        '/admin?tab=health', true)
                    : 0
            // Only mark the outage as reported once a bell actually landed, so
            // a failed fan-out is retried next tick instead of lost.
            offlineAlerted = bells > 0
        } catch (Exception e) {
            log.warn("Steam bot health check failed unexpectedly: ${e.message}")
        }
    }

    /** Null when healthy, otherwise a short reason for the alert. */
    static String describeFailure(SteamBotResult r) {
        if (r == null) return 'no answer'
        if (!r.ok) return ("unreachable: " + (r.errorCode ?: 'ERROR')).toString()
        if (!Boolean.TRUE.equals(r.raw?.loggedIn)) return 'not logged in to Steam'
        if (!Boolean.TRUE.equals(r.raw?.ready)) return 'not ready'
        return null
    }

    /** For the admin Health tile. */
    Map snapshot() {
        [
            configured:           steamTradeBotService?.enabled ?: false,
            monitorEnabled:       monitorEnabled,
            online:               lastCheckedAt == null ? null : (consecutiveFailures == 0),
            consecutiveFailures:  consecutiveFailures,
            failureThreshold:     failureThreshold,
            alerting:             offlineAlerted,
            lastCheckedAt:        lastCheckedAt,
            lastHealthyAt:        lastHealthyAt,
            loggedIn:             lastLoggedIn,
            ready:                lastReady,
            lastError:            lastError,
            pendingConfirmations: lastPendingConfirmations
        ]
    }
}
