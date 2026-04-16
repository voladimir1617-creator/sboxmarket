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

    // ── Auth ────────────────────────────────────────────────────────

    /** CSR OR ADMIN — used as the default gate on every CSR endpoint. */
    void requireCsr(Long userId) {
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
            def tx = wallet ? transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.id).take(10) : []
            [
                id:              u.id,
                steamId64:       u.steamId64,
                displayName:     u.displayName,
                avatarUrl:       u.avatarUrl,
                role:            u.role,
                banned:          u.banned,
                banReason:       u.banReason,
                createdAt:       u.createdAt,
                balance:         wallet?.balance,
                recentTx:        tx.collect { t ->
                    [id: t.id, type: t.type, status: t.status, amount: t.amount, description: t.description, createdAt: t.createdAt]
                }
            ]
        }
        [matches: matches]
    }

    // ── Ticket handling ─────────────────────────────────────────────

    List<Map> listTickets(String statusFilter) {
        // Same pushdown pattern as AdminService.listAllTickets (bug #20).
        // The old `findAll() + Groovy filter/sort` loaded every ticket
        // into memory on every CSR panel refresh.
        def status = statusFilter ? statusFilter.toUpperCase() : ''
        supportTicketRepository.findForAdmin(status).collect { t ->
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
            t.subject, t.id)
        msg
    }

    @Transactional
    SupportTicket close(Long csrUserId, Long ticketId) {
        requireCsr(csrUserId)
        def t = supportTicketRepository.findById(ticketId)
            .orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        t.status = 'RESOLVED'
        t.updatedAt = System.currentTimeMillis()
        supportTicketRepository.save(t)
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
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        if (wallet == null) throw new NotFoundException("Wallet", targetUserId)
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
            note, null)

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
        listing.description = textSanitizer.clean(((listing.description ?: '') + ' ' + note), 64)
        listingRepository.save(listing)
        log.warn("CSR ${csrUserId} flagged listing ${listingId}: ${reason}")
        [id: listing.id, flagged: true, description: listing.description]
    }
}
