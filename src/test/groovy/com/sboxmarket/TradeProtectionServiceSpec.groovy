package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeProtection
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TradeProtectionRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TradeProtectionService
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Unit coverage for the buyer-side Trade Protection add-on.
 *
 * The four money-moving paths each get their state change AND their
 * wallet/transaction movement asserted:
 *
 *   quote()     — the pure 2%-floored-at-$0.25 fee function.
 *   enable()    — buyer gate, escrow-only gate, double-enable guard,
 *                 wallet preconditions (NO_WALLET / WALLET_FROZEN /
 *                 INSUFFICIENT_BALANCE), then the fee debit + PURCHASE
 *                 transaction + ACTIVE protection row.
 *   autoClaim() — the coverage refund + REFUND transaction + flip to
 *                 CLAIMED, plus its idempotent no-op exits.
 *   expire()    — ACTIVE → EXPIRED, plus its idempotent no-op exits.
 *
 * The optional collaborators (notificationService / auditService) are
 * wired as Mocks so the side-effect pushes can be verified; the service
 * declares them @Autowired(required = false) and null-guards every call.
 */
class TradeProtectionServiceSpec extends Specification {

    TradeProtectionRepository tradeProtectionRepository = Mock()
    TradeRepository           tradeRepository           = Mock()
    WalletRepository          walletRepository          = Mock()
    TransactionRepository     transactionRepository     = Mock()
    NotificationService       notificationService       = Mock()
    AuditService              auditService              = Mock()

    @Subject
    TradeProtectionService service = new TradeProtectionService(
        tradeProtectionRepository : tradeProtectionRepository,
        tradeRepository           : tradeRepository,
        walletRepository          : walletRepository,
        transactionRepository     : transactionRepository,
        notificationService       : notificationService,
        auditService              : auditService
    )

    /** A trade in escrow, fully wired for an enable() happy path.
     *  Map first: Groovy collects `tradeIn('STATE', buyer: 10L)` named
     *  args into a LEADING map, so the optional opts param must come
     *  before the positional `state`. */
    private Trade tradeIn(Map args = [:], String state) {
        new Trade(
            id:             args.containsKey('id') ? args.id : 1L,
            listingId:      args.listingId ?: 100L,
            itemId:         args.itemId ?: 1L,
            itemName:       args.containsKey('itemName') ? args.itemName : 'Wizard Hat',
            buyerUserId:    args.containsKey('buyer') ? args.buyer : 10L,
            buyerWalletId:  args.containsKey('buyerWallet') ? args.buyerWallet : 500L,
            sellerUserId:   args.containsKey('seller') ? args.seller : 20L,
            price:          args.containsKey('price') ? args.price : new BigDecimal('50.00'),
            state:          state
        )
    }

    // ── quote ─────────────────────────────────────────────────────

    @Unroll
    def "quote(#price) -> #expected (2% floored at \$0.25, HALF_UP to cents)"() {
        expect:
        service.quote(price) == expected

        where:
        price                      || expected
        new BigDecimal('50.00')    || new BigDecimal('1.00')   // 2% of 50
        new BigDecimal('100.00')   || new BigDecimal('2.00')   // 2% of 100
        new BigDecimal('12.50')    || new BigDecimal('0.25')   // 2% = 0.25, on the floor
        new BigDecimal('13.00')    || new BigDecimal('0.26')   // 2% = 0.26, just above floor
        new BigDecimal('5.00')     || new BigDecimal('0.25')   // 2% = 0.10, below floor → MIN_FEE
        new BigDecimal('1.00')     || new BigDecimal('0.25')   // 2% = 0.02, below floor → MIN_FEE
        new BigDecimal('0.01')     || new BigDecimal('0.25')   // tiny price → MIN_FEE
    }

    def "quote rounds the 2% cut HALF_UP to cents"() {
        expect: "12.75 * 0.02 = 0.255 → rounds up to 0.26"
        service.quote(new BigDecimal('12.75')) == new BigDecimal('0.26')

        and: "12.625 * 0.02 = 0.2525 → rounds down to 0.25 (also clamped by floor)"
        service.quote(new BigDecimal('12.625')) == new BigDecimal('0.25')
    }

    @Unroll
    def "quote rounds HALF_UP to cents ABOVE the floor (#price -> #expected, floor can't mask it)"() {
        // These prices all yield a 2% cut strictly above $0.25, so the
        // result is the rounded percentage itself — a rounding regression
        // here is NOT hidden by the MIN_FEE clamp.
        expect:
        service.quote(price) == expected

        where:
        price                     || expected
        new BigDecimal('99.75')   || new BigDecimal('2.00')   // 1.9950 → 2.00 (half-up)
        new BigDecimal('99.74')   || new BigDecimal('1.99')   // 1.9948 → 1.99 (down)
        new BigDecimal('37.75')   || new BigDecimal('0.76')   // 0.7550 → 0.76 (half-up)
        new BigDecimal('37.74')   || new BigDecimal('0.75')   // 0.7548 → 0.75 (down)
    }

    def "quote of a large price scales linearly with the 2% rate"() {
        expect:
        service.quote(new BigDecimal('1000.00')) == new BigDecimal('20.00')
    }

    def "quote result is always scaled to exactly 2 decimal places"() {
        expect: "even the MIN_FEE floor and a whole-dollar fee carry scale 2 (cents)"
        service.quote(new BigDecimal('5.00')).scale() == 2
        service.quote(new BigDecimal('50.00')).scale() == 2
        service.quote(null).scale() == 2
    }

    def "quote at the exact floor boundary returns MIN_FEE without rounding artefacts"() {
        expect: "12.50 * 0.02 = 0.2500 exactly == MIN_FEE"
        service.quote(new BigDecimal('12.50')) == new BigDecimal('0.25')

        and: "a hair above the boundary tips just over the floor"
        service.quote(new BigDecimal('12.51')) == new BigDecimal('0.25')   // 0.2502 → 0.25
        service.quote(new BigDecimal('13.00')) == new BigDecimal('0.26')   // 0.2600 > floor
    }

    @Unroll
    def "quote of a null / non-positive price (#price) yields the MIN_FEE floor"() {
        expect:
        service.quote(price) == new BigDecimal('0.25')

        where:
        price << [null, BigDecimal.ZERO, new BigDecimal('0.00'), new BigDecimal('-5.00')]
    }

    // ── findForTrade / isProtected ────────────────────────────────

    def "findForTrade returns the protection row for a trade"() {
        given:
        def p = new TradeProtection(id: 7L, tradeId: 1L, status: TradeProtection.ACTIVE)
        tradeProtectionRepository.findByTradeId(1L) >> p

        expect:
        service.findForTrade(1L).is(p)
    }

    def "findForTrade returns null for a null tradeId without hitting the repo"() {
        when:
        def result = service.findForTrade(null)

        then:
        result == null
        0 * tradeProtectionRepository.findByTradeId(_)
    }

    def "isProtected is true when a protection record exists"() {
        given:
        tradeProtectionRepository.existsByTradeId(1L) >> true

        expect:
        service.isProtected(1L)
    }

    def "isProtected is false when no protection record exists"() {
        given:
        tradeProtectionRepository.existsByTradeId(1L) >> false

        expect:
        !service.isProtected(1L)
    }

    def "isProtected is false for a null tradeId without hitting the repo"() {
        when:
        def result = service.isProtected(null)

        then:
        !result
        0 * tradeProtectionRepository.existsByTradeId(_)
    }

    // ── enable — guards ───────────────────────────────────────────

    def "enable 404s an unknown trade"() {
        given:
        tradeRepository.findById(99L) >> Optional.empty()

        when:
        service.enable(10L, 99L)

        then:
        thrown(NotFoundException)
        0 * tradeProtectionRepository.save(_)
    }

    def "enable forbids a non-buyer"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(tradeIn('PENDING_SELLER_SEND', buyer: 10L))

        when: "the seller tries to protect the buyer's trade"
        service.enable(20L, 1L)

        then:
        thrown(ForbiddenException)
        0 * walletRepository.findById(_)
        0 * tradeProtectionRepository.save(_)
    }

    def "enable forbids when the trade has no buyer on it"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(tradeIn('PENDING_SELLER_SEND', buyer: null))

        when:
        service.enable(10L, 1L)

        then:
        thrown(ForbiddenException)
        0 * tradeProtectionRepository.save(_)
    }

    @Unroll
    def "enable rejects a trade in a non-protectable state (#state) with TRADE_NOT_PROTECTABLE"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(tradeIn(state, buyer: 10L))

        when:
        service.enable(10L, 1L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'TRADE_NOT_PROTECTABLE'
        0 * tradeProtectionRepository.save(_)

        where:
        state << ['VERIFIED', 'CANCELLED', 'DISPUTED']
    }

    @Unroll
    def "enable allows a trade still in escrow (#state)"() {
        given:
        def trade  = tradeIn(state, buyer: 10L, buyerWallet: 500L, price: new BigDecimal('50.00'))
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def protection = service.enable(10L, 1L)

        then:
        protection.status == TradeProtection.ACTIVE

        where:
        state << ['PENDING_SELLER_ACCEPT', 'PENDING_SELLER_SEND', 'PENDING_BUYER_CONFIRM']
    }

    def "enable rejects a double-enable with PROTECTION_EXISTS"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(tradeIn('PENDING_SELLER_SEND', buyer: 10L))
        tradeProtectionRepository.existsByTradeId(1L) >> true

        when:
        service.enable(10L, 1L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'PROTECTION_EXISTS'
        and: "no fee is charged on the rejected double-enable"
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * tradeProtectionRepository.save(_)
    }

    // ── enable — wallet preconditions ─────────────────────────────

    def "enable rejects NO_WALLET when the trade has no buyerWalletId"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(
            tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: null))
        tradeProtectionRepository.existsByTradeId(1L) >> false

        when:
        service.enable(10L, 1L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'NO_WALLET'
        0 * walletRepository.findById(_)
        0 * tradeProtectionRepository.save(_)
    }

    def "enable rejects NO_WALLET when the buyer wallet row is missing"() {
        given:
        tradeRepository.findById(1L) >> Optional.of(
            tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L))
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.empty()

        when:
        service.enable(10L, 1L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'NO_WALLET'
        0 * walletRepository.save(_)
        0 * tradeProtectionRepository.save(_)
    }

    def "enable rejects WALLET_FROZEN when the buyer wallet is frozen"() {
        given:
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('100.00'),
            currency: 'USD', frozen: true)
        tradeRepository.findById(1L) >> Optional.of(
            tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L))
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        service.enable(10L, 1L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'WALLET_FROZEN'
        and: "a frozen wallet is never debited"
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * tradeProtectionRepository.save(_)
    }

    def "enable rejects INSUFFICIENT_BALANCE when the wallet can't cover the fee"() {
        given: "fee on a \$50 trade is \$1.00 but the wallet holds only \$0.50"
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('0.50'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(
            tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L,
                price: new BigDecimal('50.00')))
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        service.enable(10L, 1L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INSUFFICIENT_BALANCE'
        and: "nothing is debited and no protection row is created"
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * tradeProtectionRepository.save(_)
        wallet.balance == new BigDecimal('0.50')
    }

    def "enable accepts a wallet balance exactly equal to the fee (bound inclusive)"() {
        given: "fee on a \$50 trade is exactly \$1.00 and the wallet holds \$1.00"
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('1.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(
            tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L,
                price: new BigDecimal('50.00')))
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def protection = service.enable(10L, 1L)

        then:
        protection.status == TradeProtection.ACTIVE
        wallet.balance == new BigDecimal('0.00')
    }

    // ── enable — happy path ───────────────────────────────────────

    def "enable debits the fee, writes a PURCHASE transaction and creates an ACTIVE protection"() {
        given:
        def trade  = tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L,
            seller: 20L, price: new BigDecimal('50.00'), itemName: 'Wizard Hat',
            listingId: 100L)
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        TradeProtection saved = null
        tradeProtectionRepository.save(_) >> { TradeProtection p -> saved = p; p.id = 7L; p }

        when:
        def protection = service.enable(10L, 1L)

        then: "the \$1.00 fee (2% of 50) is debited from the buyer wallet"
        wallet.balance == new BigDecimal('9.00')
        1 * walletRepository.save(wallet)

        and: "a COMPLETED PURCHASE transaction records the fee on the buyer wallet"
        1 * transactionRepository.save({ Transaction tx ->
            tx.walletId == 500L &&
            tx.type == 'PURCHASE' &&
            tx.status == 'COMPLETED' &&
            tx.amount == new BigDecimal('1.00') &&
            tx.currency == 'USD' &&
            tx.stripeReference == 'trade_protection' &&
            tx.listingId == 100L
        })

        and: "the protection row is ACTIVE, tied to the trade, fee + full-price cover"
        protection.is(saved)
        protection.tradeId == 1L
        protection.buyerUserId == 10L
        protection.status == TradeProtection.ACTIVE
        protection.feeAmount == new BigDecimal('1.00')
        protection.coverageAmount == new BigDecimal('50.00')
        protection.createdAt > 0
        protection.updatedAt > 0

        and: "the buyer is notified and the action is audited"
        1 * notificationService.safePush(10L, 'TRADE_PROTECTED', _, _, 1L, _)
        1 * auditService.log('TRADE_PROTECTION_ENABLED', 10L, 20L, 1L, _)
    }

    def "enable charges the MIN_FEE floor on a cheap trade"() {
        given: "a \$5 trade — 2% would be \$0.10, floored up to the \$0.25 MIN_FEE"
        def trade  = tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L,
            price: new BigDecimal('5.00'))
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def protection = service.enable(10L, 1L)

        then:
        wallet.balance == new BigDecimal('9.75')
        protection.feeAmount == new BigDecimal('0.25')
        protection.coverageAmount == new BigDecimal('5.00')
        1 * transactionRepository.save({ Transaction tx -> tx.amount == new BigDecimal('0.25') })
    }

    def "enable charges a rounded-up 2% fee above the floor (money-correct debit)"() {
        given: "a \$13 trade — 2% = \$0.26, just above the \$0.25 floor"
        def trade  = tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L,
            price: new BigDecimal('13.00'))
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def protection = service.enable(10L, 1L)

        then: "exactly \$0.26 leaves the wallet — the debit, the tx and the row all agree"
        wallet.balance == new BigDecimal('9.74')
        protection.feeAmount == new BigDecimal('0.26')
        protection.coverageAmount == new BigDecimal('13.00')
        1 * transactionRepository.save({ Transaction tx -> tx.amount == new BigDecimal('0.26') })
    }

    def "enable freezes coverage at the full item price and the fee equals quote(price)"() {
        given: "the protection row's cover must be the FULL price, fee the quoted 2%"
        def trade  = tradeIn('PENDING_BUYER_CONFIRM', buyer: 10L, buyerWallet: 500L,
            price: new BigDecimal('250.00'))
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('500.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def protection = service.enable(10L, 1L)

        then:
        protection.coverageAmount == new BigDecimal('250.00')   // full price, not net-of-fee
        protection.feeAmount == service.quote(new BigDecimal('250.00'))
        protection.feeAmount == new BigDecimal('5.00')
        wallet.balance == new BigDecimal('495.00')
    }

    def "enable carries the buyer wallet currency onto the fee transaction"() {
        given: "a non-USD wallet — the PURCHASE tx must inherit its currency"
        def trade  = tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L,
            price: new BigDecimal('50.00'))
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'), currency: 'EUR')
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        service.enable(10L, 1L)

        then:
        1 * transactionRepository.save({ Transaction tx -> tx.currency == 'EUR' })
    }

    def "enable on a trade with a null itemName falls back to a trade-number description"() {
        given:
        def trade  = tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L,
            price: new BigDecimal('50.00'), itemName: null, id: 42L)
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'), currency: 'USD')
        tradeRepository.findById(42L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(42L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        service.enable(10L, 42L)

        then: "no NPE on the null itemName — description degrades gracefully"
        1 * transactionRepository.save({ Transaction tx ->
            tx.description != null && tx.description.contains('42')
        })
    }

    def "enable still succeeds when the optional notification + audit collaborators are null"() {
        given:
        service.notificationService = null
        service.auditService = null
        def trade  = tradeIn('PENDING_SELLER_SEND', buyer: 10L, buyerWallet: 500L,
            price: new BigDecimal('50.00'))
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def protection = service.enable(10L, 1L)

        then: "no NPE on the null-guarded ?. side-effect calls"
        protection.status == TradeProtection.ACTIVE
        wallet.balance == new BigDecimal('9.00')
    }

    // ── autoClaim ─────────────────────────────────────────────────

    def "autoClaim refunds the coverage, writes a REFUND transaction and flips to CLAIMED"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, feeAmount: new BigDecimal('1.00'),
            coverageAmount: new BigDecimal('50.00'))
        def trade  = tradeIn('CANCELLED', buyer: 10L, buyerWallet: 500L,
            itemName: 'Wizard Hat', listingId: 100L)
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('5.00'), currency: 'USD')
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def result = service.autoClaim(1L, 'Seller never delivered')

        then: "the full \$50 cover is credited back to the buyer wallet"
        wallet.balance == new BigDecimal('55.00')
        1 * walletRepository.save(wallet)

        and: "a COMPLETED REFUND transaction records the payout"
        1 * transactionRepository.save({ Transaction tx ->
            tx.walletId == 500L &&
            tx.type == 'REFUND' &&
            tx.status == 'COMPLETED' &&
            tx.amount == new BigDecimal('50.00') &&
            tx.stripeReference == 'trade_protection_claim' &&
            tx.listingId == 100L
        })

        and: "the protection flips to CLAIMED with the reason + resolution stamp"
        result.is(protection)
        protection.status == TradeProtection.CLAIMED
        protection.claimReason == 'Seller never delivered'
        protection.resolvedAt != null
        protection.updatedAt == protection.resolvedAt

        and: "the buyer is notified and the claim is audited"
        1 * notificationService.safePush(10L, 'TRADE_PROTECTION_CLAIMED', _, _, 1L, _)
        1 * auditService.log('TRADE_PROTECTION_CLAIMED', null, 10L, 1L, _)
    }

    def "autoClaim defaults a null reason to 'Trade failed'"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        def trade  = tradeIn('CANCELLED', buyer: 10L, buyerWallet: 500L)
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        service.autoClaim(1L, null)

        then:
        protection.claimReason == 'Trade failed'
    }

    def "autoClaim truncates an over-long claim reason to 255 chars"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        def trade  = tradeIn('CANCELLED', buyer: 10L, buyerWallet: 500L)
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        service.autoClaim(1L, 'x' * 400)

        then:
        protection.claimReason.length() == 255
    }

    def "autoClaim is a no-op returning null when the trade has no protection"() {
        given:
        tradeProtectionRepository.findByTradeId(1L) >> null

        when:
        def result = service.autoClaim(1L, 'whatever')

        then:
        result == null
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * tradeProtectionRepository.save(_)
    }

    @Unroll
    def "autoClaim is an idempotent no-op when the protection is already #status"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: status, coverageAmount: new BigDecimal('50.00'))
        tradeProtectionRepository.findByTradeId(1L) >> protection

        when:
        def result = service.autoClaim(1L, 'double fire')

        then: "the already-resolved protection is returned untouched, no money moves"
        result.is(protection)
        protection.status == status
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * tradeProtectionRepository.save(_)

        where:
        status << [TradeProtection.CLAIMED, TradeProtection.EXPIRED]
    }

    def "autoClaim still flips to CLAIMED when the buyer wallet is missing (manual payout)"() {
        given: "the trade points at a wallet id but the wallet row is gone"
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        def trade = tradeIn('CANCELLED', buyer: 10L, buyerWallet: 500L)
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        walletRepository.findById(500L) >> Optional.empty()
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def result = service.autoClaim(1L, 'Seller fault')

        then: "no credit is written but the protection is still resolved as CLAIMED"
        result.status == TradeProtection.CLAIMED
        result.resolvedAt != null
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        1 * tradeProtectionRepository.save(protection)
    }

    def "autoClaim still flips to CLAIMED when the trade has no buyer wallet on it"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        def trade = tradeIn('CANCELLED', buyer: 10L, buyerWallet: null)
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def result = service.autoClaim(1L, 'Seller fault')

        then:
        result.status == TradeProtection.CLAIMED
        0 * walletRepository.findById(_)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
    }

    def "autoClaim still flips to CLAIMED when the trade row itself is gone"() {
        given: "protection exists but the trade can't be loaded — best-effort claim"
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.empty()
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def result = service.autoClaim(1L, 'Seller fault')

        then:
        result.status == TradeProtection.CLAIMED
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
    }

    def "autoClaim refunds the protection's frozen coverage, NOT the trade's current price"() {
        given: "coverage was locked at \$80 when protection was bought; the trade " +
               "row now (somehow) carries a different price — the payout must honour " +
               "the frozen cover, never re-derive it from the live trade"
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, feeAmount: new BigDecimal('1.60'),
            coverageAmount: new BigDecimal('80.00'))
        def trade  = tradeIn('CANCELLED', buyer: 10L, buyerWallet: 500L,
            price: new BigDecimal('5.00'))   // deliberately != coverageAmount
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        service.autoClaim(1L, 'Seller fault')

        then: "the \$80 frozen cover is paid out — the \$5 live price is irrelevant"
        wallet.balance == new BigDecimal('80.00')
        1 * transactionRepository.save({ Transaction tx -> tx.amount == new BigDecimal('80.00') })
    }

    def "autoClaim credits a frozen wallet — a payout owed to the buyer bypasses the freeze"() {
        given: "freeze blocks debits (deposits/withdrawals/purchases), never a refund " +
               "the platform owes — autoClaim is a REFUND credit, mirrors refundBuyer()"
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        def trade  = tradeIn('DISPUTED', buyer: 10L, buyerWallet: 500L)
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('5.00'),
            currency: 'USD', frozen: true)
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def result = service.autoClaim(1L, 'Trade disputed by buyer')

        then: "the cover still lands in the frozen wallet"
        wallet.balance == new BigDecimal('55.00')
        result.status == TradeProtection.CLAIMED
        1 * transactionRepository.save({ Transaction tx -> tx.type == 'REFUND' })
    }

    def "autoClaim carries the buyer wallet currency onto the payout transaction"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        def trade  = tradeIn('CANCELLED', buyer: 10L, buyerWallet: 500L)
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('0.00'), currency: 'EUR')
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        service.autoClaim(1L, 'Seller fault')

        then:
        1 * transactionRepository.save({ Transaction tx -> tx.currency == 'EUR' })
    }

    def "autoClaim is genuinely idempotent — a real second fire moves no money"() {
        given: "claim once for real, then fire again on the SAME now-CLAIMED row"
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        def trade  = tradeIn('CANCELLED', buyer: 10L, buyerWallet: 500L)
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when: "first claim pays out the \$50 cover"
        service.autoClaim(1L, 'Seller fault')

        then:
        wallet.balance == new BigDecimal('50.00')

        when: "a duplicate trade-failure path fires autoClaim again"
        def second = service.autoClaim(1L, 'Seller fault again')

        then: "no second payout — the buyer is credited exactly once for one lost sale"
        second.is(protection)
        second.status == TradeProtection.CLAIMED
        second.claimReason == 'Seller fault'          // first reason preserved, not overwritten
        wallet.balance == new BigDecimal('50.00')     // unchanged by the second call
    }

    def "autoClaim writes a single COMPLETED REFUND transaction (no PENDING intermediate)"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        def trade  = tradeIn('CANCELLED', buyer: 10L, buyerWallet: 500L)
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('0.00'), currency: 'USD')
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeRepository.findById(1L) >> Optional.of(trade)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        service.autoClaim(1L, 'Seller fault')

        then: "exactly one transaction, already COMPLETED"
        1 * transactionRepository.save({ Transaction tx -> tx.status == 'COMPLETED' })
    }

    // ── expire ────────────────────────────────────────────────────

    def "expire flips an ACTIVE protection to EXPIRED with a resolution stamp"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.ACTIVE, coverageAmount: new BigDecimal('50.00'))
        tradeProtectionRepository.findByTradeId(1L) >> protection
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def result = service.expire(1L)

        then:
        result.is(protection)
        protection.status == TradeProtection.EXPIRED
        protection.resolvedAt != null
        protection.updatedAt == protection.resolvedAt
        1 * tradeProtectionRepository.save(protection)
        and: "expiry keeps the fee — no wallet movement, no payout"
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
    }

    def "expire is a no-op returning null when the trade has no protection"() {
        given:
        tradeProtectionRepository.findByTradeId(1L) >> null

        when:
        def result = service.expire(1L)

        then:
        result == null
        0 * tradeProtectionRepository.save(_)
    }

    @Unroll
    def "expire is an idempotent no-op when the protection is already #status"() {
        given:
        def protection = new TradeProtection(id: 7L, tradeId: 1L,
            status: status, coverageAmount: new BigDecimal('50.00'))
        tradeProtectionRepository.findByTradeId(1L) >> protection

        when:
        def result = service.expire(1L)

        then:
        result.is(protection)
        protection.status == status
        0 * tradeProtectionRepository.save(_)

        where:
        status << [TradeProtection.CLAIMED, TradeProtection.EXPIRED]
    }

    // ── summary ───────────────────────────────────────────────────

    def "summary returns a compact map for a protected trade"() {
        given:
        def p = new TradeProtection(id: 7L, tradeId: 1L, buyerUserId: 10L,
            status: TradeProtection.CLAIMED, feeAmount: new BigDecimal('1.00'),
            coverageAmount: new BigDecimal('50.00'), claimReason: 'Seller fault',
            createdAt: 1000L, resolvedAt: 2000L)
        tradeProtectionRepository.findByTradeId(1L) >> p

        when:
        def summary = service.summary(1L)

        then:
        summary == [
            id:             7L,
            tradeId:        1L,
            status:         'CLAIMED',
            feeAmount:      new BigDecimal('1.00'),
            coverageAmount: new BigDecimal('50.00'),
            claimReason:    'Seller fault',
            createdAt:      1000L,
            resolvedAt:     2000L
        ]
    }

    def "summary returns null for an unprotected trade"() {
        given:
        tradeProtectionRepository.findByTradeId(1L) >> null

        expect:
        service.summary(1L) == null
    }

    def "summary returns null for a null tradeId"() {
        expect:
        service.summary(null) == null
    }
}
