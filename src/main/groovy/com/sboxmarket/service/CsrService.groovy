package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.ListingReport
import com.sboxmarket.repository.ListingReportRepository
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

    /** Per-CSR cumulative goodwill cap over a rolling 24h window. The per-
     *  adjustment cap (creditCapStr, $25) bounds ONE credit; without a daily
     *  total a compromised/colluding CSR could loop issueGoodwillCredit
     *  unbounded into a confederate wallet. Default $200/day per CSR. */
    @Value('${csr.daily-cap:200.00}') String dailyCapStr

    @Autowired SteamUserRepository steamUserRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired ListingRepository listingRepository
    @Autowired(required = false) ListingReportRepository listingReportRepository
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
        // A ban keeps the role (banUser refuses only ADMIN targets) and a
        // banned user can still sign in, so without this a banned CSR kept
        // replying to tickets and issuing goodwill credit.
        if (Boolean.TRUE.equals(user.banned)) {
            throw new ForbiddenException("Customer service privileges required")
        }
    }

    /** True if the user's role unlocks the CSR panel in the UI. */
    boolean isCsr(Long userId) {
        if (userId == null) return false
        def user = steamUserRepository.findById(userId).orElse(null)
        user?.role in ['CSR', 'ADMIN'] && !Boolean.TRUE.equals(user?.banned)
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
        // The query declares ESCAPE '\\'; escape the input so `_` and `%`
        // match literally and a trailing backslash isn't a broken pattern.
        def likeQ = q.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
        def users = steamUserRepository.searchByNameOrSteamId(
            likeQ, org.springframework.data.domain.PageRequest.of(0, 20)) ?: []

        // Numeric id lookup ("user #42"). Checked first and put on top: a
        // short number is a substring of countless 17-digit Steam ids, so
        // the old "only when nothing else matched" fallback almost never ran.
        if (q ==~ /\d{1,18}/) {
            def byId = steamUserRepository.findById(q as Long)?.orElse(null)
            if (byId != null) users = [byId] + users.findAll { it.id != byId.id }
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
        // The ticket search query declares ESCAPE '\\': match `_` / `%` literally.
        q = q.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
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
        // State-machine guard — mirror SupportService.reply (line 232) and
        // CsrService.close. The user-side reply path refuses to post into a
        // RESOLVED thread because resurrecting a closed ticket needs the
        // explicit SupportService.reopen flow. The CSR-side reply was the
        // lone hole: a stale CSR tab clicking "Reply" on what was already
        // closed silently flipped the ticket back to WAITING_USER, fired a
        // SUPPORT_REPLY bell + email at the user, and wrote a TICKET_REPLIED
        // audit row — un-closing a ticket without going through reopen() and
        // pinging a user about a thread they already considered done. Reject
        // here so the close→reopen state machine is enforced from both sides.
        if (t.status == 'RESOLVED') {
            throw new BadRequestException("ALREADY_RESOLVED",
                "Ticket is resolved — ask the user to reopen it from their support page before replying")
        }
        // Keep line breaks, same as the user's side of the thread.
        def cleanBody = TextSanitizer.multiline(textSanitizer, body)
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

        // Skip user-facing notifications + email when the ticket owner has
        // been banned since opening the ticket (e.g. fraud appeal that
        // backfired). Mirrors NotificationService.filterActiveRecipients
        // for the batch case and `issueGoodwillCredit`'s banned-target
        // refusal — a banned account is inert and shouldn't receive bell
        // pings or emails about staff activity on a frozen ticket. The
        // reply, status flip, and audit row still happen: this is a
        // delivery suppression, not an action refusal — staff need their
        // forensic trail and the CSR-side view of the thread must update.
        // Null-safe on the lookup itself so a stubbed-out repository or a
        // transient DB blip can't roll back the reply we already saved.
        SteamUser owner = null
        try {
            def lookup = steamUserRepository.findById(t.userId)
            owner = (lookup != null) ? lookup.orElse(null) : null
        } catch (Exception ignored) { /* treat as unknown */ }
        boolean ownerBanned = (owner != null && Boolean.TRUE.equals(owner.banned))
        if (!ownerBanned) {
            notificationService?.safePush(t.userId, 'SUPPORT_REPLY',
                "New reply on ticket #${t.id}",
                t.subject, t.id, '/support')
            // Email the user too (batch 475). The bell notification can sit
            // unread for hours; an email lands in the user's inbox so they
            // know to come back. Gated on email + verified — same pattern
            // as withdrawal-approved emails. Failure-tolerant: a bad SMTP
            // doesn't block the reply from saving.
            if (emailService != null) {
                try {
                    if (emailService.canSendSecurityTo(owner)) {
                        emailService.sendSupportReply(owner.email, owner.displayName,
                            t.id, t.subject, cleanBody)
                    }
                } catch (Exception e) {
                    log.warn("Support-reply email failed for ticket ${t.id}: ${e.message}")
                }
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
        //
        // Banned-owner suppression — mirrors `reply()` above (lines 293-329)
        // and `NotificationService.filterActiveRecipients` for batch sends.
        // A banned account is inert: it can't reopen the ticket, can't see
        // its bell tray (the auth layer rejects the session), and shouldn't
        // receive pings about staff activity on a frozen thread. The status
        // flip + audit row still happen — this is a delivery suppression,
        // not an action refusal — but the bell push to a banned target is
        // dropped. Null-safe lookup so a transient DB blip or a stubbed-out
        // repository can't roll back the close we already saved.
        SteamUser owner = null
        try {
            def lookup = steamUserRepository.findById(t.userId)
            owner = (lookup != null) ? lookup.orElse(null) : null
        } catch (Exception ignored) { /* treat as unknown */ }
        boolean ownerBanned = (owner != null && Boolean.TRUE.equals(owner.banned))
        if (!ownerBanned) {
            notificationService?.safePush(t.userId, 'TICKET_CLOSED',
                "Support ticket resolved · #${t.id}",
                t.subject, t.id, '/support')
        }
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
        // Rolling per-CSR 24h cumulative cap. The per-adjustment cap above bounds
        // ONE credit; without this, a compromised or colluding CSR could loop
        // issueGoodwillCredit unbounded ($25 × the 20/10s rate limit ≈ $180k/hr)
        // into a confederate wallet — the drain the class docstring wrongly
        // claims is already impossible. Sum this CSR's COMPLETED goodwill credits
        // (keyed on the 'csr_<id>' stripeReference stamped on the row below) over
        // the last 24h and reject once the day's total would exceed the cap.
        // Best-effort vs. a concurrent burst (no per-CSR lock; the rate limiter
        // bounds the burst) — it closes the sequential unbounded-loop drain.
        def dailyCap = new BigDecimal(dailyCapStr)
        def since = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)
        def used24h = transactionRepository.sumByTypeReferenceSince(
            'ADJUSTMENT_CREDIT', "csr_${csrUserId}".toString(), since) ?: BigDecimal.ZERO
        if (used24h + amount > dailyCap) {
            throw new BadRequestException("CSR_DAILY_CAP",
                "CSR daily goodwill cap is \$${dailyCap.toPlainString()} — \$${used24h.toPlainString()} already issued in the last 24h. Escalate to an admin.")
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
        // The flag goes into the admin "Reported listings" queue as a report
        // row. It used to be appended to listing.description, which is the
        // seller's public text: every buyer saw the CSR's name and the
        // accusation, admins never saw it (their queue reads reportCount),
        // and a near-full description silently cut the note off.
        def cleanReason = textSanitizer.cleanShort(reason ?: 'no reason')
        boolean alreadyFlagged = false
        if (listingReportRepository != null) {
            def existing = listingReportRepository.findByListingIdAndReporterUserId(listingId, csrUserId)
            if (existing.isPresent()) {
                alreadyFlagged = true
                def row = existing.get()
                row.note = textSanitizer.clean("Staff flag: ${cleanReason}".toString(), 500)
                listingReportRepository.save(row)
            } else {
                listingReportRepository.save(new ListingReport(
                    listingId:      listingId,
                    reporterUserId: csrUserId,
                    reason:         'Other',
                    note:           textSanitizer.clean("Staff flag: ${cleanReason}".toString(), 500),
                    createdAt:      System.currentTimeMillis()))
            }
        }
        if (!alreadyFlagged) listing.reportCount = (listing.reportCount ?: 0) + 1
        listing.lastReportedAt = System.currentTimeMillis()
        listingRepository.save(listing)
        // Audit-log the flag (matches the TICKET_REPLIED / TICKET_CLOSED /
        // CSR_CREDIT pattern above). Flagging mutates persistent listing
        // state (a report row and the listing's report count), so it's a staff
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
        [id: listing.id, flagged: true, reportCount: listing.reportCount]
    }
}
