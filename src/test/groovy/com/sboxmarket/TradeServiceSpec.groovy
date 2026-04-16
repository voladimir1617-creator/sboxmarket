package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the escrow state machine.
 *
 * Each legal transition (open → sellerAccept → sellerMarkSent →
 * buyerConfirm → VERIFIED) is asserted with both its state change and
 * its money movement. The dispute / cancel exits are covered with the
 * participant guard and the buyer refund path. Staff-side cancel (admin
 * override) is exercised via a mock AdminAuthorization.
 */
class TradeServiceSpec extends Specification {

    TradeRepository       tradeRepository       = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    AdminAuthorization    adminAuthorization    = Mock()
    TextSanitizer         textSanitizer         = Mock() {
        medium(_) >> { String s -> s ?: '' }
    }

    @Subject
    TradeService service = new TradeService(
        tradeRepository       : tradeRepository,
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        notificationService   : notificationService,
        banGuard              : banGuard,
        adminAuthorization    : adminAuthorization,
        textSanitizer         : textSanitizer,
        autoReleaseDays       : 8L
    )

    private Trade tradeIn(String state, Map args = [:]) {
        new Trade(
            id:             args.id ?: 1L,
            listingId:      args.listingId ?: 100L,
            itemId:         args.itemId ?: 1L,
            itemName:       args.itemName ?: 'Wizard Hat',
            buyerUserId:    args.buyer ?: 10L,
            buyerWalletId:  args.buyerWallet ?: 500L,
            sellerUserId:   args.seller ?: 20L,
            sellerWalletId: args.sellerWallet ?: 600L,
            price:          args.price ?: new BigDecimal("50.00"),
            feeAmount:      args.fee ?: new BigDecimal("1.00"),
            state:          state
        )
    }

    // ── open ──────────────────────────────────────────────────────

    def "open creates a PENDING_SELLER_ACCEPT trade when a seller is known"() {
        given:
        tradeRepository.save(_) >> { Trade t -> t.id = 1L; t }

        when:
        def trade = service.open(100L, 1L, 'Wizard Hat', 10L, 500L, 20L, 600L, new BigDecimal("50"))

        then:
        1 * banGuard.assertNotBanned(10L)
        trade.state == 'PENDING_SELLER_ACCEPT'
        trade.feeAmount == new BigDecimal("1.00")
        1 * notificationService.push(10L, 'TRADE_OPENED', _, _, _)
        1 * notificationService.push(20L, 'TRADE_REQUESTED', _, _, _)
    }

    def "open skips seller-accept and goes to PENDING_BUYER_CONFIRM when there is no seller"() {
        given:
        tradeRepository.save(_) >> { Trade t -> t.id = 1L; t }

        when:
        def trade = service.open(100L, 1L, 'Wizard Hat', 10L, 500L, null, null, new BigDecimal("50"))

        then:
        trade.state == 'PENDING_BUYER_CONFIRM'
        1 * notificationService.push(10L, 'TRADE_OPENED', _, _, _)
        0 * notificationService.push(_, 'TRADE_REQUESTED', _, _, _)
    }

    // ── sellerAccept / sellerMarkSent ─────────────────────────────

    def "sellerAccept advances PENDING_SELLER_ACCEPT → PENDING_SELLER_SEND"() {
        given:
        def t = tradeIn('PENDING_SELLER_ACCEPT')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        service.sellerAccept(20L, 1L)

        then:
        1 * banGuard.assertNotBanned(20L)
        t.state == 'PENDING_SELLER_SEND'
        1 * notificationService.push(10L, 'TRADE_ACCEPTED', _, _, _)
    }

    def "sellerAccept forbids a non-seller"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_SELLER_ACCEPT'))

        when:
        service.sellerAccept(99L, 1L)

        then:
        thrown(ForbiddenException)
    }

    def "sellerAccept refuses wrong starting state"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_BUYER_CONFIRM'))

        when:
        service.sellerAccept(20L, 1L)

        then:
        thrown(BadRequestException)
    }

    def "sellerMarkSent advances PENDING_SELLER_SEND → PENDING_BUYER_CONFIRM"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        service.sellerMarkSent(20L, 1L)

        then:
        t.state == 'PENDING_BUYER_CONFIRM'
        1 * notificationService.push(10L, 'TRADE_SENT', _, _, _)
    }

    // ── buyerConfirm → release ────────────────────────────────────

    def "buyerConfirm releases funds and flips to VERIFIED"() {
        given:
        def t = tradeIn('PENDING_BUYER_CONFIRM')
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.buyerConfirm(10L, 1L)

        then:
        t.state == 'VERIFIED'
        // 50.00 − 1.00 fee = 49.00 credited
        sellerWallet.balance == new BigDecimal("49.00")
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'SALE' && tx.amount == new BigDecimal("49.00")
        })
        1 * notificationService.push(10L, 'TRADE_VERIFIED', _, _, _)
        1 * notificationService.push(20L, 'TRADE_VERIFIED', _, _, _)
    }

    def "buyerConfirm forbids a non-buyer"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_BUYER_CONFIRM'))

        when:
        service.buyerConfirm(99L, 1L)

        then:
        thrown(ForbiddenException)
    }

    // ── dispute ───────────────────────────────────────────────────

    def "dispute flips the trade to DISPUTED from any pending state"() {
        given:
        def t = tradeIn(startState)
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }

        when:
        service.dispute(10L, 1L, 'seller ghosted me')

        then:
        t.state == 'DISPUTED'
        t.note == 'seller ghosted me'

        where:
        startState << ['PENDING_SELLER_ACCEPT', 'PENDING_SELLER_SEND', 'PENDING_BUYER_CONFIRM']
    }

    def "dispute forbids non-participants"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_SELLER_ACCEPT'))

        when:
        service.dispute(999L, 1L, 'nosy')

        then:
        thrown(ForbiddenException)
    }

    def "dispute refuses terminal states"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('VERIFIED'))

        when:
        service.dispute(10L, 1L, '?')

        then:
        thrown(BadRequestException)
    }

    // ── cancel ────────────────────────────────────────────────────

    def "cancel refunds the buyer wallet and flips to CANCELLED"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.cancel(10L, 1L, 'changed my mind')

        then:
        buyerWallet.balance == new BigDecimal("50.00")
        1 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' && tx.amount == new BigDecimal("50.00") })
        t.state == 'CANCELLED'
        t.settledAt != null
    }

    def "cancel by a non-participant requires admin authorization"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_SELLER_ACCEPT'))
        adminAuthorization.requireAdmin(999L) >> { throw new ForbiddenException("not admin") }

        when:
        service.cancel(999L, 1L, 'nosy')

        then:
        thrown(ForbiddenException)
    }

    def "cancel refuses terminal states"() {
        given:
        tradeRepository.findById(_) >> Optional.of(tradeIn('VERIFIED'))

        when:
        service.cancel(10L, 1L, 'too late')

        then:
        thrown(BadRequestException)
    }

    // ── 404 wrap ──────────────────────────────────────────────────

    def "get throws NotFoundException for unknown trade id"() {
        given:
        tradeRepository.findById(_) >> Optional.empty()

        when:
        service.get(999L)

        then:
        thrown(NotFoundException)
    }

    // ── ban checks (bugs #59-60) ────────────────────────────────

    def "dispute rejects banned users (bug #59)"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("banned") }
        tradeRepository.findById(_) >> Optional.of(tradeIn('PENDING_SELLER_ACCEPT'))

        when:
        service.dispute(10L, 1L, 'reason')

        then:
        thrown(ForbiddenException)
    }

    def "cancel rejects banned participants (bug #60)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        tradeRepository.findById(_) >> Optional.of(t)
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("banned") }

        when:
        service.cancel(10L, 1L, 'reason')

        then:
        thrown(ForbiddenException)
    }

    def "cancel by admin does NOT check ban on the admin actor (bug #60)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(_) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.cancel(999L, 1L, 'admin override')

        then:
        // Admin path — should NOT call banGuard for the actor, only adminAuthorization
        1 * adminAuthorization.requireAdmin(999L)
        0 * banGuard.assertNotBanned(999L)
        t.state == 'CANCELLED'
    }

    // ── audit subjects (bug #61) ──────────────────────────────────

    def "dispute logs the counterparty as audit subject (bug #61)"() {
        given:
        def t = tradeIn('PENDING_SELLER_ACCEPT')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        def auditService = Mock(com.sboxmarket.service.AuditService)
        service.auditService = auditService

        when:
        service.dispute(10L, 1L, 'seller ghosted')

        then:
        // Buyer (10) disputes → subject should be seller (20)
        1 * auditService.log('TRADE_DISPUTED', 10L, 20L, 1L, _)
    }

    def "cancel logs the counterparty as audit subject (bug #61)"() {
        given:
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        def auditService = Mock(com.sboxmarket.service.AuditService)
        service.auditService = auditService

        when:
        service.cancel(20L, 1L, 'seller cancels')

        then:
        // Seller (20) cancels → subject should be buyer (10)
        1 * auditService.log('TRADE_CANCELLED', 20L, 10L, 1L, _)
    }

    // ── price validation (bug #63) ────────────────────────────────

    def "open rejects zero price (bug #63)"() {
        when:
        service.open(100L, 1L, 'Hat', 10L, 500L, 20L, 600L, BigDecimal.ZERO)

        then:
        thrown(BadRequestException)
    }

    def "open rejects negative price (bug #63)"() {
        when:
        service.open(100L, 1L, 'Hat', 10L, 500L, 20L, 600L, new BigDecimal("-5"))

        then:
        thrown(BadRequestException)
    }

    def "open rejects price over 100k cap (bug #63)"() {
        when:
        service.open(100L, 1L, 'Hat', 10L, 500L, 20L, 600L, new BigDecimal("100001"))

        then:
        thrown(BadRequestException)
    }

    // ── sweeper + banned seller (bug #65) ─────────────────────────

    def "sweepPendingConfirm auto-cancels trades where seller is banned (bug #65)"() {
        given:
        def stale = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        banGuard.isBanned(20L) >> true
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }

        when:
        service.sweepPendingConfirm()

        then:
        stale.state == 'CANCELLED'
        buyerWallet.balance == new BigDecimal("50.00")
        1 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' })
    }

    // ── sweepPendingConfirm (auto-release) ────────────────────────

    def "sweepPendingConfirm is a no-op when the repository returns empty"() {
        given:
        tradeRepository.findPendingConfirmOlderThan(_) >> []

        when:
        service.sweepPendingConfirm()

        then:
        0 * tradeRepository.save(_)
        0 * walletRepository.save(_)
    }

    def "sweepPendingConfirm delegates the cutoff window to the indexed query (bug #27)"() {
        given:
        Long captured = null
        tradeRepository.findPendingConfirmOlderThan(_) >> { args ->
            captured = args[0] as Long
            []
        }

        when:
        service.sweepPendingConfirm()

        then:
        captured != null
        // Cutoff must be roughly now - 8 days. 5-second slop for slow CI.
        def expected = System.currentTimeMillis() - (8L * 24L * 60L * 60L * 1000L)
        Math.abs(captured - expected) < 5000L
    }

    def "sweepPendingConfirm auto-releases every stale trade the query returned"() {
        given:
        def stale1 = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        def stale2 = tradeIn('PENDING_BUYER_CONFIRM', [id: 2L])
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal("0"))
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale1, stale2]
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }

        when:
        service.sweepPendingConfirm()

        then:
        stale1.state == 'VERIFIED'
        stale2.state == 'VERIFIED'
        2 * transactionRepository.save({ Transaction tx -> tx.type == 'SALE' })
    }

    def "sweepPendingConfirm keeps going even if one release throws"() {
        given:
        def good = tradeIn('PENDING_BUYER_CONFIRM', [id: 1L])
        def bad  = tradeIn('PENDING_BUYER_CONFIRM', [id: 2L])
        def sellerWallet = new Wallet(id: 600L, balance: new BigDecimal("0"))
        tradeRepository.findPendingConfirmOlderThan(_) >> [bad, good]
        walletRepository.findById(600L) >>> [
            { throw new RuntimeException("boom") } as Object,
            Optional.of(sellerWallet)
        ]
        tradeRepository.save(_) >> { Trade t -> t }
        walletRepository.save(_) >> { Wallet w -> w }

        when:
        service.sweepPendingConfirm()

        then:
        // The second trade still gets processed despite the first blowing up.
        good.state == 'VERIFIED'
        noExceptionThrown()
    }
}
