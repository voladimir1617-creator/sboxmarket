package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for StripeService.handleTransferReversed — the `transfer.reversed`
 * webhook reconciliation that re-credits a wallet when a settled withdrawal
 * payout is later reversed by Stripe.
 *
 * The bug this guards: pre-fix there was NO handler for transfer.reversed, so
 * a reversed payout returned money to the platform balance but never re-credited
 * the user's wallet — silent permanent fund loss. The handler must:
 *   1) re-credit EXACTLY the withdrawn amount on a confirmed FULL reversal,
 *   2) be idempotent (a redelivered event / sibling pod must NOT double-credit),
 *   3) refuse to guess on a PARTIAL reversal (leave for manual ops),
 *   4) no-op for a transfer that doesn't match one of our withdrawals.
 *
 * Idempotency is enforced by the atomic claimReverseWithdrawal UPDATE
 * (COMPLETED→REVERSED), so these tests drive the claim's return value (1 = this
 * delivery owns the credit, 0 = already reconciled) and assert the wallet is
 * credited iff the claim was won.
 */
class StripeTransferReversedSpec extends Specification {

    WalletRepository walletRepository = Mock()
    TransactionRepository transactionRepository = Mock()

    @Subject
    StripeService service = new StripeService(
        walletRepository: walletRepository,
        transactionRepository: transactionRepository,
        auditService: null,          // @Autowired(required=false) — null-safe in the handler
        steamUserRepository: null
    )

    private static Transaction withdrawal(Long id = 7L, Long walletId = 3L,
                                          BigDecimal amount = new BigDecimal('25.00'),
                                          String ref = 'tr_abc123') {
        new Transaction(id: id, walletId: walletId, type: 'WITHDRAW',
                        status: 'COMPLETED', amount: amount, currency: 'USD',
                        stripeReference: ref, description: 'Withdrawal via Stripe Connect')
    }

    private static com.stripe.model.Transfer transfer(String id, Long amount, Long amountReversed) {
        def t = new com.stripe.model.Transfer()
        t.setId(id)
        t.setAmount(amount)
        t.setAmountReversed(amountReversed)
        t
    }

    def "full reversal re-credits the wallet by exactly the withdrawn amount, once"() {
        given: 'a completed $25 withdrawal and a wallet at $10'
        def tx = withdrawal()
        def wallet = new Wallet(id: 3L, username: 'steam_76561197960287930', balance: new BigDecimal('10.00'))

        when: 'Stripe reports the transfer was fully reversed (2500/2500 cents)'
        service.handleTransferReversed(transfer('tr_abc123', 2500L, 2500L))

        then: 'we look up the withdrawal, win the atomic claim, and re-credit the wallet'
        1 * transactionRepository.findByStripeReference('tr_abc123') >> tx
        1 * transactionRepository.claimReverseWithdrawal(7L, _ as Long) >> 1
        1 * walletRepository.findById(3L) >> Optional.of(wallet)
        1 * walletRepository.save({ Wallet w -> w.balance == new BigDecimal('35.00') })

        and: 'the wallet ended at $35 ($10 + $25 returned)'
        wallet.balance == new BigDecimal('35.00')
    }

    def "a redelivered / already-reconciled reversal does NOT re-credit (idempotent)"() {
        given: 'the claim reports 0 rows — a sibling pod or earlier delivery already reversed it'
        def tx = withdrawal()

        when:
        service.handleTransferReversed(transfer('tr_abc123', 2500L, 2500L))

        then:
        1 * transactionRepository.findByStripeReference('tr_abc123') >> tx
        1 * transactionRepository.claimReverseWithdrawal(7L, _ as Long) >> 0
        and: 'no wallet is loaded or credited — the second delivery is a no-op'
        0 * walletRepository.findById(_)
        0 * walletRepository.save(_)
    }

    def "a PARTIAL reversal is left for manual reconciliation (no claim, no credit)"() {
        given: 'Stripe reports only 1000 of 2500 cents reversed'
        def tx = withdrawal()

        when:
        service.handleTransferReversed(transfer('tr_abc123', 2500L, 1000L))

        then:
        1 * transactionRepository.findByStripeReference('tr_abc123') >> tx
        and: 'we refuse to guess the partial amount — no claim, no credit'
        0 * transactionRepository.claimReverseWithdrawal(_, _)
        0 * walletRepository.save(_)
    }

    def "a transfer with no matching withdrawal is a no-op"() {
        when:
        service.handleTransferReversed(transfer('tr_unknown', 2500L, 2500L))

        then:
        1 * transactionRepository.findByStripeReference('tr_unknown') >> null
        0 * transactionRepository.claimReverseWithdrawal(_, _)
        0 * walletRepository.save(_)
    }

    def "a transfer matching a non-withdrawal tx is a no-op (defensive)"() {
        given: 'the stripeReference somehow matches a DEPOSIT row'
        def deposit = new Transaction(id: 9L, walletId: 3L, type: 'DEPOSIT',
                                      status: 'COMPLETED', amount: new BigDecimal('25.00'),
                                      stripeReference: 'tr_abc123')

        when:
        service.handleTransferReversed(transfer('tr_abc123', 2500L, 2500L))

        then:
        1 * transactionRepository.findByStripeReference('tr_abc123') >> deposit
        0 * transactionRepository.claimReverseWithdrawal(_, _)
        0 * walletRepository.save(_)
    }

    def "null transfer / null id is ignored without touching repositories"() {
        when:
        service.handleTransferReversed(null)

        then:
        0 * transactionRepository.findByStripeReference(_)
        0 * walletRepository.save(_)
    }
}
