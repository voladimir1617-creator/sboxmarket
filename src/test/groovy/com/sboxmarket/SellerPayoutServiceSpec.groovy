package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.service.SellerPayoutService
import com.sboxmarket.service.StripeService
import spock.lang.Specification
import spock.lang.Subject

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The seller payouts card's numbers: available, pending, on hold, paid out,
 * in flight, the next release date and the payout history.
 */
class SellerPayoutServiceSpec extends Specification {

    static final long DAY = 24L * 60L * 60L * 1000L

    TradeRepository       tradeRepository       = Mock()
    TransactionRepository transactionRepository = Mock()
    StripeService         stripeService         = Mock()

    @Subject
    SellerPayoutService service = new SellerPayoutService(
        tradeRepository:       tradeRepository,
        transactionRepository: transactionRepository,
        stripeService:         stripeService,
        autoReleaseDays:       8L
    )

    private static long utc(int y, int m, int d) {
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    private static Wallet wallet(Map extra = [:]) {
        new Wallet([id: 42L, username: 'steam_1', balance: new BigDecimal('120.50'), currency: 'USD'] + extra)
    }

    def "splits escrowed sales into pending and on-hold, net of the fee"() {
        given:
        tradeRepository.summarizeSellerEscrow(7L) >> [
            ['PENDING_SELLER_SEND',   new BigDecimal('19.60'), 1L] as Object[],
            ['PENDING_BUYER_CONFIRM', new BigDecimal('48.02'), 2L] as Object[],
            ['DISPUTED',              new BigDecimal('9.80'),  1L] as Object[]
        ]
        transactionRepository.findPendingByWallet(42L) >> []
        transactionRepository.sumByWalletAndType(42L, _, true) >> BigDecimal.ZERO
        transactionRepository.findWithdrawalsByWallet(42L, _) >> []

        when:
        def s = service.summary(7L, wallet())

        then:
        s.available == new BigDecimal('120.50')
        s.pending.amount == new BigDecimal('67.62')
        s.pending.count == 3L
        s.onHold.amount == new BigDecimal('9.80')
        s.onHold.count == 1L
    }

    def "paid out counts completed withdrawals of both legacy type names; in flight is the pending ones"() {
        given:
        tradeRepository.summarizeSellerEscrow(_) >> []
        transactionRepository.sumByWalletAndType(42L, 'WITHDRAW', true)   >> new BigDecimal('300.00')
        transactionRepository.sumByWalletAndType(42L, 'WITHDRAWAL', true) >> new BigDecimal('25.00')
        transactionRepository.countCompletedByWalletAndType(42L, 'WITHDRAW')   >> 3L
        transactionRepository.countCompletedByWalletAndType(42L, 'WITHDRAWAL') >> 1L
        transactionRepository.findPendingByWallet(42L) >> [
            new Transaction(type: 'WITHDRAW', status: 'PENDING', amount: new BigDecimal('40.00')),
            new Transaction(type: 'DEPOSIT',  status: 'PENDING', amount: new BigDecimal('99.00'))
        ]
        transactionRepository.findWithdrawalsByWallet(42L, _) >> []

        when:
        def s = service.summary(7L, wallet())

        then:
        s.paidOut.amount == new BigDecimal('325.00')
        s.paidOut.count == 4L
        s.inFlight.amount == new BigDecimal('40.00')
        s.inFlight.count == 1L
    }

    def "next release is the oldest buyer-confirm sale plus the auto-release window"() {
        given:
        long confirmClock = utc(2026, 9, 20)
        tradeRepository.summarizeSellerEscrow(_) >> []
        tradeRepository.earliestPendingConfirmForSeller(7L) >> confirmClock
        transactionRepository.findPendingByWallet(_) >> []
        transactionRepository.findWithdrawalsByWallet(_, _) >> []

        expect:
        service.summary(7L, wallet()).nextReleaseAt == confirmClock + 8L * DAY
    }

    def "no sale waiting on a buyer means no release date"() {
        given:
        tradeRepository.summarizeSellerEscrow(_) >> []
        tradeRepository.earliestPendingConfirmForSeller(7L) >> null
        transactionRepository.findPendingByWallet(_) >> []
        transactionRepository.findWithdrawalsByWallet(_, _) >> []

        expect:
        service.summary(7L, wallet()).nextReleaseAt == null
    }

    def "cash-out is ready in test mode, and on a live deployment only after Stripe onboarding"() {
        given:
        stripeService.isLive() >> live
        tradeRepository.summarizeSellerEscrow(_) >> []
        transactionRepository.findPendingByWallet(_) >> []
        transactionRepository.findWithdrawalsByWallet(_, _) >> []

        expect:
        service.summary(7L, wallet(payoutsEnabled: enabled)).cashoutReady == ready

        where:
        live  | enabled || ready
        false | false   || true
        true  | false   || false
        true  | true    || true
    }

    def "history lists the last withdrawals with a bank arrival estimate only while money is moving"() {
        given:
        long mon = utc(2026, 9, 28)   // a Monday
        tradeRepository.summarizeSellerEscrow(_) >> []
        transactionRepository.findPendingByWallet(_) >> []
        transactionRepository.findWithdrawalsByWallet(42L, { it.pageSize == SellerPayoutService.HISTORY_LIMIT }) >> [
            new Transaction(id: 3L, type: 'WITHDRAW', status: 'PENDING',   amount: new BigDecimal('10.00'), createdAt: mon),
            new Transaction(id: 2L, type: 'WITHDRAW', status: 'COMPLETED', amount: new BigDecimal('20.00'), createdAt: mon),
            new Transaction(id: 1L, type: 'WITHDRAW', status: 'REJECTED',  amount: new BigDecimal('30.00'), createdAt: mon)
        ]

        when:
        def h = service.summary(7L, wallet(), mon).history

        then:
        h*.id == [3L, 2L, 1L]
        h*.status == ['PENDING', 'COMPLETED', 'REJECTED']
        h[0].expectedAt == utc(2026, 9, 30)
        h[1].expectedAt == utc(2026, 9, 30)
        h[2].expectedAt == null
    }

    def "a seller with no wallet yet gets zeros, not an error"() {
        given:
        tradeRepository.summarizeSellerEscrow(_) >> []

        when:
        def s = service.summary(7L, null)

        then:
        s.available == BigDecimal.ZERO
        s.paidOut.amount == BigDecimal.ZERO
        s.history == []
        0 * transactionRepository._
    }

    def "payout arrival skips weekends"() {
        expect:
        SellerPayoutService.addBusinessDays(utc(y, m, d), 2) == utc(ey, em, ed)

        where:
        y    | m | d  || ey   | em | ed
        2026 | 9 | 28 || 2026 | 9  | 30   // Mon -> Wed
        2026 | 10 | 1 || 2026 | 10 | 5    // Thu -> Mon
        2026 | 10 | 2 || 2026 | 10 | 6    // Fri -> Tue
        2026 | 10 | 3 || 2026 | 10 | 6    // Sat -> Tue
    }
}
