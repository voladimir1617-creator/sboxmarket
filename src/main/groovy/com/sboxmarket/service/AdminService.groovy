package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Central service for every privileged operation. AdminController is a thin
 * HTTP adapter that delegates to one method per endpoint here.
 *
 * Admin elevation:
 * - Users start as role="USER".
 * - First user whose steamId64 matches the `admin.bootstrap-steam-ids` env var
 *   (comma-separated) is auto-promoted on login — see `promoteBootstrapAdmin`.
 * - Any existing admin can grant/revoke via `grantAdmin` / `revokeAdmin`.
 *
 * Ban semantics: a banned user can still log in (so they can read the ban
 * reason and open a support ticket to appeal) but every state-changing
 * endpoint in the app MUST call `assertNotBanned(userId)` first.
 */
@Service
@Slf4j
class AdminService {

    @Value('${admin.bootstrap-steam-ids:}') String bootstrapIds

    @Autowired SteamUserRepository steamUserRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired ListingRepository listingRepository
    @Autowired(required = false) com.sboxmarket.repository.ListingReportRepository listingReportRepository
    @Autowired ItemRepository itemRepository
    @Autowired OfferRepository offerRepository
    @Autowired BuyOrderRepository buyOrderRepository
    @Autowired BidRepository bidRepository
    @Autowired SupportTicketRepository supportTicketRepository
    @Autowired SupportMessageRepository supportMessageRepository
    @Autowired NotificationService notificationService
    @Autowired(required = false) AuditService auditService
    @Autowired TextSanitizer textSanitizer
    // AdminService used to be a dependency of TradeService (for the ban guard)
    // AND TradeService used to be a dependency of AdminService (for moderation
    // helpers). That cycle forced a @Lazy injection. The cycle is now broken
    // by extracting BanGuard into its own bean — TradeService depends only on
    // BanGuard, so AdminService can depend on TradeService eagerly.
    @Autowired(required = false) TradeService tradeService
    @Autowired(required = false) com.sboxmarket.repository.TradeRepository tradeRepository
    @Autowired(required = false) com.sboxmarket.repository.CartItemRepository cartItemRepository
    @Autowired(required = false) com.sboxmarket.repository.WatchlistAlertRepository watchlistAlertRepository
    @Autowired(required = false) BuyOrderService buyOrderService
    @Autowired(required = false) com.sboxmarket.repository.AuditLogRepository auditLogRepository
    @Autowired(required = false) com.sboxmarket.repository.UserBlockRepository userBlockRepository
    @Autowired(required = false) com.sboxmarket.repository.SellerFollowRepository sellerFollowRepository
    @Autowired(required = false) com.sboxmarket.repository.SavedSearchRepository savedSearchRepository
    @Autowired(required = false) javax.sql.DataSource dataSource
    @Autowired BanGuard banGuard
    @Autowired AdminAuthorization adminAuthorization
    @Autowired(required = false) EmailService emailService
    @Autowired(required = false) com.sboxmarket.repository.ApiKeyRepository apiKeyRepository
    @Autowired(required = false) SteamMarketPriceService steamMarketPriceService
    @Autowired(required = false) @Lazy StripeService stripeService

    // ── Auth guard helpers (delegated to dedicated components) ──────
    // Kept as thin pass-throughs so existing AdminController code and Spock
    // tests that use adminService.requireAdmin / adminService.assertNotBanned
    // still compile without a sweeping rename. New code should inject
    // AdminAuthorization / BanGuard directly.

    void requireAdmin(Long userId) {
        adminAuthorization.requireAdmin(userId)
    }

    void assertNotBanned(Long userId) {
        banGuard.assertNotBanned(userId)
    }

    /** Called by SteamAuthService.upsertUser when a session is established. */
    @Transactional
    void promoteBootstrapAdmin(SteamUser user) {
        if (!bootstrapIds || !user?.steamId64) return
        def ids = bootstrapIds.split(',').collect { it.trim() }.findAll { it }
        if (user.steamId64 in ids && user.role != 'ADMIN') {
            user.role = 'ADMIN'
            steamUserRepository.save(user)
            log.info("Bootstrapped admin: ${user.steamId64}")
        }
    }

    // ── Dashboard stats ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    Map dashboardStats() {
        // Every row in this block is a single SQL aggregate instead of the
        // old `findAll().findAll { ... }` full-table scan. The admin
        // dashboard used to load every wallet, transaction, ticket, and
        // user row over the wire just to count them; now each figure is
        // one indexed COUNT or SUM.
        def now = System.currentTimeMillis()
        def since24h = now - 86_400_000L
        def since48h = now - 2L * 86_400_000L
        def totalEscrow  = walletRepository.sumAllBalances() ?: BigDecimal.ZERO
        def deposits24h  = transactionRepository.sumByTypeSinceCompleted('DEPOSIT', 'COMPLETED', since24h) ?: BigDecimal.ZERO
        def sales24h     = transactionRepository.sumByTypeSinceCompleted('SALE',    'COMPLETED', since24h) ?: BigDecimal.ZERO
        // Prior 24h window [now-48h, now-24h) — lets the dashboard render
        // a "+N% vs yesterday" delta per volume stat. One extra SQL
        // aggregate per stat; still cheap vs the old scan.
        def depositsPrior24h = transactionRepository.sumByTypeInRange('DEPOSIT', 'COMPLETED', since48h, since24h) ?: BigDecimal.ZERO
        def salesPrior24h    = transactionRepository.sumByTypeInRange('SALE',    'COMPLETED', since48h, since24h) ?: BigDecimal.ZERO
        def pendingCount = transactionRepository.countByTypeStatus('WITHDRAW', 'PENDING')
        def pendingAmt   = transactionRepository.sumByTypeStatus('WITHDRAW', 'PENDING') ?: BigDecimal.ZERO
        def newUsers24h  = steamUserRepository.countCreatedSince(since24h)
        def fees24h      = tradeRepository ? (tradeRepository.sumFeesSince(since24h) ?: BigDecimal.ZERO) : BigDecimal.ZERO
        def since7d      = now - 7L * 86_400_000L
        def since30d     = now - 30L * 86_400_000L
        def fees7d       = tradeRepository ? (tradeRepository.sumFeesSince(since7d) ?: BigDecimal.ZERO) : BigDecimal.ZERO
        def fees30d      = tradeRepository ? (tradeRepository.sumFeesSince(since30d) ?: BigDecimal.ZERO) : BigDecimal.ZERO
        // Trade-state probes for the dashboard action cards. DISPUTED
        // is the one that most urgently wants admin attention; PENDING_*
        // counts are there so the dashboard can surface "N trades are
        // sitting in escrow" without clicking into the trades tab.
        def disputedTrades    = tradeRepository ? tradeRepository.countByState('DISPUTED') : 0L
        def pendingSellerAct  = tradeRepository ? tradeRepository.countByState('PENDING_SELLER_ACCEPT') : 0L
        def pendingSellerSend = tradeRepository ? tradeRepository.countByState('PENDING_SELLER_SEND') : 0L
        def pendingBuyerConf  = tradeRepository ? tradeRepository.countByState('PENDING_BUYER_CONFIRM') : 0L
        // Chargeback counters (batch 486) — drive the admin dashboard
        // cards for money-at-risk visibility. activeChargebacks is the
        // count of DISPUTED deposits currently open (each drives a
        // user's withdrawal hold); chargebacks30d is the lifetime
        // rolling 30-day volume for trend spotting.
        def activeChargebacks = transactionRepository.countByTypeStatus('DEPOSIT', 'DISPUTED')
        // Batch 685 — rolling 30-day chargeback count fills the gap the
        // dashboardStats comment has promised since batch 486. Trend
        // signal for ops: a spike here is a card-fraud surge even when
        // activeChargebacks stays low (because staff clear them fast).
        def chargebacks30d = transactionRepository.countByTypeStatusSince('DEPOSIT', 'DISPUTED', since30d)

        [
            users:                    steamUserRepository.count(),
            items:                    itemRepository.count(),
            activeListings:           listingRepository.countActive(),
            totalEscrow:              (totalEscrow as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP),
            deposits24h:              (deposits24h as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP),
            depositsPrior24h:         (depositsPrior24h as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP),
            sales24h:                 (sales24h as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP),
            salesPrior24h:            (salesPrior24h as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP),
            pendingWithdrawals:       pendingCount,
            pendingWithdrawalsAmount: (pendingAmt as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP),
            openTickets:              supportTicketRepository.countOpen(),
            bannedUsers:              steamUserRepository.countBanned(),
            newUsers24h:              newUsers24h,
            fees24h:                  (fees24h as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP),
            fees7d:                   (fees7d as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP),
            fees30d:                  (fees30d as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP),
            disputedTrades:           disputedTrades,
            pendingSellerAccept:      pendingSellerAct,
            pendingSellerSend:        pendingSellerSend,
            pendingBuyerConfirm:      pendingBuyerConf,
            activeChargebacks:        activeChargebacks,
            chargebacks30d:           chargebacks30d
        ]
    }

    /**
     * System-health snapshot pulled from live JVM + Hikari instrumentation.
     * Admin-only — callers already hit requireAdmin upstream. No DB queries
     * here; the figures come from Java MXBeans + the injected DataSource.
     * Cheap enough to poll every few seconds if the admin panel wants to.
     */
    Map systemHealth() {
        def runtime = Runtime.getRuntime()
        def rtMx = java.lang.management.ManagementFactory.runtimeMXBean
        def osMx = java.lang.management.ManagementFactory.operatingSystemMXBean
        def memMx = java.lang.management.ManagementFactory.memoryMXBean
        def heap = memMx.heapMemoryUsage
        def nonHeap = memMx.nonHeapMemoryUsage
        def threadMx = java.lang.management.ManagementFactory.threadMXBean

        // Hikari exposes its live pool gauges on the injected DataSource
        // via HikariPoolMXBean. We guard the cast so a non-Hikari pool in
        // tests doesn't throw.
        def pool = null
        try {
            if (dataSource instanceof com.zaxxer.hikari.HikariDataSource) {
                def hds = (com.zaxxer.hikari.HikariDataSource) dataSource
                def bean = hds.hikariPoolMXBean
                if (bean != null) {
                    pool = [
                        active:   bean.activeConnections,
                        idle:     bean.idleConnections,
                        total:    bean.totalConnections,
                        waiting:  bean.threadsAwaitingConnection,
                        max:      hds.maximumPoolSize
                    ]
                }
            }
        } catch (Exception ignore) {}

        // Live schema version (batch 399). Previously hard-coded to 16 and
        // drifted from reality on every new Flyway migration. Reads the
        // current max version from flyway_schema_history directly so the
        // admin Health tile never lies again. Safe fallback to null on
        // any JDBC error — the tab renders "V—" in that case.
        Integer schemaVersion = null
        try {
            if (dataSource != null) {
                dataSource.connection.withCloseable { conn ->
                    conn.createStatement().withCloseable { stmt ->
                        stmt.executeQuery("SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = true AND version ~ '^[0-9]+\$'").withCloseable { rs ->
                            if (rs.next()) {
                                def raw = rs.getObject(1)
                                if (raw != null) schemaVersion = ((Number) raw).intValue()
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) { /* keep null — UI shows dash */ }

        [
            uptimeMs:       rtMx.uptime,
            startedAt:      rtMx.startTime,
            jvm: [
                vendor:  rtMx.vmVendor,
                name:    rtMx.vmName,
                version: rtMx.vmVersion
            ],
            memory: [
                heapUsedMb:     (heap.used    / (1024L * 1024L)) as long,
                heapMaxMb:      (heap.max     / (1024L * 1024L)) as long,
                heapCommittedMb:(heap.committed / (1024L * 1024L)) as long,
                nonHeapUsedMb:  (nonHeap.used / (1024L * 1024L)) as long,
                processorCount: runtime.availableProcessors()
            ],
            threads: [
                live:    threadMx.threadCount,
                peak:    threadMx.peakThreadCount,
                daemon:  threadMx.daemonThreadCount
            ],
            pool:           pool,
            systemLoad:     osMx.systemLoadAverage,
            db: [
                schemaVersion: schemaVersion
            ],
            // Steam Market sync last-run telemetry (batch 396). Null-safe —
            // the collaborator is optional so unit tests that don't wire
            // the price service don't break. Zero-filled until the first
            // sync pass completes after boot.
            priceSync:      (steamMarketPriceService != null
                                ? steamMarketPriceService.lastRunSummary
                                : null),
            // Stripe webhook telemetry (batch 471). Lets ops spot silent
            // outages — e.g. webhook secret rotated, Stripe dashboard
            // pointed at the wrong URL. lastReceivedAt = 0 until the
            // first event lands; UI renders "No events yet" when zero.
            stripeWebhook:  (stripeService != null
                                ? stripeService.webhookTelemetry
                                : null)
        ]
    }

    // ── Withdrawals ─────────────────────────────────────────────────

    /** Clear a DISPUTED deposit back to COMPLETED (batch 467). Used
     *  when a chargeback resolves in our favour, or staff verify the
     *  dispute is a false positive. Audited as DISPUTE_CLEARED so
     *  every admin override is traceable. */
    @Transactional
    Map clearDisputeHold(Long adminUserId, Long txId, String reason) {
        requireAdmin(adminUserId)
        def tx = transactionRepository.findById(txId).orElseThrow {
            new NotFoundException("Transaction", txId)
        }
        if (tx.status != 'DISPUTED') {
            throw new BadRequestException("NOT_DISPUTED",
                "Transaction is not in DISPUTED state (currently ${tx.status})")
        }
        def cleanReason = textSanitizer.medium(reason) ?: 'admin override'
        // Risk signal — if the wallet balance has already fallen below the
        // disputed deposit amount the funds are (partly) spent. Clearing
        // the hold re-enables withdrawals, and if the chargeback is later
        // upheld the platform eats the gap. We don't block the admin (a
        // false-positive dispute is a legitimate clear), but log it loudly
        // so a risky clear stays reconcilable after the fact.
        try {
            def disputeWallet = walletRepository.findById(tx.walletId).orElse(null)
            if (disputeWallet?.balance != null && tx.amount != null && disputeWallet.balance < tx.amount) {
                log.warn("clearDisputeHold tx={} — wallet {} balance \${} is below the disputed \${}; " +
                    "clearing the hold exposes a \${} shortfall if the chargeback is upheld",
                    txId, disputeWallet.id, disputeWallet.balance, tx.amount, (tx.amount - disputeWallet.balance))
            }
        } catch (Exception ignore) { /* advisory only — never block the clear */ }
        tx.status = 'COMPLETED'
        tx.description = (tx.description ?: '') + " — DISPUTE_CLEARED by admin (${cleanReason})"
        tx.updatedAt = System.currentTimeMillis()
        transactionRepository.save(tx)
        // Resolve the affected wallet owner up-front so the audit row
        // records WHO the dispute-clear touched. Previously the subject
        // was hard-coded null — unlike approveWithdrawal / rejectWithdrawal
        // which both pass walletOwnerId(...) — so DISPUTE_CLEARED rows were
        // invisible to the audit-by-subject filter and shipped a blank
        // subjectUserId / subjectName column in the CSV export.
        def ownerId = walletOwnerId(tx.walletId)
        try {
            auditService?.log('DISPUTE_CLEARED', adminUserId, ownerId, txId,
                "Cleared chargeback hold on deposit tx=${txId}: ${cleanReason}")
        } catch (Exception e) {
            log.warn("DISPUTE_CLEARED audit failed: ${e.message}")
        }
        // User-facing notification (batch 496). The user was blocked
        // from withdrawing while the dispute was open (batch 465's
        // WITHDRAW_DISPUTE_HOLD); now that it's cleared they need to
        // know they can withdraw again. Only fires when this was the
        // LAST active dispute on their wallet — otherwise they still
        // have a hold, so no "you're unblocked" message yet.
        try {
            long stillHeld = transactionRepository.countActiveDisputedDeposits(tx.walletId)
            if (stillHeld == 0L) {
                if (ownerId != null) {
                    notificationService?.push(ownerId, 'DISPUTE_CLEARED',
                        "Withdrawals re-enabled",
                        "A deposit dispute on your wallet was resolved in your favour. You can now withdraw again.",
                        txId, '/wallet')
                }
                // Email the user too (batch 520). The push + bell catch
                // active users; email catches folks who filed a
                // chargeback weeks ago and gave up checking the site.
                // Money-gate events like "you can withdraw again" are
                // email-worthy on the same tier as withdrawal approvals.
                if (emailService != null && ownerId != null) {
                    try {
                        def ownerUser = walletOwnerUser(tx.walletId)
                        if (emailService.canSendSecurityTo(ownerUser)) {
                            emailService.sendDisputeCleared(ownerUser.email, ownerUser.displayName)
                        }
                    } catch (Exception e) {
                        log.warn("DISPUTE_CLEARED email failed for tx ${txId}: ${e.message}")
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DISPUTE_CLEARED user-notify failed: ${e.message}")
        }
        log.info("Admin ${adminUserId} cleared dispute on tx=${txId}: ${cleanReason}")
        [transactionId: txId, status: tx.status]
    }

    /** All deposit transactions in DISPUTED state — flagged by Stripe
     *  chargebacks (batch 461). Mirrors `listWithdrawals` shape so the
     *  admin UI can reuse the same row renderer. Capped at 200 rows;
     *  past that the audit log + CSV export are the better surface. */
    List<Map> listDisputedDeposits() {
        def all = transactionRepository.findByTypeAndStatusPaged('DEPOSIT', 'DISPUTED',
            org.springframework.data.domain.PageRequest.of(0, 200)) ?: []
        if (all.isEmpty()) return []
        // Batch wallet + user lookup so we can show the admin who's
        // disputing without N+1 round-trips.
        def walletIds = all*.walletId.findAll { it != null }.unique()
        def walletById = walletIds.isEmpty() ? [:] :
            walletRepository.findAllById(walletIds).collectEntries { [(it.id): it] }
        // Resolve each wallet's owning steam user in one go.
        def steamIds = walletById.values()
            .collect { (it.username ?: '').startsWith('steam_') ? it.username.substring(6) : null }
            .findAll { it != null }
            .unique()
        def userBySteamId = [:]
        steamIds.each { sid ->
            def u = steamUserRepository.findBySteamId64(sid as String)
            if (u != null) userBySteamId[sid] = u
        }
        all.collect { tx ->
            def wallet = walletById[tx.walletId]
            def steamId = wallet?.username?.startsWith('steam_') ? wallet.username.substring(6) : null
            def user = steamId ? userBySteamId[steamId] : null
            // Fraud-signal enrichment (batch 515). Mirrors batch 514's
            // withdrawal-queue treatment: lifetime chargeback count,
            // wallet balance, email-verified, frozen state all help
            // staff spot a repeat-offender pattern without opening
            // every user detail drawer. Active-dispute count is
            // trivially "1 for this row" so we don't re-compute it.
            long lifetimeDisputes = 0L
            if (wallet != null) {
                try {
                    def allTx = transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.id,
                        org.springframework.data.domain.PageRequest.of(0, 500))
                    lifetimeDisputes = allTx.count { t ->
                        t.type == 'DEPOSIT' &&
                        (t.status == 'DISPUTED' || (t.description ?: '').contains('DISPUTE_CLEARED'))
                    } as long
                } catch (Exception ignored) { /* fall through */ }
            }
            [
                id:             tx.id,
                walletId:       tx.walletId,
                walletUsername: wallet?.username,
                amount:         tx.amount,
                currency:       tx.currency,
                status:         tx.status,
                description:    tx.description,
                createdAt:      tx.createdAt,
                updatedAt:      tx.updatedAt,
                userId:         user?.id,
                userDisplayName: user?.displayName,
                userBanned:     user?.banned,
                // Fraud signals (batch 515).
                userEmailVerified: user?.emailVerified,
                userCreatedAt:  user?.createdAt,
                walletFrozen:   Boolean.TRUE.equals(wallet?.frozen),
                walletBalance:  wallet?.balance,
                lifetimeDisputes: lifetimeDisputes
            ]
        }
    }

    List<Map> listWithdrawals(String statusFilter) {
        // Query by (type, status) via the indexed `idx_tx_type_status` lookup
        // instead of scanning every transaction row. Default filter is
        // 'PENDING' so the admin panel always sees a manageable list.
        // ORDER BY is now pushed into SQL instead of Groovy .sort.
        def status = (statusFilter ?: 'PENDING').toUpperCase()
        // Hard-cap at 500 rows — prevents a degenerate case (several
        // thousand pending withdrawals backed up during an incident)
        // from blowing up the admin page render. 500 is plenty for a
        // single admin session; older rows surface via the audit log /
        // CSV export instead.
        def all = transactionRepository.findByTypeAndStatusPaged('WITHDRAW', status,
            org.springframework.data.domain.PageRequest.of(0, 500)) ?: []
        if (all.isEmpty()) return []

        // Batch-resolve every wallet in one SQL call instead of per-row
        // `walletRepository.findById(...)` — that was an N+1 on the admin
        // withdrawal queue, firing 1 + N queries per page view.
        def walletIds = all*.walletId.findAll { it != null }.unique()
        def walletById = walletIds.isEmpty() ? [:] :
            walletRepository.findAllById(walletIds).collectEntries { [(it.id): it] }

        // Fraud-signal enrichment (batch 514). Resolve each wallet's owner
        // and enrich the row with the three fraud signals admins need
        // at-a-glance when approving a payout: active chargeback count,
        // lifetime chargeback count, and account age. Saves staff from
        // having to open the user detail drawer on every withdrawal.
        // Batch-resolves users via steamId64 lookup from wallet.username,
        // so this stays O(N) queries total (one per distinct user).
        def userBySteamId = [:]
        walletById.values().each { w ->
            def uname = w?.username ?: ''
            if (uname.startsWith('steam_')) {
                def sid = uname.substring('steam_'.length())
                def u = steamUserRepository.findBySteamId64(sid)
                if (u != null) userBySteamId[sid] = u
            }
        }

        all.collect { tx ->
            def wallet = walletById[tx.walletId]
            def uname = wallet?.username ?: ''
            def user = uname.startsWith('steam_') ? userBySteamId[uname.substring('steam_'.length())] : null
            long activeDisputes = wallet ? transactionRepository.countActiveDisputedDeposits(wallet.id) : 0L
            long lifetimeDisputes = 0L
            if (wallet != null) {
                try {
                    def allTx = transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.id,
                        org.springframework.data.domain.PageRequest.of(0, 500))
                    lifetimeDisputes = allTx.count { t ->
                        t.type == 'DEPOSIT' &&
                        (t.status == 'DISPUTED' || (t.description ?: '').contains('DISPUTE_CLEARED'))
                    } as long
                } catch (Exception ignored) { /* fall through */ }
            }
            [
                id:                tx.id,
                walletId:          tx.walletId,
                walletUsername:    wallet?.username,
                amount:            tx.amount,
                currency:          tx.currency,
                status:            tx.status,
                destination:       tx.stripeReference,
                description:       tx.description,
                createdAt:         tx.createdAt,
                updatedAt:         tx.updatedAt,
                // Fraud signals (batch 514).
                ownerUserId:       user?.id,
                ownerDisplayName:  user?.displayName,
                ownerEmailVerified: user?.emailVerified,
                ownerCreatedAt:    user?.createdAt,
                ownerBanned:       user?.banned,
                walletFrozen:      Boolean.TRUE.equals(wallet?.frozen),
                activeDisputes:    activeDisputes,
                lifetimeDisputes:  lifetimeDisputes,
                walletBalance:     wallet?.balance
            ]
        }
    }

    @Transactional
    Map approveWithdrawal(Long adminUserId, Long txId, String payoutRef) {
        requireAdmin(adminUserId)
        def tx = transactionRepository.findById(txId).orElseThrow { new NotFoundException("Transaction", txId) }
        if (tx.type != 'WITHDRAW') throw new BadRequestException("NOT_WITHDRAWAL", "Transaction is not a withdrawal")
        if (tx.status != 'PENDING') throw new BadRequestException("NOT_PENDING", "Withdrawal is not pending (status=${tx.status})")
        // Active-chargeback gate (batch 468). Even with admin override
        // privilege, refuse to approve a withdrawal while the wallet has
        // an unresolved DISPUTED deposit. The reasoning:
        //   1. Admin pressed Approve before realizing a dispute fired
        //      seconds earlier (race between bell notification and queue).
        //   2. The dispute might be valid — releasing funds compounds
        //      the loss because we'd owe the cardholder back PLUS lose
        //      the payout we just sent.
        // Admin can still proceed by clearing the dispute first
        // (clearDisputeHold) — which audits the override.
        long disputed = transactionRepository.countActiveDisputedDeposits(tx.walletId)
        if (disputed > 0) {
            throw new BadRequestException("DISPUTE_HOLD",
                "Cannot approve withdrawal — wallet has ${disputed} unresolved deposit dispute${disputed == 1 ? '' : 's'}. Clear the dispute(s) first via the Disputes tab.")
        }
        tx.status = 'COMPLETED'
        tx.stripeReference = payoutRef ?: tx.stripeReference
        tx.description = (tx.description ?: '') + " — approved by admin"
        tx.updatedAt = System.currentTimeMillis()
        transactionRepository.save(tx)

        notifyWalletOwner(tx.walletId, 'WITHDRAWAL_COMPLETE',
            "Withdrawal approved — \$${tx.amount.toPlainString()}",
            "Your payout has been released. Reference: ${tx.stripeReference}", tx.id)

        // Email the user too — money-out events should never rely on the
        // notification bell alone. Silent-fail to keep the approval path
        // resilient against SMTP outages.
        if (emailService != null) {
            try {
                def ownerUser = walletOwnerUser(tx.walletId)
                if (emailService.canSendSecurityTo(ownerUser)) {
                    emailService.sendWithdrawalApproved(ownerUser.email,
                        ownerUser.displayName, tx.amount, tx.stripeReference)
                }
            } catch (Exception e) {
                log.warn("Withdrawal-approved email failed for tx ${tx.id}: ${e.message}")
            }
        }

        auditService?.log(AuditService.WITHDRAW_APPROVED, adminUserId,
            walletOwnerId(tx.walletId), tx.id,
            "Approved withdrawal #${tx.id} of \$${tx.amount} (ref=${tx.stripeReference})")
        log.info("Admin ${adminUserId} approved withdrawal ${tx.id} for wallet ${tx.walletId}")
        [id: tx.id, status: tx.status]
    }

    @Transactional
    Map rejectWithdrawal(Long adminUserId, Long txId, String reason) {
        requireAdmin(adminUserId)
        def tx = transactionRepository.findById(txId).orElseThrow { new NotFoundException("Transaction", txId) }
        if (tx.type != 'WITHDRAW') throw new BadRequestException("NOT_WITHDRAWAL", "Transaction is not a withdrawal")
        if (tx.status != 'PENDING') throw new BadRequestException("NOT_PENDING", "Withdrawal is not pending")

        // Refund the wallet — withdrawal was debited optimistically on request
        def wallet = walletRepository.findById(tx.walletId).orElse(null)
        if (wallet != null) {
            wallet.balance = wallet.balance + tx.amount
            walletRepository.save(wallet)
        }
        tx.status = 'FAILED'
        tx.description = (tx.description ?: '') + " — REJECTED: " + (reason ?: 'no reason given')
        tx.updatedAt = System.currentTimeMillis()
        transactionRepository.save(tx)

        notifyWalletOwner(tx.walletId, 'WITHDRAWAL_REJECTED',
            "Withdrawal rejected — funds returned",
            reason ?: 'See the Transactions tab for details', tx.id)

        if (emailService != null) {
            try {
                def ownerUser = walletOwnerUser(tx.walletId)
                if (emailService.canSendSecurityTo(ownerUser)) {
                    emailService.sendWithdrawalRejected(ownerUser.email,
                        ownerUser.displayName, tx.amount, reason)
                }
            } catch (Exception e) {
                log.warn("Withdrawal-rejected email failed for tx ${tx.id}: ${e.message}")
            }
        }

        auditService?.log(AuditService.WITHDRAW_REJECTED, adminUserId,
            walletOwnerId(tx.walletId), tx.id,
            "Rejected withdrawal #${tx.id} of \$${tx.amount}: ${reason ?: '(no reason)'}")
        log.info("Admin ${adminUserId} rejected withdrawal ${tx.id} for wallet ${tx.walletId}, refunded \$${tx.amount}")
        [id: tx.id, status: tx.status, refunded: tx.amount]
    }

    // ── User management ─────────────────────────────────────────────

    /**
     * Consolidated per-user summary for the admin detail drawer
     * (batch 549). Aggregates the fraud/wallet signals staff already
     * see on the withdrawals + disputes + tickets queues so the
     * detail drawer doesn't need to bounce between tabs.
     */
    Map userSummary(Long targetUserId) {
        def user = steamUserRepository.findById(targetUserId)
            .orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        long activeDisputes = wallet ? transactionRepository.countActiveDisputedDeposits(wallet.id) : 0L
        long lifetimeDisputes = 0L
        BigDecimal pendingWithdraw = BigDecimal.ZERO
        if (wallet != null) {
            try {
                def tx = transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.id,
                    org.springframework.data.domain.PageRequest.of(0, 500))
                lifetimeDisputes = tx.count { t ->
                    t.type == 'DEPOSIT' &&
                    (t.status == 'DISPUTED' || (t.description ?: '').contains('DISPUTE_CLEARED'))
                } as long
                pendingWithdraw = tx.findAll { t ->
                    (t.type == 'WITHDRAW' || t.type == 'WITHDRAWAL') && t.status == 'PENDING'
                }.inject(BigDecimal.ZERO) { acc, t -> acc + (t.amount ?: BigDecimal.ZERO) }
            } catch (Exception ignored) { /* fall through */ }
        }
        long openTrades = tradeRepository ? tradeRepository.countOpenByParticipant(targetUserId) : 0L
        // Ship-time signal (batch 550 / 552). Same median-over-90d metric
        // exposed on the public stall — but surfaced to staff so fraud
        // triage can tell at-a-glance whether this account delivers or
        // ghosts. Null under the 3-sample noise floor (same semantics).
        Long typicalShipMs = tradeService ? tradeService.typicalShipMs(targetUserId) : null
        int  typicalShipSamples = tradeService ? tradeService.typicalShipSampleCount(targetUserId) : 0
        // Distinct sign-in IPs over the last 30 days (batch 603). One
        // number tells fraud triage whether this account has been
        // roamed — legit users have 1-3 typical IPs; a compromised /
        // shared account lights up at 10+ distinct IPs in a month.
        long distinctSignInIps30d = 0L
        if (auditLogRepository != null) {
            try {
                def since = System.currentTimeMillis() - (30L * 24L * 60L * 60L * 1000L)
                distinctSignInIps30d = auditLogRepository.countDistinctSignInIpsSince(targetUserId, since)
            } catch (Exception ignored) { /* fall through — 0L is fine */ }
        }
        // Active API key count (batch 703). Fraud signal: a user with
        // 10+ active keys is unusual — either a power-user running a
        // legit bot fleet, or a compromised session silently minting
        // persistence tokens. Staff can pivot to the user's /api/
        // api-keys list (via the lookup tool in batch 700/701) if the
        // count is suspiciously high.
        long activeApiKeys = 0L
        if (apiKeyRepository != null) {
            try { activeApiKeys = apiKeyRepository.countActiveByUser(targetUserId) }
            catch (Exception ignored) { /* 0 is fine */ }
        }
        [
            userId:             user.id,
            steamId64:          user.steamId64,
            displayName:        user.displayName,
            role:               user.role,
            banned:             user.banned,
            banReason:          user.banReason,
            emailVerified:      user.emailVerified,
            emailNotificationsEnabled: user.emailNotificationsEnabled,
            email:              user.email,
            twoFactorEnabled:   user.totpSecret != null,
            tradeUrl:           user.tradeUrl,
            awayModeUntil:      user.awayModeUntil,
            createdAt:          user.createdAt,
            lastSyncedAt:       user.lastSyncedAt,
            walletBalance:      wallet?.balance,
            walletFrozen:       Boolean.TRUE.equals(wallet?.frozen),
            walletFrozenReason: wallet?.frozenReason,
            walletFrozenAt:     wallet?.frozenAt,
            activeDisputes:     activeDisputes,
            lifetimeDisputes:   lifetimeDisputes,
            pendingWithdrawAmt: pendingWithdraw,
            openTrades:         openTrades,
            typicalShipMs:      typicalShipMs,
            typicalShipSamples: typicalShipSamples,
            distinctSignInIps30d: distinctSignInIps30d,
            activeApiKeys:      activeApiKeys
        ]
    }

    List<SteamUser> listUsers(String search) {
        listUsers(search, null, null)
    }

    /** Batch 765 — listUsers with role + banned filters. `role` is one
     *  of "USER", "ADMIN", "CSR", or null/blank for any. `banned` is
     *  TRUE / FALSE / null (any). `search` still takes precedence over
     *  the filter path — a staff member typing a query should see
     *  search hits regardless of role/banned state, matching the
     *  principle-of-least-surprise. */
    List<SteamUser> listUsers(String search, String role, Boolean banned) {
        def sort = org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, 'createdAt')
        def page = org.springframework.data.domain.PageRequest.of(0, 200, sort)
        if (search) {
            return steamUserRepository.searchByNameOrSteamId(search.trim(), page)
        }
        def roleFilter = (role == null || role.isBlank()) ? 'ANY' : role.trim().toUpperCase()
        // Drop the filter path + hit findAll(page) when no filters are
        // set so the plain "admin opens Users tab" path stays on the
        // fast LIMIT-only query.
        if (roleFilter == 'ANY' && banned == null) {
            return steamUserRepository.findAll(page).content
        }
        steamUserRepository.listByRoleAndBanned(roleFilter, banned, page)
    }

    @Transactional
    SteamUser banUser(Long adminUserId, Long targetUserId, String reason) {
        requireAdmin(adminUserId)
        if (adminUserId == targetUserId) {
            throw new BadRequestException("CANT_BAN_SELF", "You cannot ban yourself")
        }
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        if (user.role == 'ADMIN') {
            throw new BadRequestException("CANT_BAN_ADMIN", "Cannot ban another admin. Revoke admin role first.")
        }
        user.banned   = true
        user.banReason = textSanitizer.medium(reason ?: 'No reason provided')
        // Bump sessionEpoch so every live session the banned user holds is
        // invalidated on their next /api/* request. Without this the
        // BanGuard blocks writes but reads + navigation still work,
        // letting the banned user continue browsing + poking at the site
        // until their cookie naturally expires.
        user.sessionEpoch = System.currentTimeMillis()
        steamUserRepository.save(user)

        // Cancel all the user's active listings so the marketplace stays clean
        def active = listingRepository.findActiveBySeller(targetUserId)
        active.each { it.status = 'CANCELLED' }
        listingRepository.saveAll(active)

        // Pending-offer cleanup across all the banned seller's listings —
        // without this, buyers saw orphaned PENDING offers that could never
        // be accepted. One SQL call per listing; the typical banned user
        // has < 10 active listings, so the total volume stays bounded.
        active.each { listing ->
            try {
                def pending = offerRepository.findPendingForListing(listing.id)
                def itemName = listing.item?.name ?: 'this item'
                def itemId = listing.item?.id
                pending.each { o ->
                    o.status = 'CANCELLED'
                    o.updatedAt = System.currentTimeMillis()
                }
                if (!pending.isEmpty()) offerRepository.saveAll(pending)
                pending.each { o ->
                    if (o.buyerUserId == null) return
                    try {
                        notificationService?.push(o.buyerUserId, 'OFFER_REJECTED',
                            "Offer cancelled · ${itemName}",
                            "The seller was banned by staff.",
                            o.id,
                            itemId != null ? "/item/${itemId}" : '/offers')
                    } catch (Exception e) {
                        log.warn("Ban offer-cancel push failed for buyer ${o.buyerUserId}: ${e.message}")
                    }
                }
            } catch (Exception e) {
                log.warn("Ban offer cleanup failed for listing ${listing.id}: ${e.message}")
            }
        }

        // Auction bidder fan-out — same treatment as SellService.cancelListing
        // and AdminService.forceCancelListing. Without this, every bidder on
        // a banned seller's auction had their bid silently invalidated with
        // no signal. Dedup by bidder so one bidder who bid on multiple of
        // the banned seller's auctions only gets one ping per auction.
        def auctionListings = active.findAll { it.listingType == 'AUCTION' }
        auctionListings.each { listing ->
            try {
                def bids = bidRepository.findByListing(listing.id)
                def uniqueBidders = new LinkedHashSet<Long>()
                bids.each { b ->
                    if (b.status == 'WINNING' || b.status == 'OUTBID') {
                        b.status = 'CANCELLED'
                    }
                    if (b.bidderUserId != null) uniqueBidders.add(b.bidderUserId as Long)
                }
                if (!bids.isEmpty()) bidRepository.saveAll(bids)
                def itemName = listing.item?.name ?: 'an auction'
                uniqueBidders.each { uid ->
                    try {
                        notificationService?.push(uid, 'AUCTION_CANCELLED',
                            "Auction cancelled · ${itemName}",
                            'The seller was banned by staff. No charge was made.',
                            listing.id,
                            listing.item?.id != null ? "/item/${listing.item.id}" : null)
                    } catch (Exception e) {
                        log.warn("AUCTION_CANCELLED (ban) push failed for uid=${uid}: ${e.message}")
                    }
                }
            } catch (Exception e) {
                log.warn("Auction bidder fan-out (ban path) failed for listing ${listing.id}: ${e.message}")
            }
        }

        // Open-trade cascade (batch 502). Without this, an in-flight
        // escrowed trade where the banned user is the seller traps the
        // buyer's funds until the slow-seller auto-cancel sweep fires
        // (up to 3 days). And a banned BUYER mid-trade leaves the
        // seller wondering whether to send the item to a now-banned
        // account. tradeService.cancel handles the refund/inventory-
        // restore atomically + notifies the counterparty per its own
        // notification path. Per-trade try/catch so one failure doesn't
        // block the rest. DISPUTED trades are deliberately skipped —
        // they need human resolution.
        if (tradeService != null && tradeRepository != null) {
            try {
                def openTrades = tradeRepository.findOpenByParticipant(targetUserId)
                openTrades.each { trade ->
                    try {
                        tradeService.cancel(adminUserId, trade.id,
                            "Counterparty was banned by staff: ${user.banReason ?: 'no reason given'}")
                    } catch (Exception e) {
                        log.warn("Ban-cascade trade cancel failed for trade ${trade.id} (banned uid=${targetUserId}): ${e.message}")
                    }
                }
                if (!openTrades.isEmpty()) {
                    log.info("Ban-cascade cancelled ${openTrades.size()} open trade(s) for banned user ${targetUserId}")
                }
            } catch (Exception e) {
                log.warn("Ban-cascade open-trade lookup failed for ${targetUserId}: ${e.message}")
            }
        }

        // Buyer-side offer cleanup (batch 598). Before this patch the ban
        // cascade only swept offers WHERE the banned user was the seller.
        // If a banned user had 5 outgoing PENDING offers on other
        // sellers' listings, those offers stayed alive — confusing
        // innocent sellers who might accept and then hit a BanGuard
        // failure at acceptOffer time. Clean them up here + ping the
        // other-side seller so their incoming-offer queue reflects
        // reality.
        try {
            // Batch 1030 — indexed PENDING-only query instead of fetching
            // every historical offer the banned user ever made just to
            // filter down to the 100-or-fewer live rows.
            def pendingOutgoing = offerRepository.findPendingByBuyer(targetUserId)
            if (!pendingOutgoing.isEmpty()) {
                pendingOutgoing.each { o ->
                    o.status = 'CANCELLED'
                    o.updatedAt = System.currentTimeMillis()
                }
                offerRepository.saveAll(pendingOutgoing)
                pendingOutgoing.each { o ->
                    if (o.sellerUserId == null) return
                    try {
                        notificationService?.push(o.sellerUserId, 'OFFER_REJECTED',
                            "Offer cancelled · ${o.itemName ?: 'listing'}",
                            "The bidder's account was banned by staff.",
                            o.id, '/offers')
                    } catch (Exception e) {
                        log.warn("Ban outgoing-offer push failed for seller ${o.sellerUserId}: ${e.message}")
                    }
                }
                log.info("Ban-cascade cancelled ${pendingOutgoing.size()} outgoing offer(s) by banned user ${targetUserId}")
            }
        } catch (Exception e) {
            log.warn("Ban-cascade outgoing-offer cleanup failed for ${targetUserId}: ${e.message}")
        }

        // Buyer-side buy-order cleanup (batch 598). A banned buyer's
        // standing orders shouldn't keep matching against fresh
        // listings — BuyOrderService's own ban-check would skip the
        // match but the order row stays ACTIVE and wastes scan work
        // on every new listing. Flush them here. Silent-fail.
        try {
            if (buyOrderService != null) {
                int n = buyOrderService.cancelAllForUser(targetUserId)
                if (n > 0) log.info("Ban-cascade cancelled ${n} buy order(s) for banned user ${targetUserId}")
            }
        } catch (Exception e) {
            log.warn("Ban-cascade buy-order cleanup failed for ${targetUserId}: ${e.message}")
        }

        // safePush (batch 630): if the bell-push throws (Hibernate
        // cache miss, notification table lock, whatever) we MUST NOT
        // roll back the ban itself. Before safePush an unguarded push
        // failure here would bubble out of the @Transactional method
        // and undo the ban + every cascade step above.
        notificationService?.safePush(targetUserId, 'ACCOUNT_BANNED',
            "Your account has been banned",
            user.banReason, null, '/profile')

        // Email the user too if they've confirmed an address. Ban is a
        // significant account change — the notification bell may go
        // unchecked for days, but the email lands in their inbox.
        // Silent-fail: a broken mail relay mustn't roll back the ban.
        if (emailService != null && emailService.canSendSecurityTo(user)) {
            try {
                emailService.sendAccountBanned(user.email, user.displayName, user.banReason, null)
            } catch (Exception e) {
                log.warn("Ban-notification email failed for user ${targetUserId}: ${e.message}")
            }
        }

        auditService?.log(AuditService.USER_BANNED, adminUserId, targetUserId, null,
            "Banned user ${user.steamId64}: ${reason ?: '(no reason)'}")
        log.warn("Admin ${adminUserId} banned user ${targetUserId} (${user.steamId64}): ${reason}")
        user
    }

    /**
     * Revoke every active session the target holds without banning the
     * account. Staff use this when an account looks compromised or a
     * user reports their session was hijacked — the user has to sign in
     * again on every device but is otherwise unaffected. Bumps the
     * user's `sessionEpoch`; SessionEpochFilter then 401s every still-
     * live cookie on its next /api/* request.
     *
     * Audit-logged as a distinct event so the forensic record
     * distinguishes it from a ban. Returns the affected user.
     */
    @Transactional
    SteamUser forceLogout(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        if (adminUserId == targetUserId) {
            throw new BadRequestException("CANT_FORCE_LOGOUT_SELF",
                "Use the logout-all button on your own profile")
        }
        def user = steamUserRepository.findById(targetUserId)
            .orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        user.sessionEpoch = System.currentTimeMillis()
        steamUserRepository.save(user)
        auditService?.log(AuditService.USER_FORCE_LOGOUT, adminUserId, targetUserId, null,
            "Revoked all live sessions")
        log.warn("Admin ${adminUserId} force-logged-out user ${targetUserId} (${user.steamId64})")
        // Batch 702 — security-alert email. Out-of-band so a compromised
        // session can't hide the forced logout. Non-fatal: an SMTP
        // outage or missing email shouldn't abort the admin action.
        try {
            if (emailService != null && user.email) {
                emailService.sendForceLogout(user.email, user.displayName, null)
            }
        } catch (Exception e) {
            log.warn("Force-logout alert email failed for uid=${targetUserId}: ${e.message}")
        }
        user
    }

    @Transactional
    SteamUser unbanUser(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        user.banned = false
        user.banReason = null
        steamUserRepository.save(user)
        notificationService?.safePush(targetUserId, 'ACCOUNT_UNBANNED',
            "Your account has been reinstated", null, null)
        if (emailService != null && emailService.canSendSecurityTo(user)) {
            try {
                emailService.sendAccountUnbanned(user.email, user.displayName)
            } catch (Exception e) {
                log.warn("Unban-notification email failed for user ${targetUserId}: ${e.message}")
            }
        }
        auditService?.log(AuditService.USER_UNBANNED, adminUserId, targetUserId, null,
            "Unbanned user ${user.steamId64}")
        log.info("Admin ${adminUserId} unbanned user ${targetUserId}")
        user
    }

    @Transactional
    SteamUser grantAdmin(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        // Self-target guard — symmetric with banUser / revokeAdmin /
        // forceLogout. Granting yourself admin is a no-op (the caller
        // already passed requireAdmin), but it writes a misleading
        // ADMIN_GRANTED audit row implying a privilege escalation that
        // never happened. Reject it so the audit trail stays honest.
        if (adminUserId == targetUserId) {
            throw new BadRequestException("CANT_GRANT_SELF", "You are already an admin")
        }
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        // Don't hand admin privileges to a banned account — either the
        // admin meant to unban first, or it's a mistake that would
        // silently reinstate access despite the ban still sitting on
        // the row. Force the explicit unban-then-grant flow.
        if (Boolean.TRUE.equals(user.banned)) {
            throw new BadRequestException("USER_BANNED",
                "Cannot grant admin to a banned user — unban first")
        }
        user.role = 'ADMIN'
        steamUserRepository.save(user)
        notificationService?.safePush(targetUserId, 'ADMIN_GRANTED',
            'You are now an admin',
            'You have been promoted to ADMIN on SkinBox — the Admin Panel is now available from your user menu.',
            null, '/admin')
        auditService?.log(AuditService.ADMIN_GRANTED, adminUserId, targetUserId, null,
            "Granted ADMIN to ${user.steamId64}")
        log.info("Admin ${adminUserId} granted ADMIN to ${targetUserId}")
        user
    }

    @Transactional
    SteamUser revokeAdmin(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        if (adminUserId == targetUserId) {
            throw new BadRequestException("CANT_REVOKE_SELF", "You cannot revoke your own admin role")
        }
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        user.role = 'USER'
        steamUserRepository.save(user)
        notificationService?.safePush(targetUserId, 'ADMIN_REVOKED',
            'Admin role revoked',
            'Your admin privileges have been removed. You can still use SkinBox normally as a regular user.',
            null, '/profile')
        auditService?.log(AuditService.ADMIN_REVOKED, adminUserId, targetUserId, null,
            "Revoked ADMIN from ${user.steamId64}")
        log.info("Admin ${adminUserId} revoked ADMIN from ${targetUserId}")
        user
    }

    @Transactional
    SteamUser grantCsr(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        // Self-target guard — an admin demoting themselves to CSR would
        // silently strand their own admin access (CSR is strictly less
        // privileged). Force the explicit revokeAdmin flow instead.
        if (adminUserId == targetUserId) {
            throw new BadRequestException("CANT_GRANT_SELF",
                "You cannot change your own role — ask another admin")
        }
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        if (Boolean.TRUE.equals(user.banned)) {
            throw new BadRequestException("USER_BANNED",
                "Cannot grant CSR to a banned user — unban first")
        }
        if (user.role == 'ADMIN') {
            throw new BadRequestException("ALREADY_ADMIN",
                "User is already ADMIN — downgrade by revoking admin first")
        }
        user.role = 'CSR'
        steamUserRepository.save(user)
        notificationService?.safePush(targetUserId, 'CSR_GRANTED',
            'You are now a customer service rep',
            'You have been granted the CSR role on SkinBox. The 🎧 Customer Service panel is now available from your user menu.',
            null, '/csr')
        auditService?.log(AuditService.CSR_GRANTED, adminUserId, targetUserId, null,
            "Granted CSR to ${user.steamId64}")
        log.info("Admin ${adminUserId} granted CSR to ${targetUserId}")
        user
    }

    /**
     * Fire a test email through the real SMTP pipeline. Admins use this
     * to validate SMTP config after changing SMTP_HOST / SMTP_PASSWORD
     * in prod — without having to wait for a live trade event to hit
     * the send path. Audit-logged, capped at 200-char subject + 2000-
     * char body. Returns a { sent, smtpEnabled } map so the caller
     * can distinguish real send vs log-sink mode.
     */
    @Transactional(readOnly = true)
    Map sendTestEmail(Long adminUserId, String to, String subject, String body) {
        requireAdmin(adminUserId)
        if (emailService == null) {
            throw new BadRequestException('EMAIL_UNAVAILABLE', 'Email service is not wired')
        }
        if (to == null || to.trim().isEmpty() || !(to.contains('@'))) {
            throw new BadRequestException('INVALID_ADDRESS', 'Provide a valid email address')
        }
        def cleanSubject = textSanitizer.medium(subject ?: 'SkinBox SMTP test').take(200)
        def cleanBody    = (body ?: 'If you received this, the SkinBox SMTP pipeline is healthy.').take(2000)
        try {
            emailService.send(to.trim(), cleanSubject, cleanBody)
            // Distinct event type — was mislogged as ADMIN_GRANTED, which
            // polluted the audit-by-event filter (a test email showed up
            // as a privilege grant) and inflated the ADMIN_GRANTED count.
            auditService?.log('ADMIN_TEST_EMAIL', adminUserId, null, null,
                "SMTP test email sent to ${to.trim()}")
            log.info("Admin ${adminUserId} fired SMTP test email to ${to}")
            [sent: true, to: to.trim()]
        } catch (Exception e) {
            log.error("SMTP test email failed: ${e.message}", e)
            throw new BadRequestException('SEND_FAILED', "SMTP send failed: ${e.message}")
        }
    }

    /**
     * Admin-scoped view of a target user's wallet transaction history
     * (batch 568). Returns the 100 most-recent rows — same cap as the
     * user's own `/api/wallet/transactions` endpoint. Meant for fraud
     * triage: staff clicking a user row in the Users tab needs to see
     * who funded the wallet, when, and how the money left, without
     * bouncing to a separate SQL query path.
     */
    List<Map> listTransactionsFor(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        def user = steamUserRepository.findById(targetUserId)
            .orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        if (wallet == null) return []
        try {
            def page = org.springframework.data.domain.PageRequest.of(0, 100)
            def rows = transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.id, page)
            return rows.collect { tx ->
                [
                    id:              tx.id,
                    type:            tx.type,
                    status:          tx.status,
                    amount:          tx.amount,
                    currency:        tx.currency,
                    description:     tx.description,
                    stripeReference: tx.stripeReference,
                    createdAt:       tx.createdAt,
                    updatedAt:       tx.updatedAt
                ]
            }
        } catch (Exception e) {
            log.warn("listTransactionsFor lookup failed for uid=${targetUserId}: ${e.message}")
            return []
        }
    }

    /**
     * Admin-to-user direct message (batch 580). Pushes one
     * ADMIN_MESSAGE notification to a single target user. Useful for
     * one-off heads-ups ("we noticed you haven't verified your email
     * — please do so soon") without opening a full support ticket.
     * Rejects banned users (no point notifying a locked account) and
     * validates title + body lengths.
     */
    Map sendDirectMessage(Long adminUserId, Long targetUserId, String title, String body, String path) {
        requireAdmin(adminUserId)
        if (notificationService == null) {
            throw new BadRequestException('MESSAGE_UNAVAILABLE', 'Notification service is not wired')
        }
        def target = steamUserRepository.findById(targetUserId)
            .orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        if (Boolean.TRUE.equals(target.banned)) {
            throw new BadRequestException('USER_BANNED',
                'Cannot message a banned user — unban them first or reply via their support ticket')
        }
        def cleanTitle = (title ?: '').trim()
        if (cleanTitle.isEmpty()) {
            throw new BadRequestException('EMPTY_TITLE', 'Message title is required')
        }
        if (cleanTitle.length() > 120) cleanTitle = cleanTitle.substring(0, 120)
        def cleanBody = (body ?: '').trim()
        if (cleanBody.length() > 500) cleanBody = cleanBody.substring(0, 500)
        def cleanPath = (path ?: '').trim()
        if (cleanPath.length() > 200) cleanPath = null
        if (cleanPath != null && !cleanPath.isEmpty() && !cleanPath.startsWith('/')) {
            throw new BadRequestException('INVALID_PATH',
                'Message path must be an in-site URL starting with "/" (or blank)')
        }
        try {
            notificationService.push(targetUserId, 'ADMIN_MESSAGE',
                cleanTitle,
                cleanBody.isEmpty() ? null : cleanBody,
                null,
                cleanPath.isEmpty() ? null : cleanPath)
        } catch (Exception e) {
            // Two bugs here previously:
            //   1) `e` was dropped — the new BadRequestException carried no
            //      chained cause, so the global handler logged the wrapper
            //      with no root-cause trace. Use the cause-preserving 3-arg
            //      ctor so MDC + logs keep the full chain.
            //   2) `${e.message}` was interpolated into the user-facing
            //      response. NotificationService failures come from JPA /
            //      JDBC / Jackson — their messages routinely carry SQL
            //      fragments, constraint names ("notification_pkey"), and
            //      table names. Echoing them lets an admin probe internals.
            //      Fixed message; root cause stays in the log line.
            log.error("Direct message push failed for admin=${adminUserId} target=${targetUserId}: ${e.message}", e)
            throw new BadRequestException('SEND_FAILED',
                'Failed to deliver direct message', e)
        }
        auditService?.log('ADMIN_MESSAGE_SENT', adminUserId, targetUserId, null,
            "Message: '${cleanTitle}'")
        log.info("Admin ${adminUserId} sent direct message to user ${targetUserId}: '${cleanTitle}'")
        [sent: true, to: targetUserId]
    }

    /**
     * Broadcast a single notification row to every non-banned,
     * non-deletion-pending user (batch 566). Inserts in 500-user
     * batches keyed on `id ASC` so a 100k-user table doesn't
     * produce one 30-second transaction — each batch commits on its
     * own. Banned/deletion-pending users are excluded server-side
     * (they can't / shouldn't act on it). Kind is always
     * `ADMIN_BROADCAST` so the bell UI's icon map knows how to
     * render it.
     *
     * Hard cap of 10k users per call as a belt-and-braces guard;
     * ops with bigger audiences should queue a background job.
     * Returns `{sent, batches}` so the admin form can show "sent
     * to 3,421 users".
     */
    Map broadcastNotification(Long adminUserId, String title, String body, String path) {
        requireAdmin(adminUserId)
        if (notificationService == null) {
            throw new BadRequestException('BROADCAST_UNAVAILABLE', 'Notification service is not wired')
        }
        def cleanTitle = (title ?: '').trim()
        if (cleanTitle.isEmpty()) {
            throw new BadRequestException('EMPTY_TITLE', 'Broadcast title is required')
        }
        if (cleanTitle.length() > 120) cleanTitle = cleanTitle.substring(0, 120)
        def cleanBody = (body ?: '').trim()
        if (cleanBody.length() > 500) cleanBody = cleanBody.substring(0, 500)
        def cleanPath = (path ?: '').trim()
        if (cleanPath.length() > 200) cleanPath = null
        if (cleanPath != null && !cleanPath.isEmpty() && !cleanPath.startsWith('/')) {
            throw new BadRequestException('INVALID_PATH',
                'Broadcast path must be an in-site URL starting with "/" (or blank)')
        }

        int BATCH = 500
        int HARD_CAP = 10_000
        int sent = 0
        int batches = 0
        for (int page = 0; page < (HARD_CAP / BATCH); page++) {
            def ids = steamUserRepository.findActiveUserIds(
                org.springframework.data.domain.PageRequest.of(page, BATCH))
            if (ids == null || ids.isEmpty()) break
            batches++
            ids.each { uid ->
                try {
                    notificationService.push(uid, 'ADMIN_BROADCAST',
                        cleanTitle,
                        cleanBody.isEmpty() ? null : cleanBody,
                        null,
                        cleanPath.isEmpty() ? null : cleanPath)
                    sent++
                } catch (Exception e) {
                    log.warn("Broadcast push failed for uid=${uid}: ${e.message}")
                }
            }
            if (ids.size() < BATCH) break  // last page
        }
        auditService?.log(AuditService.ANNOUNCEMENT_CREATED, adminUserId, null, null,
            "Broadcast: '${cleanTitle}' → ${sent} user(s)")
        log.info("Admin ${adminUserId} broadcast notification '${cleanTitle}' to ${sent} user(s) in ${batches} batch(es)")
        [sent: sent, batches: batches]
    }

    /**
     * Users who have requested self-service deletion (GDPR/DSAR).
     * Returned oldest-request-first so the admin queue picks up the
     * tail of the backlog. Admin reviews each, confirms outstanding
     * obligations are clear, then either cancels (keeps account) or
     * bans (effective delete marker — the user can't log back in).
     */
    List<Map> listDeletionRequests() {
        steamUserRepository.findDeletionRequested().collect { u ->
            def wallet = walletRepository.findByUsername("steam_${u.steamId64}")
            def walletId = wallet?.id ?: -1L
            long pendingWithdrawals = wallet == null ? 0L :
                transactionRepository.countCompletedByWalletAndType(walletId, 'PENDING') ?: 0L
            // Open trades as either buyer or seller — admin must clear these before finalising.
            int openTrades = (tradeRepository?.findByParticipant(u.id) ?: [])
                .count { t -> !(t.state in ['VERIFIED','CANCELLED']) } as int
            [
                id:                   u.id,
                steamId64:            u.steamId64,
                displayName:          u.displayName,
                email:                u.email,
                deletionRequestedAt:  u.deletionRequestedAt,
                banned:               u.banned ?: false,
                walletBalance:        wallet?.balance ?: BigDecimal.ZERO,
                pendingWithdrawals:   pendingWithdrawals,
                openTrades:           openTrades
            ]
        }
    }

    /**
     * Finalise a user's self-service deletion request. Scrubs PII
     * (display name, avatar, email, trade URL) to empty placeholders,
     * wipes admin notes + 2FA secret, and marks the account banned so
     * the user can't sign back in. Does NOT delete rows — listings,
     * trades, transactions stay intact for audit/chargeback defence.
     *
     * Refuses if there's an outstanding PENDING withdrawal or an open
     * (not VERIFIED / CANCELLED) trade the admin should resolve first.
     */
    @Transactional
    Map finalizeDeletion(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        def user = steamUserRepository.findById(targetUserId)
            .orElseThrow { new NotFoundException('SteamUser', targetUserId) }
        if (user.deletionRequestedAt == null) {
            throw new BadRequestException('NO_REQUEST', 'User has no pending deletion request')
        }
        // Outstanding-obligation checks — don't want to finalise an
        // account whose payout hasn't cleared or who still has a trade
        // in escrow. Admin has to resolve those first.
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        if (wallet != null) {
            def walletId = wallet.id
            def pendingTxCount = transactionRepository.countByWalletAndType(walletId, 'WITHDRAW') ?: 0L
            // More precise: only PENDING (not COMPLETED/FAILED) withdrawals
            // should block. We iterate — the count is tiny because withdrawals
            // are rare per user.
            def pendingWithdrawals = transactionRepository.findByWalletIdOrderByCreatedAtDesc(
                walletId, org.springframework.data.domain.PageRequest.of(0, 200))
                .findAll { it.type == 'WITHDRAW' && it.status == 'PENDING' }
            if (!pendingWithdrawals.isEmpty()) {
                throw new BadRequestException('PENDING_WITHDRAWAL',
                    "User has ${pendingWithdrawals.size()} pending withdrawal(s) — approve or reject first")
            }
        }
        if (tradeRepository != null) {
            def openTrades = tradeRepository.findByParticipant(targetUserId).findAll {
                !(it.state in ['VERIFIED','CANCELLED'])
            }
            if (!openTrades.isEmpty()) {
                throw new BadRequestException('OPEN_TRADES',
                    "User has ${openTrades.size()} open trade(s) — release or cancel first")
            }
        }
        // Scrub PII. The row stays (soft-delete) for audit + chargeback
        // records — rows referencing this user id (listings, trades,
        // transactions) keep their foreign keys intact. Steam ID stays
        // so admin can still trace the account; display name is
        // replaced with a deterministic "Deleted user #N" handle.
        user.displayName = "Deleted user #${targetUserId}".toString()
        user.avatarUrl = null
        user.profileUrl = null
        user.email = null
        // V63 — clear canonical_email alongside the raw email so the
        // deleted account no longer occupies its mailbox's slot in the
        // partial UNIQUE index on canonical_email. Without this clear, a
        // legitimate user whose original Gmail mailbox got swept into a
        // deletion would be unable to reuse that same mailbox on a fresh
        // SkinBox account ever again — the soft-deleted row would keep
        // holding the unique slot.
        user.canonicalEmail = null
        user.emailVerified = false
        user.emailVerificationToken = null
        user.tradeUrl = null
        user.totpSecret = null
        user.lastTotpStep = null
        user.adminNotes = (user.adminNotes ?: '') +
            "\n[DELETION finalised by admin ${adminUserId} on ${new Date()}]"
        user.banned = true
        user.banReason = 'Account deleted at user request'
        user.deletionRequestedAt = null  // request is now fulfilled
        // Bump sessionEpoch so any still-live session (legit owner OR
        // attacker) is kicked on next /api/* request. Same rationale as
        // batch 592's banUser fix: the PII is gone but until the cookie
        // dies, the session still passes auth. Match the behaviour.
        user.sessionEpoch = System.currentTimeMillis()
        steamUserRepository.save(user)

        // Cancel any active listings so the marketplace stays clean —
        // same behaviour as banUser.
        def active = listingRepository.findActiveBySeller(targetUserId)
        active.each { it.status = 'CANCELLED' }
        if (!active.isEmpty()) listingRepository.saveAll(active)

        // Sweeper-load cleanup (batch 313). Without these, background
        // jobs keep processing orphan rows for a deleted user forever:
        //   - WatchlistAlertService sweeper scans ACTIVE alerts every
        //     5 minutes.
        //   - BuyOrderService.tryMatch walks ACTIVE orders on every
        //     new listing (bonGuard rejects the actual purchase, but
        //     each match-attempt still burns wallet + steam-user
        //     lookups).
        // Each wrapped in try/catch so one repo blip doesn't block the
        // deletion; admin can run the ban command again to re-run.
        try {
            if (watchlistAlertRepository != null) {
                def wiped = watchlistAlertRepository.deleteByUser(targetUserId)
                if (wiped > 0) log.info("finalizeDeletion: wiped ${wiped} watchlist alert(s) for user ${targetUserId}")
            }
        } catch (Exception e) {
            log.warn("finalizeDeletion: watchlist-alert cleanup failed for ${targetUserId}: ${e.message}")
        }
        try {
            if (buyOrderService != null) {
                int n = buyOrderService.cancelAllForUser(targetUserId)
                if (n > 0) log.info("finalizeDeletion: cancelled ${n} buy order(s) for user ${targetUserId}")
            }
        } catch (Exception e) {
            log.warn("finalizeDeletion: buy-order cleanup failed for ${targetUserId}: ${e.message}")
        }
        // Preference-row cleanup (batch 351). User_blocks, seller_follows,
        // and saved_searches are pure personal state — no third-party
        // interest in preserving them past the user's account deletion.
        // Per-repo try/catch so one blip doesn't poison the loop; the
        // deletion is already committed above, these are just cleanup.
        //
        // UserBlock: wipe both directions. When Alice deletes her
        // account, her block list (blocker=Alice) AND every row where
        // she was the target (blocked=Alice) both get removed so no
        // dead FKs linger on other users' block lists either.
        try {
            if (userBlockRepository != null) {
                int a = userBlockRepository.deleteByBlocker(targetUserId)
                int b = userBlockRepository.deleteByBlocked(targetUserId)
                if (a + b > 0) log.info("finalizeDeletion: wiped ${a + b} user_block row(s) for user ${targetUserId}")
            }
        } catch (Exception e) {
            log.warn("finalizeDeletion: user-block cleanup failed for ${targetUserId}: ${e.message}")
        }
        // SellerFollow: wipe both directions — as follower (the user's
        // follow list) and as seller target (incoming follower rows
        // pointing at the deleted account).
        try {
            if (sellerFollowRepository != null) {
                int a = sellerFollowRepository.deleteByFollower(targetUserId)
                int b = sellerFollowRepository.deleteBySeller(targetUserId)
                if (a + b > 0) log.info("finalizeDeletion: wiped ${a + b} seller_follow row(s) for user ${targetUserId}")
            }
        } catch (Exception e) {
            log.warn("finalizeDeletion: seller-follow cleanup failed for ${targetUserId}: ${e.message}")
        }
        try {
            if (savedSearchRepository != null) {
                int n = savedSearchRepository.deleteByUser(targetUserId)
                if (n > 0) log.info("finalizeDeletion: wiped ${n} saved_search row(s) for user ${targetUserId}")
            }
        } catch (Exception e) {
            log.warn("finalizeDeletion: saved-search cleanup failed for ${targetUserId}: ${e.message}")
        }

        auditService?.log(AuditService.USER_BANNED, adminUserId, targetUserId, null,
            "Deletion finalised for user #${targetUserId}")
        log.warn("Admin ${adminUserId} finalised deletion of user ${targetUserId}")
        [id: user.id, finalised: true]
    }

    /**
     * Read + update staff-only internal notes attached to a user. Never
     * visible to the user themselves — stored in admin_notes which is
     * @JsonIgnore'd on SteamUser. Audit-logged so abuse is traceable.
     */
    Map readAdminNotes(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        def user = steamUserRepository.findById(targetUserId)
            .orElseThrow { new NotFoundException('SteamUser', targetUserId) }
        [userId: user.id, adminNotes: user.adminNotes ?: '']
    }

    @Transactional
    Map writeAdminNotes(Long adminUserId, Long targetUserId, String notes) {
        requireAdmin(adminUserId)
        def user = steamUserRepository.findById(targetUserId)
            .orElseThrow { new NotFoundException('SteamUser', targetUserId) }
        // Cap at 4000 chars — matches the TEXT column but keeps the
        // payload reasonable + prevents admins from accidentally dumping
        // a log file into the field.
        def clean = textSanitizer?.clean(notes, 4000) ?: (notes?.take(4000))
        user.adminNotes = clean
        steamUserRepository.save(user)
        auditService?.log(AuditService.ADMIN_NOTES_UPDATED, adminUserId, targetUserId, null,
            "Updated admin notes (${(clean ?: '').length()} chars)")
        log.info("Admin ${adminUserId} updated admin notes on user ${targetUserId}")
        [userId: user.id, adminNotes: user.adminNotes ?: '']
    }

    /**
     * Wipe a user's TOTP secret — support path for locked-out users who
     * have lost access to their authenticator app. The user can then re-
     * enrol from Profile → 2FA. Deliberately high-privilege: admin-only
     * (not CSR) because it disables a second factor. Every call lands in
     * the audit log so abuse is visible.
     */
    @Transactional
    Map reset2faFor(Long adminUserId, Long targetUserId, String note) {
        requireAdmin(adminUserId)
        def target = steamUserRepository.findById(targetUserId)
            .orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        if (target.totpSecret == null || target.totpSecret.isEmpty()) {
            throw new BadRequestException("NO_TOTP",
                "User does not have 2FA enabled — nothing to reset")
        }
        target.totpSecret = null
        target.lastTotpStep = null
        // Bump sessionEpoch — 2FA reset is almost always a compromise-
        // recovery action (user lost phone / account got hijacked).
        // Revoking live sessions kicks any attacker currently signed in
        // with the old second-factor-less cookie. The legitimate user
        // just re-signs in via Steam, so no user-facing breakage.
        target.sessionEpoch = System.currentTimeMillis()
        steamUserRepository.save(target)
        def cleanNote = textSanitizer.medium(note) ?: '(no note)'
        notificationService?.safePush(targetUserId, 'TWOFA_RESET',
            "Your 2FA was reset by staff",
            "Two-factor authentication has been disabled on your account. Please re-enrol from Profile → 2FA next time you sign in.",
            null, '/profile')
        // Security alert email (batch 575). Admin-reset-2FA is a
        // high-impact action — an attacker with stolen staff creds
        // could disable a user's second factor silently unless the
        // user sees an out-of-band notification. Fire to the
        // verified email regardless of notification prefs — security
        // alerts are intentionally not opt-out-able (same policy
        // as batches 503 / 513 for email-change + trade-url-change).
        try {
            if (emailService != null && emailService.canSendSecurityTo(target)) {
                emailService.sendTwoFactorReset(target.email, target.displayName, cleanNote)
            }
        } catch (Exception e) {
            log.warn("2FA-reset email failed for user ${targetUserId}: ${e.message}")
        }
        auditService?.log(AuditService.TWOFA_RESET, adminUserId, targetUserId, null,
            "Reset 2FA for ${target.steamId64}: ${cleanNote}")
        log.info("Admin ${adminUserId} reset 2FA for user ${targetUserId}: ${cleanNote}")
        [id: target.id, totpEnabled: false]
    }

    @Transactional
    SteamUser revokeCsr(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        if (user.role != 'CSR') {
            throw new BadRequestException("NOT_CSR",
                "User is not a CSR — nothing to revoke")
        }
        user.role = 'USER'
        steamUserRepository.save(user)
        notificationService?.safePush(targetUserId, 'CSR_REVOKED',
            'CSR role revoked',
            'Your customer service role has been removed. You can still use SkinBox normally as a regular user.',
            null, '/profile')
        auditService?.log(AuditService.CSR_REVOKED, adminUserId, targetUserId, null,
            "Revoked CSR from ${user.steamId64}")
        log.info("Admin ${adminUserId} revoked CSR from ${targetUserId}")
        user
    }

    @Transactional
    Map creditWallet(Long adminUserId, Long targetUserId, BigDecimal amount, String note) {
        requireAdmin(adminUserId)
        if (amount == null || amount == BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_AMOUNT", "Amount must be non-zero")
        }
        // Hard sanity cap on the per-call adjustment size — without this
        // a typo like "1000000" (instead of "100") walks $1M out of the
        // platform wallet or into a user's account in one click. Large
        // adjustments go through the same "manual payout" path as big
        // withdrawals so they hit a second set of eyes. Keeps the admin
        // tool useful for the common $5-$500 goodwill-credit case.
        BigDecimal abs = amount.abs()
        if (abs > new BigDecimal("10000")) {
            throw new BadRequestException("ADJUSTMENT_TOO_LARGE",
                "Single admin adjustment must not exceed \$10,000 — split into smaller credits or route through manual payout")
        }
        // Note is required for audit trail — operator must leave a paper
        // trail on every adjustment, even small ones.
        if (note == null || note.trim().isEmpty()) {
            throw new BadRequestException("NOTE_REQUIRED",
                "Admin adjustments require a note for the audit trail")
        }
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        if (wallet == null) throw new NotFoundException("Wallet", targetUserId)
        wallet.balance = wallet.balance + amount
        if (wallet.balance < BigDecimal.ZERO) {
            throw new BadRequestException("WOULD_GO_NEGATIVE", "Adjustment would leave wallet negative")
        }
        walletRepository.save(wallet)

        transactionRepository.save(new Transaction(
            walletId:        wallet.id,
            type:            amount > BigDecimal.ZERO ? 'ADJUSTMENT_CREDIT' : 'ADJUSTMENT_DEBIT',
            status:          'COMPLETED',
            amount:          amount.abs(),
            currency:        wallet.currency,
            stripeReference: 'admin',
            description:     "Admin adjustment: " + (note ?: 'no note')
        ))

        notificationService?.push(targetUserId,
            amount > BigDecimal.ZERO ? 'ADMIN_CREDIT' : 'ADMIN_DEBIT',
            "Wallet adjusted by staff · ${amount > 0 ? '+' : ''}\$${amount.toPlainString()}",
            note ?: '', null, '/wallet')

        auditService?.log(AuditService.ADMIN_CREDIT, adminUserId, targetUserId, wallet.id,
            "Adjusted wallet ${wallet.username} by \$${amount}: ${note ?: '(no note)'}")
        log.info("Admin ${adminUserId} adjusted wallet ${wallet.id} by \$${amount}")
        [walletId: wallet.id, newBalance: wallet.balance]
    }

    /**
     * Freeze a user's wallet (batch 509). Softer than a ban — the user
     * can still sign in and browse, but all money-in / money-out paths
     * refuse. Use for regulatory holds, fraud-investigation pauses, or
     * a user-requested security lockout. A ban still cascades
     * everything (listings, offers, trades); this freezes just the
     * wallet. Reason is required for the audit trail.
     */
    @Transactional
    Map freezeWallet(Long adminUserId, Long targetUserId, String reason) {
        requireAdmin(adminUserId)
        if (reason == null || reason.trim().isEmpty()) {
            throw new BadRequestException("NOTE_REQUIRED",
                "Freeze reason is required for the audit trail")
        }
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        if (wallet == null) throw new NotFoundException("Wallet", targetUserId)
        if (Boolean.TRUE.equals(wallet.frozen)) {
            return [walletId: wallet.id, frozen: true, noChange: true]
        }
        wallet.frozen = true
        wallet.frozenReason = textSanitizer.medium(reason)
        wallet.frozenAt = System.currentTimeMillis()
        walletRepository.save(wallet)

        notificationService?.push(targetUserId, 'ADMIN_DEBIT',
            "Your wallet has been frozen",
            wallet.frozenReason ?: 'Contact support for details.',
            wallet.id, '/wallet')

        // Security email (batch 584). Freezing a wallet blocks
        // deposits/withdrawals/purchases — high enough impact that
        // users need to see it out-of-band even if notifications
        // are globally off. Falls into the "security alert" bucket
        // (same policy as email-change / 2FA-reset): we fire
        // regardless of emailNotificationsEnabled so an attacker
        // with a stolen session can't silence the alarm.
        try {
            if (emailService != null && emailService.canSendSecurityTo(user)) {
                emailService.sendWalletFrozen(user.email, user.displayName, wallet.frozenReason)
            }
        } catch (Exception e) {
            log.warn("Wallet-frozen email failed for user ${targetUserId}: ${e.message}")
        }

        auditService?.log('WALLET_FROZEN', adminUserId, targetUserId, wallet.id,
            "Froze wallet ${wallet.username}: ${wallet.frozenReason}")
        log.warn("Admin ${adminUserId} froze wallet ${wallet.id}: ${wallet.frozenReason}")
        [walletId: wallet.id, frozen: true, reason: wallet.frozenReason]
    }

    @Transactional
    Map unfreezeWallet(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        if (wallet == null) throw new NotFoundException("Wallet", targetUserId)
        if (!Boolean.TRUE.equals(wallet.frozen)) {
            return [walletId: wallet.id, frozen: false, noChange: true]
        }
        wallet.frozen = false
        wallet.frozenReason = null
        wallet.frozenAt = null
        walletRepository.save(wallet)

        notificationService?.push(targetUserId, 'ADMIN_CREDIT',
            "Your wallet has been unfrozen",
            "You can deposit, withdraw, and trade again.",
            wallet.id, '/wallet')

        // Unfreeze email (batch 584) — good-news counterpart to the
        // freeze alert. Closes the loop: user knows they're free to
        // transact again without opening the site.
        try {
            if (emailService != null && emailService.canSendSecurityTo(user)) {
                emailService.sendWalletUnfrozen(user.email, user.displayName)
            }
        } catch (Exception e) {
            log.warn("Wallet-unfrozen email failed for user ${targetUserId}: ${e.message}")
        }

        auditService?.log('WALLET_UNFROZEN', adminUserId, targetUserId, wallet.id,
            "Unfroze wallet ${wallet.username}")
        log.info("Admin ${adminUserId} unfroze wallet ${wallet.id}")
        [walletId: wallet.id, frozen: false]
    }

    // ── Listing moderation ──────────────────────────────────────────

    /**
     * Returns active listings with at least one user report, ordered by
     * report_count DESC so the admin triages the loudest complaints first.
     * Each row includes the top 5 recent reasons + total distinct reporters
     * so the admin sees *what* is being flagged without opening a drill-down.
     */
    List<Map> findReportedListings(int limit = 50) {
        def lim = Math.min(Math.max(limit, 1), 200)
        def rows = listingRepository.selectReportedActive()?.take(lim)
        if (rows == null || rows.isEmpty()) return []
        def out = []
        rows.each { l ->
            def reports = listingReportRepository?.findByListingIdOrderByCreatedAtDesc(l.id) ?: []
            def reasons = reports.take(5).collect { it.reason }
            def distinctReporters = reports.collect { it.reporterUserId }.unique().size()
            out << [
                id:               l.id,
                itemName:         l.item?.name,
                itemId:           l.item?.id,
                price:            l.price,
                sellerName:       l.sellerName,
                sellerUserId:     l.sellerUserId,
                listedAt:         l.listedAt,
                status:           l.status,
                reportCount:      l.reportCount ?: 0,
                lastReportedAt:   l.lastReportedAt,
                distinctReporters: distinctReporters,
                topReasons:       reasons,
                recentNotes:      reports.take(3).collect { [reason: it.reason, note: it.note, at: it.createdAt] }
            ]
        }
        out
    }

    @Transactional
    Map forceCancelListing(Long adminUserId, Long listingId, String reason) {
        requireAdmin(adminUserId)
        def listing = listingRepository.findById(listingId).orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.status != 'ACTIVE') {
            throw new BadRequestException("NOT_ACTIVE", "Listing is not active")
        }
        def cleanReason = textSanitizer.medium(reason) ?: 'policy violation'
        // Trade-in-escrow guard — must run BEFORE we flip the listing to
        // CANCELLED. If a buyer has already been escrowed against this
        // listing, tradeService.cancel refunds them and returns the item
        // to inventory. Without this, admin force-cancel traps the
        // buyer's funds and orphans the trade row. Mirrors the same
        // guard on SellService.cancelListing (batch 69 / trade-v1).
        if (tradeRepository != null && tradeService != null) {
            try {
                def openTrade = tradeRepository.findByListingId(listingId)
                if (openTrade != null && openTrade.state != 'VERIFIED' && openTrade.state != 'CANCELLED') {
                    tradeService.cancel(adminUserId, openTrade.id,
                        "Staff removed listing: ${cleanReason}")
                }
            } catch (Exception e) {
                log.warn("Admin force-cancel trade refund failed for listing ${listingId}: ${e.message}")
            }
        }
        listing.status = 'CANCELLED'
        listingRepository.save(listing)
        if (listing.sellerUserId != null) {
            notificationService?.push(listing.sellerUserId, 'LISTING_REMOVED',
                "Your listing was removed by staff",
                "${listing.item?.name}: ${cleanReason}", listing.id,
                listing.item?.id != null ? "/item/${listing.item.id}" : '/me/stall')
        }
        // Live-offer cleanup — same pattern as SellService.cancelListing.
        // Offer rows whose listing got force-cancelled should flip to
        // CANCELLED so the buyer's Offers tab reflects reality.
        //
        // "Live" is PENDING *or* COUNTERED — both are negotiation states
        // the buyer can still act on. The old findPendingForListing swept
        // PENDING-only: a COUNTERED buyer original was left dangling (its
        // child SELLER counter got cancelled but the COUNTERED parent
        // never reached a terminal state), so the buyer kept seeing a live
        // offer on a removed listing. findByListingId returns every offer
        // in one indexed query; we filter to the live pair here. (2026-05-20)
        try {
            def live = offerRepository.findByListingId(listingId)
                .findAll { it.status == 'PENDING' || it.status == 'COUNTERED' }
            def itemName = listing.item?.name ?: 'this item'
            def itemId = listing.item?.id
            live.each { o ->
                o.status = 'CANCELLED'
                o.updatedAt = System.currentTimeMillis()
            }
            if (!live.isEmpty()) offerRepository.saveAll(live)
            // Dedup per buyer — a buyer holding both a COUNTERED original
            // and its PENDING child counter has two rows on this listing
            // but should get one "offer cancelled" ping, not two.
            def notified = new HashSet<Long>()
            live.each { o ->
                if (o.buyerUserId == null || !notified.add(o.buyerUserId as Long)) return
                try {
                    notificationService?.push(o.buyerUserId, 'OFFER_REJECTED',
                        "Offer cancelled · ${itemName}",
                        "Staff removed the listing your offer was tied to.",
                        o.id,
                        itemId != null ? "/item/${itemId}" : '/offers')
                } catch (Exception e) {
                    log.warn("Admin offer-cancel push failed for buyer ${o.buyerUserId}: ${e.message}")
                }
            }
        } catch (Exception e) {
            log.warn("Admin force-cancel offer cleanup failed for listing ${listingId}: ${e.message}")
        }
        // Mirror of SellService.cancelListing's auction-bidder fanout —
        // admin force-cancels should surface the same courtesy notification
        // so bidders aren't left wondering why their bid disappeared.
        if (listing.listingType == 'AUCTION' && bidRepository != null) {
            try {
                def bids = bidRepository.findByListing(listingId)
                def uniqueBidders = new LinkedHashSet<Long>()
                bids.each { b ->
                    if (b.status == 'WINNING' || b.status == 'OUTBID') {
                        b.status = 'CANCELLED'
                    }
                    if (b.bidderUserId != null) uniqueBidders.add(b.bidderUserId as Long)
                }
                if (!bids.isEmpty()) bidRepository.saveAll(bids)
                def itemName = listing.item?.name ?: 'an auction'
                uniqueBidders.each { uid ->
                    try {
                        notificationService?.push(uid, 'AUCTION_CANCELLED',
                            "Auction cancelled · ${itemName}",
                            'Staff removed this listing. No charge was made.',
                            listing.id,
                            listing.item?.id != null ? "/item/${listing.item.id}" : null)
                    } catch (Exception e) {
                        log.warn("AUCTION_CANCELLED (admin) push failed for uid=${uid}: ${e.message}")
                    }
                }
            } catch (Exception e) {
                log.warn("Auction bidder fan-out (admin force-cancel) failed for listing {}: {}", listingId, e.message)
            }
        }
        // Cart-holder fan-out (batch 504). Same pattern as SellService
        // self-cancel: tell every user with this listing queued in
        // their cart that the listing is gone, and scrub the rows so
        // the next /api/cart fetch is clean. Passes null for
        // excludeUserId via the seller's id (or 0L for system listings)
        // so admins don't get spammed about their own carts.
        if (cartItemRepository != null && notificationService != null) {
            try {
                def excludeUid = listing.sellerUserId ?: 0L
                def others = cartItemRepository.findOtherUsersWithListing(listingId, excludeUid) ?: []
                if (!others.isEmpty()) {
                    def itemName = listing.item?.name ?: 'an item'
                    def itemId = listing.item?.id
                    // Drop banned recipients (batch 316/317) — same bug
                    // class as the PurchaseService.buy fan-out.
                    def recipients = notificationService.filterActiveRecipients(others.take(50) as List<Long>)
                    recipients.each { uid ->
                        try {
                            notificationService.push(uid, 'CART_ITEM_SOLD',
                                "Cart item removed · ${itemName}",
                                "${itemName} was removed by staff. Other listings may still be available — find a similar one in the marketplace.",
                                listingId,
                                itemId != null ? "/item/${itemId}" : '/cart')
                        } catch (Exception e) {
                            log.warn("CART_ITEM_SOLD (admin-cancel) push failed for uid=${uid}: ${e.message}")
                        }
                    }
                }
                try {
                    cartItemRepository.deleteAllByListing(listingId)
                } catch (Exception e) {
                    log.warn("CART scrub failed for listing=${listingId} on admin-cancel: ${e.message}")
                }
            } catch (Exception e) {
                log.warn("CART fan-out failed for listing=${listingId} on admin-cancel: ${e.message}")
            }
        }

        // Close the loop for every user who reported this listing — they
        // filed a report and deserve to know their signal got action. Dedup
        // by reporter so two reports from the same user only trigger one
        // notification. Fire-and-forget; an exception here shouldn't roll
        // back the force-cancel itself (the listing is already gone and the
        // audit row is already written a few lines down).
        try {
            def reports = listingReportRepository?.findByListingIdOrderByCreatedAtDesc(listingId) ?: []
            def distinct = reports.collect { it.reporterUserId }.unique()
            distinct.each { uid ->
                if (uid == null) return
                try {
                    notificationService?.push(uid, 'REPORT_ACTIONED',
                        "Thanks — your report was actioned",
                        "The listing you flagged (${listing.item?.name ?: 'item'}) has been removed.",
                        listing.id, null)
                } catch (Exception inner) {
                    log.warn("Report-closed notification failed for user ${uid}: ${inner.message}")
                }
            }
        } catch (Exception outer) {
            log.warn("Failed to close report loop for listing ${listingId}: ${outer.message}")
        }
        auditService?.log(AuditService.LISTING_FORCE_CANCELLED, adminUserId, listing.sellerUserId, listing.id,
            "Force-cancelled listing ${listing.item?.name}: ${cleanReason}")
        log.info("Admin ${adminUserId} force-cancelled listing ${listingId}: ${cleanReason}")
        [id: listing.id, status: listing.status]
    }

    /**
     * Dismiss a listing's user reports without cancelling it — for when an
     * admin reviews and decides the reports were unfounded. Clears the
     * aggregate counter so the listing drops off the queue, and notifies
     * each distinct reporter that their report was reviewed (without
     * implying a bad faith on the reporter's part).
     */
    @Transactional
    Map dismissListingReports(Long adminUserId, Long listingId, String note) {
        requireAdmin(adminUserId)
        def listing = listingRepository.findById(listingId).orElseThrow { new NotFoundException("Listing", listingId) }
        def reports = listingReportRepository?.findByListingIdOrderByCreatedAtDesc(listingId) ?: []
        def cleanNote = textSanitizer.medium(note) ?: 'no policy violation found'
        def distinct = reports.collect { it.reporterUserId }.unique()
        distinct.each { uid ->
            if (uid == null) return
            try {
                notificationService?.push(uid, 'REPORT_REVIEWED',
                    "Your report was reviewed",
                    "Admins looked at the listing (${listing.item?.name ?: 'item'}) and decided not to take action.",
                    listing.id, null)
            } catch (Exception inner) {
                log.warn("Report-reviewed notification failed for user ${uid}: ${inner.message}")
            }
        }
        // Null out the aggregate so the reports queue drops this listing. The
        // detail rows in listing_reports stay — admins can still read them
        // later if a pattern emerges, and keeping history avoids the case
        // where the same listing gets re-reported and the prior context is
        // gone.
        listing.reportCount = 0
        listing.lastReportedAt = null
        listingRepository.save(listing)
        // Audit-log the dismissal — every other listing-moderation
        // mutation (force-cancel) writes an audit row; clearing a
        // listing's report counter is a staff judgement call that
        // belongs in the trail too, so an unfounded-vs-missed dismissal
        // is reviewable after the fact.
        try {
            auditService?.log('LISTING_REPORTS_DISMISSED', adminUserId, listing.sellerUserId, listing.id,
                "Dismissed ${reports.size()} report(s) on listing ${listing.item?.name ?: listingId}: ${cleanNote}")
        } catch (Exception e) {
            log.warn("LISTING_REPORTS_DISMISSED audit failed for listing ${listingId}: ${e.message}")
        }
        log.info("Admin ${adminUserId} dismissed ${reports.size()} reports on listing ${listingId}: ${cleanNote}")
        [id: listing.id, dismissed: reports.size(), distinctReporters: distinct.size()]
    }

    // ── Support ─────────────────────────────────────────────────────

    List<Map> listAllTickets(String statusFilter, String search = null) {
        def status = statusFilter ? statusFilter.toUpperCase() : ''
        def q = (search ?: '').trim()
        // Cap the free-text search (batch 576) — anything longer is abuse.
        // Strip null bytes so a crafted input can't poison Postgres.
        if (q.length() > 100) q = q.substring(0, 100)
        q = q.replace('\u0000', '')
        // 500-row cap — consistent with listWithdrawals / listTrades.
        def rows = q.isEmpty()
            ? (supportTicketRepository.findForAdmin(status) ?: []).take(500)
            : (supportTicketRepository.searchForAdmin(status, q) ?: []).take(500)
        if (rows.isEmpty()) return []
        // Fraud-signal enrichment (batch 525). Same pattern as batch 514
        // (withdrawals) and 515 (disputes) — give staff triage context
        // at the list level so they don't bounce into the user detail
        // drawer for every ticket. Batches user lookups by id (unique
        // set) to keep this O(N + K).
        def userIds = rows*.userId.findAll { it != null }.unique()
        def userById = userIds.isEmpty() ? [:] :
            steamUserRepository.findAllById(userIds).collectEntries { [(it.id): it] }
        rows.collect { t ->
            def user = userById[t.userId]
            // lifetimeDisputes — scan last 500 tx for DEPOSIT + DISPUTED
            // or DISPUTE_CLEARED marker. Null-safe for users without a
            // wallet yet (shouldn't happen post-signup but belt-and-braces).
            long lifetimeDisputes = 0L
            boolean walletFrozen = false
            if (user != null) {
                try {
                    def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
                    if (wallet != null) {
                        walletFrozen = Boolean.TRUE.equals(wallet.frozen)
                        def allTx = transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.id,
                            org.springframework.data.domain.PageRequest.of(0, 500))
                        lifetimeDisputes = allTx.count { tx ->
                            tx.type == 'DEPOSIT' &&
                            (tx.status == 'DISPUTED' || (tx.description ?: '').contains('DISPUTE_CLEARED'))
                        } as long
                    }
                } catch (Exception ignored) { /* fall through */ }
            }
            [
                id:             t.id,
                subject:        t.subject,
                category:       t.category,
                status:         t.status,
                userId:         t.userId,
                username:       t.username,
                createdAt:      t.createdAt,
                updatedAt:      t.updatedAt,
                // Fraud signals (batch 525).
                userCreatedAt:  user?.createdAt,
                userEmailVerified: user?.emailVerified,
                userBanned:     user?.banned,
                walletFrozen:   walletFrozen,
                lifetimeDisputes: lifetimeDisputes
            ]
        }
    }

    Map getTicket(Long adminUserId, Long ticketId) {
        requireAdmin(adminUserId)
        def t = supportTicketRepository.findById(ticketId).orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        [ticket: t, messages: supportMessageRepository.findByTicket(ticketId)]
    }

    @Transactional
    SupportMessage staffReply(Long adminUserId, Long ticketId, String body) {
        requireAdmin(adminUserId)
        def admin = steamUserRepository.findById(adminUserId).orElseThrow { new ForbiddenException("Unknown admin") }
        def t = supportTicketRepository.findById(ticketId).orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        def cleanBody = textSanitizer.body(body)
        if (!cleanBody || cleanBody.isEmpty()) {
            throw new BadRequestException("INVALID_BODY", "Reply body required")
        }
        def msg = supportMessageRepository.save(new SupportMessage(
            ticketId:   ticketId,
            author:     'STAFF',
            authorName: textSanitizer.cleanShort(admin.displayName ?: 'Staff'),
            body:       cleanBody
        ))
        t.status = 'WAITING_USER'
        t.updatedAt = System.currentTimeMillis()
        supportTicketRepository.save(t)

        notificationService?.push(t.userId, 'SUPPORT_REPLY',
            "New reply on ticket #${t.id}",
            t.subject, t.id, '/support')
        // Email the user (batch 475 — same path as CsrService.reply).
        if (emailService != null) {
            try {
                def user = steamUserRepository.findById(t.userId).orElse(null)
                if (emailService.canSendSecurityTo(user)) {
                    emailService.sendSupportReply(user.email, user.displayName,
                        t.id, t.subject, cleanBody)
                }
            } catch (Exception e) {
                log.warn("Support-reply email failed for ticket ${t.id} (admin path): ${e.message}")
            }
        }
        // Audit-log the staff reply — every other staff mutation (bans,
        // credits, force-cancels) leaves an audit row; ticket actions
        // were the gap. Without this a staff member could read and reply
        // to a user's ticket with no forensic trail.
        auditService?.log(AuditService.TICKET_REPLIED, adminUserId, t.userId, ticketId,
            "Replied to ticket #${ticketId}: ${t.subject}")
        msg
    }

    @Transactional
    SupportTicket closeTicket(Long adminUserId, Long ticketId) {
        requireAdmin(adminUserId)
        def t = supportTicketRepository.findById(ticketId).orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        // State-machine guard — mirror SupportService.resolve. Re-resolving
        // an already-RESOLVED ticket silently bumped updatedAt for no
        // reason; a stale admin tab / double-click now gets a clean 400.
        if (t.status == 'RESOLVED') {
            throw new BadRequestException("ALREADY_RESOLVED", "Ticket is already resolved")
        }
        t.status = 'RESOLVED'
        t.updatedAt = System.currentTimeMillis()
        supportTicketRepository.save(t)
        auditService?.log(AuditService.TICKET_CLOSED, adminUserId, t.userId, ticketId,
            "Closed ticket #${ticketId}: ${t.subject}")
        t
    }

    // ── Trade moderation ───────────────────────────────────────────

    /**
     * Admin view of all open or disputed trades. Ops uses this to clear
     * the queue when a buyer never confirms OR when a dispute fires.
     * Previously `findAll()` + Groovy filter + Groovy sort — now pushed
     * down to a single indexed JPQL query.
     */
    List<Map> listTrades(String stateFilter) {
        if (tradeRepository == null) return []
        def state = (stateFilter && stateFilter != 'ALL') ? stateFilter : ''
        // Hard-cap at 500 — matches the admin withdrawals queue cap. Past
        // that, ops should use the audit log / CSV for historical digging
        // rather than hydrating a mega-list into memory on every page.
        def trades = tradeRepository.findForAdmin(state)?.take(500) ?: []
        if (trades.isEmpty()) return []
        // Fraud-signal enrichment (batch 526). Batches buyer + seller
        // user lookups so dispute triage has the context inline: who's
        // the repeat-chargeback offender, whose account is fresh,
        // which side is banned. Tally user ids from BOTH sides into
        // one unique set so the join is O(distinct participants).
        def userIds = (trades*.buyerUserId + trades*.sellerUserId).findAll { it != null }.unique()
        def userById = userIds.isEmpty() ? [:] :
            steamUserRepository.findAllById(userIds).collectEntries { [(it.id): it] }
        // Lifetime chargeback cache per user id — computed once per
        // distinct id, reused for both sides of every trade where
        // they participate. Same pattern as batches 514 / 515 / 525.
        Map<Long, Long> lifetimeCache = [:]
        Closure<Long> computeLifetimeDisputes = { Long uid ->
            if (uid == null) return 0L
            if (lifetimeCache.containsKey(uid)) return lifetimeCache[uid]
            long total = 0L
            try {
                def u = userById[uid]
                if (u != null) {
                    def w = walletRepository.findByUsername("steam_${u.steamId64}")
                    if (w != null) {
                        def tx = transactionRepository.findByWalletIdOrderByCreatedAtDesc(w.id,
                            org.springframework.data.domain.PageRequest.of(0, 500))
                        total = tx.count { t ->
                            t.type == 'DEPOSIT' &&
                            (t.status == 'DISPUTED' || (t.description ?: '').contains('DISPUTE_CLEARED'))
                        } as long
                    }
                }
            } catch (Exception ignored) { /* fall through */ }
            lifetimeCache[uid] = total
            return total
        }
        trades.collect { t ->
            def buyer = userById[t.buyerUserId]
            def seller = userById[t.sellerUserId]
            [
                id:           t.id,
                listingId:    t.listingId,
                itemId:       t.itemId,
                itemName:     t.itemName,
                itemImageUrl: t.itemImageUrl,
                buyerUserId:  t.buyerUserId,
                sellerUserId: t.sellerUserId,
                buyerWalletId:  t.buyerWalletId,
                sellerWalletId: t.sellerWalletId,
                price:        t.price,
                feeAmount:    t.feeAmount,
                state:        t.state,
                note:         t.note,
                createdAt:    t.createdAt,
                updatedAt:    t.updatedAt,
                settledAt:    t.settledAt,
                sentAt:       t.sentAt,
                // Batch 781 — surface the seller-captured Steam trade-offer
                // URL so admin trades-tab + dispute triage can link straight
                // to the exact offer without digging through Steam inboxes.
                tradeOfferUrl: t.tradeOfferUrl,
                // Fraud signals (batch 526) — two halves per row.
                buyerName:             buyer?.displayName,
                buyerCreatedAt:        buyer?.createdAt,
                buyerBanned:           buyer?.banned,
                buyerEmailVerified:    buyer?.emailVerified,
                buyerLifetimeDisputes: computeLifetimeDisputes(t.buyerUserId),
                sellerName:            seller?.displayName,
                sellerCreatedAt:       seller?.createdAt,
                sellerBanned:          seller?.banned,
                sellerEmailVerified:   seller?.emailVerified,
                sellerLifetimeDisputes: computeLifetimeDisputes(t.sellerUserId)
            ]
        }
    }

    /** Force-release a trade regardless of its current state. Delegates
     *  to `TradeService.adminRelease` (batch 327) which skips the
     *  buyer-side ban-guard — admin may need to release a trade even
     *  when the buyer was banned in the window between purchase and
     *  release. The prior implementation routed through
     *  `tradeService.buyerConfirm` which DID assert-not-banned on the
     *  buyer, making force-release fail exactly when it was most
     *  needed (banned-account cleanup). */
    @Transactional
    Map forceReleaseTrade(Long adminUserId, Long tradeId, String reason) {
        requireAdmin(adminUserId)
        if (tradeService == null || tradeRepository == null) {
            throw new BadRequestException("UNSUPPORTED", "Trade system not available")
        }
        def t = tradeRepository.findById(tradeId).orElseThrow { new NotFoundException("Trade", tradeId) }
        if (t.state in ['VERIFIED','CANCELLED']) {
            throw new BadRequestException("ALREADY_SETTLED", "Trade is already settled")
        }
        def released = tradeService.adminRelease(adminUserId, tradeId, reason)
        // Audit row is written inside TradeService.adminRelease (line ~633)
        // so this wrapper deliberately does NOT log a second one — a
        // duplicate audit row used to land on every force-release, doubling
        // the TRADE_FORCE_RELEASED count and polluting the per-actor audit
        // filter. forceCancelTrade is symmetric: its audit row stays here
        // because TradeService.cancel doesn't write TRADE_FORCE_CANCELLED
        // (cancel is the shared user/admin path).
        [id: released.id, state: released.state]
    }

    /** Force-cancel a trade and refund the buyer. */
    @Transactional
    Map forceCancelTrade(Long adminUserId, Long tradeId, String reason) {
        requireAdmin(adminUserId)
        if (tradeService == null) {
            throw new BadRequestException("UNSUPPORTED", "Trade system not available")
        }
        def cancelled = tradeService.cancel(adminUserId, tradeId, "Admin: ${reason ?: '(no reason)'}")
        auditService?.log(AuditService.TRADE_FORCE_CANCELLED, adminUserId, cancelled.buyerUserId, cancelled.id,
            "Force-cancelled: ${reason ?: '(no reason)'}")
        [id: cancelled.id, state: cancelled.state]
    }

    private void notifyWalletOwner(Long walletId, String kind, String title, String body, Long refId, String path = '/wallet') {
        def id = walletOwnerId(walletId)
        if (id != null) notificationService?.push(id, kind, title, body, refId, path)
    }

    /** Resolve the SteamUser.id that owns a given wallet, or null for non-steam wallets. */
    private Long walletOwnerId(Long walletId) {
        def wallet = walletRepository.findById(walletId).orElse(null)
        if (wallet?.username?.startsWith('steam_')) {
            def steamId = wallet.username.substring('steam_'.size())
            def user = steamUserRepository.findBySteamId64(steamId)
            return user?.id
        }
        null
    }

    /** Resolve the SteamUser row owning a wallet; returns null if the wallet
     *  isn't steam-backed or the user has no row. */
    private com.sboxmarket.model.SteamUser walletOwnerUser(Long walletId) {
        def wallet = walletRepository.findById(walletId).orElse(null)
        if (wallet?.username?.startsWith('steam_')) {
            def steamId = wallet.username.substring('steam_'.size())
            return steamUserRepository.findBySteamId64(steamId)
        }
        null
    }
}
