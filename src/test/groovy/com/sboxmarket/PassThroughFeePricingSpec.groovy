package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PlatformLedgerService
import com.sboxmarket.service.StripeService
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Pass-through processor pricing: the platform charges 2% on a sale and
 * nothing else, and Stripe's costs are borne by the user who incurs them.
 *
 * <h3>What was wrong</h3>
 *
 * {@code completeDeposit} credited the wallet the GROSS amount the card was
 * charged while Stripe kept 2.9% + $0.30 of it. Every deposit therefore left
 * the platform $3.20-per-$100 down before a single trade happened, which is
 * the whole reason UNIT-ECONOMICS.md concluded that "a dollar deposited,
 * traded once, and withdrawn loses money at every deposit size". The payout
 * leg had the same shape and, worse, no accounting at all.
 *
 * <h3>What these pin</h3>
 *
 * Three properties, in descending order of how much money they are worth:
 *
 * <ol>
 *   <li><b>The user is credited/paid NET.</b> Gross credit is the defect.</li>
 *   <li><b>The user is TOLD first.</b> A deposit screen that says $100 and
 *       credits $96.80 does not have a rounding problem, it has a chargeback
 *       problem. Every quote surface is pinned to the same server-side
 *       arithmetic that performs the charge.</li>
 *   <li><b>Rounding runs toward the USER, and the books say so.</b> The fee
 *       charged is FLOOR-rounded while the cost estimate is HALF_UP, so the
 *       treasury residual on a pass-through pair is always ≤ 0. A residual
 *       that could go positive would mean the platform is quietly earning
 *       fractions of a cent it never disclosed.</li>
 * </ol>
 */
class PassThroughFeePricingSpec extends Specification {

    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()

    Wallet treasury = new Wallet(id: 99L, username: PlatformLedgerService.TREASURY_USERNAME,
                                 balance: BigDecimal.ZERO, currency: 'USD')
    List<Transaction> ledgerRows = []

    PlatformLedgerService ledger = new PlatformLedgerService(
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        processingFeePercent  : new BigDecimal('2.9'),
        processingFeeFixed    : new BigDecimal('0.30'),
        payoutFeePercent      : new BigDecimal('0.25'),
        payoutFeeFixed        : new BigDecimal('0.25')
    )

    StripeService service = new StripeService(
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        platformLedgerService : ledger,
        // The three credit-arithmetic cases below drive completeDeposit on the
        // SIMULATED path. Crediting there fabricates money-path state, so it is
        // opt-in — the same DevCreditGate conjunction createDepositSession and
        // devModeDeposit already sit behind, and the same line StripeServiceSpec
        // carries. Without it `environment` is null, which resolves CLOSED and
        // is why these specs refuse rather than credit.
        environment           : SpecEnvs.creditOptedIn(),
        secretKey             : 'sk_test_replace_me',   // dev-mode unless overridden
        publishableKey        : 'pk_test_replace_me',
        webhookSecret         : 'whsec_replace_me',
        successUrl            : 'http://localhost/ok',
        cancelUrl             : 'http://localhost/cancel',
        currency              : 'usd',
        dailyDepositCap       : new BigDecimal('5000')
    )

    def setup() {
        walletRepository.findByUsername(PlatformLedgerService.TREASURY_USERNAME) >> treasury
    }

    /** Sum the treasury rows of one type — the itemised half of the margin. */
    private BigDecimal booked(String type) {
        ledgerRows.findAll { it.type == type }
                  .inject(BigDecimal.ZERO) { acc, t -> acc + t.amount } as BigDecimal
    }

    // ══ 1. The arithmetic: who eats the rounding ═════════════════════

    @Unroll
    def "a \$#gross deposit charges the user \$#fee and credits \$#net"() {
        expect:
        ledger.depositFeeCharged(new BigDecimal(gross)) == new BigDecimal(fee)
        ledger.depositNetCredit(new BigDecimal(gross))  == new BigDecimal(net)

        where:
        gross    || fee    | net
        '10.00'  || '0.59' | '9.41'      // 0.29 + 0.30
        '20.00'  || '0.88' | '19.12'
        '100.00' || '3.20' | '96.80'
        '500.00' || '14.80'| '485.20'
    }

    def "the deposit fee rounds DOWN — the sub-cent goes to the user, never to the platform"() {
        given: "2.9% of 33.33 is 0.966570, so the true fee is 1.266570"
        def gross = new BigDecimal('33.33')

        expect: "FLOOR charges 1.26. HALF_UP would charge 1.27 and quietly take the half-cent on EVERY deposit"
        ledger.depositFeeCharged(gross) == new BigDecimal('1.26')
        ledger.depositNetCredit(gross)  == new BigDecimal('32.07')

        and: "the platform's own cost estimate is NOT biased the same way — that one must stay honest"
        ledger.estimateProcessingCost(gross) == new BigDecimal('1.27')
    }

    def "the payout fee rounds DOWN too"() {
        given: "0.25% of 33.33 is 0.0833250, so the true fee is 0.3333250"
        expect:
        ledger.payoutFeeCharged(new BigDecimal('33.33')) == new BigDecimal('0.33')
        ledger.payoutNet(new BigDecimal('33.33'))        == new BigDecimal('33.00')
    }

    def "the payout fee is priced off the PAYOUT rate, not the deposit rate"() {
        expect: "0.25% + 0.25 on 100.00 — using the 2.9% + 0.30 deposit rate would charge 3.20, a 12x overcharge"
        ledger.payoutFeeCharged(new BigDecimal('100.00')) == new BigDecimal('0.50')
        ledger.payoutFeeCharged(new BigDecimal('100.00')) != ledger.depositFeeCharged(new BigDecimal('100.00'))
    }

    @Unroll
    def "the residual on a \$#gross pass-through deposit is #residual — never positive"() {
        given: "what the user is charged (FLOOR) against what the processor takes (HALF_UP)"
        def charged = ledger.depositFeeCharged(new BigDecimal(gross))
        def cost    = ledger.estimateProcessingCost(new BigDecimal(gross))

        expect: "charged <= cost ALWAYS. If this can invert, the platform is earning undisclosed fractions of a cent"
        (charged - cost) == new BigDecimal(residual)
        charged <= cost

        where:
        gross    || residual
        '100.00' || '0.00'     // exact — nothing to round either way
        '10.01'  || '0.00'     // 0.59029 — rounds the same both ways
        '25.00'  || '-0.01'    // 1.0250 exactly: the half that HALF_UP takes and FLOOR gives back
        '15.00'  || '-0.01'    // 0.7350 exactly — same knife edge
        '33.33'  || '-0.01'
        '77.77'  || '-0.01'
    }

    @Unroll
    def "a \$#gross deposit #verdict — the fee must never credit zero or less"() {
        expect: "the threshold sits exactly where net stops being positive: 0.29 -> -0.01, 0.30 -> 0.00, 0.31 -> +0.01"
        ledger.feeExceedsAmount(new BigDecimal(gross), ledger.depositFeeCharged(new BigDecimal(gross))) == refused

        where:
        gross  | refused || verdict
        '0.29' | true    || 'is REFUSED (net would be negative)'
        '0.30' | true    || 'is REFUSED (net would be exactly zero)'
        '0.31' | false   || 'is allowed (one cent lands)'
        '1.00' | false   || 'is allowed'
    }

    def "createDepositSession actually CALLS the below-fee guard — a rule nobody consults is not a rule"() {
        given: 'live mode, and a deposit smaller than its own processing fee'
        service.secretKey = 'sk_live_tiny'
        def wallet = new Wallet(id: 500L, username: 'steam_1', balance: BigDecimal.ZERO)
        walletRepository.findByIdForUpdate(500L) >> Optional.of(wallet)
        transactionRepository.sumDepositsSince(_, _) >> BigDecimal.ZERO

        when:
        service.createDepositSession(500L, new BigDecimal('0.30'))

        then: "refused with a branchable code — never a card charged for a \$0.00 credit"
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'DEPOSIT_BELOW_FEE'

        and: "and refused BEFORE Stripe is involved, so no session and no PENDING row exist to strand"
        0 * transactionRepository.save(_)
    }

    def "requestWithdrawal actually CALLS the below-fee guard, and refuses BEFORE the debit"() {
        given: 'live mode, and a withdrawal smaller than its own payout fee'
        service.secretKey = 'sk_live_tinywd'
        ledger.payoutFeeFixed = new BigDecimal('5.00')      // a misconfigured fixed leg
        def wallet = new Wallet(id: 500L, username: 'steam_1', balance: new BigDecimal('100.00'),
            payoutsEnabled: true, stripeConnectAccountId: 'acct_seller_1')
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        service.requestWithdrawal(500L, new BigDecimal('4.00'), 'acct_external')

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'WITHDRAWAL_BELOW_FEE'

        and: "the balance is untouched — a refusal must never leave the wallet short"
        wallet.balance == new BigDecimal('100.00')
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
    }

    // ══ 2. The user is credited NET ══════════════════════════════════

    def "completeDeposit credits the wallet NET of the fee stored on the row"() {
        given: "a PENDING deposit created under pass-through pricing: card charged 100.00, fee 3.20"
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('100.00'), feeAmount: new BigDecimal('3.20'),
            currency: 'USD', stripeReference: 'dev_passthrough')
        def wallet = new Wallet(id: 500L, balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('dev_passthrough') >> tx
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        service.completeDeposit('dev_passthrough')

        then: "96.80, not 100.00 — the gross credit is the defect this replaces"
        wallet.balance == new BigDecimal('96.80')

        and: "the GROSS stays on the row: completeDeposit's Stripe amount-match compares it against session.amount_total"
        tx.amount == new BigDecimal('100.00')
        tx.status == 'COMPLETED'
    }

    def "a row written before pass-through pricing still credits GROSS"() {
        given: "feeAmount is null — nobody measured a fee for this deposit, because none was charged"
        def tx = new Transaction(id: 2L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('60.00'), feeAmount: null,
            currency: 'USD', stripeReference: 'dev_legacy')
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('40.00'))
        transactionRepository.findByStripeReference('dev_legacy') >> tx
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        service.completeDeposit('dev_legacy')

        then: "null reads as zero — historic rows keep meaning exactly what they meant"
        wallet.balance == new BigDecimal('100.00')
    }

    def "a fee that swallows the whole deposit credits GROSS and never debits the user"() {
        given: "a misconfigured rate wrote a fee larger than the deposit. The card is ALREADY charged by now"
        def tx = new Transaction(id: 3L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('5.00'), feeAmount: new BigDecimal('9.00'),
            currency: 'USD', stripeReference: 'dev_broken')
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'))
        transactionRepository.findByStripeReference('dev_broken') >> tx
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        service.completeDeposit('dev_broken')

        then: "clamped to the gross credit — a deposit must never move a balance DOWN"
        wallet.balance == new BigDecimal('15.00')
    }

    // ══ 3. The user is told BEFORE they commit ═══════════════════════

    def "the deposit session response carries the fee and the net credit, not just the gross"() {
        given: 'live mode so the real Checkout path runs'
        service.secretKey = 'sk_live_quote_test'
        def wallet = new Wallet(id: 500L, username: 'steam_1', balance: BigDecimal.ZERO)
        walletRepository.findByIdForUpdate(500L) >> Optional.of(wallet)
        transactionRepository.sumDepositsSince(_, _) >> BigDecimal.ZERO
        transactionRepository.findByStripeReference('cs_live_quote') >> null
        def saved = null
        transactionRepository.save(_) >> { Transaction t -> t.id = 11L; saved = t; t }

        and:
        def fakeSession = [id: 'cs_live_quote', url: 'https://stripe/co/cs_live_quote']
        GroovySpy(com.stripe.model.checkout.Session, global: true)
        com.stripe.model.checkout.Session.create(_, _) >> fakeSession

        when:
        def result = service.createDepositSession(500L, new BigDecimal('100.00'))

        then: 'the deposit screen can render "you receive $96.80" BEFORE the redirect'
        result.amount        == new BigDecimal('100.00')
        result.processingFee == new BigDecimal('3.20')
        result.netCredit     == new BigDecimal('96.80')

        and: "and the quoted fee is PERSISTED, so a rate change before the webhook can't credit a different number"
        saved.feeAmount == new BigDecimal('3.20')
        saved.amount    == new BigDecimal('100.00')
    }

    def "the fee schedule the wallet UI quotes from comes from server config, and is OFF in dev mode"() {
        when: 'dev mode — no Stripe keys, so no Stripe charge'
        def dev = service.passThroughFeeSchedule()

        then: 'advertising a deduction that never happens is the same lie in the other direction'
        dev.active == false

        when:
        service.secretKey = 'sk_live_schedule'
        def live = service.passThroughFeeSchedule()

        then: 'the rates the charge is computed from — never a hardcoded client-side copy'
        live.active               == true
        live.depositFeePercent    == new BigDecimal('2.9')
        live.depositFeeFixed      == new BigDecimal('0.30')
        live.withdrawalFeePercent == new BigDecimal('0.25')
        live.withdrawalFeeFixed   == new BigDecimal('0.25')
    }

    // ══ 4. The withdrawal leg ════════════════════════════════════════

    def "a live withdrawal debits the wallet GROSS and transfers NET"() {
        given:
        service.secretKey = 'sk_live_wd'
        def wallet = new Wallet(id: 500L, username: 'steam_1', balance: new BigDecimal('100.00'),
            payoutsEnabled: true, stripeConnectAccountId: 'acct_seller_1')
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.findByStripeReference(_) >> null
        def saved = null
        transactionRepository.save(_) >> { Transaction t ->
            if (t.type == 'WITHDRAW') { t.id = 21L; saved = t } else { ledgerRows << t; t.id = ledgerRows.size() as Long }
            t
        }

        and: 'capture the cents Stripe is actually asked to move'
        Long transferredCents = null
        def fakeTransfer = Mock(com.stripe.model.Transfer) { getId() >> 'tr_live_pt' }
        GroovySpy(com.stripe.model.Transfer, global: true)
        com.stripe.model.Transfer.create(_, _) >> { params, opts ->
            transferredCents = params.amount
            fakeTransfer
        }

        when:
        def tx = service.requestWithdrawal(500L, new BigDecimal('100.00'), 'acct_external')

        then: "the wallet loses the full 100.00 the user asked to withdraw"
        wallet.balance == new BigDecimal('0.00')

        and: "but only 99.50 is sent — the 0.50 retained is what pays Stripe for the payout"
        transferredCents == 9950L

        and: "the row records both halves, so 'what did I actually receive' is answerable from the ledger alone"
        saved.amount    == new BigDecimal('100.00')
        saved.feeAmount == new BigDecimal('0.50')
        tx.status == 'COMPLETED'
    }

    def "a dev-mode withdrawal charges NO payout fee — there is no Stripe to pay"() {
        given: 'dev mode: no Transfer is created, so no processor cost is incurred'
        def wallet = new Wallet(id: 500L, username: 'steam_1', balance: new BigDecimal('100.00'))
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        def saved = null
        transactionRepository.save(_) >> { Transaction t -> t.id = 22L; saved = t; t }

        when:
        service.requestWithdrawal(500L, new BigDecimal('100.00'), 'manual')

        then: "inventing a fee for a simulated payout would debit a real balance for imaginary work"
        wallet.balance  == new BigDecimal('0.00')
        saved.amount    == new BigDecimal('100.00')
        saved.feeAmount == null
    }

    // ══ 5. The treasury bookkeeping ══════════════════════════════════

    def "PROCESSING_RECOVERY is REVENUE — booked as a cost it would double-count instead of offsetting"() {
        expect:
        PlatformLedgerService.TYPE_PROCESSING_RECOVERY in PlatformLedgerService.REVENUE_TYPES
        !(PlatformLedgerService.TYPE_PROCESSING_RECOVERY in PlatformLedgerService.COST_TYPES)
    }

    def "a pass-through deposit books BOTH legs and nets the treasury to zero — not a \$3.20 loss"() {
        given: 'live mode, with the Stripe interrogation stubbed at its seam rather than bypassed'
        service.secretKey = 'sk_live_book'
        // This case needs live mode (the ledger booking below is isLive()-gated)
        // AND it needs the credit to happen. It used to buy that with a non-cs_
        // reference, whose ONLY effect was to skip the payment verification —
        // the bypass this spec's own `given:` line used to advertise. That is a
        // hole in production standing open so a bookkeeping test can reach the
        // arithmetic behind it. Overriding the seam gets the same reach while
        // the production path verifies unconditionally; what is asserted below
        // (the two ledger legs) is unchanged.
        service.metaClass.assertDepositPaidAtStripe = { String s, Transaction t -> null }
        def tx = new Transaction(id: 4L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('100.00'), feeAmount: new BigDecimal('3.20'),
            currency: 'USD', stripeReference: 'cs_book_1')
        def wallet = new Wallet(id: 500L, balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('cs_book_1') >> tx
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t ->
            if (t.walletId == 99L) { ledgerRows << t; t.id = ledgerRows.size() as Long }
            t
        }

        when:
        service.completeDeposit('cs_book_1')

        then: "the user was credited net, so the cost is already borne — booking ONLY the debit counts it twice"
        wallet.balance == new BigDecimal('96.80')
        booked(PlatformLedgerService.TYPE_PROCESSING_COST)     == new BigDecimal('3.20')
        booked(PlatformLedgerService.TYPE_PROCESSING_RECOVERY) == new BigDecimal('3.20')

        and: "margin is untouched by a leg that is economically break-even"
        treasury.balance == new BigDecimal('0.00')

        and: "both legs are present — a half-posted pair understates margin by the whole processor cut"
        ledgerRows.size() == 2
    }

    def "a pass-through withdrawal books the PAYOUT cost, not the deposit cost"() {
        given:
        service.secretKey = 'sk_live_wdbook'
        def wallet = new Wallet(id: 500L, username: 'steam_1', balance: new BigDecimal('100.00'),
            payoutsEnabled: true, stripeConnectAccountId: 'acct_seller_1')
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.findByStripeReference(_) >> null
        transactionRepository.save(_) >> { Transaction t ->
            if (t.walletId == 99L) { ledgerRows << t; t.id = ledgerRows.size() as Long }
            t
        }
        def fakeTransfer = Mock(com.stripe.model.Transfer) { getId() >> 'tr_book' }
        GroovySpy(com.stripe.model.Transfer, global: true)
        com.stripe.model.Transfer.create(_, _) >> fakeTransfer

        when:
        service.requestWithdrawal(500L, new BigDecimal('100.00'), 'acct_external')

        then: "0.50, not the 3.20 the deposit rate would have produced — the payout leg is nearly free"
        booked(PlatformLedgerService.TYPE_PROCESSING_COST)     == new BigDecimal('0.50')
        booked(PlatformLedgerService.TYPE_PROCESSING_RECOVERY) == new BigDecimal('0.50')
        treasury.balance == new BigDecimal('0.00')
    }

    def "a reversed payout un-books the recovery, leaving the cost the platform really ate"() {
        given: "a completed withdrawal that charged the user a 0.50 payout fee"
        def tx = new Transaction(id: 31L, walletId: 500L, type: 'WITHDRAW', status: 'COMPLETED',
            amount: new BigDecimal('100.00'), feeAmount: new BigDecimal('0.50'),
            currency: 'USD', stripeReference: 'tr_rev_1')
        def wallet = new Wallet(id: 500L, username: 'steam_1', balance: BigDecimal.ZERO)
        transactionRepository.findByStripeReference('tr_rev_1') >> tx
        transactionRepository.claimReverseWithdrawal(31L, _) >> 1
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t ->
            if (t.walletId == 99L) { ledgerRows << t; t.id = ledgerRows.size() as Long }
            t
        }
        def transfer = Mock(com.stripe.model.Transfer) {
            getId() >> 'tr_rev_1'; getAmount() >> 9950L; getAmountReversed() >> 9950L
        }

        when:
        service.handleTransferReversed(transfer)

        then: "the user gets the GROSS back — the withdrawal did not happen, so they should not hold a fee for it"
        wallet.balance == new BigDecimal('100.00')

        and: "and the recovery credit, now handed back, is cancelled — leaving margin honest about eating the payout"
        booked(PlatformLedgerService.TYPE_PROCESSING_COST) == new BigDecimal('0.50')
        treasury.balance == new BigDecimal('-0.50')
    }

    // ══ 6. Platform revenue is unchanged ═════════════════════════════

    def "the platform's own take is still 2% of the trade price and nothing else"() {
        given: "FEE_RATE is private, so read the declaration itself rather than a Groovy-visibility accident"
        def src = new File('src/main/groovy/com/sboxmarket/service/TradeService.groovy').text

        expect: "pass-through moved WHO pays Stripe. It must not have moved the selling fee"
        src.contains("FEE_RATE = new BigDecimal('0.02')")

        and: "…and the processor rates are NOT quietly folded into it"
        !src.contains('processingFee')
        !src.contains('payoutFee')
    }
}
