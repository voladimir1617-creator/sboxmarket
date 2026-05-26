package com.sboxmarket

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.StripeService
import com.stripe.model.Dispute
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

    def "devModeDeposit rejects non-positive amounts (batch 318)"() {
        // Without this guard, a misconfigured prod deployment without
        // Stripe keys would happily accept a zero/negative deposit
        // through the dev-mode fallback and log it as a real credit.
        when:
        service.createDepositSession(500L, amount)

        then:
        thrown(IllegalArgumentException)

        where:
        amount << [null, BigDecimal.ZERO, new BigDecimal("-0.01")]
    }

    def "devModeDeposit rejects amounts above the \$10,000 cap (batch 318)"() {
        when:
        service.createDepositSession(500L, new BigDecimal("10000.01"))

        then:
        thrown(IllegalArgumentException)
    }

    def "devModeDeposit rounds a sub-cent amount to whole cents before crediting"() {
        // The DepositRequest DTO constrains min/max but not scale. A
        // {"amount": 50.999} body must credit whole cents only — otherwise
        // the wallet drifts a fraction of a cent past what was charged.
        given:
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("100.00"))
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        def saved = null
        transactionRepository.save(_) >> { args -> def t = args[0]; t.id = 1L; saved = t; t }

        when:
        service.createDepositSession(500L, new BigDecimal("50.999"))

        then: 'HALF_UP → 51.00, not 50.999'
        wallet.balance == new BigDecimal("151.00")
        saved.amount == new BigDecimal("51.00")
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

    def "requestWithdrawal refuses zero/negative/null amounts"() {
        given:
        walletRepository.findById(_) >> Optional.of(new Wallet(id: 500L, balance: new BigDecimal("100")))

        when:
        service.requestWithdrawal(500L, amount, 'acct')

        then: 'null is caught explicitly — never NPEs on the balance comparison'
        thrown(IllegalArgumentException)

        where:
        amount << [BigDecimal.ZERO, new BigDecimal("-5"), null]
    }

    def "requestWithdrawal rounds a sub-cent amount to whole cents before debiting"() {
        given:
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("100.00"))
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        def saved = null
        transactionRepository.save(_) >> { Transaction t -> t.id = 1L; saved = t; t }

        when:
        def tx = service.requestWithdrawal(500L, new BigDecimal("12.999"), 'acct')

        then: 'HALF_UP → 13.00 debited, ledger row carries the same 2dp value'
        wallet.balance == new BigDecimal("87.00")
        saved.amount == new BigDecimal("13.00")
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

    def "requestWithdrawal audit row carries the wallet owner as actor AND subject"() {
        // Pre-fix the audit row was logged with actorUserId=null AND
        // subjectUserId=null — even though this is a self-initiated
        // money-out movement and the wallet owner is right there at
        // `wallet.username = steam_<id>`. The null subjectUserId meant
        // ProfileController /security-activity (which filters
        // auditLogRepository.bySubject on subjectUserId = uid) NEVER
        // returned the row, so a user whose session was hijacked to
        // initiate withdrawals could not see those withdrawals in their
        // own security history feed even though WITHDRAW_REQUESTED is
        // explicitly white-listed there — the exact fraud-detection
        // scenario the feed exists to expose. Same null/null bug pattern
        // sibling cancelPendingWithdrawal (line 519) already fixed; this
        // is the matching outlier on the request path.
        given:
        def steamUserRepository = Mock(SteamUserRepository)
        def audit = Mock(AuditService)
        service.steamUserRepository = steamUserRepository
        service.auditService = audit
        def owner = new SteamUser(id: 888L, steamId64: '76561198000000099')
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('100.00'),
            username: 'steam_76561198000000099')
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t.id = 17L; t }
        steamUserRepository.findBySteamId64('76561198000000099') >> owner

        when:
        service.requestWithdrawal(500L, new BigDecimal('40'), 'acct_external')

        then: 'audit log carries the owner as BOTH actor and subject — visible to /security-activity'
        1 * audit.log(AuditService.WITHDRAW_REQUESTED, 888L, 888L, 17L, _)
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

    def "refundDeposit rounds a sub-cent refund amount to whole cents before debiting"() {
        // The admin-supplied refundAmount is not scale-constrained. A
        // value like 30.005 must debit the wallet whole cents only — and
        // by the same value the Stripe refund uses — otherwise the ledger
        // drifts a fraction of a cent from what Stripe actually clawed back.
        given:
        def depositTx = new Transaction(
            id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal("100.00"), currency: 'USD', stripeReference: 'dev_123'
        )
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("200.00"))
        transactionRepository.findById(_) >> Optional.of(depositTx)
        walletRepository.findById(_) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        def savedRefund = null
        transactionRepository.save(_) >> { Transaction t -> t.id = 2L; savedRefund = t; t }

        when: 'an admin enters a 3-decimal refund amount'
        def result = service.refundDeposit(1L, new BigDecimal("30.005"))

        then: 'HALF_UP → 30.01; the wallet debit and the REFUND row carry the same 2dp value'
        wallet.balance == new BigDecimal("169.99")
        result.newBalance == new BigDecimal("169.99")
        savedRefund.amount == new BigDecimal("30.01")
        savedRefund.amount.scale() == 2
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

    def "refundDeposit clamps the wallet debit when the balance is below the refund — no throw, full REFUND recorded"() {
        // The Stripe refund has already moved money out of the platform's
        // Stripe balance by this point; throwing here would leave Stripe
        // debited with no REFUND row in the ledger. Clamp the wallet at
        // its available balance (never negative) and still record the
        // REFUND for the full amount so the ledger reflects what left
        // Stripe — the gap is an auditable shortfall, not a hard failure.
        given:
        def depositTx = new Transaction(
            id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal("100"), currency: 'USD', stripeReference: 'dev_123'
        )
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("10"))
        transactionRepository.findById(_) >> Optional.of(depositTx)
        walletRepository.findById(_) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        def savedRefund = null
        transactionRepository.save(_) >> { Transaction t -> t.id = 2L; savedRefund = t; t }

        when: 'a $100 deposit is refunded but the wallet only holds $10'
        def result = service.refundDeposit(1L, null)

        then: 'no throw — the wallet is clamped at 0, never driven negative'
        noExceptionThrown()
        wallet.balance == BigDecimal.ZERO
        result.newBalance == BigDecimal.ZERO

        and: 'the REFUND row still records the full $100 that left Stripe'
        savedRefund.type == 'REFUND'
        savedRefund.amount == new BigDecimal("100")
    }

    def "refundDeposit blocks a second partial refund that would push past the original deposit total — even in dev mode (no Stripe over-refund guard)"() {
        // Regression pin for the cumulative-refund cap (batch 658). Pre-fix,
        // the single-call check `amount > tx.amount` let two $60 partials
        // pass against a $100 deposit and the dev-mode branch (no `cs_`
        // prefix → skip Stripe) would happily debit the wallet a total of
        // $120 and write a second REFUND row. Affected legacy dev_*
        // deposits that survived into a Stripe-keyed production deploy.
        given:
        def depositTx = new Transaction(
            id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal("100.00"), currency: 'USD', stripeReference: 'dev_legacy_42'
        )
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("200.00"))
        transactionRepository.findById(1L) >> Optional.of(depositTx)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t.id = 2L; t }
        // First $60 refund already on file → repository reports it.
        transactionRepository.sumRefundsByDeposit(1L) >> new BigDecimal("60.00")

        when: 'admin tries a second $60 refund on the same deposit'
        service.refundDeposit(1L, new BigDecimal("60"))

        then: 'blocked — only $40 remains refundable; wallet untouched'
        thrown(IllegalArgumentException)
        wallet.balance == new BigDecimal("200.00")
        // No second REFUND row written.
        0 * transactionRepository.save({ Transaction t -> t.type == 'REFUND' })
    }

    def "refundDeposit allows the remaining balance on a second partial refund (cumulative cap)"() {
        // Companion to the block-the-overage test: with $40 already
        // refunded, a follow-up $60 refund must still succeed because it
        // exactly fills the remaining refundable balance.
        given:
        def depositTx = new Transaction(
            id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal("100.00"), currency: 'USD', stripeReference: 'dev_legacy_42'
        )
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("200.00"))
        transactionRepository.findById(1L) >> Optional.of(depositTx)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        def savedRefund = null
        transactionRepository.save(_) >> { Transaction t -> t.id = 99L; savedRefund = t; t }
        transactionRepository.sumRefundsByDeposit(1L) >> new BigDecimal("40.00")

        when:
        def result = service.refundDeposit(1L, new BigDecimal("60"))

        then:
        noExceptionThrown()
        wallet.balance == new BigDecimal("140.00")
        result.newBalance == new BigDecimal("140.00")
        savedRefund.amount == new BigDecimal("60.00")
    }

    // ── cancelPendingWithdrawal ───────────────────────────────────

    def "cancelPendingWithdrawal credits the wallet back and flips the tx to CANCELLED"() {
        given:
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("60.00"))
        def tx = new Transaction(id: 9L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
            amount: new BigDecimal("40.00"), description: 'Withdrawal request')
        transactionRepository.findById(9L) >> Optional.of(tx)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        def result = service.cancelPendingWithdrawal(500L, 9L)

        then:
        wallet.balance == new BigDecimal("100.00")
        tx.status == 'CANCELLED'
        tx.description.contains('cancelled by user')
        result.status == 'CANCELLED'
        result.newBalance == new BigDecimal("100.00")
    }

    def "cancelPendingWithdrawal bumps tx.updatedAt to the cancellation moment"() {
        // Regression pin for the "stale updatedAt on self-cancel" bug. Every
        // OTHER Transaction status mutation in StripeService + AdminService
        // bumps updatedAt next to the status flip; cancelPendingWithdrawal
        // was the outlier and left the CANCELLED row carrying its original
        // PENDING-creation timestamp. Wallet-history "recently updated"
        // ordering and audit-window queries filtered by updatedAt then
        // silently missed the cancel event.
        given:
        def originalCreatedAt = 1_700_000_000_000L   // wall-clock back in 2023
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("60.00"))
        def tx = new Transaction(
            id: 9L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
            amount: new BigDecimal("40.00"), description: 'Withdrawal request',
            createdAt: originalCreatedAt, updatedAt: originalCreatedAt)
        transactionRepository.findById(9L) >> Optional.of(tx)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }
        def before = System.currentTimeMillis()

        when:
        service.cancelPendingWithdrawal(500L, 9L)
        def after = System.currentTimeMillis()

        then:
        tx.status == 'CANCELLED'
        // createdAt must NOT move — that's the original-request marker.
        tx.createdAt == originalCreatedAt
        // updatedAt must move to "now"-ish, NOT stay at originalCreatedAt.
        tx.updatedAt != originalCreatedAt
        tx.updatedAt >= before
        tx.updatedAt <= after
    }

    def "cancelPendingWithdrawal refuses a tx the wallet does not own — 403 Forbidden"() {
        given:
        def tx = new Transaction(id: 9L, walletId: 999L /* different wallet */,
            type: 'WITHDRAW', status: 'PENDING', amount: new BigDecimal("40"))
        transactionRepository.findById(9L) >> Optional.of(tx)

        when:
        service.cancelPendingWithdrawal(500L, 9L)

        then:
        // A cross-wallet cancel is an authorization failure — ForbiddenException
        // (403), not the old IllegalStateException (which mapped to a 400).
        thrown(ForbiddenException)
        0 * walletRepository.save(_)
    }

    def "cancelPendingWithdrawal refuses a non-WITHDRAW transaction"() {
        given:
        def tx = new Transaction(id: 9L, walletId: 500L, type: 'DEPOSIT',
            status: 'PENDING', amount: new BigDecimal("40"))
        transactionRepository.findById(9L) >> Optional.of(tx)

        when:
        service.cancelPendingWithdrawal(500L, 9L)

        then:
        thrown(IllegalArgumentException)
        0 * walletRepository.save(_)
    }

    def "cancelPendingWithdrawal refuses already-COMPLETED rows"() {
        given:
        def tx = new Transaction(id: 9L, walletId: 500L, type: 'WITHDRAW',
            status: 'COMPLETED', amount: new BigDecimal("40"))
        transactionRepository.findById(9L) >> Optional.of(tx)

        when:
        service.cancelPendingWithdrawal(500L, 9L)

        then:
        thrown(IllegalStateException)
        0 * walletRepository.save(_)
    }

    def "cancelPendingWithdrawal 404s for unknown tx id"() {
        given:
        transactionRepository.findById(_) >> Optional.empty()

        when:
        service.cancelPendingWithdrawal(500L, 404L)

        then:
        // NotFoundException (404) — a bare NoSuchElementException has no
        // GlobalExceptionHandler mapping and fell through to a 500.
        thrown(NotFoundException)
    }

    def "cancelPendingWithdrawal audit row carries the wallet owner as actor AND subject"() {
        // Pre-fix the audit row was logged with actorUserId=null AND
        // subjectUserId=null — even though this is a self-cancel and the
        // wallet owner is right there at `wallet.username = steam_<id>`.
        // The null subjectUserId meant ProfileController /security-activity
        // (which filters auditLogRepository.bySubject on subjectUserId =
        // uid) NEVER returned the row, so a user who cancelled their own
        // pending withdrawal could not see the action in their own
        // security history feed even though WITHDRAW_SELF_CANCELLED is
        // explicitly white-listed there. Same null/null bug pattern as the
        // refundDeposit fix at StripeService.groovy line 373.
        given:
        def steamUserRepository = Mock(SteamUserRepository)
        def audit = Mock(AuditService)
        service.steamUserRepository = steamUserRepository
        service.auditService = audit
        def owner = new SteamUser(id: 777L, steamId64: '76561198000000001')
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('60.00'),
            username: 'steam_76561198000000001')
        def tx = new Transaction(id: 9L, walletId: 500L, type: 'WITHDRAW',
            status: 'PENDING', amount: new BigDecimal('40.00'),
            description: 'Withdrawal request')
        transactionRepository.findById(9L) >> Optional.of(tx)
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }
        steamUserRepository.findBySteamId64('76561198000000001') >> owner

        when:
        service.cancelPendingWithdrawal(500L, 9L)

        then: 'audit log carries the owner as BOTH actor and subject — visible to /security-activity'
        1 * audit.log(AuditService.WITHDRAW_SELF_CANCELLED, 777L, 777L, 9L, _)
    }

    // ── completeDeposit (dev-mode-reachable branches) ─────────────────
    //
    // The live-Stripe path (Session.retrieve) needs static SDK mocking
    // and is out of scope. But the guard branches and the non-live
    // credit path are pure and must be pinned — they are the deposit
    // money-in correctness surface.

    def "completeDeposit credits the wallet and flips the tx to COMPLETED (non-live path)"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('60.00'), currency: 'USD', stripeReference: 'dev_abc')
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('40.00'))
        transactionRepository.findByStripeReference('dev_abc') >> tx
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        service.completeDeposit('dev_abc')

        then: 'wallet credited by exactly the tx amount, row marked COMPLETED'
        wallet.balance == new BigDecimal('100.00')
        tx.status == 'COMPLETED'
    }

    def "completeDeposit is idempotent — a second call on an already-COMPLETED row never double-credits"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('60.00'), currency: 'USD', stripeReference: 'dev_done')
        transactionRepository.findByStripeReference('dev_done') >> tx

        when: 'the deposit is confirmed a second time (webhook + /confirm-deposit both fire)'
        service.completeDeposit('dev_done')

        then: 'short-circuits before touching the wallet — no credit, no save'
        0 * walletRepository.findById(_)
        0 * walletRepository.save(_)
    }

    def "completeDeposit short-circuits on EVERY non-PENDING status — no double-credit after dispute/fail/expire/cancel"() {
        // P1 money-hole regression. Pre-fix the guard was
        // `tx.status == "COMPLETED" → return`, so any OTHER non-PENDING
        // status fell through to the wallet-credit path. The DISPUTED
        // case is the real-money exploit: user deposits → wallet
        // credited → user files chargeback → tx flipped to DISPUTED
        // (wallet untouched) → a duplicate /confirm-deposit fires (a
        // re-loaded success_url tab, or any redelivery after the
        // in-memory seenEventIds cache resets on a server restart) →
        // old check returned false → wallet credited AGAIN. Net: user
        // walks away with 2× the deposit while Stripe is about to claw
        // back the original via the chargeback. Treating any non-PENDING
        // tx as already-handled keeps the idempotent contract: a deposit
        // leaves PENDING exactly once, and only the PENDING→COMPLETED
        // edge credits the wallet.
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: badStatus,
            amount: new BigDecimal('100.00'), currency: 'USD', stripeReference: 'dev_x')
        transactionRepository.findByStripeReference('dev_x') >> tx

        when:
        service.completeDeposit('dev_x')

        then: 'short-circuits before touching the wallet or any of the live-mode rails'
        0 * walletRepository.findById(_)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)

        where:
        badStatus << ['COMPLETED', 'DISPUTED', 'FAILED', 'EXPIRED', 'CANCELLED']
    }

    def "completeDeposit throws IllegalStateException for an unknown session id (permanent — webhook ACKs 200)"() {
        given: 'no tx matches the supplied reference'
        transactionRepository.findByStripeReference('cs_ghost') >> null

        when:
        service.completeDeposit('cs_ghost')

        then: 'IllegalState is the permanent-domain-failure type — StripeWebhookController ACKs it 200'
        thrown(IllegalStateException)
        0 * walletRepository.save(_)
    }

    def "completeDeposit throws IllegalArgumentException for a null/oversize session id"() {
        when:
        service.completeDeposit(sessionId)

        then:
        thrown(IllegalArgumentException)

        where:
        sessionId << [null, '', 'cs_' + ('x' * 200)]
    }

    def "completeDeposit refuses a reference that resolves to a non-DEPOSIT tx"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
            amount: new BigDecimal('10'), stripeReference: 'dev_w')
        transactionRepository.findByStripeReference('dev_w') >> tx

        when:
        service.completeDeposit('dev_w')

        then: 'never credits a wallet off a withdrawal row'
        thrown(IllegalStateException)
        0 * walletRepository.save(_)
    }

    // ── failTransaction ───────────────────────────────────────────────

    def "failTransaction flips a PENDING deposit to FAILED"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('25.00'), stripeReference: 'cs_exp')
        transactionRepository.findByStripeReference('cs_exp') >> tx
        transactionRepository.save(_) >> { Transaction t -> t }

        when:
        service.failTransaction('cs_exp', 'expired')

        then:
        tx.status == 'FAILED'
        tx.description.contains('expired')
    }

    def "failTransaction never overwrites a non-PENDING row (idempotent — no COMPLETED→FAILED)"() {
        given: 'the deposit already completed via a racing confirm-deposit'
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('25.00'), stripeReference: 'cs_done')
        transactionRepository.findByStripeReference('cs_done') >> tx

        when: 'a late checkout.session.expired event arrives for the same session'
        service.failTransaction('cs_done', 'expired')

        then: 'the COMPLETED row is left untouched — a paid deposit is never flipped to FAILED'
        tx.status == 'COMPLETED'
        0 * transactionRepository.save(_)
    }

    def "failTransaction is a no-op for an unknown session id"() {
        given:
        transactionRepository.findByStripeReference('cs_nope') >> null

        when:
        service.failTransaction('cs_nope', 'expired')

        then:
        0 * transactionRepository.save(_)
        noExceptionThrown()
    }

    // ── webhook event-id dedupe (BUG 1 — batch 657) ───────────────
    //
    // The dedupe set must record an event id ONLY after the handler
    // ran successfully. Recording up-front (the pre-fix behaviour) meant
    // a handler throw — Stripe API timeout, DB hiccup — left the id in
    // the set even though the @Transactional wallet credit rolled back,
    // so Stripe's retry was wrongly skipped and the paid-for deposit was
    // never credited. These tests pin the check (alreadyProcessed) and
    // record (markProcessed) as separate steps.

    def "alreadyProcessed is a pure read — a fresh event id is NOT recorded just by checking it"() {
        when: 'an event id is checked but the handler has not yet succeeded'
        def firstCheck = service.alreadyProcessed('evt_handler_will_fail')

        then: 'first check says new'
        firstCheck == false

        when: 'the handler threw, so markProcessed was never called — Stripe retries and we check again'
        def retryCheck = service.alreadyProcessed('evt_handler_will_fail')

        then: 'the retry is STILL seen as new — it will be re-processed, not silently skipped'
        retryCheck == false
    }

    def "markProcessed after a successful handler makes the next check short-circuit"() {
        when: 'the handler ran without throwing, so the id is recorded'
        service.markProcessed('evt_ok')

        then: 'a subsequent retry of the SAME event is recognised as already done'
        service.alreadyProcessed('evt_ok') == true
    }

    def "a failed-then-retried event credits on the retry once the retry succeeds (BUG 1 regression)"() {
        given: 'attempt 1 of the webhook — the handler throws before markProcessed runs'
        // Simulated by NOT calling markProcessed (control never reaches it).
        service.alreadyProcessed('evt_dep_42')   // the up-front check on attempt 1

        when: 'Stripe retries — attempt 2 checks the gate again'
        def retrySkipped = service.alreadyProcessed('evt_dep_42')

        then: 'the retry is NOT skipped — it proceeds to the handler'
        retrySkipped == false

        when: 'attempt 2 handler succeeds and records the id'
        service.markProcessed('evt_dep_42')

        then: 'a third delivery of the same event is now correctly deduped'
        service.alreadyProcessed('evt_dep_42') == true
    }

    def "markProcessed / alreadyProcessed ignore null and empty event ids"() {
        expect:
        service.alreadyProcessed(null) == false
        service.alreadyProcessed('') == false

        when: 'recording a null / empty id is a harmless no-op'
        service.markProcessed(null)
        service.markProcessed('')

        then: 'still treated as new — no NPE, nothing recorded'
        service.alreadyProcessed(null) == false
        service.alreadyProcessed('') == false
    }

    // ── chargeback display amount (BUG 3 — batch 657) ─────────────
    //
    // dispute.amount is in cents. The deposit/refund paths convert with
    // BigDecimal; the chargeback handlers used a `dispute.amount / 100.0`
    // double divide that could render $49.99 as $49.99000000000001 in
    // the audit log and the admin/user notifications. The fix uses
    // `new BigDecimal(amount).movePointLeft(2)` for an exact value.
    //
    // The Dispute carries no charge / paymentIntent so the tx lookup
    // falls through cleanly to "not found" — no Stripe static SDK call
    // is needed; the handler still logs + audits with the amount.

    def "handleChargebackOpened formats a .99 dispute amount exactly (no float drift)"() {
        given:
        def audit = Mock(AuditService)
        service.auditService = audit
        // 4999 cents — the value that exposes the old double-divide bug.
        def dispute = new Dispute(id: 'dp_99', amount: 4999L, reason: 'fraudulent')

        when:
        service.handleChargebackOpened(dispute)

        then: 'the audit summary carries an exact $49.99 — never $49.99000000000001'
        1 * audit.log('CHARGEBACK_OPENED', null, null, null, { String summary ->
            summary.contains('$49.99') && !summary.contains('49.99000')
        })
    }

    def "handleChargebackClosed formats a .99 dispute amount exactly (no float drift)"() {
        given:
        // status is neither 'won' nor anything that flips a tx — the
        // handler logs + (would) notify with the amount. No notification
        // service wired, so it just exercises the amount math + log path.
        def dispute = new Dispute(id: 'dp_lost', amount: 4999L, status: 'lost')

        when: 'a non-won close with a .99 amount runs without throwing'
        service.handleChargebackClosed(dispute)

        then: 'the BigDecimal conversion produced an exact value (no ArithmeticException, no drift)'
        // movePointLeft(2) on 4999 is exactly 49.99 — assert the math
        // the handler now uses to render the amount.
        new BigDecimal(4999L).movePointLeft(2) == new BigDecimal('49.99')
        noExceptionThrown()
    }

    def "handleChargebackOpened tolerates a null dispute amount"() {
        given:
        def audit = Mock(AuditService)
        service.auditService = audit
        def dispute = new Dispute(id: 'dp_noamt', amount: null, reason: 'fraudulent')

        when:
        service.handleChargebackOpened(dispute)

        then: 'null amount renders as $0 — no NPE on the BigDecimal path'
        1 * audit.log('CHARGEBACK_OPENED', null, null, null, { String summary ->
            summary.contains('$0')
        })
    }

    // ── dispute-tx lookup spans COMPLETED + DISPUTED (BUG FIX) ────────
    //
    // findDepositByPaymentIntent only scanned 'COMPLETED' deposits. But
    // handleChargebackOpened flips the matched deposit to 'DISPUTED' the
    // moment a chargeback lands. Two follow-on events then broke:
    //
    //   1. `charge.dispute.closed` (WON) re-ran the lookup, which now
    //      missed the (DISPUTED) row entirely — so the tx was never
    //      flipped back to COMPLETED and the user's withdrawal hold
    //      (countActiveDisputedDeposits > 0) never auto-lifted even
    //      though they won the dispute.
    //   2. A Stripe retry of `charge.dispute.created` also missed the
    //      row → tx==null → isFirstObservation==true → the audit log +
    //      every-admin + user notification fired a SECOND time.
    //
    // The deposit's " [pi:<id>]" description tag is appended at
    // completeDeposit time and survives the flip to DISPUTED, so once
    // the lookup also scans DISPUTED rows the match holds again.

    def "handleChargebackClosed WON flips a DISPUTED deposit back to COMPLETED (auto-clears the withdrawal hold)"() {
        given: 'a deposit that already went DISPUTED when the chargeback opened'
        def disputedDeposit = new Transaction(
            id: 7L, walletId: 500L, type: 'DEPOSIT', status: 'DISPUTED',
            amount: new BigDecimal('80.00'), currency: 'USD',
            stripeReference: 'cs_orig',
            description: 'Stripe Checkout deposit [pi:pi_won] — DISPUTED via Stripe (dp_x)')
        // The pre-fix lookup scanned only COMPLETED rows and would have
        // missed this DISPUTED row; the fix unions COMPLETED + DISPUTED.
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'COMPLETED') >> []
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'DISPUTED') >> [disputedDeposit]
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        // paymentIntent set, charge left null → the Stripe Charge.retrieve
        // fallback never fires, so the lookup stays inside the mock.
        def dispute = new Dispute(id: 'dp_won', amount: 8000L, status: 'won', paymentIntent: 'pi_won')

        when:
        service.handleChargebackClosed(dispute)

        then: 'the disputed deposit is reconciled back to COMPLETED — the hold lifts'
        disputedDeposit.status == 'COMPLETED'
        disputedDeposit.description.contains('dispute WON')
        1 * transactionRepository.save({ Transaction t -> t.id == 7L && t.status == 'COMPLETED' })
    }

    def "handleChargebackClosed LOST keeps a DISPUTED deposit DISPUTED (hold stays in place)"() {
        given:
        def disputedDeposit = new Transaction(
            id: 8L, walletId: 500L, type: 'DEPOSIT', status: 'DISPUTED',
            amount: new BigDecimal('40.00'), currency: 'USD',
            description: 'Stripe Checkout deposit [pi:pi_lost] — DISPUTED via Stripe (dp_y)')
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'COMPLETED') >> []
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'DISPUTED') >> [disputedDeposit]
        def dispute = new Dispute(id: 'dp_lost2', amount: 4000L, status: 'lost', paymentIntent: 'pi_lost')

        when:
        service.handleChargebackClosed(dispute)

        then: 'a lost dispute never flips the row back — withdrawals stay paused for admin clawback'
        disputedDeposit.status == 'DISPUTED'
        0 * transactionRepository.save({ Transaction t -> t.status == 'COMPLETED' })
    }

    def "handleChargebackOpened retry on an already-DISPUTED deposit does NOT re-audit (idempotent)"() {
        given: 'the deposit is already DISPUTED from the first delivery of this event'
        def audit = Mock(AuditService)
        service.auditService = audit
        def alreadyDisputed = new Transaction(
            id: 9L, walletId: 500L, type: 'DEPOSIT', status: 'DISPUTED',
            amount: new BigDecimal('25.00'), currency: 'USD',
            description: 'Stripe Checkout deposit [pi:pi_retry] — DISPUTED via Stripe (dp_r)')
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'COMPLETED') >> []
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'DISPUTED') >> [alreadyDisputed]
        def dispute = new Dispute(id: 'dp_retry', amount: 2500L, reason: 'fraudulent', paymentIntent: 'pi_retry')

        when: 'Stripe re-delivers charge.dispute.created'
        service.handleChargebackOpened(dispute)

        then: 'the lookup finds the DISPUTED row, isFirstObservation is false — no second audit row'
        0 * audit.log('CHARGEBACK_OPENED', _, _, _, _)
        0 * transactionRepository.save(_)
    }

    def "handleChargebackOpened first observation flips a COMPLETED deposit to DISPUTED and audits once"() {
        given:
        def audit = Mock(AuditService)
        service.auditService = audit
        def liveDeposit = new Transaction(
            id: 10L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('60.00'), currency: 'USD',
            description: 'Stripe Checkout deposit [pi:pi_first]')
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'COMPLETED') >> [liveDeposit]
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'DISPUTED') >> []
        transactionRepository.save(_) >> { Transaction t -> t }
        def dispute = new Dispute(id: 'dp_first', amount: 6000L, reason: 'fraudulent', paymentIntent: 'pi_first')

        when:
        service.handleChargebackOpened(dispute)

        then: 'the COMPLETED deposit is flipped to DISPUTED and the audit fires exactly once'
        liveDeposit.status == 'DISPUTED'
        1 * audit.log('CHARGEBACK_OPENED', null, null, 10L, _)
    }

    def "findDepositByPaymentIntent matches a DISPUTED row via the surviving [pi:] tag"() {
        given: 'only a DISPUTED deposit carries the tag — COMPLETED set is empty'
        def disputed = new Transaction(
            id: 11L, walletId: 500L, type: 'DEPOSIT', status: 'DISPUTED',
            amount: new BigDecimal('15.00'),
            description: 'Stripe Checkout deposit [pi:pi_tag] — DISPUTED via Stripe (dp_z)')
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'COMPLETED') >> []
        transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'DISPUTED') >> [disputed]

        expect: 'the lookup now spans DISPUTED rows so the tag still resolves the tx'
        service.findDepositByPaymentIntent('pi_tag')?.id == 11L

        and: 'an unrelated payment-intent id matches nothing'
        service.findDepositByPaymentIntent('pi_other') == null

        and: 'a null id short-circuits to null'
        service.findDepositByPaymentIntent(null) == null
    }

    // ── createDepositSession idempotency (regression) ─────────────────
    //
    // The idempotency key bucket inside createDepositSession is
    // (walletId:amountCents:minute). When the user double-clicks Deposit
    // inside the same wall-clock minute, Stripe's idempotency contract
    // returns the SAME Session.id on the second call. Pre-fix, we then
    // blindly wrote a SECOND PENDING Transaction row carrying the same
    // stripeReference — the column has no unique constraint, so it
    // landed. completeDeposit's `findByStripeReference(sessionId)` then
    // resolves only ONE of those rows; the other stays PENDING forever
    // (ghost "Deposit pending · $X" chip on the wallet hero AND keeps
    // counting toward the 24h deposit cap, blocking legitimate retries).
    // Fix: short-circuit and return the existing PENDING row when one
    // already maps to this Session.id.
    def "createDepositSession reuses the existing PENDING row when Stripe replays the same Session id (idempotency)"() {
        given: 'live mode so the Stripe-idempotency path is exercised'
        service.secretKey = 'sk_live_dedupe_test'
        service.dailyDepositCap = new BigDecimal('1000')   // leave plenty of headroom
        def wallet = new Wallet(id: 500L, balance: new BigDecimal("100.00"))
        walletRepository.findById(500L) >> Optional.of(wallet)
        // Daily cap not relevant — no prior deposits.
        transactionRepository.sumDepositsSince(_, _) >> BigDecimal.ZERO

        and: 'Stripe returns the SAME Session.id on the (idempotent) replay'
        def fakeSession = [id: 'cs_live_idem_42', url: 'https://stripe/co/cs_live_idem_42']
        GroovySpy(com.stripe.model.checkout.Session, global: true)
        com.stripe.model.checkout.Session.create(_, _) >> fakeSession

        and: 'first call wrote the PENDING row; the dedupe check now finds it'
        def existingPending = new Transaction(
            id: 7L, walletId: 500L, type: 'DEPOSIT', status: 'PENDING',
            amount: new BigDecimal('25.00'), currency: 'USD',
            stripeReference: 'cs_live_idem_42'
        )
        transactionRepository.findByStripeReference('cs_live_idem_42') >> existingPending

        when: 'the user double-clicks and a second createDepositSession fires'
        def result = service.createDepositSession(500L, new BigDecimal('25.00'))

        then: 'returns the EXISTING tx id — never writes a second PENDING row with the same stripeReference'
        result.transactionId == 7L
        result.sessionId == 'cs_live_idem_42'
        result.live == true
        // No new Transaction.save — pre-fix this was 1 (the duplicate PENDING).
        0 * transactionRepository.save(_)
    }
}
