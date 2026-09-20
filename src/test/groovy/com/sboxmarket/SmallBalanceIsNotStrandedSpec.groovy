package com.sboxmarket

import com.sboxmarket.dto.request.WithdrawRequest
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PlatformLedgerService
import com.sboxmarket.service.StripeService
import jakarta.validation.Validation
import jakarta.validation.Validator
import spock.lang.Shared
import spock.lang.Specification

/**
 * A seller with a small balance must be able to get it out, and must know
 * what they will get before they ask.
 *
 * <h3>The two holes this closes</h3>
 *
 * The per-account payout charge shipped with a derived minimum withdrawal
 * ($23.08 on a wallet's first payout of a month) and a full-balance SWEEP
 * exemption, on the principle that a minimum a balance cannot reach is a
 * minimum that keeps the balance. Both halves were correct and both were
 * defeated one layer away:
 *
 * <ol>
 *   <li><b>Stranding.</b> {@code WithdrawRequest} carried
 *       {@code @DecimalMin("1.00")}. Bean validation runs BEFORE the
 *       controller, so a $0.60 sweep — payable on a rail with no
 *       per-account charge, and explicitly exempted by the derived minimum
 *       — was rejected 400 by a validation constant three layers from the
 *       rates that justify it. The exemption existed and could not be
 *       reached by the balances it was written for.</li>
 *   <li><b>Surprise.</b> The wallet modal's "you receive $X" preview was
 *       built from the percentage legs only. On a $10 first-of-month
 *       withdrawal it promised "− $0.27 · you receive $9.73" against an
 *       actual $2.27 and $7.73. Disclosure wrong by 20% of the payout is
 *       worse than none.</li>
 * </ol>
 *
 * <h3>And the no-op</h3>
 *
 * Setting {@code platform.payout-account-monthly-fee} to 0.00 — a rail with
 * no such charge — must collapse the ENTIRE mechanism, not just the fee:
 * the break-even, both minimums, the quoted schedule and the charge on the
 * money path. Pinned here as one case so a future change cannot leave a
 * fragment of it live on a rail that never bills it.
 */
class SmallBalanceIsNotStrandedSpec extends Specification {

    @Shared Validator validator = Validation.buildDefaultValidatorFactory().validator

    TransactionRepository transactionRepository = Mock()
    WalletRepository      walletRepository      = Mock()
    SteamUserRepository   steamUserRepository   = Mock()

    private PlatformLedgerService ledger(Map overrides = [:]) {
        def svc = new PlatformLedgerService(
            walletRepository:        walletRepository,
            transactionRepository:   transactionRepository,
            processingFeePercent:    new BigDecimal('2.9'),
            processingFeeFixed:      new BigDecimal('0.30'),
            payoutFeePercent:        new BigDecimal('0.25'),
            payoutFeeFixed:          new BigDecimal('0.25'),
            payoutAccountMonthlyFee: new BigDecimal('2.00'),
            maxFeeShare:             new BigDecimal('0.10')
        )
        overrides.each { k, v -> svc."$k" = v }
        svc
    }

    /** secretKey without "replace_me" ⇒ isLive() ⇒ the real payout branch. */
    private StripeService stripe(PlatformLedgerService led) {
        new StripeService(
            transactionRepository: transactionRepository,
            walletRepository:      walletRepository,
            steamUserRepository:   steamUserRepository,
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
        new Wallet(id: 911L, balance: balance, username: 'steam_76561198000000911',
                   payoutsEnabled: true, stripeConnectAccountId: 'acct_unit_test')
    }

    private WithdrawRequest req(String amount) {
        new WithdrawRequest(amount: new BigDecimal(amount), destination: 'acct_unit_test')
    }

    // ── 1. The DTO no longer cancels the sweep exemption ────────────

    def "a sub-dollar withdrawal survives bean validation, because the sweep exemption lives below it"() {
        expect: "the amounts the old \$1.00 @DecimalMin rejected outright"
        validator.validate(req('0.60')).isEmpty()
        validator.validate(req('0.35')).isEmpty()
        validator.validate(req('0.01')).isEmpty()
    }

    def "the floor that remains is arithmetic, not policy"() {
        expect: "below a cent there is no payable amount, so the annotation still refuses"
        !validator.validate(req('0.00')).isEmpty()
        !validator.validate(req('-1.00')).isEmpty()

        and: "and the per-request ceiling is untouched"
        !validator.validate(req('10000.01')).isEmpty()
        validator.validate(req('10000.00')).isEmpty()
    }

    def "a sub-dollar sweep actually reaches the payout on a rail with no per-account charge"() {
        given: "a \$0.60 balance — payable at \$0.25 of payout fee, leaving \$0.35"
        def wallet = payoutReadyWallet(new BigDecimal('0.60'))
        walletRepository.findById(911L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.sumWithdrawalsSince(911L, _) >> BigDecimal.ZERO

        when:
        stripe(ledger(payoutAccountMonthlyFee: BigDecimal.ZERO))
            .requestWithdrawal(911L, new BigDecimal('0.60'), 'acct_unit_test')

        then: "no refusal of ours — it gets as far as the Transfer, which the fake key fails"
        def e = thrown(Exception)
        !(e instanceof BadRequestException)

        and: "and the balance really was taken to zero, not left stranded"
        wallet.balance == new BigDecimal('0.00')
    }

    // ── 2. What the seller is shown is what the seller is charged ───

    def "the schedule hands the client every input it needs, and none it must invent"() {
        given:
        def svc = stripe(ledger())

        when:
        def schedule = svc.passThroughFeeSchedule()

        then: "the rate legs"
        schedule.withdrawalFeePercent == new BigDecimal('0.25')
        schedule.withdrawalFeeFixed   == new BigDecimal('0.25')

        and: "the leg that is not a rate, and the line that waives it"
        schedule.perAccountMonthlyFee == new BigDecimal('2.00')
        schedule.perAccountWaiverAt   == new BigDecimal('100.00')

        and: "both minimums, so a form never offers an amount the server will bounce"
        schedule.minWithdrawalFirstOfMonth == new BigDecimal('23.08')
        schedule.minWithdrawal             == new BigDecimal('2.57')
    }

    def "the number a client computes from the schedule is the number the server charges"() {
        given: "a \$2.20 balance, swept, first payout of the month"
        def wallet = payoutReadyWallet(new BigDecimal('2.20'))
        walletRepository.findById(911L) >> Optional.of(wallet)
        transactionRepository.sumWithdrawalsSince(911L, _) >> BigDecimal.ZERO
        def svc = stripe(ledger())
        def schedule = svc.passThroughFeeSchedule()
        def amount = new BigDecimal('2.20')

        when: "the client's arithmetic: the rate legs, plus the per-account charge below the waiver"
        def rateLeg = ((amount * schedule.withdrawalFeePercent / 100G) + schedule.withdrawalFeeFixed)
                          .setScale(2, java.math.RoundingMode.FLOOR)
        def chargeDue = !svc.perAccountChargeAlreadyBilled(911L)
        def clientQuote = rateLeg + ((chargeDue && amount < schedule.perAccountWaiverAt)
                                     ? schedule.perAccountMonthlyFee : BigDecimal.ZERO)

        and: "and the server is asked for the same withdrawal"
        svc.requestWithdrawal(911L, amount, 'acct_unit_test')

        then: "the server names the same figure the client would have shown"
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAWAL_BELOW_FEE'
        clientQuote == new BigDecimal('2.25')
        e.message.contains('\$' + clientQuote.toPlainString())

        and: "nothing moved — the refusal precedes the debit"
        wallet.balance == new BigDecimal('2.20')
    }

    def "the same balance on the same rail is cheap once the month is already billed"() {
        given: "identical \$2.20 sweep, but this wallet already took a payout this month"
        def wallet = payoutReadyWallet(new BigDecimal('2.20'))
        walletRepository.findById(911L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.sumWithdrawalsSince(911L, _) >> new BigDecimal('40.00')
        def svc = stripe(ledger())

        expect: "the flag the client renders from says so"
        svc.perAccountChargeAlreadyBilled(911L)

        when:
        svc.requestWithdrawal(911L, new BigDecimal('2.20'), 'acct_unit_test')

        then: "and the charge really is absent — \$0.25 of fee, not \$2.25, so it pays out"
        def e = thrown(Exception)
        !(e instanceof BadRequestException)
        wallet.balance == new BigDecimal('0.00')
    }

    // ── 3. Zero collapses the WHOLE mechanism, not just the fee ─────

    def "a per-account fee of zero is a no-op end to end"() {
        given:
        def free = ledger(payoutAccountMonthlyFee: BigDecimal.ZERO)
        def svc  = stripe(free)

        expect: "the break-even is nothing to recover"
        free.perAccountBreakEvenGmv() == new BigDecimal('0.00')

        and: "the two minimums collapse into one — there is no 'first payout of the month'"
        free.minWithdrawal(true) == free.minWithdrawal(false)
        free.minWithdrawal(true) == new BigDecimal('2.57')

        and: "the quoted schedule carries no charge and no waiver line for the client to render"
        def schedule = svc.passThroughFeeSchedule()
        schedule.perAccountMonthlyFee == new BigDecimal('0.00')
        schedule.perAccountWaiverAt   == new BigDecimal('0.00')
        schedule.minWithdrawal        == schedule.minWithdrawalFirstOfMonth

        and: "and the service-level accessors agree, since they are what the payload reads"
        svc.perAccountMonthlyFee() == new BigDecimal('0.00')
        svc.perAccountWaiverAt()   == new BigDecimal('0.00')
    }

    def "with the charge at zero nothing is folded into the payout fee on the money path"() {
        given: "the \$2.00 sweep that the \$2.00-per-account rail refuses outright"
        def wallet = payoutReadyWallet(new BigDecimal('2.00'))
        walletRepository.findById(911L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.sumWithdrawalsSince(911L, _) >> BigDecimal.ZERO

        when:
        stripe(ledger(payoutAccountMonthlyFee: BigDecimal.ZERO))
            .requestWithdrawal(911L, new BigDecimal('2.00'), 'acct_unit_test')

        then: "no WITHDRAWAL_BELOW_FEE, no WITHDRAW_BELOW_MINIMUM — the rail is the only difference"
        def e = thrown(Exception)
        !(e instanceof BadRequestException)
        wallet.balance == new BigDecimal('0.00')
    }

    def "a null per-account fee behaves as zero rather than throwing on the money path"() {
        given: "an operator who removed the knob entirely"
        def wallet = payoutReadyWallet(new BigDecimal('2.00'))
        walletRepository.findById(911L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.sumWithdrawalsSince(911L, _) >> BigDecimal.ZERO
        def bare = ledger(payoutAccountMonthlyFee: null)

        expect:
        bare.perAccountBreakEvenGmv() == new BigDecimal('0.00')
        bare.minWithdrawal(true) == bare.minWithdrawal(false)

        when:
        stripe(bare).requestWithdrawal(911L, new BigDecimal('2.00'), 'acct_unit_test')

        then:
        def e = thrown(Exception)
        !(e instanceof BadRequestException)
        wallet.balance == new BigDecimal('0.00')
    }
}
