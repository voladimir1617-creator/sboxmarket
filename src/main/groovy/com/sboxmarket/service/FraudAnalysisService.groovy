package com.sboxmarket.service

import com.sboxmarket.model.AuditLog
import com.sboxmarket.repository.AuditLogRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
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
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository
    /** Cluster-wide dedup ledger — wave-112-style multi-pod race claim
     *  for the fraud-signal sweeper. Optional so existing unit tests
     *  that build the service with `new FraudAnalysisService(...)` and
     *  no Spring context continue to work; in that case the per-JVM
     *  {@link #seenSignatures} set is the only gate (single-process
     *  behaviour, identical to the pre-fix posture). When wired (every
     *  prod boot wires it), it becomes the authoritative cross-pod
     *  gate and the in-memory set is a fast-path cache.
     *
     *  Without this, every pod's @Scheduled tick independently re-fans
     *  the same HIGH fraud signal because each pod's in-memory
     *  `seenSignatures` is blind to the other pods'. Two pods = 2×
     *  duplicate "⚠ Fraud signal" bells per admin per HIGH signal,
     *  N pods = N×. */
    @Autowired(required = false) com.sboxmarket.repository.FraudSignalClaimRepository fraudSignalClaimRepository

    /** Signatures of HIGH-severity signals we've already pushed to the
     *  admin bell (batch 507). Without this, the 30-min sweeper would
     *  re-fan the same MULTIPLE_IPS_PER_USER alert every pass until
     *  the audit rows aged past the 24h window — spamming every
     *  admin with duplicate bells.
     *
     *  Signature is (type, userId, ip, bucketedCount) — `count` is
     *  bucketed to power-of-two so a growing count from 6 → 7 → 8 IPs
     *  on the same user doesn't look like three distinct alerts, but
     *  jumping from 8 → 16 (bucket shift) legitimately re-fires because
     *  the attack meaningfully escalated. Capped LinkedHashSet with
     *  FIFO eviction keeps the memory bounded.
     *
     *  Single-pod scope (per-JVM). The wave-112-style cross-pod gate is
     *  {@link #fraudSignalClaimRepository} above; this set just
     *  short-circuits the DB round-trip on signatures this pod has
     *  already handled itself this lifetime. */
    private static final int SEEN_SIG_CAP = 1000
    private final java.util.LinkedHashSet<String> seenSignatures = new java.util.LinkedHashSet<>()

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
        signals.addAll(detectChargebacks(rows))
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
                    createdAt: latest,
                    // Sweeper-signature override (batch 1015 fix). `ip` is the
                    // joined IP-list shown in the admin UI/CSV — its content
                    // changes every time a new IP joins the user's set, so
                    // including it in the dedup signature breaks the bucketed
                    // dedup the sweeper relies on: growing 6→7→8 IPs would
                    // flip the signature on each new IP and fan out a fresh
                    // HIGH bell to every admin even though the bucketed count
                    // is unchanged. The (type, userId, bucketed-count) tuple
                    // is already a sufficient discriminator for a per-user
                    // signal — pin the signature-ip to a stable empty string
                    // so the bucketing actually works for this detector,
                    // matching the documented intent in seenSignatures.
                    signatureIp: ''
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
        // Correlate by actorUserId — so require a non-null actor on BOTH
        // sides. The audit row's actorUserId is genuinely null for some
        // wallet events (the Stripe webhook has no servlet user context),
        // and Groovy's `null == w.actorUserId` is `true` when both are
        // null. Without this filter an unrelated null-actor deposit
        // matches an unrelated null-actor withdrawal and the rule fires a
        // bogus cross-product of HIGH signals on every userbase that has
        // any deposit + withdrawal activity inside the same 15m window.
        def deposits  = rows.findAll { it.eventType == AuditService.DEPOSIT_COMPLETE && it.actorUserId }
        def withdraws = rows.findAll { it.eventType == AuditService.WITHDRAW_REQUESTED && it.actorUserId }
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
                    createdAt: w.createdAt,
                    // Per-withdraw dedup discriminator. Without this, two
                    // distinct deposit→withdraw cycles from the same user
                    // whose gap minutes land in the same power-of-two
                    // bucket (e.g. 5 min and 7 min both bucket to 8) would
                    // collapse to the identical sweeper signature
                    // (RAPID|userId|ip|8|) and only the FIRST cycle would
                    // ever raise an admin alert. The withdraw row id is
                    // unique per event yet stable across the 30-min
                    // sweeps, so each distinct deposit→withdraw cycle
                    // alerts exactly once — mirrors the chargeback dedup.
                    dedupKey:  w.id
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
            // True O(n) sliding window — `lo` is the trailing edge of the
            // PURCHASE_VELOCITY_WINDOW and only ever advances, so the count
            // for each purchase is a pointer subtraction. The previous code
            // claimed "sliding window" but re-scanned sorted[0..i] for
            // every i — O(n²) over an unbounded 24h audit-row set, which
            // burned CPU on the synchronous admin Fraud-tab call. Produces
            // identical peak counts, just without the quadratic blowup.
            int peak = 0
            long peakAt = 0
            int lo = 0
            sorted.eachWithIndex { row, i ->
                long windowStart = row.createdAt - PURCHASE_VELOCITY_WINDOW
                while (sorted[lo].createdAt < windowStart) lo++
                int count = i - lo + 1
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

    // ── Signal 5: chargeback opened in the last 24h ──────────────
    // Every chargeback is already flagged by the webhook handler
    // (batch 461) — but the admin fraud rollup is where staff expect
    // to see high-severity signals grouped together. A chargeback on
    // a wallet that's also hitting the velocity or multi-IP signals
    // is a correlated pattern the admin needs to see at once.
    private List<Map> detectChargebacks(List<AuditLog> rows) {
        def chargebacks = rows.findAll { it.eventType == AuditService.CHARGEBACK_OPENED }
        chargebacks.collect { cb ->
            [
                type:      'CHARGEBACK_IN_WINDOW',
                severity:  'HIGH',
                userId:    cb.subjectUserId,
                userName:  cb.subjectName,
                ip:        cb.ipAddress,
                count:     1L,
                summary:   "Stripe chargeback opened: ${cb.summary ?: 'see audit log'}",
                createdAt: cb.createdAt,
                // Per-chargeback dedup discriminator. The Stripe webhook
                // writes CHARGEBACK_OPENED audit rows with a null
                // subjectUserId AND null ipAddress, so without this every
                // chargeback collapses to the identical sweeper signature
                // (CHARGEBACK_IN_WINDOW|||1) and only the FIRST one in the
                // process lifetime would ever raise an admin alert. The
                // audit row id is unique per chargeback event yet stable
                // across the 30-min sweeps, so each distinct chargeback
                // alerts exactly once. (2026-05-20)
                dedupKey:  cb.id
            ]
        }
    }

    /**
     * Scheduled fraud-signal sweeper (batch 507). Fan out HIGH-severity
     * signals to every admin's bell so a pattern is caught the moment
     * it crosses the HIGH threshold, instead of waiting for someone to
     * refresh the Fraud tab. Signatures are deduped so the same attack
     * doesn't spam the bell every pass.
     *
     * Bucketing: signal `count` is rounded to the nearest power of two
     * before hashing. A user's IP count going 6 → 7 → 8 is one signal
     * (all fall in the [6,8] bucket); a jump to 16 is a fresh alert
     * because the attack meaningfully escalated. Keeps the signature
     * set bounded even if the same attacker slowly ratchets up.
     *
     * Only fires when there are admins configured — `findByRole` is
     * cheap via the role index but we still skip it when we have
     * nothing to send.
     *
     * NOTE: this method is `@Transactional` (read-write), NOT
     * `readOnly = true`, even though `computeSignals()` + the admin
     * lookup are pure reads. The sweeper's whole job is to WRITE
     * notification rows via `notificationService.push()`. `push` is
     * `@Transactional` with default REQUIRED propagation, so when
     * invoked from inside this method's transaction it JOINS that
     * transaction rather than starting its own. A `readOnly = true`
     * outer transaction puts Hibernate in `FlushMode.MANUAL` and never
     * flushes at commit — so every `notificationRepository.save()`
     * would be silently discarded and no admin would ever receive a
     * fraud bell. Read-write is required for the joined `push` save to
     * actually persist. Matches `TradeService.sweepReviewNudge` /
     * `sweepSlowSellerWarning`, the other sweepers that fan out pushes.
     */
    @Scheduled(fixedDelay = 30L * 60L * 1000L, initialDelay = 10L * 60L * 1000L)
    @Transactional
    void sweepAndPushFraudSignals() {
        if (notificationService == null || steamUserRepository == null) return
        List<Map> signals
        try {
            signals = computeSignals() ?: []
        } catch (Exception e) {
            log.warn("Fraud sweeper computeSignals failed: ${e.message}")
            return
        }
        def highs = signals.findAll { it.severity == 'HIGH' }
        if (highs.isEmpty()) return
        // Resolve admin list once — same shape as chargeback/trade-dispute fan-outs.
        def admins
        try {
            admins = steamUserRepository.findByRole('ADMIN') ?: []
        } catch (Exception e) {
            log.warn("Fraud sweeper admin lookup failed: ${e.message}")
            return
        }
        // Drop banned admins from the fan-out. A banned admin is a
        // demoted/compromised staff account — they shouldn't receive
        // HIGH fraud signal bells (they can't act on them anyway, and
        // a banned-but-still-ADMIN-role account being included in the
        // fan-out is the classic "leaked admin notification stream"
        // problem). BanGuard would reject any action they tried to
        // take in the admin panel; the bell entry is dead-end noise
        // at best and an information leak at worst (an ex-admin
        // shouldn't be tipped off the moment a HIGH signal lands).
        // Matches the banned-recipient filter NotificationService
        // applies to every other multi-target fan-out (PRICE_DROPPED,
        // CART_ITEM_SOLD, AUCTION_ENDING).
        admins = admins.findAll { !Boolean.TRUE.equals(it.banned) }
        if (admins.isEmpty()) return
        int pushed = 0
        highs.each { sig ->
            try {
                def rawCount = (sig.count instanceof Number) ? (sig.count as long) : 1L
                def bucket = bucketize(rawCount)
                // dedupKey (optional) makes the signature unique per
                // event when userId+ip are both null — see detectChargebacks.
                // Detectors that don't set it get a trailing `|` uniformly,
                // so their dedup behaviour is unchanged.
                // `signatureIp` lets a detector override the display `ip`
                // for dedup purposes — needed by MULTIPLE_IPS_PER_USER whose
                // displayed `ip` field is the joined IP list and so
                // necessarily changes as the user's IP set grows. Detectors
                // that don't set it fall through to `sig.ip` unchanged.
                def sigIp = sig.containsKey('signatureIp') ? sig.signatureIp : sig.ip
                def signature = "${sig.type}|${sig.userId ?: ''}|${sigIp ?: ''}|${bucket}|${sig.dedupKey ?: ''}".toString()
                // Dedup check only — DO NOT commit the signature yet.
                // Previously the signature was added pre-fanout; if every
                // admin push then threw (DB blip, transient bell-storage
                // error, etc.) the signature was permanently marked seen
                // and the next 30-min tick — and every subsequent tick
                // for the next 1000 unique signatures of LRU lifetime —
                // skipped re-firing, so a HIGH fraud signal could land
                // ZERO admin bells silently. We now only stamp the
                // signature after at least one push lands.
                synchronized (seenSignatures) {
                    if (seenSignatures.contains(signature)) return
                }
                // Wave-112-style cross-pod claim. The per-JVM
                // `seenSignatures` set above is blind to sibling pods,
                // so without this gate every pod's @Scheduled tick
                // independently fans out the same HIGH fraud signal —
                // every admin receives 2× / 3× / N× duplicate "⚠ Fraud
                // signal" bells. The conditional INSERT (backed by a
                // UNIQUE constraint on `signature`) is the
                // authoritative gate: whichever pod's INSERT lands
                // first persists the row; the loser surfaces a
                // DataIntegrityViolationException and bails BEFORE any
                // notificationService.push fans out. Same shape as
                // WatchlistAlertRepository.claimForFiring.
                //
                // Mirrors the seenSignatures sequencing: we check
                // existence here as a fast path (skip if some pod has
                // already claimed it) but the AUTHORITATIVE write
                // happens AFTER at least one push succeeds, so a fully
                // failed admin fan-out leaves the signature uncommitted
                // and the next tick legitimately retries — same
                // retry-on-failure invariant the FraudAnalysisServiceSpec
                // "sweeper retries the signature next pass when every
                // admin push failed" regression pins.
                if (fraudSignalClaimRepository != null) {
                    try {
                        if (fraudSignalClaimRepository.existsBySignature(signature)) return
                    } catch (Exception e) {
                        // Lookup failed (DB blip) — fail open: fall through
                        // and rely on the per-JVM cache + UNIQUE index to
                        // catch a true duplicate at INSERT time. Better
                        // to occasionally over-notify on a transient DB
                        // hiccup than to silently swallow a HIGH bell.
                        log.warn("Fraud sweeper claim lookup failed for ${signature}: ${e.message}")
                    }
                }
                def summary = (sig.summary ?: sig.type ?: 'fraud signal').toString().take(240)
                def refId = (sig.userId instanceof Number) ? (sig.userId as Long) : null
                boolean anyPushed = false
                admins.each { admin ->
                    try {
                        notificationService.push(admin.id, 'FRAUD_SIGNAL_HIGH',
                            "⚠ Fraud signal: ${sig.type}",
                            summary,
                            refId,
                            '/admin?tab=fraud')
                        anyPushed = true
                    } catch (Exception e) {
                        log.warn("FRAUD_SIGNAL_HIGH push failed for admin=${admin.id}: ${e.message}")
                    }
                }
                if (anyPushed) {
                    synchronized (seenSignatures) {
                        if (seenSignatures.size() >= SEEN_SIG_CAP) {
                            def oldest = seenSignatures.iterator().next()
                            seenSignatures.remove(oldest)
                        }
                        seenSignatures.add(signature)
                    }
                    // Persist the cluster-wide claim AFTER at least one
                    // push has landed, mirroring the in-memory commit
                    // ordering. A UNIQUE-constraint violation here means
                    // a sibling pod beat us to the post-push stamp
                    // between our existence check and this INSERT — that
                    // pod already counts as "owner" of the signature, so
                    // we swallow the violation and move on. Any other
                    // DB error is logged but non-fatal (the bells
                    // already went out — losing the dedup row only
                    // means the next tick might re-fire the same
                    // signal). Mirrors the watchlist sweeper's "save
                    // failures don't block fan-out" posture.
                    if (fraudSignalClaimRepository != null) {
                        try {
                            fraudSignalClaimRepository.save(
                                new com.sboxmarket.model.FraudSignalClaim(
                                    signature: signature,
                                    claimedAt: System.currentTimeMillis()))
                        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                            log.debug("Fraud sweeper signature claimed by sibling pod between check and insert: ${signature}")
                        } catch (Exception e) {
                            log.warn("Fraud sweeper claim insert failed for ${signature}: ${e.message}")
                        }
                    }
                    pushed++
                }
            } catch (Exception e) {
                log.warn("Fraud sweeper row failed: ${e.message}")
            }
        }
        if (pushed > 0) {
            log.info("Fraud sweeper pushed ${pushed} HIGH signal(s) to ${admins.size()} admin(s)")
        }
    }

    /** Daily retention sweep for {@link FraudSignalClaim} rows. The
     *  signature window is 24h (signals only fire on audit rows inside
     *  the {@link #WINDOW_24H_MS} window, so a claim older than that
     *  can never re-trip the same signature anyway). Pruning at 48h
     *  gives a generous safety margin without letting the table grow
     *  unbounded across years of uptime.
     *
     *  Runs at a 15-minute offset so it doesn't collide with the
     *  fraud-signal sweeper itself (10-minute initialDelay) or the
     *  notification retention sweep (30-minute offset). */
    @Scheduled(fixedDelay = 24L * 60L * 60L * 1000L,
               initialDelay = 15L * 60L * 1000L)
    @Transactional
    void sweepOldFraudSignalClaims() {
        if (fraudSignalClaimRepository == null) return
        try {
            def cutoff = System.currentTimeMillis() - (2L * WINDOW_24H_MS)
            int n = fraudSignalClaimRepository.deleteOlderThan(cutoff)
            if (n > 0) log.info("Fraud signal claim retention sweep: deleted ${n} row(s) older than 48h")
        } catch (Exception e) {
            log.warn("Fraud signal claim retention sweep failed: ${e.message}")
        }
    }

    /** Bucketize a count to a power of two — keeps the signature set
     *  from ballooning when the same attacker's count slowly grows.
     *
     *  Overflow guard: once b reaches 2^62 the next left shift would
     *  produce Long.MIN_VALUE and then 0 (the sign bit shifts out),
     *  spinning the `b < n` loop forever for any reasonably large n.
     *  In practice no signal can ever generate a count that large, but
     *  a future detector adding a quadratic count (or a corrupt audit
     *  row with a wild long) would silently wedge the @Scheduled
     *  sweeper thread. Saturate at 2^62 so the function is total. */
    private static final long BUCKET_MAX = 1L << 62
    private static long bucketize(long n) {
        if (n < 1L) return 0L
        if (n >= BUCKET_MAX) return BUCKET_MAX
        long b = 1L
        while (b < n) b <<= 1
        return b
    }
}
