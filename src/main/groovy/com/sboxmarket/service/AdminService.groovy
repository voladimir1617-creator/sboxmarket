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
    @Autowired(required = false) javax.sql.DataSource dataSource
    @Autowired BanGuard banGuard
    @Autowired AdminAuthorization adminAuthorization
    @Autowired(required = false) EmailService emailService

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
            fees24h:                  (fees24h as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
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
                schemaVersion: 16   // bumps when a new Flyway migration lands
            ]
        ]
    }

    // ── Withdrawals ─────────────────────────────────────────────────

    List<Map> listWithdrawals(String statusFilter) {
        // Query by (type, status) via the indexed `idx_tx_type_status` lookup
        // instead of scanning every transaction row. Default filter is
        // 'PENDING' so the admin panel always sees a manageable list.
        // ORDER BY is now pushed into SQL instead of Groovy .sort.
        def status = (statusFilter ?: 'PENDING').toUpperCase()
        def all = transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('WITHDRAW', status)
        if (all.isEmpty()) return []

        // Batch-resolve every wallet in one SQL call instead of per-row
        // `walletRepository.findById(...)` — that was an N+1 on the admin
        // withdrawal queue, firing 1 + N queries per page view.
        def walletIds = all*.walletId.findAll { it != null }.unique()
        def walletById = walletIds.isEmpty() ? [:] :
            walletRepository.findAllById(walletIds).collectEntries { [(it.id): it] }

        all.collect { tx ->
            def wallet = walletById[tx.walletId]
            [
                id:              tx.id,
                walletId:        tx.walletId,
                walletUsername:  wallet?.username,
                amount:          tx.amount,
                currency:        tx.currency,
                status:          tx.status,
                destination:     tx.stripeReference,
                description:     tx.description,
                createdAt:       tx.createdAt,
                updatedAt:       tx.updatedAt
            ]
        }
    }

    @Transactional
    Map approveWithdrawal(Long adminUserId, Long txId, String payoutRef) {
        requireAdmin(adminUserId)
        def tx = transactionRepository.findById(txId).orElseThrow { new NotFoundException("Transaction", txId) }
        if (tx.type != 'WITHDRAW') throw new BadRequestException("NOT_WITHDRAWAL", "Transaction is not a withdrawal")
        if (tx.status != 'PENDING') throw new BadRequestException("NOT_PENDING", "Withdrawal is not pending (status=${tx.status})")
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
                if (ownerUser != null && Boolean.TRUE.equals(ownerUser.emailVerified) && ownerUser.email) {
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
                if (ownerUser != null && Boolean.TRUE.equals(ownerUser.emailVerified) && ownerUser.email) {
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

    List<SteamUser> listUsers(String search) {
        // Search path uses the V9 indexes (idx_steam_users_display_lower +
        // the unique steamId64 index) so admin user lookup stays fast even
        // at 1M+ users. Capped at 200 rows for the initial listing view —
        // the admin Users tab only renders the first page anyway.
        def sort = org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, 'createdAt')
        def page = org.springframework.data.domain.PageRequest.of(0, 200, sort)
        if (search) {
            return steamUserRepository.searchByNameOrSteamId(search.trim(), page)
        }
        // Unfiltered list: PageRequest drives a SQL `LIMIT 200` directly
        // instead of the old `findAll(sort).take(200)` which pulled every
        // user row into memory before slicing. For a 1M-user table the
        // difference is ~1M rows vs ~200 rows off the disk per admin
        // page view.
        steamUserRepository.findAll(page).content
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
        steamUserRepository.save(user)

        // Cancel all the user's active listings so the marketplace stays clean
        def active = listingRepository.findActiveBySeller(targetUserId)
        active.each { it.status = 'CANCELLED' }
        listingRepository.saveAll(active)

        notificationService?.push(targetUserId, 'ACCOUNT_BANNED',
            "Your account has been banned",
            user.banReason, null, '/profile')

        // Email the user too if they've confirmed an address. Ban is a
        // significant account change — the notification bell may go
        // unchecked for days, but the email lands in their inbox.
        // Silent-fail: a broken mail relay mustn't roll back the ban.
        if (emailService != null && Boolean.TRUE.equals(user.emailVerified) && user.email) {
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

    @Transactional
    SteamUser unbanUser(Long adminUserId, Long targetUserId) {
        requireAdmin(adminUserId)
        def user = steamUserRepository.findById(targetUserId).orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        user.banned = false
        user.banReason = null
        steamUserRepository.save(user)
        notificationService?.push(targetUserId, 'ACCOUNT_UNBANNED',
            "Your account has been reinstated", null, null)
        if (emailService != null && Boolean.TRUE.equals(user.emailVerified) && user.email) {
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
        notificationService?.push(targetUserId, 'ADMIN_GRANTED',
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
        notificationService?.push(targetUserId, 'ADMIN_REVOKED',
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
        notificationService?.push(targetUserId, 'CSR_GRANTED',
            'You are now a customer service rep',
            'You have been granted the CSR role on SkinBox. The 🎧 Customer Service panel is now available from your user menu.',
            null, '/csr')
        auditService?.log(AuditService.CSR_GRANTED, adminUserId, targetUserId, null,
            "Granted CSR to ${user.steamId64}")
        log.info("Admin ${adminUserId} granted CSR to ${targetUserId}")
        user
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
        steamUserRepository.save(user)

        // Cancel any active listings so the marketplace stays clean —
        // same behaviour as banUser.
        def active = listingRepository.findActiveBySeller(targetUserId)
        active.each { it.status = 'CANCELLED' }
        if (!active.isEmpty()) listingRepository.saveAll(active)

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
        auditService?.log(AuditService.ADMIN_GRANTED, adminUserId, targetUserId, null,
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
        steamUserRepository.save(target)
        def cleanNote = textSanitizer.medium(note) ?: '(no note)'
        notificationService?.push(targetUserId, 'TWOFA_RESET',
            "Your 2FA was reset by staff",
            "Two-factor authentication has been disabled on your account. Please re-enrol from Profile → 2FA next time you sign in.",
            null, '/profile')
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
        notificationService?.push(targetUserId, 'CSR_REVOKED',
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
        listing.status = 'CANCELLED'
        listingRepository.save(listing)
        def cleanReason = textSanitizer.medium(reason) ?: 'policy violation'
        if (listing.sellerUserId != null) {
            notificationService?.push(listing.sellerUserId, 'LISTING_REMOVED',
                "Your listing was removed by staff",
                "${listing.item?.name}: ${cleanReason}", listing.id,
                listing.item?.id != null ? "/item/${listing.item.id}" : '/me/stall')
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
        log.info("Admin ${adminUserId} dismissed ${reports.size()} reports on listing ${listingId}: ${cleanNote}")
        [id: listing.id, dismissed: reports.size(), distinctReporters: distinct.size()]
    }

    // ── Support ─────────────────────────────────────────────────────

    List<Map> listAllTickets(String statusFilter) {
        def status = statusFilter ? statusFilter.toUpperCase() : ''
        def rows = supportTicketRepository.findForAdmin(status)
        rows.collect { t ->
            [
                id:        t.id,
                subject:   t.subject,
                category:  t.category,
                status:    t.status,
                userId:    t.userId,
                username:  t.username,
                createdAt: t.createdAt,
                updatedAt: t.updatedAt
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
        msg
    }

    @Transactional
    SupportTicket closeTicket(Long adminUserId, Long ticketId) {
        requireAdmin(adminUserId)
        def t = supportTicketRepository.findById(ticketId).orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        t.status = 'RESOLVED'
        t.updatedAt = System.currentTimeMillis()
        supportTicketRepository.save(t)
    }

    // ── Trade moderation ───────────────────────────────────────────

    /**
     * Admin view of all open or disputed trades. Ops uses this to clear
     * the queue when a buyer never confirms OR when a dispute fires.
     * Previously `findAll()` + Groovy filter + Groovy sort — now pushed
     * down to a single indexed JPQL query.
     */
    List<com.sboxmarket.model.Trade> listTrades(String stateFilter) {
        if (tradeRepository == null) return []
        def state = (stateFilter && stateFilter != 'ALL') ? stateFilter : ''
        tradeRepository.findForAdmin(state)
    }

    /** Force-release a trade regardless of its current state. */
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
        // Short-circuit: flip to PENDING_BUYER_CONFIRM then call release() via
        // the regular buyerConfirm path — but we're admin, not the buyer, so
        // we invoke the state machine directly.
        t.state = 'PENDING_BUYER_CONFIRM'
        tradeRepository.save(t)
        try {
            tradeService.buyerConfirm(t.buyerUserId, t.id)
        } catch (Exception e) {
            log.error("Admin force-release failed for trade ${t.id}: ${e.message}")
            throw e
        }
        auditService?.log('TRADE_FORCE_RELEASED', adminUserId, t.sellerUserId, t.id,
            "Force-released: ${reason ?: '(no reason)'}")
        [id: t.id, state: 'VERIFIED']
    }

    /** Force-cancel a trade and refund the buyer. */
    @Transactional
    Map forceCancelTrade(Long adminUserId, Long tradeId, String reason) {
        requireAdmin(adminUserId)
        if (tradeService == null) {
            throw new BadRequestException("UNSUPPORTED", "Trade system not available")
        }
        def cancelled = tradeService.cancel(adminUserId, tradeId, "Admin: ${reason ?: '(no reason)'}")
        auditService?.log('TRADE_FORCE_CANCELLED', adminUserId, cancelled.buyerUserId, cancelled.id,
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
