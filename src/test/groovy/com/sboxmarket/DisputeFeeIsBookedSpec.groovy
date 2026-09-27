package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.PlatformLedgerService
import com.sboxmarket.service.StripeService
import com.stripe.model.Dispute
import spock.lang.Specification

/**
 * The USD 15 that appeared nowhere.
 *
 * <h3>What was wrong</h3>
 *
 * Stripe bills a fixed dispute-handling fee — USD 15.00 in the US — the
 * moment a cardholder disputes a charge, and keeps it whether the dispute is
 * later won or lost. {@code charge.dispute.created} flipped the deposit row
 * to DISPUTED, fanned an alert out to every admin and emailed the user, and
 * booked NOTHING. The treasury, {@code PlatformLedgerService.margin()} and
 * the admin panel's 30-day net margin all recorded a dispute as free.
 *
 * It is not a rounding-scale omission. At the 2% take rate one dispute costs
 * more than the entire commission on USD 750 of GMV.
 *
 * <h3>What these pin</h3>
 *
 * <ol>
 *   <li>The charge reaches the treasury, as a DEBIT, on a real dispute.</li>
 *   <li>A Stripe webhook RETRY books it once, not twice — and the guard is
 *       the observed database state transition, not an in-memory set.</li>
 *   <li>A dispute that matches no deposit row books NOTHING rather than
 *       booking on every retry, and says so.</li>
 *   <li>Configuring the charge to zero is a no-op: no row, not a $0.00 row.</li>
 *   <li>It is classified as a cost, so it cannot drop out of the margin
 *       arithmetic — and it is NOT PROCESSING_COST, whose residual against
 *       PROCESSING_RECOVERY is the operator's rate diagnostic.</li>
 * </ol>
 *
 * Every case asserts on an observable: the treasury balance moving, or the
 * ledger rows actually written. None asserts on the presence of a line of
 * source.
 */
class DisputeFeeIsBookedSpec extends Specification {

    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    AuditService          auditService          = Mock()

    Wallet treasury = new Wallet(id: 99L, username: PlatformLedgerService.TREASURY_USERNAME,
                                 balance: BigDecimal.ZERO, currency: 'USD')

    List<Transaction> written = []

    private PlatformLedgerService ledger(BigDecimal fee = new BigDecimal('15.00')) {
        new PlatformLedgerService(
            walletRepository:      walletRepository,
            transactionRepository: transactionRepository,
            processingFeePercent:  new BigDecimal('2.9'),
            processingFeeFixed:    new BigDecimal('0.30'),
            payoutFeePercent:      new BigDecimal('0.25'),
            payoutFeeFixed:        new BigDecimal('0.25'),
            disputeFee:            fee
        )
    }

    private StripeService stripe(PlatformLedgerService led) {
        new StripeService(
            walletRepository:      walletRepository,
            transactionRepository: transactionRepository,
            steamUserRepository:   steamUserRepository,
            auditService:          auditService,
            platformLedgerService: led,
            secretKey:             'sk_test_replace_me',
            publishableKey:        'pk_test_replace_me',
            webhookSecret:         'whsec_replace_me',
            successUrl:            'http://localhost/ok',
            cancelUrl:             'http://localhost/cancel',
            currency:              'usd'
        )
    }

    def setup() {
        walletRepository.findByUsername(PlatformLedgerService.TREASURY_USERNAME) >> treasury
        walletRepository.save(_) >> { Wallet w -> w }
        steamUserRepository.findByRole('ADMIN') >> []
        transactionRepository.save(_) >> { Transaction t ->
            written << t
            t.id = written.size() as Long
            t
        }
    }

    /** A COMPLETED deposit row the dispute will match on. */
    private Transaction matchedDeposit() {
        new Transaction(id: 770L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
                        amount: new BigDecimal('50.00'), currency: 'USD',
                        stripeReference: 'ch_disputed_1', description: 'card deposit')
    }

    private List<Transaction> disputeRows() {
        written.findAll { it.type == PlatformLedgerService.TYPE_DISPUTE_COST }
    }

    // ── 1. It is booked at all ──────────────────────────────────────

    def "a dispute on a matched deposit DEBITS the treasury the processor's dispute fee"() {
        given: "the deposit row Stripe's dispute points at"
        def dep = matchedDeposit()
        transactionRepository.findByStripeReference('ch_disputed_1') >> dep
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc(_, _) >> []

        when:
        stripe(ledger()).handleChargebackOpened(
            new Dispute(id: 'dp_booked', amount: 5000L, reason: 'fraudulent', charge: 'ch_disputed_1'))

        then: "the treasury is \$15.00 poorer — a dispute is no longer free"
        treasury.balance == new BigDecimal('-15.00')

        and: "exactly one DISPUTE_COST row, for the configured amount"
        disputeRows().size() == 1
        disputeRows()[0].amount == new BigDecimal('15.00')

        and: "referenced by the processor's own dispute id, so it reconciles"
        disputeRows()[0].stripeReference == 'dp_booked'

        and: "the deposit row was flipped, which is the transition it was booked on"
        dep.status == 'DISPUTED'
    }

    def "the booked amount follows the configured fee rather than a remembered 15"() {
        given:
        def dep = matchedDeposit()
        transactionRepository.findByStripeReference('ch_disputed_1') >> dep
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc(_, _) >> []

        when: "an operator on a rail that charges \$25"
        stripe(ledger(new BigDecimal('25.00'))).handleChargebackOpened(
            new Dispute(id: 'dp_25', amount: 5000L, reason: 'fraudulent', charge: 'ch_disputed_1'))

        then:
        disputeRows()*.amount == [new BigDecimal('25.00')]
        treasury.balance == new BigDecimal('-25.00')
    }

    // ── 2. Stripe retries the webhook ───────────────────────────────

    def "a webhook retry of the same dispute books the fee once, not once per delivery"() {
        given: "one deposit row, shared across both deliveries"
        def dep = matchedDeposit()
        transactionRepository.findByStripeReference('ch_disputed_1') >> dep
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc(_, _) >> []
        def svc = stripe(ledger())
        def event = new Dispute(id: 'dp_retry', amount: 5000L, reason: 'fraudulent', charge: 'ch_disputed_1')

        when: "Stripe delivers it three times, as it does on any 5xx"
        svc.handleChargebackOpened(event)
        svc.handleChargebackOpened(event)
        svc.handleChargebackOpened(event)

        then: "the platform paid \$15 once and is charged \$15 once"
        disputeRows().size() == 1
        treasury.balance == new BigDecimal('-15.00')
    }

    def "an unmatched dispute books nothing at all rather than booking on every retry"() {
        given: "no deposit row anywhere matches this charge"
        transactionRepository.findByStripeReference(_) >> null
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc(_, _) >> []
        def svc = stripe(ledger())
        def event = new Dispute(id: 'dp_orphan', amount: 5000L, reason: 'fraudulent')

        when: "and Stripe retries it, as it will"
        svc.handleChargebackOpened(event)
        svc.handleChargebackOpened(event)

        then: "nothing is booked — there is no durable state that could dedupe it, and"
        "guessing the count is worse than a log line an operator reconciles"
        disputeRows().isEmpty()
        treasury.balance == BigDecimal.ZERO

        and: "the dispute itself is still recorded for staff"
        2 * auditService.log('CHARGEBACK_OPENED', _, _, _, _)
    }

    // ── 3. Zero collapses the mechanism ─────────────────────────────

    def "a rail that charges nothing for a dispute books no row, not a \$0.00 row"() {
        given:
        def dep = matchedDeposit()
        transactionRepository.findByStripeReference('ch_disputed_1') >> dep
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc(_, _) >> []

        when:
        stripe(ledger(BigDecimal.ZERO)).handleChargebackOpened(
            new Dispute(id: 'dp_free', amount: 5000L, reason: 'fraudulent', charge: 'ch_disputed_1'))

        then: "a cost that was not incurred leaves no trace"
        disputeRows().isEmpty()
        treasury.balance == BigDecimal.ZERO

        and: "everything else about the dispute still happened"
        dep.status == 'DISPUTED'
    }

    def "an unset dispute fee is the same no-op as an explicit zero"() {
        given:
        def dep = matchedDeposit()
        transactionRepository.findByStripeReference('ch_disputed_1') >> dep
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc(_, _) >> []

        when: "disputeFee left null, as a bare-constructed service has it"
        stripe(ledger(null)).handleChargebackOpened(
            new Dispute(id: 'dp_null', amount: 5000L, reason: 'fraudulent', charge: 'ch_disputed_1'))

        then:
        disputeRows().isEmpty()
        noExceptionThrown()
    }

    // ── 4. It cannot fall out of the margin ─────────────────────────

    def "the dispute fee is classified as a COST, and not as the pass-through kind"() {
        expect: "in COST_TYPES, so it reaches margin()"
        PlatformLedgerService.TYPE_DISPUTE_COST in PlatformLedgerService.COST_TYPES

        and: "never in REVENUE_TYPES — a cost posted as revenue makes a loss look like a profit"
        !(PlatformLedgerService.TYPE_DISPUTE_COST in PlatformLedgerService.REVENUE_TYPES)

        and: "and DISTINCT from PROCESSING_COST, whose residual against PROCESSING_RECOVERY " +
             "is the operator's only check on the four configured rates"
        PlatformLedgerService.TYPE_DISPUTE_COST != PlatformLedgerService.TYPE_PROCESSING_COST
    }

    def "booking a dispute fee does not disturb the pass-through residual"() {
        given: "a deposit's cost and its offsetting recovery, which net to zero"
        def led = ledger()
        led.postPassThroughProcessing(new BigDecimal('3.20'), new BigDecimal('3.20'),
                                      'ch_x', 'cost', 'recovery')

        when: "and then a dispute lands"
        led.postDisputeFee('dp_residual', new BigDecimal('50.00'))

        then: "the rate residual is still zero — the \$15 sits in its own type"
        def recovery = written.findAll { it.type == PlatformLedgerService.TYPE_PROCESSING_RECOVERY }
                              .sum { it.amount } ?: BigDecimal.ZERO
        def processing = written.findAll { it.type == PlatformLedgerService.TYPE_PROCESSING_COST }
                                .sum { it.amount } ?: BigDecimal.ZERO
        (recovery as BigDecimal) - (processing as BigDecimal) == BigDecimal.ZERO

        and: "while the treasury is genuinely \$15 down"
        treasury.balance == new BigDecimal('-15.00')
    }

    // ── 5. Stripe keeps it when the dispute is WON ──────────────────

    def "winning the dispute does not give the fee back"() {
        given: "a disputed deposit that staff/Stripe then resolve in our favour"
        def dep = matchedDeposit()
        transactionRepository.findByStripeReference('ch_disputed_1') >> dep
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc(_, _) >> []
        def svc = stripe(ledger())
        svc.handleChargebackOpened(
            new Dispute(id: 'dp_won', amount: 5000L, reason: 'fraudulent', charge: 'ch_disputed_1'))
        def afterOpen = treasury.balance

        when:
        svc.handleChargebackClosed(
            new Dispute(id: 'dp_won', amount: 5000L, status: 'won', charge: 'ch_disputed_1'))

        then: "no credit back — Stripe keeps the handling fee on a win, and a " +
              "'refund it when we win' branch would understate cost on the happy path"
        treasury.balance == afterOpen
        disputeRows().size() == 1
    }
}
