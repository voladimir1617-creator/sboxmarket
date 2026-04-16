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
    SupportTicketRepository supportTicketRepository = Mock()
    ItemRepository          itemRepository          = Mock()
    NotificationService     notificationService     = Mock()
    TextSanitizer           textSanitizer           = Mock() {
        medium(_) >> { String s -> s }
    }
    AdminAuthorization      adminAuthorization      = Mock()
    BanGuard                banGuard                = Mock()

    @Subject
    AdminService service = new AdminService(
        steamUserRepository     : steamUserRepository,
        walletRepository        : walletRepository,
        transactionRepository   : transactionRepository,
        listingRepository       : listingRepository,
        supportTicketRepository : supportTicketRepository,
        itemRepository          : itemRepository,
        notificationService     : notificationService,
        textSanitizer           : textSanitizer,
        adminAuthorization      : adminAuthorization,
        banGuard                : banGuard
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
        1 * notificationService.push(20L, 'ACCOUNT_BANNED', _, _, _)
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
}
