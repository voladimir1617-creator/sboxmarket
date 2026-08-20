package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.PlatformLedgerService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * The 2% must land somewhere.
 *
 * {@link TradeService#release} subtracted {@code feeAmount} from the seller's
 * credit and then did nothing with it. There was no platform wallet, no
 * treasury account, and no FEE transaction type — the money was taken from one
 * party and credited to no party. Platform revenue existed only as a residual
 * you could reconstruct by summing {@code Trade.feeAmount} after the fact,
 * which meant the business could not observe its own margin against the
 * processing cost it pays on every deposit.
 *
 * This spec wires a REAL {@link PlatformLedgerService} (over mock repositories)
 * rather than a mock, so it proves the money actually arrives in an account
 * rather than merely that a method was called.
 */
class TradeServiceFeeLedgerSpec extends Specification {

    TradeRepository       tradeRepository       = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    AdminAuthorization    adminAuthorization    = Mock()
    AuditService          auditService          = Mock()
    TextSanitizer         textSanitizer         = Mock() { medium(_) >> { String s -> s ?: '' } }

    Wallet sellerWallet = new Wallet(id: 600L, balance: BigDecimal.ZERO, currency: 'USD')
    Wallet treasury     = new Wallet(id: 900L, username: PlatformLedgerService.TREASURY_USERNAME,
                                     balance: BigDecimal.ZERO, currency: 'USD')

    List<Transaction> ledger = []

    PlatformLedgerService platformLedgerService = new PlatformLedgerService(
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        processingFeePercent  : new BigDecimal('2.9'),
        processingFeeFixed    : new BigDecimal('0.30')
    )

    @Subject
    TradeService service = new TradeService(
        tradeRepository       : tradeRepository,
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        notificationService   : notificationService,
        banGuard              : banGuard,
        adminAuthorization    : adminAuthorization,
        auditService          : auditService,
        textSanitizer         : textSanitizer,
        platformLedgerService : platformLedgerService,
        autoReleaseDays       : 8L,
        sellerResponseDays    : 3L
    )

    def setup() {
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.findByUsername(PlatformLedgerService.TREASURY_USERNAME) >> treasury
        walletRepository.save(_) >> { Wallet w -> w }
        tradeRepository.save(_) >> { Trade t -> t }
        transactionRepository.save(_) >> { Transaction tx -> ledger << tx; tx }
    }

    private Trade pendingConfirm(long id = 1L) {
        new Trade(
            id:             id,
            listingId:      100L,
            itemId:         1L,
            itemName:       'AK-47 | Redline',
            buyerUserId:    10L,
            buyerWalletId:  500L,
            sellerUserId:   20L,
            sellerWalletId: 600L,
            price:          new BigDecimal('50.00'),
            feeAmount:      new BigDecimal('1.00'),
            state:          'PENDING_BUYER_CONFIRM'
        )
    }

    def "buyerConfirm credits the seller net AND books the fee to the platform treasury"() {
        given:
        def t = pendingConfirm(9L)
        tradeRepository.findById(9L) >> Optional.of(t)

        when:
        service.buyerConfirm(10L, 9L)

        then: "the seller gets price - fee, unchanged"
        t.state == 'VERIFIED'
        sellerWallet.balance == new BigDecimal('49.00')

        and: "and the fee is no longer credited to nobody"
        treasury.balance == new BigDecimal('1.00')

        and: "every cent of the price is accounted for across the two accounts"
        sellerWallet.balance + treasury.balance == t.price
    }

    def "a FEE ledger row is written against the treasury, joinable to the listing"() {
        given:
        def t = pendingConfirm(11L)
        tradeRepository.findById(11L) >> Optional.of(t)

        when:
        service.buyerConfirm(10L, 11L)

        then:
        def fee = ledger.find { it.type == PlatformLedgerService.TYPE_FEE }
        fee != null
        fee.walletId  == 900L
        fee.amount    == new BigDecimal('1.00')
        fee.status    == 'COMPLETED'
        fee.listingId == 100L

        and: "the seller's SALE row still records the NET credit, not the gross"
        def sale = ledger.find { it.type == 'SALE' }
        sale.amount == new BigDecimal('49.00')
    }

    def "the auto-release sweep books the fee too — it is a real sale, not a special case"() {
        given: "a trade the buyer went silent on"
        def stale = pendingConfirm(7L)
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        banGuard.isBanned(20L) >> false

        when:
        service.sweepPendingConfirm()

        then:
        stale.state == 'VERIFIED'
        treasury.balance == new BigDecimal('1.00')
    }

    def "an admin force-release books the fee"() {
        given:
        def disputed = pendingConfirm(13L)
        disputed.state = 'DISPUTED'
        tradeRepository.findById(13L) >> Optional.of(disputed)

        when:
        service.adminRelease(1L, 13L, 'seller proved delivery')

        then:
        treasury.balance == new BigDecimal('1.00')
    }

    def "the fee is booked even when the seller credit had to be skipped"() {
        given: "a VERIFIED trade whose seller wallet has gone missing"
        def t = pendingConfirm(15L)
        t.sellerWalletId = null
        tradeRepository.findById(15L) >> Optional.of(t)

        when:
        service.buyerConfirm(10L, 15L)

        then: "the seller is owed a manual payout"
        1 * auditService.log(AuditService.MANUAL_PAYOUT_REQUIRED, _, _, 15L, _)

        and: "but the fee was still earned on a VERIFIED trade — booking it on a"
        // narrower condition than TradeRepository.sumFeesSince (which counts
        // every VERIFIED row) would put two disagreeing revenue figures on the
        // same admin dashboard.
        treasury.balance == new BigDecimal('1.00')
    }

    def "a zero-fee trade books nothing rather than an empty row"() {
        given:
        def t = pendingConfirm(17L)
        t.feeAmount = BigDecimal.ZERO
        tradeRepository.findById(17L) >> Optional.of(t)

        when:
        service.buyerConfirm(10L, 17L)

        then:
        sellerWallet.balance == new BigDecimal('50.00')
        treasury.balance == BigDecimal.ZERO
        ledger.every { it.type != PlatformLedgerService.TYPE_FEE }
    }

    def "a cancelled trade books no fee — nothing was earned"() {
        given:
        def t = pendingConfirm(19L)
        t.state = 'PENDING_SELLER_ACCEPT'
        tradeRepository.findById(19L) >> Optional.of(t)
        walletRepository.findById(500L) >> Optional.of(new Wallet(id: 500L, balance: BigDecimal.ZERO, currency: 'USD'))

        when:
        service.cancel(20L, 19L, 'seller cannot deliver')

        then:
        t.state == 'CANCELLED'
        treasury.balance == BigDecimal.ZERO
    }

    def "TradeService still functions with no ledger wired (older test contexts)"() {
        given: "the optional collaborator absent, as ~200 existing specs construct it"
        def bare = new TradeService(
            tradeRepository: tradeRepository, walletRepository: walletRepository,
            transactionRepository: transactionRepository, notificationService: notificationService,
            banGuard: banGuard, adminAuthorization: adminAuthorization, auditService: auditService,
            textSanitizer: textSanitizer, autoReleaseDays: 8L, sellerResponseDays: 3L)
        def t = pendingConfirm(21L)
        tradeRepository.findById(21L) >> Optional.of(t)

        when:
        bare.buyerConfirm(10L, 21L)

        then: "the release still completes; only the bookkeeping row is absent"
        notThrown(Exception)
        t.state == 'VERIFIED'
        sellerWallet.balance == new BigDecimal('49.00')
    }
}
