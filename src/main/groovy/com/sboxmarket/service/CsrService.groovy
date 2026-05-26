package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.model.Transaction
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Customer Service Representative (CSR) service — the limited-power companion
 * to AdminService. A CSR is anyone whose role is `CSR` OR `ADMIN`, so admins
 * automatically inherit every CSR capability.
 *
 * What CSRs CAN do:
 *  - Look up any user's profile, wallet balance, recent transactions
 *  - Reply to support tickets and close them
 *  - Issue small wallet credits as goodwill compensation (capped by
 *    `csr.credit-cap` — default \$25 per single adjustment)
 *  - Flag a listing for admin review (adds a system note, doesn't remove it)
 *
 * What CSRs CANNOT do — these require full ADMIN and live in AdminService:
 *  - Approve or reject withdrawals
 *  - Ban, unban, grant, or revoke admin
 *  - Force-cancel listings
 *  - Process Stripe refunds
 *  - Adjust wallets by more than the credit cap
 *
 * This separation exists for damage control: a compromised CSR account
 * cannot drain wallets or escape through admin grants.
 */
@Service
@Slf4j
class CsrService {

    @Value('${csr.credit-cap:25.00}') String creditCapStr

    @Autowired SteamUserRepository steamUserRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired ListingRepository listingRepository
    @Autowired OfferRepository offerRepository
    @Autowired SupportTicketRepository supportTicketRepository
    @Autowired SupportMessageRepository supportMessageRepository
    @Autowired NotificationService notificationService
    @Autowired TextSanitizer textSanitizer
    @Autowired(required = false) EmailService emailService
    @Autowired(required = false) AuditService auditService
    @Autowired(required = false) com.sboxmarket.repository.ReviewRepository reviewRepository

    // ── Auth ────────────────────────────────────────────────────────

    /** CSR OR ADMIN — used as the default gate on every CSR endpoint. */
    void requireCsr(Long userId) {
        // Null guard mirrors `isCsr` AND AdminAuthorization.requireAdmin. A
        // `null` userId reaching this method (e.g. an unauthenticated
        // controller path) used to fall into Spring Data's `findById(null)`,
        // which throws an `IllegalArgumentException` and surfaces as an
        // opaque 500 instead of the friendly 403 the controller layer maps
        // `ForbiddenException` onto. Reject up-front so the auth failure
        // mode is consistent regardless of whether the caller went through
        // `isCsr` first. Sibling: AdminAuthorization.requireAdmin.
        if (userId == null) {
            throw new ForbiddenException("Customer service privileges required")
        }
        def user = steamUserRepository.findById(userId).orElseThrow { new ForbiddenException("Unknown user") }
        if (!(user.role in ['CSR', 'ADMIN'])) {
            throw new ForbiddenException("Customer service privileges required")
        }
    }

    /** True if the user's role unlocks the CSR panel in the UI. */
    boolean isCsr(Long userId) {
        if (userId == null) return false
        def user = steamUserRepository.findById(userId).orElse(null)
        user?.role in ['CSR', 'ADMIN']
    }

    // ── Dashboard ───────────────────────────────────────────────────

    Map dashboardStats() {
        def now = System.currentTimeMillis()
        def waitingStaff = supportTicketRepository.countByStatus('WAITING_STAFF')
        def waitingUser  = supportTicketRepository.countByStatus('WAITING_USER')
        def open         = supportTicketRepository.countOpen()
        def oldest       = supportTicketRepository.oldestWaitingStaffUpdatedAt()
        [
            openTickets:        open,
            waitingStaff:       waitingStaff,
            waitingUser:        waitingUser,
            oldestWaitingAgeMs: oldest != null ? (now - oldest) : 0L,
            creditCap:          new BigDecimal(creditCapStr)
        ]
    }

    // ── User lookup ─────────────────────────────────────────────────

    Map lookupUser(String query) {
        if (!query) return [matches: []]
        def q = query.trim()
        if (q.isEmpty()) return [matches: []]

        // Use the indexed search method instead of the old full-table scan.
        // PageRequest.of(0, 20) caps results at 20 so a single CSR search
        // never dumps the whole user table.
        def users = steamUserRepository.searchByNameOrSteamId(
            q, org.springframework.data.domain.PageRequest.of(0, 20))

        // Allow a numeric id lookup as a power-user convenience — matches
        // `id == q` exactly. Uses findById (indexed PK) instead of scan.
        if (users.isEmpty() && q ==~ /\d{1,18}/) {
            def byId = steamUserRepository.findById(q as Long).orElse(null)
            if (byId != null) users = [byId]
        }

        def matches = users.take(20).collect { u ->
            def wallet = walletRepository.findByUsername("steam_${u.steamId64}")
            // PageRequest(0, 10) at the DB boundary instead of fetching every
            // tx row and `.take(10)`-ing in memory. The CSR search renders up
            // to 20 user cards, each of which previously hydrated that user's
            // ENTIRE transaction history just to display the top 10 — for a
            // heavy-trading wallet that's thousands of rows × 20 cards per
            // search, an O(N×20) memory + GC burst on every CSR lookup.
            // The paginated overload (line 17 of TransactionRepository) has
            // existed since the wave-38 pagination pass; this call site never
            // got migrated.
            def tx = wallet ? transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.id, PageRequest.of(0, 10)) : []
            // Chargeback context (batch 469). Active count = currently
            // DISPUTED deposits (drives the user's withdrawal hold);
            // lifetime count = every DISPUTED tx ever including cleared
            // ones, so CSR can spot a repeat-offender pattern even after
            // staff cleared the row.
            long activeDisputes = wallet ? transactionRepository.countActiveDisputedDeposits(wallet.id) : 0L
            long lifetimeDisputes = tx.count { it.type == 'DEPOSIT' && (it.status == 'DISPUTED' || (it.description ?: '').contains('DISPUTE_CLEARED')) } as long
            // Rating summary (batch 489) — gives CSR a one-glance
            // trust signal for the user. Null when no reviews yet.
            Map ratingSummary = null
            if (reviewRepository != null) {
                try {
                    def agg = reviewRepository.aggregateForUser(u.id)
                    if (agg != null && !agg.isEmpty()) {
                        def row = agg[0]
                        def count = (row[0] ?: 0L) as long
                        if (count > 0) {
                            def avg = row[1] != null
                                ? (row[1] as BigDecimal).setScale(2, java.math.RoundingMode.HALF_UP)
                                : null
                            ratingSummary = [count: count, average: avg]
                        }
                    }
                } catch (Exception ignored) { /* keep ratingSummary null */ }
            }
            [
                id:               u.id,
                steamId64:        u.steamId64,
                displayName:      u.displayName,
                avatarUrl:        u.avatarUrl,
                role:             u.role,
                banned:           u.banned,
                banReason:        u.banReason,
                createdAt:        u.createdAt,
                balance:          wallet?.balance,
                activeDisputes:   activeDisputes,
                lifetimeDisputes: lifetimeDisputes,
                rating:           ratingSummary,
                recentTx:         tx.collect { t ->
                    [id: t.id, type: t.type, status: t.status, amount: t.amount, description: t.description, createdAt: t.createdAt]
                }
            ]
        }
        [matches: matches]
    }

    // ── Ticket handling ─────────────────────────────────────────────

    List<Map> listTickets(String statusFilter, String search = null) {
        // Same pushdown pattern as AdminService.listAllTickets (bug #20).
        // The old `findAll() + Groovy filter/sort` loaded every ticket
        // into memory on every CSR panel refresh.
        def status = statusFilter ? statusFilter.toUpperCase() : ''
        // Batch 581 — mirror admin ticket search: case-insensitive LIKE
        // across subject / username / category. Same server-side cap
        // + null-byte strip so the query can't be poisoned.
        def q = (search ?: '').trim()
        if (q.length() > 100) q = q.substring(0, 100)
        q = q.replace('\u0000', '')
        // Cap at 500 rows — consistent with AdminService.listWithdrawals /
        // listTrades. Past that the CSR UI should use CSV / direct DB for
        // historical digging rather than hydrating a mega-list per refresh.
        def rows = q.isEmpty()
            ? (supportTicketRepository.findForAdmin(status) ?: []).take(500)
            : (supportTicketRepository.searchForAdmin(status, q) ?: []).take(500)
        if (rows.isEmpty()) return []
        // Fraud-signal enrichment (batch 527). Mirrors batch 525's
        // admin-ticket enrichment so CSR has the same triage context
        // (account age, prior chargebacks, frozen wallet, banned,
        // email-unverified). Batches user lookups to stay O(N+K).
        def userIds = rows*.userId.findAll { it != null }.unique()
        def userById = userIds.isEmpty() ? [:] :
            steamUserRepository.findAllById(userIds).collectEntries { [(it.id): it] }
        rows.collect { t ->
            def user = userById[t.userId]
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
                id:              t.id,
                subject:         t.subject,
                category:        t.category,
                status:          t.status,
                userId:          t.userId,
                username:        t.username,
                createdAt:       t.createdAt,
                updatedAt:       t.updatedAt,
                userCreatedAt:   user?.createdAt,
                userEmailVerified: user?.emailVerified,
                userBanned:      user?.banned,
                walletFrozen:    walletFrozen,
                lifetimeDisputes: lifetimeDisputes
            ]
        }
    }

    Map getTicket(Long csrUserId, Long ticketId) {
        requireCsr(csrUserId)
        def t = supportTicketRepository.findById(ticketId)
            .orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        [ticket: t, messages: supportMessageRepository.findByTicket(ticketId)]
    }

    @Transactional
    SupportMessage reply(Long csrUserId, Long ticketId, String body) {
        requireCsr(csrUserId)
        def csr = steamUserRepository.findById(csrUserId)
            .orElseThrow { new ForbiddenException("Unknown CSR") }
        def t = supportTicketRepository.findById(ticketId)
            .orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        def cleanBody = textSanitizer.body(body)
        if (!cleanBody || cleanBody.isEmpty()) {
            throw new BadRequestException("INVALID_BODY", "Reply body required")
        }
        def msg = supportMessageRepository.save(new SupportMessage(
            ticketId:   ticketId,
            author:     'STAFF',
            authorName: textSanitizer.cleanShort((csr.displayName ?: 'Staff') + ' (CSR)'),
            body:       cleanBody
        ))
        t.status = 'WAITING_USER'
        t.updatedAt = System.currentTimeMillis()
        supportTicketRepository.save(t)

        notificationService?.push(t.userId, 'SUPPORT_REPLY',
            "New reply on ticket #${t.id}",
            t.subject, t.id, '/support')
        // Email the user too (batch 475). The bell notification can sit
        // unread for hours; an email lands in the user's inbox so they
        // know to come back. Gated on email + verified — same pattern
        // as withdrawal-approved emails. Failure-tolerant: a bad SMTP
        // doesn't block the reply from saving.
        if (emailService != null) {
            try {
                def user = steamUserRepository.findById(t.userId).orElse(null)
                if (emailService.canSendSecurityTo(user)) {
                    emailService.sendSupportReply(user.email, user.displayName,
                        t.id, t.subject, cleanBody)
                }
            } catch (Exception e) {
                log.warn("Support-reply email failed for ticket ${t.id}: ${e.message}")
            }
        }
        // Audit-log the CSR reply (matches the CSR_CREDIT audit pattern
        // below). A CSR reading and replying to a user's ticket is a
        // staff mutation and needs the same forensic trail as bans /
        // credits / force-cancels. Failure-tolerant — a bad audit write
        // must not block the reply from saving.
        try {
            auditService?.log(AuditService.TICKET_REPLIED, csrUserId, t.userId, ticketId,
                "Replied to ticket #${ticketId}: ${t.subject}")
        } catch (Exception e) {
            log.warn("TICKET_REPLIED audit-log failed for csr=${csrUserId} ticket=${ticketId}: ${e.message}")
        }
        msg
    }

    @Transactional
    SupportTicket close(Long csrUserId, Long ticketId) {
        requireCsr(csrUserId)
        def t = supportTicketRepository.findById(ticketId)
            .orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        // State-machine guard — mirror SupportService.resolve. Re-resolving
        // an already-RESOLVED ticket silently bumped updatedAt and could be
        // used to churn the row; a stale CSR tab / double-click now gets a
        // clean 400 instead. This is also the idempotency anchor for any
        // CSR-side dispute-resolution path that closes the related ticket
        // — a double-click on Resolve must not re-fire the close-notify
        // / re-write the audit row a second time.
        if (t.status == 'RESOLVED') {
            throw new BadRequestException("ALREADY_RESOLVED", "Ticket is already resolved")
        }
        t.status = 'RESOLVED'
        t.updatedAt = System.currentTimeMillis()
        supportTicketRepository.save(t)
        // Notify the ticket owner that their ticket was closed. Without
        // this push the user has zero signal — `reply()` pings on every
        // staff message but `close()` was silently flipping the row,
        // leaving disputers waiting indefinitely for a verdict that had
        // already been delivered. Mirrors SupportService.sweepStaleWaitingUser
        // which already fires TICKET_AUTO_RESOLVED on the auto-close path —
        // the manual-close path was the gap. Failure-tolerant: the bell
        // service uses safePush so a bad push can't roll back the close.
        notificationService?.safePush(t.userId, 'TICKET_CLOSED',
            "Support ticket resolved · #${t.id}",
            t.subject, t.id, '/support')
        try {
            auditService?.log(AuditService.TICKET_CLOSED, csrUserId, t.userId, ticketId,
                "Closed ticket #${ticketId}: ${t.subject}")
        } catch (Exception e) {
            log.warn("TICKET_CLOSED audit-log failed for csr=${csrUserId} ticket=${ticketId}: ${e.message}")
        }
        t
    }

    // ── Goodwill credit ─────────────────────────────────────────────

    /**
     * Issue a small compensation credit to a user's wallet. Capped at `csr.credit-cap`
     * per adjustment. CSRs cannot debit — only positive amounts allowed.
     */
    @Transactional
    Map issueGoodwillCredit(Long csrUserId, Long targetUserId, BigDecimal amount, String note) {
        requireCsr(csrUserId)
        def cap = new BigDecimal(creditCapStr)
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_AMOUNT", "Goodwill credit must be positive")
        }
        if (amount > cap) {
            throw new BadRequestException("OVER_CAP",
                "CSR credit cap is \$${cap.toPlainString()} per adjustment — escalate to an admin for more")
        }
        def cleanNote = textSanitizer.medium(note)
        if (!cleanNote || cleanNote.isEmpty()) {
            throw new BadRequestException("NOTE_REQUIRED", "A note is required for goodwill credits (audit trail)")
        }
        note = cleanNote

        def user = steamUserRepository.findById(targetUserId)
            .orElseThrow { new NotFoundException("SteamUser", targetUserId) }
        // A banned account can't transact — crediting it is pointless and
        // inconsistent with the direct-message path, which already rejects
        // banned targets. Staff accounts are off-limits as goodwill
        // targets so a CSR pair can't shuttle credits to each other.
        if (Boolean.TRUE.equals(user.banned)) {
            throw new BadRequestException("USER_BANNED",
                "This account is banned — goodwill credits can't be issued to it.")
        }
        if (user.role in ['CSR', 'ADMIN']) {
            throw new BadRequestException("STAFF_TARGET",
                "Goodwill credits can't be issued to a staff account — escalate to an admin.")
        }
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        if (wallet == null) throw new NotFoundException("Wallet", targetUserId)
        // A frozen wallet is a hard hold (typically a fraud investigation)
        // — it must refuse money-IN as well as money-out, otherwise a CSR
        // could top up an account staff deliberately locked.
        if (Boolean.TRUE.equals(wallet.frozen)) {
            throw new BadRequestException("WALLET_FROZEN",
                "This wallet is frozen — a goodwill credit can't be issued until the hold is lifted.")
        }
        wallet.balance = wallet.balance + amount
        walletRepository.save(wallet)

        def csr = steamUserRepository.findById(csrUserId).orElse(null)
        transactionRepository.save(new Transaction(
            walletId:        wallet.id,
            type:            'ADJUSTMENT_CREDIT',
            status:          'COMPLETED',
            amount:          amount,
            currency:        wallet.currency,
            stripeReference: "csr_${csrUserId}",
            description:     "CSR goodwill by ${csr?.displayName ?: csrUserId}: ${note.take(200)}"
        ))

        notificationService?.push(targetUserId, 'CSR_CREDIT',
            "Goodwill credit · +\$${amount.toPlainString()}",
            note, null, '/wallet')

        // Audit-log the goodwill credit (batch 484). The transaction
        // row carries the reason in its description, but staff actions
        // on user wallets need a separate audit trail — without this,
        // the Audit tab's per-user view wouldn't show goodwill credits
        // alongside other staff interventions (bans, forced trades).
        try {
            auditService?.log('CSR_CREDIT', csrUserId, targetUserId, wallet.id,
                "Goodwill credit of \$${amount.toPlainString()} to user ${targetUserId}: ${note}")
        } catch (Exception e) {
            log.warn("CSR_CREDIT audit-log failed for csr=${csrUserId} target=${targetUserId}: ${e.message}")
        }
        log.info("CSR ${csrUserId} issued goodwill \$${amount} to user ${targetUserId}: ${note}")
        [walletId: wallet.id, newBalance: wallet.balance, cap: cap]
    }

    // ── Flag listing for admin review ───────────────────────────────

    @Transactional
    Map flagListing(Long csrUserId, Long listingId, String reason) {
        requireCsr(csrUserId)
        def listing = listingRepository.findById(listingId)
            .orElseThrow { new NotFoundException("Listing", listingId) }
        def csr = steamUserRepository.findById(csrUserId).orElse(null)
        // Append a flag note to the listing description so admins see it in the
        // moderation queue. We don't have a dedicated flag table — the note is
        // a low-risk hint that the admin can act on, nothing more.
        def cleanReason = textSanitizer.cleanShort(reason ?: 'no reason')
        def note = "[FLAGGED by ${textSanitizer.cleanShort(csr?.displayName ?: csrUserId.toString())}: ${cleanReason}]"
        // 500-char cap matches the column size (V39). Append-append
        // patterns will eventually hit the ceiling but that's intended —
        // once a listing's description is full of flag notes it's past
        // the point where another flag helps anyone.
        listing.description = textSanitizer.clean(((listing.description ?: '') + ' ' + note), 500)
        listingRepository.save(listing)
        // Audit-log the flag (matches the TICKET_REPLIED / TICKET_CLOSED /
        // CSR_CREDIT pattern above). Flagging mutates persistent listing
        // state — it rewrites the description column — so it's a staff
        // mutation that needs the same forensic trail as every other CSR
        // action. Without this the Audit tab's per-user view shows a CSR's
        // credits and ticket replies but silently drops their listing
        // flags. String-literal event type — AuditService has no constant
        // for it (same as AdminService's 'LISTING_REPORTS_DISMISSED').
        // Failure-tolerant: a bad audit write must not block the flag.
        try {
            auditService?.log('LISTING_FLAGGED', csrUserId, listing.sellerUserId, listingId,
                "Flagged listing #${listingId}: ${cleanReason}")
        } catch (Exception e) {
            log.warn("LISTING_FLAGGED audit-log failed for csr=${csrUserId} listing=${listingId}: ${e.message}")
        }
        log.warn("CSR ${csrUserId} flagged listing ${listingId}: ${reason}")
        [id: listing.id, flagged: true, description: listing.description]
    }
}
