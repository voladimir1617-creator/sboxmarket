package com.sboxmarket.service

import com.sboxmarket.model.AuditLog
import com.sboxmarket.repository.AuditLogRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Read-only analysis layer on top of {@link AuditLog}. Produces a list of
 * suspicious-activity signals for the admin panel — no new table, no
 * scheduling, just a rollup query the admin UI can poll.
 *
 * Signals currently generated:
 *
 *   1. **MULTIPLE_IPS_PER_USER** — same user acting from >= 3 distinct
 *      client IPs in the last 24h. Common for legitimate mobile-hotspot
 *      users but also the classic "account takeover" tell.
 *
 *   2. **SHARED_IP_MULTIPLE_USERS** — same IP acting as different users
 *      in the last 24h. Household sharing is normal; 5+ accounts from one
 *      IP within an hour usually isn't.
 *
 *   3. **RAPID_WITHDRAW_AFTER_DEPOSIT** — a user deposits then requests
 *      withdrawal within 15 minutes. A classic card-testing / refund
 *      fraud pattern.
 *
 *   4. **HIGH_VELOCITY_PURCHASES** — same user buying >= 10 listings in
 *      a 10-minute window. Either a reseller script or a compromised
 *      account draining balance fast.
 *
 * Each signal returns a structured map with `type`, `severity` (LOW/MED/
 * HIGH), `userId`, `ip`, `count`, and a human `summary`. The admin tab
 * renders this list directly.
 *
 * IMPORTANT: this is a best-effort heuristic layer. It triages — it
 * doesn't judge. Admin staff still make the ban/unban decision manually.
 */
@Service
@Slf4j
class FraudAnalysisService {

    // Tunables — kept here instead of config so a future change is a code
    // review instead of a silent env-var edit. Adjust and ship.
    private static final long  WINDOW_24H_MS      = 24L * 60L * 60L * 1000L
    private static final int   IPS_PER_USER_TRIP  = 3
    private static final int   USERS_PER_IP_TRIP  = 5
    private static final long  WITHDRAW_AFTER_DEPOSIT_MS = 15L * 60L * 1000L
    private static final int   PURCHASE_VELOCITY_TRIP    = 10
    private static final long  PURCHASE_VELOCITY_WINDOW  = 10L * 60L * 1000L

    @Autowired AuditLogRepository auditLogRepository

    @Transactional(readOnly = true)
    List<Map> computeSignals() {
        def since = System.currentTimeMillis() - WINDOW_24H_MS
        def rows = auditLogRepository.since(since)
        if (!rows) return []

        def signals = []
        signals.addAll(detectMultipleIpsPerUser(rows))
        signals.addAll(detectSharedIpAcrossUsers(rows))
        signals.addAll(detectRapidWithdrawAfterDeposit(rows))
        signals.addAll(detectHighVelocityPurchases(rows))
        // Sort HIGH > MED > LOW, then newest-first within a severity bucket.
        // IMPORTANT: Groovy's `?:` treats 0 as falsy, so `sev[HIGH] ?: 9`
        // would turn rank 0 into 9 and break the order. Use the Map-as-
        // function form which returns null for misses and handle that
        // explicitly.
        def sev = [HIGH: 0, MED: 1, LOW: 2]
        signals.sort { a, b ->
            def ra = sev.containsKey(a.severity) ? sev[a.severity] : 9
            def rb = sev.containsKey(b.severity) ? sev[b.severity] : 9
            def c = ra <=> rb
            c != 0 ? c : (b.createdAt ?: 0L) <=> (a.createdAt ?: 0L)
        }
        signals
    }

    // ── Signal 1: one user, many IPs ──────────────────────────────
    private List<Map> detectMultipleIpsPerUser(List<AuditLog> rows) {
        def byUser = rows.findAll { it.actorUserId && it.ipAddress }
                         .groupBy { it.actorUserId }
        def out = []
        byUser.each { uid, list ->
            def ips = list.collect { it.ipAddress }.toSet()
            if (ips.size() >= IPS_PER_USER_TRIP) {
                def latest = list*.createdAt.max()
                out << [
                    type:      'MULTIPLE_IPS_PER_USER',
                    severity:  ips.size() >= 6 ? 'HIGH' : 'MED',
                    userId:    uid,
                    userName:  list[0].actorName,
                    count:     ips.size(),
                    ip:        ips.join(', ').take(200),
                    summary:   "User ${list[0].actorName ?: uid} acted from ${ips.size()} distinct IPs in 24h",
                    createdAt: latest
                ]
            }
        }
        out
    }

    // ── Signal 2: one IP, many users ──────────────────────────────
    private List<Map> detectSharedIpAcrossUsers(List<AuditLog> rows) {
        def byIp = rows.findAll { it.actorUserId && it.ipAddress }
                       .groupBy { it.ipAddress }
        def out = []
        byIp.each { ip, list ->
            def users = list.collect { it.actorUserId }.toSet()
            if (users.size() >= USERS_PER_IP_TRIP) {
                def latest = list*.createdAt.max()
                out << [
                    type:      'SHARED_IP_MULTIPLE_USERS',
                    severity:  users.size() >= 10 ? 'HIGH' : 'MED',
                    ip:        ip,
                    count:     users.size(),
                    userId:    null,
                    summary:   "${users.size()} different user accounts acted from IP ${ip} in 24h",
                    createdAt: latest
                ]
            }
        }
        out
    }

    // ── Signal 3: deposit -> withdraw within 15m ──────────────────
    private List<Map> detectRapidWithdrawAfterDeposit(List<AuditLog> rows) {
        def deposits  = rows.findAll { it.eventType == AuditService.DEPOSIT_COMPLETE }
        def withdraws = rows.findAll { it.eventType == AuditService.WITHDRAW_REQUESTED }
        def out = []
        withdraws.each { w ->
            def matchedDeposit = deposits.find {
                it.actorUserId == w.actorUserId &&
                it.createdAt   <= w.createdAt &&
                (w.createdAt - it.createdAt) <= WITHDRAW_AFTER_DEPOSIT_MS
            }
            if (matchedDeposit) {
                def gapMin = ((w.createdAt - matchedDeposit.createdAt) / 60000L) as long
                out << [
                    type:      'RAPID_WITHDRAW_AFTER_DEPOSIT',
                    severity:  'HIGH',
                    userId:    w.actorUserId,
                    userName:  w.actorName,
                    ip:        w.ipAddress,
                    count:     gapMin,
                    summary:   "User ${w.actorName ?: w.actorUserId} requested withdrawal ${gapMin} min after deposit — potential card-testing",
                    createdAt: w.createdAt
                ]
            }
        }
        out
    }

    // ── Signal 4: high-velocity purchases ─────────────────────────
    private List<Map> detectHighVelocityPurchases(List<AuditLog> rows) {
        def purchases = rows.findAll { it.eventType == AuditService.LISTING_PURCHASED && it.actorUserId }
        def byUser = purchases.groupBy { it.actorUserId }
        def out = []
        byUser.each { uid, list ->
            def sorted = list.sort { it.createdAt }
            // Sliding window: for each purchase, count the number of purchases
            // within the trailing PURCHASE_VELOCITY_WINDOW ms.
            int peak = 0
            long peakAt = 0
            sorted.eachWithIndex { row, i ->
                def windowStart = row.createdAt - PURCHASE_VELOCITY_WINDOW
                int count = sorted[0..i].count { it.createdAt >= windowStart }
                if (count > peak) { peak = count; peakAt = row.createdAt }
            }
            if (peak >= PURCHASE_VELOCITY_TRIP) {
                out << [
                    type:      'HIGH_VELOCITY_PURCHASES',
                    severity:  peak >= 20 ? 'HIGH' : 'MED',
                    userId:    uid,
                    userName:  sorted[0].actorName,
                    ip:        sorted[-1].ipAddress,
                    count:     peak,
                    summary:   "User ${sorted[0].actorName ?: uid} purchased ${peak} listings in 10 min — bot or compromised account",
                    createdAt: peakAt
                ]
            }
        }
        out
    }
}
