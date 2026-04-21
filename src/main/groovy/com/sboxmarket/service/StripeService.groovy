package com.sboxmarket.service

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.stripe.Stripe
import com.stripe.model.Charge
import com.stripe.model.Dispute
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
import org.springframework.scheduling.annotation.Scheduled
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

    /** Rolling 24-hour deposit cap per wallet (batch 497). Defense against
     *  card-testing + stolen-card drain: even if an attacker obtains a
     *  valid card number, they can't push more than this past Stripe in
     *  24h before our fraud filters and the eventual chargeback wave
     *  catch up. Default $5,000/day — override via env for verified
     *  high-volume users (future feature). */
    @Value('${sbox.stripe.daily-deposit-cap:5000}')
    BigDecimal dailyDepositCap

    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired(required = false) AuditService auditService
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository
    @Autowired(required = false) EmailService emailService

    /** Last-webhook-received telemetry (batch 471). Surfaces in the
     *  admin Health tile so ops can spot silent Stripe outages —
     *  e.g. webhook secret rotated by mistake → no events arrive →
     *  deposits silently never credit. Volatile so the read from the
     *  HTTP worker thread sees the writer thread's update without
     *  needing a memory barrier. Both fields are 0 until the first
     *  webhook lands. `lastWebhookType` is the most recent event type. */
    private volatile long lastWebhookAt = 0L
    private volatile String lastWebhookType = null

    /** Snapshot for the admin Health endpoint. */
    Map getWebhookTelemetry() {
        [lastReceivedAt: lastWebhookAt, lastEventType: lastWebhookType]
    }

    /** Stripe-event-id dedupe set (batch 476). Stripe retries webhooks
     *  on transient 5xx — without this dedupe, every retry of a
     *  charge.dispute.created would re-spam every admin's inbox AND
     *  every retry of any deposit/refund event would log a duplicate
     *  audit row. Cap at 5000 with FIFO eviction so a long-running
     *  container can't accumulate unbounded state. */
    private static final int SEEN_EVENTS_CAP = 5000
    private final java.util.LinkedHashSet<String> seenEventIds = new java.util.LinkedHashSet<>()

    /** Card-testing detector (batch 501). Records timestamps of recent
     *  payment_intent.payment_failed events keyed by walletId. When a
     *  wallet accumulates >= CARD_TEST_THRESHOLD failures within
     *  CARD_TEST_WINDOW_MS, fan out a CARD_TESTING_DETECTED notification
     *  to every admin (deduped per wallet within the window so a single
     *  attack doesn't spam the bell every retry). The map is bounded by
     *  active-wallet count and prunes stale entries on every read. */
    private static final int  CARD_TEST_THRESHOLD  = 3
    private static final long CARD_TEST_WINDOW_MS  = 60L * 60L * 1000L
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.List<Long>> recentFailuresByWallet = new java.util.concurrent.ConcurrentHashMap<>()
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> cardTestAlertedAt = new java.util.concurrent.ConcurrentHashMap<>()

    /** Returns true if this event id was already processed (and the
     *  caller should short-circuit), false if it's new (the caller
     *  should proceed and the id is now recorded). Synchronized
     *  because Stripe webhooks land on the Tomcat worker pool — two
     *  retries can race in the same JVM. */
    private synchronized boolean alreadyProcessed(String eventId) {
        if (eventId == null || eventId.isEmpty()) return false
        if (seenEventIds.contains(eventId)) return true
        if (seenEventIds.size() >= SEEN_EVENTS_CAP) {
            def oldest = seenEventIds.iterator().next()
            seenEventIds.remove(oldest)
        }
        seenEventIds.add(eventId)
        return false
    }

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

        // Rolling 24-hour deposit cap (batch 497). Sums every DEPOSIT
        // row that either already credited the wallet (COMPLETED), is
        // currently in-flight (PENDING), or is under dispute (DISPUTED)
        // — so an attacker can't bypass the cap by queuing many
        // parallel Checkout sessions. Rejects with DEPOSIT_DAILY_CAP
        // and a clear message naming the remaining amount.
        def since = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)
        def used24h = transactionRepository.sumDepositsSince(walletId, since) ?: BigDecimal.ZERO
        def remaining = (dailyDepositCap ?: BigDecimal.ZERO) - used24h
        if (remaining < BigDecimal.ZERO) remaining = BigDecimal.ZERO
        if (amount > remaining) {
            // Explicit warn log so ops can grep for cap-hitters in the
            // aggregated logs — useful for both fraud triage (repeated
            // DEPOSIT_DAILY_CAP from one wallet = possible card testing)
            // and support (legitimate high-volume user asking for a raise).
            log.warn("DEPOSIT_DAILY_CAP hit for wallet ${walletId}: attempted \$${amount}, used \$${used24h}/\$${dailyDepositCap ?: 0} in 24h (remaining \$${remaining})")
            throw new com.sboxmarket.exception.BadRequestException("DEPOSIT_DAILY_CAP",
                "Daily deposit cap reached — \$${used24h.toPlainString()} of \$${(dailyDepositCap ?: BigDecimal.ZERO).toPlainString()} used in the last 24h. " +
                "\$${remaining.toPlainString()} remaining. Try again in 24 hours or open a support ticket for a temporary limit raise.")
        }

        long amountCents = (amount * 100).longValue()

        def params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            // Payment methods defer to the Stripe Dashboard config (batch 516).
            // Previously hard-coded to CARD only, which blocked Apple Pay /
            // Google Pay / Link / SEPA / etc. even when the merchant account
            // had them enabled. Stripe's default (no explicit
            // addPaymentMethodType) reads the method list from the Dashboard
            // so ops can expand the payment mix without a code deploy.
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
            // Mirror metadata onto the auto-created PaymentIntent (batch 501)
            // so payment_intent.payment_failed events can match back to a
            // wallet without walking Charge → Session → metadata. Powers the
            // card-testing detector — repeated declines from the same wallet
            // within a window get fanned out to admins.
            .setPaymentIntentData(
                SessionCreateParams.PaymentIntentData.builder()
                    .putMetadata("walletId", walletId.toString())
                    .putMetadata("type", "DEPOSIT")
                    .build())
            .build()

        // Idempotency key — Stripe guarantees repeated requests with the same
        // key return the original session rather than creating a second one.
        // Key is (walletId:amount:minute) — fast replays within the same
        // minute return the same Checkout Session, while slower replays create
        // a fresh one (a user who double-clicks won't pay twice).
        def idemKey = "dep_${walletId}_${(amountCents)}_${System.currentTimeMillis().intdiv(60_000)}"
        def reqOpts = RequestOptions.builder().setIdempotencyKey(idemKey).build()

        // Stripe SDK throws checked StripeException. Groovy doesn't
        // enforce checked exceptions at compile time, but Spring's CGLIB
        // @Transactional proxy DOES — an undeclared checked exception
        // gets wrapped in UndeclaredThrowableException → 500. Catch it
        // here and rethrow as a runtime exception the proxy can pass.
        def session
        try {
            session = Session.create(params, reqOpts)
        } catch (Exception e) {
            log.error("Stripe session creation failed for wallet $walletId: ${e.message}")
            throw new IllegalStateException("Stripe checkout session could not be created — try again", e)
        }

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
        // User-facing push + email (batch 523). Admin-initiated refunds
        // now reach the user via the same channels as dashboard-
        // initiated refunds (batch 522). Symmetry matters: a user
        // seeing their balance drop should get the same "refund issued"
        // signal regardless of whether ops clicked our admin button or
        // Stripe's Dashboard button.
        if (notificationService != null && steamUserRepository != null) {
            try {
                def uname = wallet.username ?: ''
                if (uname.startsWith('steam_')) {
                    def user = steamUserRepository.findBySteamId64(uname.substring('steam_'.length()))
                    if (user != null) {
                        // Batch 632: safePush so a bell failure can't roll
                        // back the admin-initiated refund + wallet credit.
                        notificationService.safePush(user.id, 'REFUND_ISSUED',
                            "Deposit refunded · \$${amount.toPlainString()}",
                            "A deposit on your wallet was refunded. New balance: \$${wallet.balance.toPlainString()}.",
                            refundTx.id, '/wallet')
                        if (emailService != null && emailService.canSendSecurityTo(user)) {
                            try {
                                emailService.sendRefundIssued(user.email, user.displayName, amount, wallet.balance)
                            } catch (Exception em) {
                                log.warn("REFUND_ISSUED email (admin path) failed for tx=${refundTx.id}: ${em.message}")
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("REFUND_ISSUED notify (admin path) failed for tx=${refundTx.id}: ${e.message}")
            }
        }
        log.info("Refund \$${amount} processed for deposit ${tx.id} (stripeRef=${refundId})")
        [refundId: refundTx.id, stripeRefund: refundId, newBalance: wallet.balance]
    }

    /* ── WITHDRAWAL ──────────────────────────────────────
     * Real payouts require Stripe Connect (Express accounts).
     * For this marketplace we debit the wallet and record a PENDING
     * withdrawal that an operator would fulfil off-platform. */
    /**
     * Self-cancel a PENDING withdrawal the user requested. Credits the
     * wallet back and flips the transaction to CANCELLED. Only works on
     * PENDING rows — COMPLETED ones have already gone through Stripe
     * Connect and need an admin-side refund, not a self-cancel.
     *
     * Caller must be the wallet owner — enforced at the controller
     * layer via session userId → walletId lookup.
     */
    @Transactional
    Map cancelPendingWithdrawal(Long walletId, Long txId) {
        def tx = transactionRepository.findById(txId)
            .orElseThrow { new NoSuchElementException("Transaction $txId not found") }
        if (tx.walletId != walletId) {
            throw new IllegalStateException("Not your withdrawal")
        }
        def type = (tx.type ?: '').toUpperCase()
        if (type != 'WITHDRAW' && type != 'WITHDRAWAL') {
            throw new IllegalArgumentException("Transaction is not a withdrawal")
        }
        if (tx.status != 'PENDING') {
            throw new IllegalStateException(
                "Withdrawal is ${tx.status}, not PENDING — cannot cancel. " +
                "If it already paid out, contact support for a reversal."
            )
        }
        def wallet = walletRepository.findById(walletId)
            .orElseThrow { new NoSuchElementException("Wallet $walletId not found") }
        // Credit the amount back exactly as requestWithdrawal debited it.
        wallet.balance = wallet.balance + (tx.amount ?: BigDecimal.ZERO)
        walletRepository.save(wallet)
        tx.status = 'CANCELLED'
        tx.description = ((tx.description ?: '') + ' · cancelled by user').take(500)
        transactionRepository.save(tx)
        try {
            auditService?.log(AuditService.WITHDRAW_SELF_CANCELLED, null, null, tx.id,
                "User cancelled pending withdrawal \$${tx.amount} from wallet ${wallet.username}")
        } catch (Exception ignore) {}
        log.info("User cancelled pending withdrawal ${tx.id} from wallet ${walletId}")
        [id: tx.id, status: tx.status, newBalance: wallet.balance]
    }

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

        log.info("Stripe webhook received: ${event.type} (id=${event.id})")
        lastWebhookAt = System.currentTimeMillis()
        lastWebhookType = event.type

        // Dedupe by Stripe event id (batch 476). Stripe retries webhooks
        // on transient 5xx; without this, a retry of charge.dispute.created
        // re-spams every admin, a retry of checkout.session.completed
        // would re-credit the wallet (already row-idempotent at the tx
        // status level, but the dedupe keeps the audit log clean too).
        // Returns 200 to Stripe so they stop retrying.
        if (alreadyProcessed(event.id)) {
            log.info("Stripe webhook ${event.id} already processed — skipping")
            return
        }

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
                // Card-testing detector (batch 501). Walks the PI metadata
                // back to a wallet id, accumulates failures in a sliding
                // 1h window, and pings every admin with a CARD_TESTING_DETECTED
                // notification when a single wallet crosses the threshold.
                try {
                    def pi = event.dataObjectDeserializer.object.orElse(null)
                    if (pi != null) handlePaymentIntentFailed(pi as com.stripe.model.PaymentIntent)
                } catch (Exception e) {
                    log.error("Failed to process payment_intent.payment_failed (event=${event.id}): ${e.message}", e)
                }
                break
            case "refund.created":
                // Dashboard-refund sync (batch 498). Previously a bare log.info —
                // if ops manually refunded from the Stripe Dashboard (instead of
                // via our /api/admin/refund endpoint), the wallet stayed
                // credited even though Stripe had clawed back the money,
                // letting the user withdraw funds that no longer existed in
                // our Stripe balance. Now the webhook reconciles the ledger.
                try {
                    def refund = (Refund) event.dataObjectDeserializer.object.orElse(null)
                    if (refund != null) handleRefundCreated(refund)
                } catch (Exception e) {
                    log.error("Failed to process refund.created (event=${event.id}): ${e.message}", e)
                }
                break
            case "charge.refunded":
                // Mirror — `charge.refunded` is also fired by Stripe on the same
                // refund event (ordering is not guaranteed). Idempotent
                // handler so whichever arrives first reconciles; the second
                // is a no-op.
                log.info("charge.refunded received (${event.id}) — refund.created handles reconciliation")
                break
            // Chargeback handling (batch 461). When a buyer files a
            // dispute with their bank, Stripe's `charge.dispute.created`
            // webhook fires. Without a handler, the disputed deposit
            // stays as COMPLETED in our DB and the buyer keeps the
            // wallet credit while we eat the chargeback. Now: flip
            // the matching deposit transaction to DISPUTED, log to
            // audit, and notify every admin so they can investigate +
            // ban/credit-clawback the offender.
            case "charge.dispute.created":
                try {
                    def dispute = (Dispute) event.dataObjectDeserializer.object.orElse(null)
                    if (dispute != null) handleChargebackOpened(dispute)
                } catch (Exception e) {
                    log.error("Failed to process charge.dispute.created (event=${event.id}): ${e.message}", e)
                }
                break
            case "charge.dispute.closed":
                // Dispute resolved by Stripe (batch 495). If we won the
                // dispute, auto-clear the hold so the user's withdraw
                // lock lifts and the tx reverts to COMPLETED. If we
                // lost, keep DISPUTED and notify admins so they can
                // decide on a wallet clawback before the user drains
                // the now-under-water balance.
                try {
                    def dispute = (Dispute) event.dataObjectDeserializer.object.orElse(null)
                    if (dispute != null) handleChargebackClosed(dispute)
                } catch (Exception e) {
                    log.error("Failed to process charge.dispute.closed (event=${event.id}): ${e.message}", e)
                }
                break
            default:
                log.debug("Ignoring Stripe event: ${event.type}")
        }
    }

    /**
     * Flag a disputed deposit so staff can investigate. Does NOT auto-
     * debit the user's wallet — the dispute might be reversed (the
     * buyer dropped the claim), and a forced clawback could leave a
     * legitimate seller short. Staff use the admin panel to credit-
     * clawback or refund as the dispute resolves.
     *
     * Match by `stripeReference == dispute.charge` first; falls back to
     * `paymentIntent` for newer Stripe object models. Transactional so
     * the tx-flip + audit-log are atomic.
     */
    /**
     * Look up a DEPOSIT transaction by its captured payment_intent tag
     * (batch 494). `completeDeposit` stamps the description with
     * " [pi:<id>]" so the dispute handler can match a Stripe dispute
     * back to the originating transaction even though dispute.charge
     * references the charge id (ch_*), not our stored session id (cs_*).
     * Falls back gracefully to a LIKE query which is fine at our scale
     * (deposits table is small — low-volume indexed type/status narrows
     * the LIKE scan further).
     */
    private Transaction findDepositByPaymentIntent(String paymentIntentId) {
        if (!paymentIntentId) return null
        def rows = transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'COMPLETED')
        return (rows ?: []).find { (it.description ?: '').contains("[pi:${paymentIntentId}]") }
    }

    /**
     * Reconcile a Stripe-dashboard-initiated refund (batch 498). When ops
     * clicks "Refund" in the Stripe Dashboard instead of going through our
     * /api/admin/refund endpoint, Stripe fires `refund.created` but our
     * DB stays out of sync — the wallet is still credited, letting the
     * user withdraw funds that have already been clawed back. This
     * handler closes that gap by walking the refund's paymentIntent →
     * original deposit tx, then creating a matching REFUND row and
     * debiting the wallet (exactly what our internal `refundDeposit`
     * does on the admin path).
     *
     * Idempotent: if we've already recorded a REFUND row with this Stripe
     * refund id (either from our own admin path or from a duplicate webhook
     * retry), we skip.
     */
    @Transactional
    void handleRefundCreated(Refund refund) {
        if (refund == null) return
        def refundId = refund.id
        if (!refundId) {
            log.warn("refund.created received with no id — ignoring")
            return
        }
        // Dedupe — our own refundDeposit path has already written a
        // REFUND tx with the stripeReference set to this refund id.
        def existing = transactionRepository.findByStripeReference(refundId)
        if (existing != null) {
            log.info("Refund ${refundId} already reconciled (tx=${existing.id}) — webhook is a no-op")
            return
        }
        // Resolve the originating deposit via paymentIntent → [pi:<id>]
        // tag lookup (same walk as the chargeback handler).
        String paymentIntentId = refund.paymentIntent
        if (!paymentIntentId && refund.charge) {
            try {
                def ch = Charge.retrieve(refund.charge)
                paymentIntentId = ch?.paymentIntent
            } catch (Exception ignored) { /* fall through */ }
        }
        def depositTx = paymentIntentId ? findDepositByPaymentIntent(paymentIntentId) : null
        if (depositTx == null) {
            log.warn("refund.created ${refundId} could not be matched to a deposit tx (pi=${paymentIntentId}) — manual reconciliation required")
            return
        }
        // Amount is in cents; coerce to BigDecimal dollars.
        BigDecimal amount = BigDecimal.ZERO
        if (refund.amount != null) {
            amount = new BigDecimal(refund.amount).divide(new BigDecimal(100))
        }
        if (amount <= BigDecimal.ZERO) {
            log.warn("refund.created ${refundId} arrived with non-positive amount=${refund.amount} — ignoring")
            return
        }
        def wallet = walletRepository.findById(depositTx.walletId).orElse(null)
        if (wallet == null) {
            log.error("refund.created ${refundId} wallet ${depositTx.walletId} not found — cannot debit")
            return
        }
        // Defensive: if the wallet is already drained below the refund
        // amount, we still record the REFUND for ledger completeness but
        // clamp the wallet at 0 (never go negative). The resulting gap
        // becomes an auditable negative "owed" position for admins to
        // chase via the user's trade history.
        def debit = amount.min(wallet.balance)
        wallet.balance = wallet.balance - debit
        if (wallet.balance < BigDecimal.ZERO) wallet.balance = BigDecimal.ZERO
        walletRepository.save(wallet)

        def refundTx = new Transaction(
            walletId:        depositTx.walletId,
            type:            'REFUND',
            status:          'COMPLETED',
            amount:          amount,
            currency:        depositTx.currency,
            stripeReference: refundId,
            description:     "Refund of deposit #${depositTx.id} (via Stripe Dashboard)"
        )
        transactionRepository.save(refundTx)

        try {
            auditService?.log(AuditService.REFUND_ISSUED, null, null, refundTx.id,
                "Dashboard refund \$${amount} of deposit ${depositTx.id} (stripeRef=${refundId}, wallet debit=\$${debit})")
        } catch (Exception ignored) {}
        // User-facing notification so they know money moved out of the
        // wallet — otherwise their balance silently drops and they file
        // a support ticket.
        if (notificationService != null && steamUserRepository != null) {
            try {
                def uname = wallet.username ?: ''
                if (uname.startsWith('steam_')) {
                    def user = steamUserRepository.findBySteamId64(uname.substring('steam_'.length()))
                    if (user != null) {
                        // Batch 632: safePush so a bell failure can't roll back
                        // the webhook-driven refund + wallet debit.
                        notificationService.safePush(user.id, 'REFUND_ISSUED',
                            "Deposit refunded · \$${amount.toPlainString()}",
                            "A deposit on your wallet was refunded. New balance: \$${wallet.balance.toPlainString()}.",
                            refundTx.id, '/wallet')
                        // Email (batch 522) — refund is a money-out
                        // event on the same tier as withdrawal approval,
                        // deserves an email so the user sees the balance
                        // drop with context even if the bell was missed.
                        if (emailService != null && emailService.canSendSecurityTo(user)) {
                            try {
                                emailService.sendRefundIssued(user.email, user.displayName, amount, wallet.balance)
                            } catch (Exception em) {
                                log.warn("REFUND_ISSUED email failed for tx=${refundTx.id}: ${em.message}")
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Dashboard-refund notify failed for tx=${refundTx.id}: ${e.message}")
            }
        }
        log.info("Reconciled dashboard refund ${refundId} for deposit ${depositTx.id}: debited \$${debit} (amount=\$${amount})")
    }

    /**
     * Card-testing detector (batch 501). Each `payment_intent.payment_failed`
     * is recorded against the wallet id stamped in the PI metadata at
     * Checkout-Session creation time. When a wallet's failure count in
     * the trailing CARD_TEST_WINDOW_MS hits CARD_TEST_THRESHOLD, fan out
     * a `CARD_TESTING_DETECTED` notification to every admin so they can
     * preemptively freeze the wallet before the attacker finds a working
     * card. Per-wallet alert dedupe so a single attack doesn't spam the
     * bell on every retry attempt — re-arms after the window slides past.
     *
     * Metadata-only flow (no DB writes) keeps the hot path cheap; the
     * map is bounded by active-attacker count, not platform user count,
     * and stale entries get pruned on every read.
     */
    void handlePaymentIntentFailed(com.stripe.model.PaymentIntent pi) {
        if (pi == null) return
        String walletIdStr = pi.metadata?.get('walletId')
        if (!walletIdStr) {
            log.info("payment_intent.payment_failed ${pi.id} has no walletId metadata — skipping fraud track")
            return
        }
        Long walletId
        try { walletId = Long.valueOf(walletIdStr) }
        catch (NumberFormatException ignored) { return }
        long now = System.currentTimeMillis()
        long windowStart = now - CARD_TEST_WINDOW_MS
        // Append + prune in one critical section per wallet. The list is
        // small (a single attack rarely exceeds 20 attempts before we
        // alert and admins act), so an O(n) prune is fine.
        def updated = recentFailuresByWallet.compute(walletId, { _, existing ->
            def list = existing ?: new java.util.ArrayList<Long>()
            list.add(now)
            list.removeIf { (it as Long) < windowStart }
            list
        })
        log.warn("payment_intent.payment_failed wallet=${walletId} pi=${pi.id} reason=${pi.lastPaymentError?.code ?: 'unknown'} (${updated.size()} failures in last ${CARD_TEST_WINDOW_MS / 60_000L}min)")
        if (updated.size() < CARD_TEST_THRESHOLD) return
        // Per-wallet alert dedupe — only fire once per window.
        def lastAlertedAt = cardTestAlertedAt.get(walletId)
        if (lastAlertedAt != null && lastAlertedAt > windowStart) return
        cardTestAlertedAt.put(walletId, now)
        // Fan out to admins. Same shape as chargeback fan-out — role-
        // indexed query, per-row try/catch, kind="CARD_TESTING_DETECTED".
        if (notificationService != null && steamUserRepository != null) {
            try {
                def reason = pi.lastPaymentError?.code ?: 'unknown'
                // Batch 632: safePush — a single admin push failure must
                // not break the fan-out for the rest of the admin pool.
                steamUserRepository.findByRole('ADMIN').each { admin ->
                    notificationService.safePush(admin.id, 'CARD_TESTING_DETECTED',
                        "⚠ Card-testing on wallet ${walletId}",
                        "${updated.size()} declined deposit attempts in the last ${CARD_TEST_WINDOW_MS / 60_000L}min " +
                            "(latest: ${reason}). Consider freezing the wallet via the Users tab before the attacker finds a working card.",
                        walletId,
                        '/admin?tab=users')
                }
            } catch (Exception e) {
                log.warn("CARD_TESTING_DETECTED admin fan-out failed for wallet ${walletId}: ${e.message}")
            }
        }
    }

    @Transactional
    void handleChargebackOpened(Dispute dispute) {
        if (dispute == null) return
        // Stripe disputes carry a `charge` id. Our deposit transaction
        // stores the Checkout Session id (cs_*), not the charge id
        // (ch_*) — so `findByStripeReference(charge)` never matches
        // a real session-backed deposit. Batch 494: walk the chain
        // chargeId → Charge.paymentIntent → Session (via retrieve
        // sessions with paymentIntent filter). The Stripe Dispute
        // object also carries `paymentIntent` on recent API versions,
        // so we prefer that when present and only fall back to the
        // extra Charge retrieve when it's missing.
        String chargeId = dispute.charge
        String paymentIntentId = null
        try { paymentIntentId = dispute.paymentIntent } catch (Exception ignored) {}
        Transaction tx = null
        // Try 1 — dispute.paymentIntent directly.
        if (paymentIntentId) {
            tx = findDepositByPaymentIntent(paymentIntentId)
        }
        // Try 2 — walk chargeId → charge.paymentIntent via Stripe API.
        if (tx == null && chargeId) {
            try {
                def charge = com.stripe.model.Charge.retrieve(chargeId)
                def pi = charge?.paymentIntent
                if (pi) {
                    paymentIntentId = pi
                    tx = findDepositByPaymentIntent(pi)
                }
            } catch (Exception e) {
                log.warn("Chargeback charge.retrieve(${chargeId}) failed: ${e.message}")
            }
        }
        // Try 3 — legacy fallback, matches rows created before the
        // paymentIntent search was wired (stripeReference might be
        // the charge id in old test fixtures).
        if (tx == null && chargeId) {
            tx = transactionRepository.findByStripeReference(chargeId)
        }
        // Idempotency gate (batch 476). Stripe retries webhooks on
        // transient 5xx — without this check, every retry of the same
        // charge.dispute.created event would re-audit + re-notify every
        // admin, spamming the bell. We only fire the side effects on
        // the FIRST observation (tx is non-DISPUTED before this call,
        // OR there's no matching tx — the latter still gets one alert).
        boolean isFirstObservation = (tx == null || tx.status != 'DISPUTED')
        // Mark the tx as disputed (or audit-only if not found).
        if (tx != null && tx.status != 'DISPUTED') {
            tx.status = 'DISPUTED'
            tx.description = (tx.description ?: '') + " — DISPUTED via Stripe (${dispute.id})"
            tx.updatedAt = System.currentTimeMillis()
            transactionRepository.save(tx)
        }
        def amount = dispute.amount != null ? (dispute.amount / 100.0) : 0
        if (!isFirstObservation) {
            log.info("CHARGEBACK retry — dispute=${dispute.id} already on file (tx=${tx?.id}), skipping audit + notify")
            return
        }
        log.error("CHARGEBACK opened — Stripe dispute=${dispute.id} amount=\$${amount} reason=${dispute.reason} charge=${chargeId} matchedTx=${tx?.id}")
        try {
            auditService?.log('CHARGEBACK_OPENED', null, null, tx?.id,
                "Stripe dispute ${dispute.id} on charge ${chargeId} for \$${amount} (reason=${dispute.reason}). Admin review required.")
        } catch (Exception e) {
            log.warn("Chargeback audit-log failed: ${e.message}")
        }
        // Notify every admin so the dispute hits at least one inbox.
        // Admins are typically a small set (1-3 in production) so a fan-
        // out push is fine. Failure-tolerant: a notify miss doesn't
        // un-flag the transaction.
        if (notificationService != null && steamUserRepository != null) {
            try {
                // Role-indexed fetch (batch 483) — previously this was
                // `findAll().findAll { role == 'ADMIN' }` which full-
                // scanned the users table on every chargeback. Now backed
                // by idx_steam_users_role so the cost is O(admin count),
                // not O(total users).
                // Batch 632: safePush — the @Transactional marker on
                // handleChargebackOpened means an uncaught push exception
                // would roll back the tx.status=DISPUTED flip. safePush
                // keeps the dispute on record even if the admin bell is
                // unhappy.
                steamUserRepository.findByRole('ADMIN').each { admin ->
                    notificationService.safePush(admin.id, 'CHARGEBACK_OPENED',
                        "⚠ Chargeback opened · \$${amount}",
                        "Stripe dispute ${dispute.id} (reason: ${dispute.reason}). Investigate the user and decide on a clawback.",
                        tx?.id,
                        '/admin?tab=disputes')
                }
            } catch (Exception e) {
                log.warn("Chargeback admin fan-out failed: ${e.message}")
            }
        }
        // User-facing notification (batch 521). Two scenarios:
        //  - Friendly-fraud (user filed the chargeback): tells them
        //    their wallet is on hold pending resolution.
        //  - Compromised account (someone else used the user's card):
        //    flags the dispute so they can contact their bank + us.
        // Push + email because this is a money-gate event on par with
        // WITHDRAWAL_REJECTED. Fires once per dispute via the isFirstObservation
        // gate above.
        if (tx != null && notificationService != null && steamUserRepository != null) {
            try {
                def wallet = walletRepository.findById(tx.walletId).orElse(null)
                def uname = wallet?.username ?: ''
                if (uname.startsWith('steam_')) {
                    def user = steamUserRepository.findBySteamId64(uname.substring('steam_'.length()))
                    if (user != null) {
                        // Batch 632: safePush — same reasoning as the admin
                        // fan-out above, the @Transactional DISPUTED flip
                        // must not be undone by a user-side push failure.
                        notificationService.safePush(user.id, 'CHARGEBACK_OPENED',
                            "Withdrawal hold · deposit disputed",
                            "A chargeback was filed against your \$${tx.amount?.toPlainString() ?: amount} deposit. " +
                            "Withdrawals, purchases, and offers are paused until the dispute resolves. " +
                            "If this wasn't you, contact your bank and open a support ticket immediately.",
                            tx.id, '/wallet')
                        if (emailService != null && emailService.canSendSecurityTo(user)) {
                            try {
                                emailService.sendChargebackOpened(user.email, user.displayName, tx.amount)
                            } catch (Exception em) {
                                log.warn("CHARGEBACK_OPENED email failed for user ${user.id}: ${em.message}")
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("CHARGEBACK_OPENED user-notify failed for tx=${tx.id}: ${e.message}")
            }
        }
    }

    /**
     * Dispute resolved by Stripe (batch 495). Outcomes:
     *   - WON         — bank sided with us, the charge stands. We
     *                   auto-clear the hold so the user can withdraw
     *                   again and the tx returns to COMPLETED.
     *   - LOST        — bank sided with the cardholder; funds are
     *                   debited from our Stripe balance. Keep the tx
     *                   DISPUTED so the withdrawal hold stays in place
     *                   and notify admins to decide on a wallet
     *                   clawback (user might still have the balance).
     *   - WARNING_*   — transitional states; leave DISPUTED.
     *   - OTHER       — log + notify for manual review.
     *
     * Matches the same three-tier lookup as handleChargebackOpened so
     * we find the originating tx regardless of which Stripe API
     * version populated the dispute object.
     */
    @Transactional
    void handleChargebackClosed(Dispute dispute) {
        if (dispute == null) return
        String chargeId = dispute.charge
        String paymentIntentId = null
        try { paymentIntentId = dispute.paymentIntent } catch (Exception ignored) {}
        Transaction tx = null
        if (paymentIntentId) tx = findDepositByPaymentIntent(paymentIntentId)
        if (tx == null && chargeId) {
            try {
                def charge = com.stripe.model.Charge.retrieve(chargeId)
                def pi = charge?.paymentIntent
                if (pi) tx = findDepositByPaymentIntent(pi)
            } catch (Exception ignored) {}
        }
        if (tx == null && chargeId) tx = transactionRepository.findByStripeReference(chargeId)

        def status = (dispute.status ?: '').toLowerCase()
        def amount = dispute.amount != null ? (dispute.amount / 100.0) : 0
        if (status == 'won') {
            if (tx != null && tx.status == 'DISPUTED') {
                tx.status = 'COMPLETED'
                tx.description = (tx.description ?: '') + " — dispute WON via Stripe (${dispute.id})"
                tx.updatedAt = System.currentTimeMillis()
                transactionRepository.save(tx)
                log.info("Chargeback ${dispute.id} WON — tx=${tx.id} back to COMPLETED, withdrawal hold lifts")
                // User-facing notification (batch 496) — same as the
                // admin-override clear path. Only fires when this was
                // the LAST active dispute on their wallet.
                try {
                    long stillHeld = transactionRepository.countActiveDisputedDeposits(tx.walletId)
                    if (stillHeld == 0L && notificationService != null && steamUserRepository != null) {
                        def wallet = walletRepository.findById(tx.walletId).orElse(null)
                        if (wallet != null) {
                            def uname = wallet.username ?: ''
                            if (uname.startsWith('steam_')) {
                                def user = steamUserRepository.findBySteamId64(uname.substring('steam_'.length()))
                                if (user != null) {
                                    // Batch 632: safePush — @Transactional
                                    // WON path already flipped the tx back to
                                    // COMPLETED; a push failure must not
                                    // reinstate the DISPUTED hold.
                                    notificationService.safePush(user.id, 'DISPUTE_CLEARED',
                                        "Withdrawals re-enabled",
                                        "A deposit dispute on your wallet was resolved in your favour. You can now withdraw again.",
                                        tx.id, '/wallet')
                                    // Email too (batch 520). Auto-reconcile
                                    // fires from a Stripe webhook at an
                                    // unpredictable time — the user is
                                    // unlikely to be on the site, so push
                                    // alone can miss. Mirrors the
                                    // AdminService.clearDisputeHold email.
                                    if (emailService != null && emailService.canSendSecurityTo(user)) {
                                        try {
                                            emailService.sendDisputeCleared(user.email, user.displayName)
                                        } catch (Exception em) {
                                            log.warn("DISPUTE_CLEARED email (auto-reconcile) failed for tx=${tx.id}: ${em.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("DISPUTE_CLEARED user-notify failed for tx=${tx.id}: ${e.message}")
                }
            }
            try {
                auditService?.log('DISPUTE_CLEARED', null, null, tx?.id,
                    "Stripe dispute ${dispute.id} resolved WON — hold lifted automatically")
            } catch (Exception e) { log.warn("DISPUTE_CLEARED audit failed: ${e.message}") }
            return
        }
        // LOST or any other terminal resolution against us — keep the
        // tx DISPUTED (withdrawal hold remains), but alert admins that
        // the resolution is in.
        log.warn("Chargeback ${dispute.id} closed with status=${status} amount=\$${amount} matchedTx=${tx?.id} — keeping DISPUTED, admin review needed")
        if (notificationService != null && steamUserRepository != null) {
            try {
                // Batch 632: safePush — handleChargebackClosed is
                // @Transactional; a push failure here must not roll back
                // the tx state already saved in the WON branch above.
                steamUserRepository.findByRole('ADMIN').each { admin ->
                    notificationService.safePush(admin.id, 'CHARGEBACK_OPENED',
                        "Chargeback ${status.toUpperCase()} · \$${amount}",
                        "Stripe dispute ${dispute.id} closed with status=${status}. Review the user and decide on a wallet clawback before the hold is lifted.",
                        tx?.id, '/admin?tab=disputes')
                }
            } catch (Exception e) {
                log.warn("Chargeback-closed admin fan-out failed: ${e.message}")
            }
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

        // Capture the payment_intent id (batch 494). Needed so the
        // chargeback handler can walk `dispute.paymentIntent → tx`
        // via description-based lookup. Stripe's Session → Charge
        // chain doesn't preserve custom metadata all the way down,
        // so we stamp it here while we still have the Session object.
        String paymentIntentId = null
        if (isLive() && sessionId.startsWith('cs_')) {
            try {
                def freshSession = Session.retrieve(sessionId)
                paymentIntentId = freshSession?.paymentIntent
            } catch (Exception ignored) { /* tolerant — chargeback lookup has a fallback */ }
        }
        tx.status = "COMPLETED"
        tx.updatedAt = System.currentTimeMillis()
        if (paymentIntentId) {
            def tag = " [pi:${paymentIntentId}]"
            if (!(tx.description ?: '').contains(tag)) {
                tx.description = (tx.description ?: '') + tag
            }
        }
        transactionRepository.save(tx)

        try {
            auditService?.log(AuditService.DEPOSIT_COMPLETE, null, null, tx.id,
                "Deposit \$${tx.amount} credited to wallet ${wallet.username} (stripe=${sessionId})")
        } catch (Exception ignore) {}
        // Notification push (batch 457) — closes the silent-success gap
        // where a user who deposited and switched tabs got NO signal that
        // their wallet had been credited. Bell + tab-title now show the
        // event the moment Stripe's webhook lands. Resolves user via
        // Wallet.username = "steam_<steamId64>" → SteamUser. Failure-
        // tolerant: a notify miss is logged and the deposit still
        // completes (the wallet balance + tx row are the source of truth).
        if (notificationService != null && steamUserRepository != null) {
            // Batch 632: safePush — deposit money is already credited above;
            // a push failure must not roll back the wallet credit + audit row.
            try {
                def uname = wallet.username ?: ''
                if (uname.startsWith('steam_')) {
                    def steamId = uname.substring('steam_'.length())
                    def user = steamUserRepository.findBySteamId64(steamId)
                    if (user != null) {
                        notificationService.safePush(user.id, 'DEPOSIT_COMPLETE',
                            "Deposit complete · +\$${tx.amount?.toPlainString() ?: '0.00'}",
                            "New balance: \$${wallet.balance?.toPlainString() ?: '0.00'}",
                            tx.id,
                            '/wallet')
                    }
                }
            } catch (Exception e) {
                log.warn("Deposit-complete lookup failed for tx=${tx.id}: ${e.message}")
            }
        }
        log.info("Deposit \$${tx.amount} credited to wallet ${tx.walletId} (session ${sessionId})")
    }

    @Transactional
    void failTransaction(String sessionId, String reason) {
        def tx = transactionRepository.findByStripeReference(sessionId)
        if (tx == null) return
        // Idempotency — session.expired can arrive after a race where
        // we already flipped the tx via some other path. Don't overwrite
        // non-PENDING rows with FAILED.
        if (tx.status != 'PENDING') return
        tx.status = "FAILED"
        tx.description = (tx.description ?: "") + " — " + reason
        tx.updatedAt = System.currentTimeMillis()
        transactionRepository.save(tx)
        // DEPOSIT_EXPIRED notification (batch 519). The user may have
        // left the Stripe Checkout tab open for hours before Stripe's
        // 24h expiry fires — without this ping, they only learn about
        // the failed deposit if they manually open the wallet. The
        // DEPOSIT_EXPIRED kind is already in the bell UI routing tables.
        // Silent-fail so a notify outage doesn't roll back the status
        // flip (the tx row is the source of truth).
        if (tx.type == 'DEPOSIT' && notificationService != null && steamUserRepository != null) {
            try {
                def wallet = walletRepository.findById(tx.walletId).orElse(null)
                def uname = wallet?.username ?: ''
                if (uname.startsWith('steam_')) {
                    def user = steamUserRepository.findBySteamId64(uname.substring('steam_'.length()))
                    if (user != null) {
                        // Batch 632: safePush — failTransaction is
                        // @Transactional and already flipped the tx to
                        // FAILED; don't let a push failure un-fail it.
                        notificationService.safePush(user.id, 'DEPOSIT_EXPIRED',
                            "Deposit didn't go through · \$${tx.amount?.toPlainString() ?: '0'}",
                            "The Stripe Checkout session ${reason == 'expired' ? 'expired' : "failed (${reason})"}. No money was debited — try again from the Wallet page.",
                            tx.id,
                            '/wallet')
                    }
                }
            } catch (Exception e) {
                log.warn("DEPOSIT_EXPIRED notify failed for tx=${tx.id}: ${e.message}")
            }
        }
    }

    /* ── DEV MODE FALLBACK ───────────────────────────────
     * When no real Stripe keys are configured, credit the wallet
     * immediately so the UI is usable. Returns a pseudo-URL the
     * frontend redirects to locally. */
    @Transactional
    Map devModeDeposit(Long walletId, BigDecimal amount) {
        // Same positive + cap validation as the live Stripe path. Without
        // these guards, a dev deployment without Stripe keys (or a
        // misconfigured prod rollout that lost its keys) would accept
        // any amount — including $999,999,999 — via this fallback.
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new IllegalArgumentException("Deposit amount must be positive")
        }
        if (amount > new BigDecimal("10000")) {
            throw new IllegalArgumentException("Deposit amount exceeds \$10,000 limit")
        }
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

    /**
     * Stale-PENDING-deposit sweeper (batch 403). Stripe Checkout sessions
     * auto-expire at 24h on Stripe's side — after that the session can no
     * longer complete and its matching PENDING transaction is dead weight
     * (shows a ghost "Deposit pending · $X" chip on the wallet hero for
     * a user who abandoned the flow). We flip those rows to EXPIRED so
     * the UI stops showing the ghost, and log a one-liner for ops.
     *
     * Cutoff is 48h to give Stripe's own expiry + any webhook retries a
     * comfortable margin. Runs every 4 hours. Idempotent — already-EXPIRED
     * rows are not returned by `findStalePending` (PENDING-only filter).
     */
    @Scheduled(fixedDelay = 4L * 60L * 60L * 1000L, initialDelay = 5L * 60L * 1000L)
    @Transactional
    void sweepStalePendingDeposits() {
        def cutoff = System.currentTimeMillis() - (48L * 60L * 60L * 1000L)
        def stale = transactionRepository.findStalePending('DEPOSIT', cutoff)
        if (stale.isEmpty()) return
        stale.each { tx ->
            tx.status = 'EXPIRED'
            tx.description = (tx.description ?: '') +
                ' — auto-expired after 48h without completion'
        }
        transactionRepository.saveAll(stale)
        log.info("Deposit sweeper: flipped ${stale.size()} stale PENDING deposits to EXPIRED")

        // Notify each affected user so the pending chip disappearing
        // from the Wallet hero isn't a silent event. Resolves userId
        // via Wallet.username (= "steam_<steamId64>") → SteamUser.
        // Failure on any one row is logged and continues — sweeper
        // keeps moving even if one lookup fails.
        if (notificationService == null || steamUserRepository == null) return
        // Batch 632: safePush — sweeper is @Transactional across the whole
        // batch save; one bad user lookup must not roll back everyone else's
        // flip to EXPIRED. The wallet/user-lookup try/catch stays (safePush
        // only covers the notify call, not the repository lookups).
        stale.each { tx ->
            try {
                def wallet = walletRepository.findById(tx.walletId).orElse(null)
                if (wallet == null) return
                def uname = wallet.username ?: ''
                if (!uname.startsWith('steam_')) return
                def steamId = uname.substring('steam_'.length())
                def user = steamUserRepository.findBySteamId64(steamId)
                if (user == null) return
                notificationService.safePush(user.id, 'DEPOSIT_EXPIRED',
                    "Deposit expired",
                    "Your \$${tx.amount?.toPlainString() ?: '0.00'} deposit was auto-expired after 48h without completion. If you still want to top up, start a fresh deposit.",
                    tx.id,
                    '/wallet')
            } catch (Exception e) {
                log.warn("Deposit-expired lookup failed for tx=${tx.id}: ${e.message}")
            }
        }
    }
}
