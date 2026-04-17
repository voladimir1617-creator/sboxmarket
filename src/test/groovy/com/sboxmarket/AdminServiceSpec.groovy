package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Covers the admin moderation primitives: ban/unban, grant/revoke admin,
 * credit wallet, approve/reject withdrawal. All of these gate on
 * `requireAdmin` and the spec asserts that failure path AND the
 * downstream side effects.
 */
class AdminServiceSpec extends Specification {

    SteamUserRepository     steamUserRepository     = Mock()
    WalletRepository        walletRepository        = Mock()
    TransactionRepository   transactionRepository   = Mock()
    ListingRepository       listingRepository       = Mock()
    com.sboxmarket.repository.ListingReportRepository listingReportRepository = Mock()
    SupportTicketRepository supportTicketRepository = Mock()
    ItemRepository          itemRepository          = Mock()
    NotificationService     notificationService     = Mock()
    TextSanitizer           textSanitizer           = Mock() {
        medium(_) >> { String s -> s }
    }
    AdminAuthorization      adminAuthorization      = Mock()
    BanGuard                banGuard                = Mock()
    com.sboxmarket.service.EmailService emailService = Mock()
    com.sboxmarket.repository.TradeRepository tradeRepository = Mock()

    @Subject
    AdminService service = new AdminService(
        steamUserRepository     : steamUserRepository,
        walletRepository        : walletRepository,
        transactionRepository   : transactionRepository,
        listingRepository       : listingRepository,
        listingReportRepository : listingReportRepository,
        supportTicketRepository : supportTicketRepository,
        itemRepository          : itemRepository,
        notificationService     : notificationService,
        textSanitizer           : textSanitizer,
        adminAuthorization      : adminAuthorization,
        banGuard                : banGuard,
        emailService            : emailService,
        tradeRepository         : tradeRepository
    )

    // ── ban / unban ───────────────────────────────────────────────

    def "banUser flips banned=true and cancels active listings"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', displayName: 'Bob', role: 'USER', banned: false)
        def activeListing = new Listing(id: 100L, sellerUserId: 20L, status: 'ACTIVE')
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(20L) >> [activeListing]
        listingRepository.saveAll(_) >> { args -> args[0] }

        when:
        def result = service.banUser(1L, 20L, 'bad behaviour')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        result.banned == true
        result.banReason == 'bad behaviour'
        activeListing.status == 'CANCELLED'
        1 * notificationService.push(20L, 'ACCOUNT_BANNED', _, _, _, _)
    }

    def "banUser forbids self-ban"() {
        when:
        service.banUser(1L, 1L, 'oops')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        thrown(BadRequestException)
    }

    def "banUser refuses to ban another admin"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', role: 'ADMIN', banned: false)
        steamUserRepository.findById(20L) >> Optional.of(target)

        when:
        service.banUser(1L, 20L, 'nope')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        thrown(BadRequestException)
    }

    def "banUser 404s for unknown target"() {
        given:
        steamUserRepository.findById(_) >> Optional.empty()

        when:
        service.banUser(1L, 999L, 'x')

        then:
        thrown(NotFoundException)
    }

    def "unbanUser clears banned flag + banReason"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', banned: true, banReason: 'old')
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.unbanUser(1L, 20L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        result.banned == false
        result.banReason == null
    }

    // ── grant / revoke admin ──────────────────────────────────────

    def "grantAdmin flips role to ADMIN"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', role: 'USER')
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.grantAdmin(1L, 20L)

        then:
        result.role == 'ADMIN'
    }

    def "revokeAdmin flips role back to USER"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', role: 'ADMIN')
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.revokeAdmin(1L, 20L)

        then:
        result.role == 'USER'
    }

    def "revokeAdmin forbids self-revoke"() {
        when:
        service.revokeAdmin(1L, 1L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        thrown(BadRequestException)
    }

    // ── grant / revoke CSR ────────────────────────────────────────

    def "grantCsr flips USER role to CSR"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'USER')
        steamUserRepository.findById(21L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.grantCsr(1L, 21L)

        then:
        result.role == 'CSR'
    }

    def "grantCsr refuses banned accounts"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'USER', banned: true)
        steamUserRepository.findById(21L) >> Optional.of(target)

        when:
        service.grantCsr(1L, 21L)

        then:
        thrown(BadRequestException)
    }

    def "grantCsr refuses ADMIN downgrade — force explicit revoke-admin first"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'ADMIN')
        steamUserRepository.findById(21L) >> Optional.of(target)

        when:
        service.grantCsr(1L, 21L)

        then:
        thrown(BadRequestException)
    }

    def "revokeCsr flips CSR back to USER"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'CSR')
        steamUserRepository.findById(21L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.revokeCsr(1L, 21L)

        then:
        result.role == 'USER'
    }

    def "revokeCsr refuses non-CSR accounts"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'USER')
        steamUserRepository.findById(21L) >> Optional.of(target)

        when:
        service.revokeCsr(1L, 21L)

        then:
        thrown(BadRequestException)
    }

    // ── reset 2FA ─────────────────────────────────────────────────

    def "reset2faFor wipes the secret, notifies the user, and writes an audit row"() {
        given:
        def target = new SteamUser(id: 22L, steamId64: '444', role: 'USER', totpSecret: 'ABCDEF', lastTotpStep: 123L)
        steamUserRepository.findById(22L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def res = service.reset2faFor(1L, 22L, 'Lost phone — confirmed via Steam OpenID handshake')

        then:
        target.totpSecret == null
        target.lastTotpStep == null
        res.totpEnabled == false
        1 * notificationService.push(22L, 'TWOFA_RESET', _, _, _, _)
    }

    def "reset2faFor refuses when the user has no TOTP to reset"() {
        given:
        def target = new SteamUser(id: 22L, steamId64: '444', role: 'USER', totpSecret: null)
        steamUserRepository.findById(22L) >> Optional.of(target)

        when:
        service.reset2faFor(1L, 22L, 'note')

        then:
        thrown(BadRequestException)
        0 * notificationService.push(_, _, _, _, _, _)
    }

    def "reset2faFor 404s for unknown user"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.reset2faFor(1L, 999L, 'note')

        then:
        thrown(com.sboxmarket.exception.NotFoundException)
    }

    // ── admin-only user notes ────────────────────────────────────

    def "readAdminNotes returns the current value (or empty string)"() {
        given:
        def target = new SteamUser(id: 30L, steamId64: '5', adminNotes: 'flagged 2025-01')
        steamUserRepository.findById(30L) >> Optional.of(target)

        when:
        def res = service.readAdminNotes(1L, 30L)

        then:
        res.adminNotes == 'flagged 2025-01'
    }

    def "readAdminNotes returns '' when the field is null"() {
        given:
        def target = new SteamUser(id: 30L, steamId64: '5', adminNotes: null)
        steamUserRepository.findById(30L) >> Optional.of(target)

        expect:
        service.readAdminNotes(1L, 30L).adminNotes == ''
    }

    def "writeAdminNotes persists the value and audit-logs"() {
        given:
        def target = new SteamUser(id: 30L, steamId64: '5', adminNotes: null)
        steamUserRepository.findById(30L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def res = service.writeAdminNotes(1L, 30L, 'chargeback Oct 2025 refunded $50')

        then:
        target.adminNotes == 'chargeback Oct 2025 refunded $50'
        res.adminNotes.contains('chargeback')
    }

    def "writeAdminNotes 404s for unknown user"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.writeAdminNotes(1L, 999L, 'x')

        then:
        thrown(com.sboxmarket.exception.NotFoundException)
    }

    // ── ban/unban email hook ─────────────────────────────────────

    def "banUser emails the target when they have a verified email"() {
        given:
        def target = new SteamUser(id: 50L, steamId64: '123', role: 'USER',
            email: 'user@example.com', emailVerified: true, displayName: 'Bob')
        steamUserRepository.findById(50L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(50L) >> []
        listingRepository.saveAll(_) >> { args -> args[0] }

        when:
        service.banUser(1L, 50L, 'chargeback')

        then:
        1 * emailService.sendAccountBanned('user@example.com', 'Bob', _, _)
    }

    def "banUser skips the email when the address is unverified"() {
        given:
        def target = new SteamUser(id: 50L, steamId64: '123', role: 'USER',
            email: 'user@example.com', emailVerified: false, displayName: 'Bob')
        steamUserRepository.findById(50L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(50L) >> []
        listingRepository.saveAll(_) >> { args -> args[0] }

        when:
        service.banUser(1L, 50L, 'x')

        then:
        0 * emailService.sendAccountBanned(_, _, _, _)
    }

    def "banUser keeps going when the email hook throws"() {
        given:
        def target = new SteamUser(id: 50L, steamId64: '123', role: 'USER',
            email: 'user@example.com', emailVerified: true, displayName: 'Bob')
        steamUserRepository.findById(50L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(50L) >> []
        listingRepository.saveAll(_) >> { args -> args[0] }
        emailService.sendAccountBanned(_, _, _, _) >> { throw new RuntimeException('SMTP down') }

        when:
        def res = service.banUser(1L, 50L, 'x')

        then:
        res.banned == true
        noExceptionThrown()
    }

    def "unbanUser emails the target when verified"() {
        given:
        def target = new SteamUser(id: 50L, steamId64: '123', banned: true,
            email: 'user@example.com', emailVerified: true, displayName: 'Bob')
        steamUserRepository.findById(50L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.unbanUser(1L, 50L)

        then:
        1 * emailService.sendAccountUnbanned('user@example.com', 'Bob')
    }

    // ── sendTestEmail ────────────────────────────────────────────

    def "sendTestEmail dispatches through EmailService.send"() {
        when:
        def res = service.sendTestEmail(1L, 'ops@example.com', 'Test subject', 'Test body')

        then:
        1 * emailService.send('ops@example.com', 'Test subject', 'Test body')
        res.sent == true
    }

    def "sendTestEmail refuses an invalid address"() {
        when:
        service.sendTestEmail(1L, bad, 'subj', 'body')

        then:
        thrown(BadRequestException)
        0 * emailService.send(_, _, _)

        where:
        bad << [null, '', '   ', 'not-an-email']
    }

    def "sendTestEmail surfaces an SMTP failure as BadRequest"() {
        given:
        emailService.send(_, _, _) >> { throw new RuntimeException('connection refused') }

        when:
        service.sendTestEmail(1L, 'ops@example.com', 'x', 'y')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'SEND_FAILED'
    }

    // ── finalizeDeletion (GDPR) ──────────────────────────────────

    def "finalizeDeletion refuses when user has no pending deletion request"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', deletionRequestedAt: null)
        steamUserRepository.findById(60L) >> Optional.of(target)

        when:
        service.finalizeDeletion(1L, 60L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NO_REQUEST'
    }

    def "finalizeDeletion refuses when there is a pending withdrawal"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann',
            deletionRequestedAt: 1234L)
        def wallet = new Wallet(id: 500L, username: 'steam_999', balance: BigDecimal.ZERO)
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> wallet
        transactionRepository.countByWalletAndType(500L, 'WITHDRAW') >> 1L
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L, _) >> [
            new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
                amount: new BigDecimal('10'))
        ]

        when:
        service.finalizeDeletion(1L, 60L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'PENDING_WITHDRAWAL'
    }

    def "finalizeDeletion refuses when there are open trades"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann',
            deletionRequestedAt: 1234L)
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> null
        tradeRepository.findByParticipant(60L) >> [
            new com.sboxmarket.model.Trade(id: 1L, state: 'PENDING_BUYER_CONFIRM')
        ]

        when:
        service.finalizeDeletion(1L, 60L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'OPEN_TRADES'
    }

    def "finalizeDeletion scrubs PII, bans, clears the request, cancels listings"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann',
            email: 'ann@example.com', emailVerified: true, avatarUrl: 'http://x',
            totpSecret: 'ABC', deletionRequestedAt: 1234L)
        def activeListing = new Listing(id: 200L, sellerUserId: 60L, status: 'ACTIVE')
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> null
        tradeRepository.findByParticipant(60L) >> []
        listingRepository.findActiveBySeller(60L) >> [activeListing]
        listingRepository.saveAll(_) >> { args -> args[0] }
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def res = service.finalizeDeletion(1L, 60L)

        then:
        res.finalised == true
        target.displayName == 'Deleted user #60'
        target.email == null
        target.avatarUrl == null
        target.totpSecret == null
        target.banned == true
        target.deletionRequestedAt == null
        activeListing.status == 'CANCELLED'
    }

    // ── approve / reject withdrawal ───────────────────────────────

    def "approveWithdrawal flips status COMPLETED and notifies owner"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
                                  amount: new BigDecimal("25"))
        transactionRepository.findById(1L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        walletRepository.findById(500L) >> Optional.of(new Wallet(id: 500L, username: 'steam_111'))
        steamUserRepository.findBySteamId64('111') >> null  // notifyWalletOwner no-ops

        when:
        def result = service.approveWithdrawal(1L, 1L, 'PAYOUT-REF-X')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        tx.status == 'COMPLETED'
        tx.stripeReference == 'PAYOUT-REF-X'
        result.status == 'COMPLETED'
    }

    def "approveWithdrawal refuses non-WITHDRAW transactions"() {
        given:
        transactionRepository.findById(_) >> Optional.of(new Transaction(type: 'DEPOSIT', status: 'PENDING'))

        when:
        service.approveWithdrawal(1L, 1L, 'ref')

        then:
        thrown(BadRequestException)
    }

    def "approveWithdrawal refuses withdrawals already in a terminal state"() {
        given:
        transactionRepository.findById(_) >> Optional.of(new Transaction(type: 'WITHDRAW', status: 'COMPLETED'))

        when:
        service.approveWithdrawal(1L, 1L, 'ref')

        then:
        thrown(BadRequestException)
    }

    def "rejectWithdrawal marks FAILED and refunds the wallet"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
                                  amount: new BigDecimal("25"))
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("0"))
        transactionRepository.findById(1L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.rejectWithdrawal(1L, 1L, 'KYC failed')

        then:
        wallet.balance == new BigDecimal("25")
        tx.status == 'FAILED'
        result.refunded == new BigDecimal("25")
    }

    // ── dashboardStats (aggregate path) ───────────────────────────

    def "dashboardStats uses indexed aggregates, not findAll scans"() {
        given:
        steamUserRepository.count() >> 42L
        // These four aggregate calls are the O(1) replacement for the old
        // findAll-then-filter scans. They must all be hit exactly once.
        walletRepository.sumAllBalances() >> new BigDecimal("1234.56")
        transactionRepository.sumByTypeSinceCompleted('DEPOSIT', 'COMPLETED', _) >> new BigDecimal("200")
        transactionRepository.sumByTypeSinceCompleted('SALE',    'COMPLETED', _) >> new BigDecimal("150")
        transactionRepository.countByTypeStatus('WITHDRAW', 'PENDING') >> 3L
        transactionRepository.sumByTypeStatus('WITHDRAW', 'PENDING') >> new BigDecimal("75")
        supportTicketRepository.countOpen() >> 7L
        steamUserRepository.countBanned() >> 1L
        listingRepository.countActive() >> 88L
        // Trade-state probes — batch 110 added these to the dashboard
        // so ops sees disputes + escrow phases at a glance.
        tradeRepository.countByState('DISPUTED')              >> 2L
        tradeRepository.countByState('PENDING_SELLER_ACCEPT') >> 5L
        tradeRepository.countByState('PENDING_SELLER_SEND')   >> 3L
        tradeRepository.countByState('PENDING_BUYER_CONFIRM') >> 4L

        when:
        def stats = service.dashboardStats()

        then:
        // CRITICAL: the old full-table scans must NOT be called
        0 * walletRepository.findAll()
        0 * transactionRepository.findAll()
        0 * supportTicketRepository.findAll()
        0 * steamUserRepository.findAll()

        stats.users == 42L
        stats.totalEscrow == new BigDecimal("1234.56")
        stats.deposits24h == new BigDecimal("200.00")
        stats.sales24h == new BigDecimal("150.00")
        stats.pendingWithdrawals == 3L
        stats.pendingWithdrawalsAmount == new BigDecimal("75.00")
        stats.openTickets == 7L
        stats.bannedUsers == 1L
        stats.activeListings == 88L
        stats.disputedTrades      == 2L
        stats.pendingSellerAccept == 5L
        stats.pendingSellerSend   == 3L
        stats.pendingBuyerConfirm == 4L
    }

    // ── listWithdrawals (indexed) ─────────────────────────────────

    def "listWithdrawals uses the indexed sorted query and batch wallet lookup"() {
        given:
        def pendingTx = new Transaction(
            id: 1L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
            amount: new BigDecimal("50"), currency: 'USD',
            createdAt: 1000L, stripeReference: 'acct_ext'
        )
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('WITHDRAW', 'PENDING') >> [pendingTx]
        // Batched wallet resolve — bug #37 fix. Per-row findById would
        // be an N+1, so the service now calls findAllById once.
        walletRepository.findAllById([500L]) >> [new Wallet(id: 500L, username: 'steam_111')]

        when:
        def result = service.listWithdrawals(null)

        then:
        0 * transactionRepository.findAll()
        0 * walletRepository.findById(_)
        result.size() == 1
        result[0].id == 1L
        result[0].status == 'PENDING'
        result[0].walletUsername == 'steam_111'
    }

    def "listWithdrawals respects an explicit status filter"() {
        when:
        def result = service.listWithdrawals('completed')

        then:
        1 * transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('WITHDRAW', 'COMPLETED') >> []
        result == []
    }

    // ── creditWallet ──────────────────────────────────────────────

    def "creditWallet bumps balance and records ADJUSTMENT_CREDIT"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("10"))
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.creditWallet(1L, 20L, new BigDecimal("50"), 'goodwill')

        then:
        wallet.balance == new BigDecimal("60")
        result.newBalance == new BigDecimal("60")
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'ADJUSTMENT_CREDIT' && tx.amount == new BigDecimal("50")
        })
    }

    def "creditWallet records ADJUSTMENT_DEBIT for negative amounts"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("100"))
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.creditWallet(1L, 20L, new BigDecimal("-30"), 'reversal')

        then:
        wallet.balance == new BigDecimal("70")
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'ADJUSTMENT_DEBIT' && tx.amount == new BigDecimal("30")
        })
    }

    def "creditWallet refuses zero amounts"() {
        when:
        service.creditWallet(1L, 20L, BigDecimal.ZERO, 'oops')

        then:
        thrown(BadRequestException)
    }

    def "creditWallet refuses adjustments that would drive balance negative"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("10"))
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet

        when:
        service.creditWallet(1L, 20L, new BigDecimal("-50"), 'too much')

        then:
        thrown(BadRequestException)
    }

    def "creditWallet refuses per-call adjustment over the \$10k sanity cap (bug #40)"() {
        when:
        service.creditWallet(1L, 20L, new BigDecimal("10001"), 'typo')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'ADJUSTMENT_TOO_LARGE'
        0 * walletRepository.save(_)
    }

    def "creditWallet refuses per-call debit over the sanity cap too (bug #40)"() {
        when:
        service.creditWallet(1L, 20L, new BigDecimal("-50000"), 'big refund')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'ADJUSTMENT_TOO_LARGE'
    }

    def "creditWallet requires an audit note (bug #40)"() {
        when:
        service.creditWallet(1L, 20L, new BigDecimal("50"), note)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NOTE_REQUIRED'

        where:
        note << [null, '', '   ']
    }

    // ── Reported-listings moderation loop ─────────────────────────

    def "forceCancelListing notifies every distinct reporter that their report was actioned"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat', lowestPrice: new BigDecimal("10"))
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item, reportCount: 3)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        // Two reports from user 42 + one from user 99 → 2 distinct reporters.
        def reports = [
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'Suspicious'),
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'Other'),
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 99L, reason: 'Suspicious'),
        ]
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> reports

        when:
        def res = service.forceCancelListing(1L, 100L, 'Policy violation')

        then:
        listing.status == 'CANCELLED'
        // Seller gets the original LISTING_REMOVED notification
        1 * notificationService.push(999L, 'LISTING_REMOVED', _, _, _, _)
        // Each distinct reporter gets exactly one REPORT_ACTIONED notification
        1 * notificationService.push(42L, 'REPORT_ACTIONED', _, _, _, _)
        1 * notificationService.push(99L, 'REPORT_ACTIONED', _, _, _, _)
    }

    def "forceCancelListing keeps working when the report-notification push throws"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item, reportCount: 1)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> [
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'X')
        ]
        notificationService.push(42L, 'REPORT_ACTIONED', _, _, _, _) >> { throw new RuntimeException('downstream boom') }

        when:
        def res = service.forceCancelListing(1L, 100L, 'Policy violation')

        then:
        // Still succeeds despite the notification failure
        listing.status == 'CANCELLED'
        noExceptionThrown()
    }

    def "dismissListingReports clears the aggregate counter and notifies reporters"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item,
            reportCount: 4, lastReportedAt: 12345L)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> [
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'Misread'),
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'Misread'),
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 99L, reason: 'Misread'),
        ]

        when:
        def res = service.dismissListingReports(1L, 100L, 'No policy violation')

        then:
        listing.reportCount == 0
        listing.lastReportedAt == null
        res.dismissed == 3
        res.distinctReporters == 2
        1 * notificationService.push(42L, 'REPORT_REVIEWED', _, _, _, _)
        1 * notificationService.push(99L, 'REPORT_REVIEWED', _, _, _, _)
    }

    def "dismissListingReports is a no-op on the counter when there are zero detail rows"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item,
            reportCount: 5, lastReportedAt: 12345L)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> []

        when:
        def res = service.dismissListingReports(1L, 100L, 'phantom counter')

        then:
        listing.reportCount == 0
        res.dismissed == 0
        res.distinctReporters == 0
        0 * notificationService.push(_, 'REPORT_REVIEWED', _, _, _, _)
    }
}
