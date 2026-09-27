package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.PlatformLedgerService
import com.sboxmarket.service.StripeService
import com.sboxmarket.service.TradeService
import spock.lang.Specification

/**
 * The per-account payout charge, and the minimums derived from it.
 *
 * <h3>What was wrong</h3>
 *
 * Stripe Connect bills USD 2.00 per MONTHLY ACTIVE connected account -
 * fixed, per person, not per dollar. Every cost in the model was a rate, so
 * this one was absent entirely, and the shipped USD 1.00 minimum withdrawal
 * let a seller yield USD 0.02 of commission against USD 2.00 of cost.
 *
 * <h3>What these tests pin</h3>
 *
 * <ol>
 *   <li>The thresholds are DERIVED from the charge and the take rate, not
 *       hardcoded: change the charge and they move.</li>
 *   <li>The charge is folded into the payout fee the user is quoted, but
 *       only on a payout below the break-even and only once a calendar
 *       month.</li>
 *   <li>A raised minimum does not strand money: a FULL-BALANCE sweep is
 *       exempt from it, and is refused only when the fees genuinely leave
 *       nothing.</li>
 * </ol>
 *
 * Each case asserts on behaviour a caller can observe - a refusal code, the
 * dollar figure inside a refusal, or the wallet actually being debited -
 * never on the presence of a line of source.
 */
class PerAccountPayoutChargeSpec extends Specification {

    TransactionRepository transactionRepository = Mock()
    WalletRepository      walletRepository      = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    AuditService          auditService          = Mock()
    NotificationService   notificationService   = Mock()

    /** Shipped rates, stated explicitly so a config drift fails here first. */
    private PlatformLedgerService ledger(Map overrides = [:]) {
        def svc = new PlatformLedgerService(
            walletRepository:          walletRepository,
            transactionRepository:     transactionRepository,
            processingFeePercent:      new BigDecimal('2.9'),
            processingFeeFixed:        new BigDecimal('0.30'),
            payoutFeePercent:          new BigDecimal('0.25'),
            payoutFeeFixed:            new BigDecimal('0.25'),
            payoutAccountMonthlyFee:   new BigDecimal('2.00'),
            maxFeeShare:               new BigDecimal('0.10')
        )
        overrides.each { k, v -> svc."$k" = v }
        svc
    }

    /** secretKey without "replace_me" => isLive() true => the real payout branch. */
    private StripeService stripe(PlatformLedgerService led) {
        new StripeService(
            transactionRepository: transactionRepository,
            walletRepository:      walletRepository,
            steamUserRepository:   steamUserRepository,
            auditService:          auditService,
            notificationService:   notificationService,
            platformLedgerService: led,
            secretKey:             'sk_test_live_unit_fake',
            publishableKey:        'pk_test_live_unit_fake',
            webhookSecret:         'whsec_unit_fake',
            successUrl:            'http://localhost/ok',
            cancelUrl:             'http://localhost/cancel',
            currency:              'usd'
        )
    }

    private Wallet payoutReadyWallet(BigDecimal balance) {
        new Wallet(
            id: 910L,
            balance: balance,
            username: 'steam_76561198000000910',
            payoutsEnabled: true,
            stripeConnectAccountId: 'acct_unit_test'
        )
    }

    // ── 1. The derivations ──────────────────────────────────────────

    def "the break-even is the per-account charge divided by the take rate"() {
        expect: "2.00 / 0.02 - not the 101.95 the brief carried"
        ledger().perAccountBreakEvenGmv() == new BigDecimal('100.00')

        and: "and it is the SHIPPED take rate, one copy of it"
        ledger().takeRate() == TradeService.FEE_RATE
    }

    def "the break-even follows the charge instead of being a remembered constant"() {
        expect:
        ledger(payoutAccountMonthlyFee: fee).perAccountBreakEvenGmv() == new BigDecimal(expected)

        where:
        fee                      || expected
        new BigDecimal('2.00')   || '100.00'
        new BigDecimal('5.00')   || '250.00'
        new BigDecimal('0.50')   || '25.00'
        BigDecimal.ZERO          || '0.00'     // a rail with no per-account charge
    }

    def "the minimum deposit is the smallest amount whose fee stays inside the share"() {
        given:
        def led = ledger()

        expect: "0.30 / (0.10 - 0.029)"
        led.minDeposit() == new BigDecimal('4.23')

        and: "the fee is inside the share at the minimum, and outside it below"
        led.depositFeeCharged(new BigDecimal('4.23')) / new BigDecimal('4.23') <= new BigDecimal('0.10')
        led.depositFeeCharged(new BigDecimal('4.00')) / new BigDecimal('4.00') >  new BigDecimal('0.10')
        // NOT asserted: that $4.22 breaks the share. It does not - the fee is
        // FLOORed in the user's favour, so the charged share at $4.22 is
        // 9.95% and the true boundary sits at $4.21. The closed form solves
        // on the UNROUNDED fee and is therefore conservative by a cent or
        // two, which is the correct direction for a minimum and the reason
        // this test pins the property rather than an off-by-rounding edge.

        and: "the shipped \$1.00 minimum was a 32% fee"
        led.depositFeeCharged(new BigDecimal('1.00')) == new BigDecimal('0.32')
    }

    def "the minimum withdrawal has two values because the charge falls once a month"() {
        given:
        def led = ledger()

        expect: "(0.25 + 2.00) / 0.0975 on the first payout of a month"
        led.minWithdrawal(true) == new BigDecimal('23.08')

        and: "0.25 / 0.0975 on any payout after it"
        led.minWithdrawal(false) == new BigDecimal('2.57')

        and: "a rail with no per-account charge collapses the two together"
        def free = ledger(payoutAccountMonthlyFee: BigDecimal.ZERO)
        free.minWithdrawal(true) == free.minWithdrawal(false)
        free.minWithdrawal(true) == new BigDecimal('2.57')
    }

    def "a max-fee-share below the percentage leg degrades to the fee-swallow floor, not to refusing everything"() {
        given: "a 1% share, which 2.9% can never satisfy"
        def led = ledger(maxFeeShare: new BigDecimal('0.01'))

        when:
        def min = led.minDeposit()

        then: "a positive, payable floor - a mis-set knob must not close the money path"
        min > BigDecimal.ZERO
        !led.feeExceedsAmount(min, led.depositFeeCharged(min))
    }

    // ── 2. The charge, on the money path ────────────────────────────

    def "a below-break-even payout is quoted the per-account charge folded into its fee"() {
        given: "a \$2.00 balance, swept, with no earlier payout this month"
        def wallet = payoutReadyWallet(new BigDecimal('2.00'))
        walletRepository.findById(910L) >> Optional.of(wallet)
        transactionRepository.sumWithdrawalsSince(910L, _) >> BigDecimal.ZERO

        when:
        stripe(ledger()).requestWithdrawal(910L, new BigDecimal('2.00'), 'acct_unit_test')

        then: "refused because \$0.25 payout fee + \$2.00 per-account charge exceeds it"
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAWAL_BELOW_FEE'
        e.message.contains('\$2.25')

        and: "and the balance is untouched - the refusal precedes the debit"
        wallet.balance == new BigDecimal('2.00')
    }

    def "the same payout carries no charge once the month is already billed"() {
        given: "an identical \$2.00 sweep, but this wallet already withdrew this month"
        def wallet = payoutReadyWallet(new BigDecimal('2.00'))
        walletRepository.findById(910L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.sumWithdrawalsSince(910L, _) >> new BigDecimal('50.00')

        when:
        stripe(ledger()).requestWithdrawal(910L, new BigDecimal('2.00'), 'acct_unit_test')

        then: "not refused on fees - it reaches Stripe, which fails on the fake key"
        thrown(IllegalStateException)

        and: "the wallet was debited the gross, so the payout really was attempted"
        wallet.balance == new BigDecimal('0.00')
    }

    def "a payout at or above the break-even is not charged the per-account fee"() {
        given: "a \$100.00 withdrawal - exactly the break-even - first of the month"
        def wallet = payoutReadyWallet(new BigDecimal('100.00'))
        walletRepository.findById(910L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.sumWithdrawalsSince(910L, _) >> BigDecimal.ZERO

        when:
        stripe(ledger()).requestWithdrawal(910L, new BigDecimal('100.00'), 'acct_unit_test')

        then: "it proceeds to the Transfer; the commission already paid for the account"
        thrown(IllegalStateException)
        wallet.balance == new BigDecimal('0.00')
    }

    def "a deposit below the derived minimum is refused before it reaches Stripe"() {
        when: "the amount the shipped minimum used to allow, where the fee is 32%"
        stripe(ledger()).createDepositSession(910L, new BigDecimal('1.00'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'DEPOSIT_BELOW_MINIMUM'
        e.message.contains('\$4.23')
    }

    def "a deposit at the derived minimum is not refused by it"() {
        when:
        stripe(ledger()).createDepositSession(910L, new BigDecimal('4.23'))

        then: "whatever stops it next, it is not the minimum"
        def e = thrown(Exception)
        !(e instanceof BadRequestException && e.code == 'DEPOSIT_BELOW_MINIMUM')
    }

    // ── 3. The minimum, and the sweep that stops it stranding money ──

    def "a below-minimum withdrawal that leaves a balance behind is refused"() {
        given: "a \$50 balance and a \$12 request, under the \$23.08 first-of-month minimum"
        def wallet = payoutReadyWallet(new BigDecimal('50.00'))
        walletRepository.findById(910L) >> Optional.of(wallet)
        transactionRepository.sumWithdrawalsSince(910L, _) >> BigDecimal.ZERO

        when:
        stripe(ledger()).requestWithdrawal(910L, new BigDecimal('12.00'), 'acct_unit_test')

        then:
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAW_BELOW_MINIMUM'

        and: "the refusal names the minimum AND the way out"
        e.message.contains('\$23.08')
        e.message.contains('\$50.00')

        and: "nothing was debited"
        wallet.balance == new BigDecimal('50.00')
    }

    def "the same amount goes through when it empties the wallet - a raised minimum never strands a balance"() {
        given: "the SAME \$12, but now it is the whole balance"
        def wallet = payoutReadyWallet(new BigDecimal('12.00'))
        walletRepository.findById(910L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.sumWithdrawalsSince(910L, _) >> BigDecimal.ZERO

        when:
        stripe(ledger()).requestWithdrawal(910L, new BigDecimal('12.00'), 'acct_unit_test')

        then: "no minimum refusal - it reaches the Transfer and is debited in full"
        def e = thrown(Exception)
        !(e instanceof BadRequestException && e.code == 'WITHDRAW_BELOW_MINIMUM')
        wallet.balance == new BigDecimal('0.00')
    }

    def "a rail with no per-account charge lets the same small balance out"() {
        given: "the \$2.00 sweep that the Connect rail refuses, on a rail billing no per-account fee"
        def wallet = payoutReadyWallet(new BigDecimal('2.00'))
        walletRepository.findById(910L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.sumWithdrawalsSince(910L, _) >> BigDecimal.ZERO

        when:
        stripe(ledger(payoutAccountMonthlyFee: BigDecimal.ZERO))
            .requestWithdrawal(910L, new BigDecimal('2.00'), 'acct_unit_test')

        then: "it reaches the Transfer - what blocked it on Connect was the rail, not us"
        thrown(IllegalStateException)
        wallet.balance == new BigDecimal('0.00')
    }
}
