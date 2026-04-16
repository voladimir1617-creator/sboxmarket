package com.sboxmarket.service

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.stripe.Stripe
import com.stripe.model.Refund
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.net.Webhook
import com.stripe.param.RefundCreateParams
import com.stripe.param.checkout.SessionCreateParams
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Slf4j
class StripeService {

    @Value('${stripe.secret-key}')       String secretKey
    @Value('${stripe.publishable-key}')  String publishableKey
    @Value('${stripe.webhook-secret}')   String webhookSecret
    @Value('${stripe.success-url}')      String successUrl
    @Value('${stripe.cancel-url}')       String cancelUrl
    @Value('${stripe.currency}')         String currency

    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired(required = false) AuditService auditService

    @PostConstruct
    void init() {
        Stripe.apiKey = secretKey
        log.info("Stripe initialised (key prefix: ${secretKey?.take(7)}…)")
    }

    String getPublishableKey() { publishableKey }

    boolean isLive() {
        secretKey && !secretKey.contains("replace_me")
    }

    /* ── DEPOSIT ─────────────────────────────────────────
     * Creates a Stripe Checkout Session and stores a PENDING tx.
     * Returns the hosted Checkout URL for redirect. */
    @Transactional
    Map createDepositSession(Long walletId, BigDecimal amount) {
        if (!isLive()) {
            // fallback dev-mode: instant fake deposit so UI works without real keys
            return devModeDeposit(walletId, amount)
        }
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new IllegalArgumentException("Deposit amount must be positive")
        }
        if (amount > new BigDecimal("10000")) {
            throw new IllegalArgumentException("Deposit amount exceeds \$10,000 limit")
        }

        def wallet = walletRepository.findById(walletId)
                .orElseThrow { new NoSuchElementException("Wallet $walletId not found") }

        long amountCents = (amount * 100).longValue()

        def params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl(successUrl + "&session_id={CHECKOUT_SESSION_ID}")
            .setCancelUrl(cancelUrl)
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(currency)
                            .setUnitAmount(amountCents)
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName("SkinBox Wallet Deposit")
                                    .setDescription("Deposit \$${amount} into @${wallet.username}")
                                    .build())
                            .build())
                    .build())
            .putMetadata("walletId", walletId.toString())
            .putMetadata("type", "DEPOSIT")
            .build()

        // Idempotency key — Stripe guarantees repeated requests with the same
        // key return the original session rather than creating a second one.
        // Key is (walletId:amount:minute) — fast replays within the same
        // minute return the same Checkout Session, while slower replays create
        // a fresh one (a user who double-clicks won't pay twice).
        def idemKey = "dep_${walletId}_${(amountCents)}_${System.currentTimeMillis().intdiv(60_000)}"
        def reqOpts = RequestOptions.builder().setIdempotencyKey(idemKey).build()

        def session = Session.create(params, reqOpts)

        def tx = new Transaction(
            walletId:        walletId,
            type:            "DEPOSIT",
            status:          "PENDING",
            amount:          amount,
            currency:        currency.toUpperCase(),
            stripeReference: session.id,
            description:     "Stripe Checkout deposit"
        )
        transactionRepository.save(tx)

        log.info("Created Stripe Checkout session ${session.id} for wallet $walletId amount \$${amount} (idem=${idemKey})")
        [checkoutUrl: session.url, sessionId: session.id, transactionId: tx.id, live: true]
    }

    /* ── REFUND ──────────────────────────────────────────
     * Creates a Stripe Refund for a prior deposit. Only callable via the
     * admin panel (not by end users). Credits back out of the user's wallet
     * so the balance stays consistent with Stripe. */
    @Transactional
    Map refundDeposit(Long depositTxId, BigDecimal refundAmount = null) {
        def tx = transactionRepository.findById(depositTxId)
                .orElseThrow { new NoSuchElementException("Transaction $depositTxId not found") }
        if (tx.type != 'DEPOSIT' || tx.status != 'COMPLETED') {
            throw new IllegalStateException("Only completed deposits can be refunded")
        }
        def amount = refundAmount ?: tx.amount
        if (amount <= BigDecimal.ZERO || amount > tx.amount) {
            throw new IllegalArgumentException("Refund amount must be between 0 and \$${tx.amount}")
        }

        String refundId = 'dev'
        if (isLive() && tx.stripeReference?.startsWith('cs_')) {
            try {
                // Look up the Checkout Session → payment intent → refund.
                def session = Session.retrieve(tx.stripeReference)
                def refundParams = RefundCreateParams.builder()
                    .setPaymentIntent(session.paymentIntent)
                    .setAmount((amount * 100).longValue())
                    .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                    .build()
                def refund = Refund.create(refundParams)
                refundId = refund.id
            } catch (Exception e) {
                log.error("Stripe refund failed for tx ${depositTxId}: ${e.message}")
                throw new IllegalStateException("Stripe refund failed: ${e.message}")
            }
        }

        // Debit the wallet and record the refund as its own transaction
        def wallet = walletRepository.findById(tx.walletId)
                .orElseThrow { new NoSuchElementException("Wallet not found") }
        if (wallet.balance < amount) {
            throw new IllegalStateException("Wallet balance too low to refund (have \$${wallet.balance}, need \$${amount})")
        }
        wallet.balance = wallet.balance - amount
        walletRepository.save(wallet)

        def refundTx = new Transaction(
            walletId:        tx.walletId,
            type:            'REFUND',
            status:          'COMPLETED',
            amount:          amount,
            currency:        tx.currency,
            stripeReference: refundId,
            description:     "Refund of deposit #${tx.id}"
        )
        transactionRepository.save(refundTx)
        try {
            auditService?.log(AuditService.REFUND_ISSUED, null, null, refundTx.id,
                "Refunded \$${amount} of deposit ${tx.id} (stripeRef=${refundId})")
        } catch (Exception ignore) {}
        log.info("Refund \$${amount} processed for deposit ${tx.id} (stripeRef=${refundId})")
        [refundId: refundTx.id, stripeRefund: refundId, newBalance: wallet.balance]
    }

    /* ── WITHDRAWAL ──────────────────────────────────────
     * Real payouts require Stripe Connect (Express accounts).
     * For this marketplace we debit the wallet and record a PENDING
     * withdrawal that an operator would fulfil off-platform. */
    @Transactional
    Transaction requestWithdrawal(Long walletId, BigDecimal amount, String destinationRef) {
        def wallet = walletRepository.findById(walletId)
                .orElseThrow { new NoSuchElementException("Wallet $walletId not found") }

        if (wallet.balance < amount) {
            throw new IllegalStateException("Insufficient balance: have \$${wallet.balance}, need \$${amount}")
        }
        if (amount <= BigDecimal.ZERO) {
            throw new IllegalArgumentException("Amount must be positive")
        }

        wallet.balance = wallet.balance - amount
        walletRepository.save(wallet)

        def tx = new Transaction(
            walletId:        walletId,
            type:            "WITHDRAW",
            status:          isLive() ? "PENDING" : "COMPLETED",
            amount:          amount,
            currency:        currency.toUpperCase(),
            stripeReference: destinationRef ?: "manual",
            description:     "Withdrawal request" + (isLive() ? " (awaiting Stripe Connect payout)" : " (dev-mode instant)")
        )
        transactionRepository.save(tx)

        try {
            auditService?.log(AuditService.WITHDRAW_REQUESTED, null, null, tx.id,
                "Withdrawal \$${amount} requested from wallet ${wallet.username} → ${destinationRef}")
        } catch (Exception ignore) {}
        log.info("Withdrawal \$${amount} from wallet $walletId → ${tx.status}")
        tx
    }

    /* ── WEBHOOK HANDLER ─────────────────────────────────
     * Called by StripeWebhookController when Stripe posts to /api/stripe/webhook. */
    @Transactional
    void handleWebhookEvent(String payload, String sigHeader) {
        def event
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret)
        } catch (Exception e) {
            log.warn("Invalid Stripe webhook signature: ${e.message}")
            throw new SecurityException("Invalid signature")
        }

        log.info("Stripe webhook received: ${event.type}")

        switch (event.type) {
            case "checkout.session.completed":
                def session = (Session) event.dataObjectDeserializer.object.orElse(null)
                if (session != null) completeDeposit(session.id)
                break
            case "checkout.session.expired":
                def session = (Session) event.dataObjectDeserializer.object.orElse(null)
                if (session != null) failTransaction(session.id, "expired")
                break
            case "payment_intent.succeeded":
                log.info("PaymentIntent succeeded: ${event.id}")
                break
            case "payment_intent.payment_failed":
                log.warn("PaymentIntent failed: ${event.id}")
                break
            case "refund.created":
                log.info("Refund created via Stripe dashboard: ${event.id}")
                break
            default:
                log.debug("Ignoring Stripe event: ${event.type}")
        }
    }

    /**
     * Confirms a deposit AFTER validating the Stripe session is real and paid.
     * Attacker mitigation: we previously credited whichever PENDING tx matched
     * the session id the client supplied, which meant an attacker could hit
     * `/api/wallet/confirm-deposit?sessionId=<anything>` — if any matching
     * row existed the wallet would be credited even without a real payment.
     *
     * Now:
     *   1) Look up the PENDING transaction by reference — must exist.
     *   2) In live mode, retrieve the session from Stripe and verify:
     *      - the session exists
     *      - payment_status == 'paid'
     *      - the metadata walletId matches what we stored
     *      - the amount_total matches what we stored
     *   3) Only then flip the row to COMPLETED and credit the wallet.
     *   4) Re-confirming an already-COMPLETED row is a no-op (idempotent).
     *
     * In dev mode (no Stripe keys) we trust the local `devModeDeposit` flow
     * — that path bypasses this entirely by writing `stripeReference="dev_..."`.
     */
    @Transactional
    void completeDeposit(String sessionId) {
        if (!sessionId || sessionId.length() > 200) {
            throw new IllegalArgumentException("Invalid session id")
        }
        def tx = transactionRepository.findByStripeReference(sessionId)
        if (tx == null) {
            // Hard failure instead of silent return — the old behaviour let
            // an attacker probe arbitrary session ids and get a harmless
            // 200. That masked a bug and looked like "success" in client code.
            log.warn("confirm-deposit called with unknown sessionId=${sessionId}")
            throw new IllegalStateException("Unknown deposit session")
        }
        if (tx.status == "COMPLETED") return   // idempotent
        if (tx.type != 'DEPOSIT') {
            log.warn("confirm-deposit called against a non-deposit tx ${tx.id}")
            throw new IllegalStateException("Transaction is not a deposit")
        }

        // Live-mode verification — ask Stripe the ground truth. We ignore the
        // sessionId the client handed us for anything other than a lookup;
        // the authoritative answer comes from Stripe itself.
        if (isLive() && sessionId.startsWith('cs_')) {
            def session
            try {
                session = Session.retrieve(sessionId)
            } catch (Exception e) {
                log.warn("Stripe session retrieve failed for ${sessionId}: ${e.message}")
                throw new IllegalStateException("Stripe session could not be verified")
            }
            if (session == null) {
                throw new IllegalStateException("Stripe session not found")
            }
            def paymentStatus = session.paymentStatus  // 'paid' | 'unpaid' | 'no_payment_required'
            if (!'paid'.equalsIgnoreCase(paymentStatus)) {
                log.warn("confirm-deposit refused: session ${sessionId} payment_status=${paymentStatus}")
                throw new IllegalStateException("Payment is not complete")
            }
            // Metadata and amount must match what we stored when we created
            // the session — refuses replays that target a different wallet.
            def metaWalletId = session.metadata?.get('walletId')
            if (metaWalletId == null || metaWalletId.toString() != tx.walletId.toString()) {
                log.error("confirm-deposit refused: session walletId=${metaWalletId} != tx.walletId=${tx.walletId}")
                throw new IllegalStateException("Session / wallet mismatch")
            }
            def expectedCents = (tx.amount * 100).longValue()
            if (session.amountTotal != null && session.amountTotal != expectedCents) {
                log.error("confirm-deposit refused: session amount=${session.amountTotal} != tx amount=${expectedCents}")
                throw new IllegalStateException("Amount mismatch")
            }
        }

        def wallet = walletRepository.findById(tx.walletId).orElseThrow()
        wallet.balance = wallet.balance + tx.amount
        walletRepository.save(wallet)

        tx.status = "COMPLETED"
        tx.updatedAt = System.currentTimeMillis()
        transactionRepository.save(tx)

        try {
            auditService?.log(AuditService.DEPOSIT_COMPLETE, null, null, tx.id,
                "Deposit \$${tx.amount} credited to wallet ${wallet.username} (stripe=${sessionId})")
        } catch (Exception ignore) {}
        log.info("Deposit \$${tx.amount} credited to wallet ${tx.walletId} (session ${sessionId})")
    }

    @Transactional
    void failTransaction(String sessionId, String reason) {
        def tx = transactionRepository.findByStripeReference(sessionId)
        if (tx == null) return
        tx.status = "FAILED"
        tx.description = (tx.description ?: "") + " — " + reason
        tx.updatedAt = System.currentTimeMillis()
        transactionRepository.save(tx)
    }

    /* ── DEV MODE FALLBACK ───────────────────────────────
     * When no real Stripe keys are configured, credit the wallet
     * immediately so the UI is usable. Returns a pseudo-URL the
     * frontend redirects to locally. */
    @Transactional
    Map devModeDeposit(Long walletId, BigDecimal amount) {
        def wallet = walletRepository.findById(walletId).orElseThrow()
        wallet.balance = wallet.balance + amount
        walletRepository.save(wallet)

        def tx = new Transaction(
            walletId:        walletId,
            type:            "DEPOSIT",
            status:          "COMPLETED",
            amount:          amount,
            currency:        currency.toUpperCase(),
            stripeReference: "dev_" + System.currentTimeMillis(),
            description:     "Dev-mode deposit (no Stripe keys configured)"
        )
        transactionRepository.save(tx)

        log.info("[DEV MODE] credited \$${amount} to wallet $walletId")
        [checkoutUrl: null, sessionId: tx.stripeReference, transactionId: tx.id, live: false, newBalance: wallet.balance]
    }
}
