package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the dev-mode and non-Stripe-SDK paths of StripeService:
 *   - isLive() flag
 *   - devModeDeposit credit path
 *   - refundDeposit guards + wallet debit
 *   - requestWithdrawal guards + wallet debit
 *
 * The live-Stripe-SDK paths (createDepositSession → Session.create,
 * handleWebhookEvent → Webhook.constructEvent) require mocking static
 * methods on the Stripe SDK and are intentionally out of scope — they
 * would need a PowerMock-style bridge that doesn't add security value
 * over a real end-to-end test against Stripe's test-mode API.
 */
class StripeServiceSpec extends Specification {

    WalletRepository       walletRepository      = Mock()
    TransactionRepository  transactionRepository = Mock()

    @Subject
    StripeService service = new StripeService(
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        secretKey             : 'sk_test_replace_me',   // dev-mode
        publishableKey        : 'pk_test_replace_me',
        webhookSecret         : 'whsec_replace_me',
        successUrl            : 'http://localhost/ok',
        cancelUrl             : 'http://localhost/cancel',
        currency              : 'usd'
    )

    def "isLive returns false for the default replace_me placeholder key"() {
        expect:
        service.isLive() == false
    }

    def "isLive returns true when a real-looking secret is wired"() {
        given:
        service.secretKey = 'sk_live_abc123'

        expect:
        service.isLive() == true
    }

    // ── devModeDeposit ────────────────────────────────────────────

    def "devModeDeposit credits the wallet and returns live=false"() {
        given:
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("100.00"))
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        def saved = null
        transactionRepository.save(_) >> { args ->
            def t = args[0]
            t.id = 1L
            saved = t
            t
        }

        when:
        def result = service.createDepositSession(500L, new BigDecimal("50"))

        then:
        wallet.balance == new BigDecimal("150.00")
        result.live == false
        result.newBalance == new BigDecimal("150.00")
        saved != null
        saved.type == 'DEPOSIT'
        saved.status == 'COMPLETED'
        saved.amount == new BigDecimal("50")
    }

    // ── requestWithdrawal ─────────────────────────────────────────

    def "requestWithdrawal debits the wallet and records a COMPLETED tx in dev mode"() {
        given:
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("100.00"))
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t.id = 1L; t }

        when:
        def tx = service.requestWithdrawal(500L, new BigDecimal("40"), 'acct_external')

        then:
        wallet.balance == new BigDecimal("60.00")
        tx.type == 'WITHDRAW'
        tx.status == 'COMPLETED'
        tx.amount == new BigDecimal("40")
    }

    def "requestWithdrawal refuses if wallet is short"() {
        given:
        walletRepository.findById(_) >> Optional.of(new Wallet(id: 500L, balance: new BigDecimal("10")))

        when:
        service.requestWithdrawal(500L, new BigDecimal("40"), 'acct_external')

        then:
        thrown(IllegalStateException)
    }

    def "requestWithdrawal refuses zero/negative amounts"() {
        given:
        walletRepository.findById(_) >> Optional.of(new Wallet(id: 500L, balance: new BigDecimal("100")))

        when:
        service.requestWithdrawal(500L, amount, 'acct')

        then:
        thrown(IllegalArgumentException)

        where:
        amount << [BigDecimal.ZERO, new BigDecimal("-5")]
    }

    def "requestWithdrawal marks PENDING (not COMPLETED) when Stripe is live"() {
        given:
        service.secretKey = 'sk_live_abc'
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("100.00"))
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        def tx = service.requestWithdrawal(500L, new BigDecimal("40"), 'acct_external')

        then:
        tx.status == 'PENDING'
    }

    // ── refundDeposit ─────────────────────────────────────────────

    def "refundDeposit debits the wallet and records a REFUND tx (dev mode)"() {
        given:
        def depositTx = new Transaction(
            id:     1L,
            walletId: 500L,
            type:   'DEPOSIT',
            status: 'COMPLETED',
            amount: new BigDecimal("100"),
            currency: 'USD',
            stripeReference: 'dev_123'
        )
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("200"))
        transactionRepository.findById(1L) >> Optional.of(depositTx)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t.id = 2L; t }

        when:
        def result = service.refundDeposit(1L, null)

        then:
        wallet.balance == new BigDecimal("100")
        result.newBalance == new BigDecimal("100")
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'REFUND' && tx.amount == new BigDecimal("100")
        })
    }

    def "refundDeposit honours a partial amount"() {
        given:
        def depositTx = new Transaction(
            id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal("100"), currency: 'USD', stripeReference: 'dev_123'
        )
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("200"))
        transactionRepository.findById(_) >> Optional.of(depositTx)
        walletRepository.findById(_) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        def result = service.refundDeposit(1L, new BigDecimal("30"))

        then:
        wallet.balance == new BigDecimal("170")
        result.newBalance == new BigDecimal("170")
    }

    def "refundDeposit refuses when deposit tx is not COMPLETED"() {
        given:
        transactionRepository.findById(_) >> Optional.of(new Transaction(type: 'DEPOSIT', status: 'PENDING'))

        when:
        service.refundDeposit(1L, null)

        then:
        thrown(IllegalStateException)
    }

    def "refundDeposit refuses non-DEPOSIT tx types"() {
        given:
        transactionRepository.findById(_) >> Optional.of(new Transaction(type: 'SALE', status: 'COMPLETED'))

        when:
        service.refundDeposit(1L, null)

        then:
        thrown(IllegalStateException)
    }

    def "refundDeposit refuses amounts greater than the original deposit"() {
        given:
        transactionRepository.findById(_) >> Optional.of(new Transaction(
            type: 'DEPOSIT', status: 'COMPLETED', amount: new BigDecimal("100")))

        when:
        service.refundDeposit(1L, new BigDecimal("150"))

        then:
        thrown(IllegalArgumentException)
    }

    def "refundDeposit refuses when wallet balance is lower than refund"() {
        given:
        def depositTx = new Transaction(
            id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal("100"), stripeReference: 'dev_123'
        )
        transactionRepository.findById(_) >> Optional.of(depositTx)
        walletRepository.findById(_) >> Optional.of(new Wallet(balance: new BigDecimal("10")))

        when:
        service.refundDeposit(1L, null)

        then:
        thrown(IllegalStateException)
    }
}
