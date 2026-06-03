package com.sboxmarket.service

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
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

    /* ── Stripe Connect (Express) — real money-out rail ───────────────
     * Sellers onboard onto a Stripe Connect Express account (which is
     * also the KYC / identity step); withdrawals then move real money via
     * a Stripe Transfer from the platform balance to that connected
     * account. All four settings have safe @Value defaults so dev / CI
     * (no env) still boots; prod overrides via the env vars named below.
     */

    /** Where Stripe sends the user back after they finish (or abandon)
     *  the hosted Connect onboarding flow. Defaults under the app's
     *  public URL so a fresh dev boot has a working return target; prod
     *  sets STRIPE_CONNECT_RETURN_URL. The `connect=done` query lets the
     *  SPA re-poll /api/wallet/connect/status on return. */
    @Value('${stripe.connect.return-url:${app.public-url}/wallet?connect=done}')
    String connectReturnUrl

    /** Where Stripe sends the user if the onboarding AccountLink expired
     *  or is otherwise stale and must be regenerated. The SPA hits
     *  /api/wallet/connect/onboard again to mint a fresh link. Prod sets
     *  STRIPE_CONNECT_REFRESH_URL. */
    @Value('${stripe.connect.refresh-url:${app.public-url}/wallet?connect=refresh}')
    String connectRefreshUrl

    /** Two-letter ISO country for the connected account (Stripe requires
     *  it at Account.create). Defaults to US; override per-deployment via
     *  STRIPE_CONNECT_COUNTRY. A future enhancement could derive this
     *  per-user, but a single platform-country default matches how the
     *  rest of the money model (USD-only) is configured today. */
    @Value('${stripe.connect.country:US}')
    String connectCountry

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

    /** Cluster-wide webhook-event idempotency ledger (wave 147). Optional
     *  so existing unit specs that build the service with
     *  `new StripeService(...)` and no Spring context keep working — in
     *  that case the per-JVM {@link #seenEventIds} set is the only gate
     *  (single-process behaviour, identical to the pre-fix posture). When
     *  wired (every prod boot wires it), the DB row is the AUTHORITATIVE
     *  cross-pod gate and the in-memory set is a fast-path cache.
     *
     *  Without this, a Stripe webhook retry routed to a DIFFERENT pod than
     *  the original sees an empty per-JVM `seenEventIds` and re-runs the
     *  side-effecting handler — a re-delivered charge.dispute.created
     *  re-spams every admin's bell, a re-delivered deposit/refund event
     *  logs a duplicate audit row. (The money path is independently safe —
     *  completeDeposit / the dispute + refund handlers are row-idempotent
     *  on the tx status / stripeReference they set — so this gate is purely
     *  about not RE-FIRING notifications + audit on a cross-pod retry.)
     *  Same wave-112-style claim shape as
     *  {@link com.sboxmarket.repository.FraudSignalClaimRepository}. */
    @Autowired(required = false) com.sboxmarket.repository.ProcessedStripeEventRepository processedStripeEventRepository

    /** Cluster-wide card-testing failure ledger (wave 148). Optional so
     *  existing unit specs that build the service with `new StripeService(...)`
     *  and no Spring context keep working — in that case the per-JVM
     *  {@link #recentFailuresByWallet} map is the only counter (single-process
     *  behaviour, identical to the pre-fix posture). When wired (every prod
     *  boot wires it), the DB COUNT is the AUTHORITATIVE cross-pod source of
     *  truth and the in-memory map is a fast-path cache.
     *
     *  Without this, the card-testing detector counted payment_intent.payment_failed
     *  events in a PER-POD map — so an attacker spreading declines across pods
     *  (Stripe webhook deliveries are load-balanced) accumulated only a
     *  fraction of the count on any single pod, never crossed the per-pod
     *  threshold, and the CARD_TESTING_DETECTED alert never fired. The DB row
     *  + {@link com.sboxmarket.repository.WalletPaymentFailureRepository#countByWalletIdAndFailedAtAfter}
     *  aggregate sums across every pod so the threshold sees the full
     *  cross-pod failure volume. Same wave-147-style shape as
     *  {@link #processedStripeEventRepository}. */
    @Autowired(required = false) com.sboxmarket.repository.WalletPaymentFailureRepository walletPaymentFailureRepository

    /** Cluster-wide alert-dedup claim (wave 148). Reuses the existing
     *  fraud_signal_claims ledger (V70) so a CARD_TESTING_DETECTED alert
     *  fans out to admins exactly ONCE per wallet per window CLUSTER-wide —
     *  not once per pod. Without it, every pod that pushes a wallet over the
     *  (now cross-pod) threshold would independently fan the bell to every
     *  admin (N pods = N× the noise). The signature is
     *  {@code card_test_alert:<walletId>:<windowBucket>}; whichever pod's
     *  INSERT lands first wins the claim and the losers bail before the
     *  fan-out (catching DataIntegrityViolationException on the UNIQUE index,
     *  same as {@link #claimStripeEvent} and
     *  FraudAnalysisService.sweepAndPushFraudSignals). Optional so context-less
     *  unit specs degrade to the per-JVM {@link #cardTestAlertedAt} dedupe. */
    @Autowired(required = false) com.sboxmarket.repository.FraudSignalClaimRepository fraudSignalClaimRepository

    /** Used to run the card-test fraud-tracking DB writes (insert + count +
     *  prune + alert-claim) in their own REQUIRES_NEW transaction (wave 148),
     *  so a fraud-tracking failure — INCLUDING a caught exception that would
     *  otherwise mark a transaction rollback-only — is fully CONTAINED and can
     *  never poison the surrounding webhook transaction in handleWebhookEvent.
     *  This is the deposit/withdraw-untouching, money-math-untouching
     *  isolation the spec requires: a failure in fraud-COUNT must never break
     *  Stripe webhook handling. By the time handlePaymentIntentFailed runs,
     *  the payment_intent.payment_failed switch case has done NO money
     *  mutation (a failed PI never credits a wallet), so this inner
     *  transaction holds no money-row locks. Optional (same posture as
     *  AuditService.transactionManager) — when unwired (unit tests / context-
     *  less builds) the tracking work runs inline, which is the degraded
     *  single-process behaviour the specs exercise. */
    @Autowired(required = false) org.springframework.transaction.PlatformTransactionManager transactionManager

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
     *  container can't accumulate unbounded state.
     *
     *  Wave 147 scope note: this set is PER-JVM and so is blind to
     *  sibling pods — a Stripe retry routed to a different pod sees an
     *  empty set here. The AUTHORITATIVE cross-pod gate is now the
     *  {@link #processedStripeEventRepository} DB row (see
     *  {@link #claimStripeEvent}); this set survives only as a fast-path
     *  cache that short-circuits the DB round-trip on events THIS pod has
     *  already handled this lifetime. */
    private static final int SEEN_EVENTS_CAP = 5000
    private final java.util.LinkedHashSet<String> seenEventIds = new java.util.LinkedHashSet<>()

    /** Card-testing detector (batch 501; multi-pod-hardened wave 148).
     *  Records timestamps of recent payment_intent.payment_failed events
     *  keyed by walletId. When a wallet accumulates >= CARD_TEST_THRESHOLD
     *  failures within CARD_TEST_WINDOW_MS, fan out a CARD_TESTING_DETECTED
     *  notification to every admin (deduped per wallet within the window so a
     *  single attack doesn't spam the bell every retry).
     *
     *  Wave 148 scope note: these two maps are PER-JVM and so are blind to
     *  sibling pods — an attacker spreading declines across pods kept any
     *  single pod's list under the threshold, so the alert never fired. The
     *  AUTHORITATIVE cross-pod count is now the
     *  {@link #walletPaymentFailureRepository} DB aggregate (see
     *  {@link #handlePaymentIntentFailed}); these maps survive only as a
     *  fast-path cache. The map is bounded by active-attacker count and
     *  prunes stale entries on every read. */
    private static final int  CARD_TEST_THRESHOLD  = 3
    private static final long CARD_TEST_WINDOW_MS  = 60L * 60L * 1000L
    /** Retention horizon for the wallet_payment_failures ledger. A row older
     *  than this can never contribute to the trailing CARD_TEST_WINDOW_MS
     *  count, so it's safe to prune. 24h ≫ the 1h window gives a generous
     *  margin while keeping the table bounded by active-attack volume rather
     *  than uptime. Pruned best-effort on every failure (see
     *  {@link #handlePaymentIntentFailed}). */
    private static final long CARD_TEST_FAILURE_RETENTION_MS = 24L * 60L * 60L * 1000L
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.List<Long>> recentFailuresByWallet = new java.util.concurrent.ConcurrentHashMap<>()
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> cardTestAlertedAt = new java.util.concurrent.ConcurrentHashMap<>()

    /** Returns true if this event id was already processed successfully
     *  (and the caller should short-circuit), false if it's new or a
     *  retry of a previously-failed event (the caller should proceed).
     *
     *  Batch 657 fix: this is now a pure read — it does NOT record the
     *  id. Recording happens via {@link #markProcessed} only AFTER the
     *  handler switch completes successfully. The old behaviour recorded
     *  the id here, before the handler ran: if completeDeposit then threw
     *  (Stripe timeout, DB hiccup), the @Transactional rolled the DB work
     *  back but the id stayed in seenEventIds — so Stripe's retry hit
     *  this gate, got skipped, and the wallet was never credited even
     *  though the user paid. Record-after-success is safe because
     *  completeDeposit's own COMPLETED-status check is idempotent, so a
     *  retry of an already-applied event can't double-credit.
     *
     *  Synchronized because Stripe webhooks land on the Tomcat worker
     *  pool — two retries can race in the same JVM. */
    private synchronized boolean alreadyProcessed(String eventId) {
        if (eventId == null || eventId.isEmpty()) return false
        return seenEventIds.contains(eventId)
    }

    /** Records an event id as successfully processed so future retries
     *  of the SAME event short-circuit at {@link #alreadyProcessed}.
     *  Call this only after the handler ran without throwing. FIFO
     *  eviction caps the set at {@link #SEEN_EVENTS_CAP} so a long-lived
     *  container can't accumulate unbounded state. Synchronized for the
     *  same worker-pool-race reason as {@link #alreadyProcessed}. */
    private synchronized void markProcessed(String eventId) {
        if (eventId == null || eventId.isEmpty()) return
        if (seenEventIds.contains(eventId)) return
        if (seenEventIds.size() >= SEEN_EVENTS_CAP) {
            def oldest = seenEventIds.iterator().next()
            seenEventIds.remove(oldest)
        }
        seenEventIds.add(eventId)
    }

    /** Cluster-wide webhook-event claim (wave 147). Attempts to record the
     *  Stripe event id in the {@code processed_stripe_events} ledger BEFORE
     *  the side-effecting handler runs. Returns {@code true} when THIS pod
     *  won the claim (the caller proceeds to run the handler) and
     *  {@code false} when the event has already been processed — by this
     *  pod (fast-path {@link #seenEventIds} cache hit), by a prior delivery
     *  (existence check), or by a sibling pod that raced us to the INSERT
     *  (unique-constraint violation) — in which case the caller SKIPS the
     *  handler and ACKs 200 so Stripe stops retrying.
     *
     *  This is the multi-pod-safe replacement for the old per-JVM-only
     *  {@code seenEventIds.contains} gate: a Stripe retry routed to a
     *  different pod than the original now hits the shared DB row instead
     *  of an empty in-memory set, so a cross-pod retry no longer re-fires
     *  the admin bells / audit rows the handlers emit.
     *
     *  Claim-then-skip ordering (NOT record-after-success): unlike the
     *  legacy {@link #markProcessed} (which deliberately recorded only
     *  AFTER a successful handler so a thrown handler's @Transactional
     *  rollback didn't strand the id — BUG-1, batch 657), this claim is
     *  taken UP FRONT. That's safe here precisely BECAUSE the claim row is
     *  written inside the SAME @Transactional as the handler: if the
     *  handler throws, the whole transaction — INCLUDING this claim row —
     *  rolls back, so Stripe's retry finds no claim and genuinely re-runs
     *  the event (the BUG-1 invariant is preserved by transactional
     *  atomicity, not by deferring the write). And re-running is harmless:
     *  completeDeposit / failTransaction / the dispute + refund handlers
     *  are each idempotent on the tx status / stripeReference they set.
     *
     *  The DataIntegrityViolationException catch is what makes a true
     *  cross-pod race (both pods pass the existence check, both INSERT)
     *  resolve to "duplicate, skip" rather than a 500 — mirrors
     *  {@code FraudAnalysisService.sweepAndPushFraudSignals}. The enclosing
     *  {@link #handleWebhookEvent} is annotated
     *  {@code noRollbackFor = DataIntegrityViolationException} so this
     *  caught violation doesn't mark the surrounding transaction
     *  rollback-only.
     *
     *  Degraded posture when the repo is unwired (unit tests / a boot
     *  without the bean): fall back to the per-JVM {@link #seenEventIds}
     *  set alone — identical to the single-process pre-fix behaviour. */
    private boolean claimStripeEvent(String eventId, String eventType) {
        if (eventId == null || eventId.isEmpty()) return true
        // Fast-path: this pod already handled (and recorded) the event this
        // lifetime — skip the DB round-trip entirely.
        if (alreadyProcessed(eventId)) return false
        // No DB ledger wired (unit test / context-less build) — degrade to
        // the per-JVM set as the sole gate, then record so a second call in
        // the same JVM dedupes. Single-process behaviour, identical to the
        // pre-fix posture.
        if (processedStripeEventRepository == null) {
            markProcessed(eventId)
            return true
        }
        // Cross-pod fast path: a sibling pod (or a prior delivery on any
        // pod) already claimed this event id → skip. A transient DB error
        // here fails OPEN — fall through to the authoritative INSERT, which
        // the UNIQUE index still backstops; better to occasionally re-run
        // an idempotent handler than to 500 a webhook on a read blip.
        try {
            if (processedStripeEventRepository.existsByEventId(eventId)) {
                markProcessed(eventId)   // warm the local cache for next time
                return false
            }
        } catch (Exception e) {
            log.warn("Stripe event claim existence-check failed for ${eventId}: ${e.message}")
        }
        // Authoritative INSERT. Whichever pod's row lands first wins; a
        // sibling pod racing the same id between the check above and this
        // write trips the UNIQUE(event_id) constraint → caught as a
        // duplicate (skip the handler), NOT a 500.
        try {
            processedStripeEventRepository.save(new com.sboxmarket.model.ProcessedStripeEvent(
                eventId:     eventId,
                eventType:   eventType,
                processedAt: System.currentTimeMillis()))
            markProcessed(eventId)
            return true
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Lost the cross-pod race — a sibling pod claimed the same id
            // first. That pod owns the handler run; we skip cleanly.
            log.info("Stripe webhook ${eventId} claimed by sibling pod between check and insert — treating as duplicate, skipping")
            markProcessed(eventId)
            return false
        }
    }

    @PostConstruct
    void init() {
        Stripe.apiKey = secretKey
        log.info("Stripe initialised (key prefix: ${secretKey?.take(7)}…)")
    }

    /**
     * Redact a Stripe Checkout Session id for the application log.
     *
     * A raw session id (`cs_live_<long-opaque-token>`) is a capability,
     * not a harmless reference: combined with the publishable key (which
     * IS public) it lets a holder call Stripe.js `retrieveCheckoutSession`
     * and read the buyer's email, amount, and line items. The prod
     * logging config (application-prod.yml) deliberately pins
     * `org.springframework.web` to WARN precisely so request URIs carrying
     * `&session_id=cs_live_…` (see the success-url wiring in
     * createDepositSession) never reach /var/log/skinbox/skinbox.log — but
     * `com.sboxmarket` logs at INFO, so any `log.info`/`log.warn` here that
     * interpolated the raw id silently defeated that protection.
     *
     * We keep the mode-qualified prefix (`cs_live_` / `cs_test_`, not
     * secret) plus four chars so ops can still eyeball-correlate a line
     * with a Stripe dashboard search, and drop the rest — symmetric with
     * how {@link #init} logs only the secret-key prefix. Null/short/non-cs
     * values pass through a generic mask rather than throwing.
     */
    static String redactSession(String sessionId) {
        if (!sessionId) return '<none>'
        // Keep `cs_<mode>_` + 4 chars of the opaque token, redact the rest.
        // `cs_live_` / `cs_test_` is 8 chars → 12 keeps a short, non-usable
        // breadcrumb. Anything that doesn't look like a Stripe session id
        // (legacy/dev refs like "manual" or "dev_…") is masked wholesale.
        if (sessionId.startsWith('cs_') && sessionId.length() > 12) {
            return sessionId.substring(0, 12) + '…'
        }
        // Short or non-cs reference — never echo it verbatim; show only a
        // 3-char head so a truncated dev/manual ref stays greppable.
        return (sessionId.length() <= 3 ? sessionId : sessionId.substring(0, 3)) + '…'
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
        // Normalise to whole cents BEFORE anything downstream reads it.
        // The DTO @DecimalMin/@DecimalMax don't constrain scale, so a body
        // like {"amount": 50.999} would otherwise: charge Stripe
        // (50.999*100).longValue() = 5099 cents ($50.99) but persist the
        // PENDING tx with amount=50.999 — and completeDeposit credits the
        // wallet tx.amount (50.999), a fraction of a cent more than Stripe
        // actually collected. Rounding here keeps the Stripe charge, the
        // stored tx, the amount-match check, and the wallet credit all on
        // the same 2dp value.
        amount = amount.setScale(2, java.math.RoundingMode.HALF_UP)

        // PESSIMISTIC_WRITE lock on the wallet row. The cap check just below is
        // read-then-act (sumDepositsSince → compare → the PENDING row that
        // counts is only INSERTED later, after the Stripe round-trip), and the
        // deposit path never SAVES the wallet, so the @Version that serializes
        // withdrawals/purchases never fires here. Without this lock, N
        // concurrent POST /api/wallet/deposit all read the same used24h and
        // every one passes the cap — letting ~20 parallel Checkout sessions
        // (RateLimitFilter allows 20 writes/10s and is a counter, not a lock)
        // blow a $5k/24h cap to ~$190k: exactly the card-testing / stolen-card
        // drain the cap exists to stop. The lock serializes deposit-session
        // creation per wallet — caller #2 blocks here until #1 commits its
        // PENDING row, then reads a used24h that includes it. Per-wallet, so
        // distinct users never contend; deposits are low-frequency, so holding
        // the row lock across the Stripe call is an acceptable trade for a
        // correct fraud cap.
        def wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow { new NoSuchElementException("Wallet $walletId not found") }

        // Rolling 24-hour deposit cap (batch 497). Sums every DEPOSIT row that
        // already credited the wallet (COMPLETED), is in-flight (PENDING), or
        // is under dispute (DISPUTED). Combined with the wallet lock above,
        // parallel Checkout sessions can no longer race past it. Rejects with
        // DEPOSIT_DAILY_CAP and a clear message naming the remaining amount.
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

        // Stripe-idempotent-reuse guard. The idemKey above buckets by
        // minute, so a fast double-click (two POSTs within the same
        // wall-clock minute) makes Stripe return the SAME Session.id on
        // the second call rather than creating a new one. Pre-fix, we
        // blindly wrote a second PENDING Transaction with the same
        // stripeReference — the column has no unique constraint, so it
        // landed. Then completeDeposit's `findByStripeReference(sessionId)`
        // only resolves ONE of those rows; the other stays PENDING
        // forever (ghost "Deposit pending · $X" chip on the wallet hero
        // AND continues to count toward the 24h deposit cap, blocking
        // legitimate retries). Reuse the existing PENDING row so the
        // happy-path double-click is a true no-op at the ledger layer.
        def existingForSession = transactionRepository.findByStripeReference(session.id)
        if (existingForSession != null) {
            log.info("Reusing existing deposit tx ${existingForSession.id} for idempotent Stripe session ${redactSession(session.id)} (idem=${idemKey})")
            return [checkoutUrl: session.url, sessionId: session.id,
                    transactionId: existingForSession.id, live: true]
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

        log.info("Created Stripe Checkout session ${redactSession(session.id)} for wallet $walletId amount \$${amount} (idem=${idemKey})")
        [checkoutUrl: session.url, sessionId: session.id, transactionId: tx.id, live: true]
    }

    /* ── REFUND ──────────────────────────────────────────
     * Creates a Stripe Refund for a prior deposit. Only callable via the
     * admin panel (not by end users). Credits back out of the user's wallet
     * so the balance stays consistent with Stripe. */
    @Transactional
    Map refundDeposit(Long depositTxId, BigDecimal refundAmount = null, Long adminUserId = null) {
        def tx = transactionRepository.findById(depositTxId)
                .orElseThrow { new NoSuchElementException("Transaction $depositTxId not found") }
        if (tx.type != 'DEPOSIT' || tx.status != 'COMPLETED') {
            throw new IllegalStateException("Only completed deposits can be refunded")
        }
        def amount = refundAmount ?: tx.amount
        // Normalise to whole cents — mirrors createDepositSession /
        // requestWithdrawal. The admin-supplied refundAmount is NOT
        // scale-constrained, so a value like 30.005 would otherwise:
        // refund Stripe (30.005*100).longValue() = 3000 cents ($30.00)
        // but debit the wallet the un-rounded 30.005 and record a REFUND
        // row of 30.005 — a sub-cent drift between Stripe and the ledger.
        // Rounding here keeps the Stripe refund, the wallet debit, and
        // the stored REFUND tx all on the same 2dp value. Done before the
        // range check so `amount > tx.amount` compares like-scaled values.
        amount = amount.setScale(2, java.math.RoundingMode.HALF_UP)
        if (amount <= BigDecimal.ZERO || amount > tx.amount) {
            throw new IllegalArgumentException("Refund amount must be between 0 and \$${tx.amount}")
        }
        // Cumulative-refund cap (batch 658 fix). The single-call check
        // above (`amount > tx.amount`) only blocks one oversized call —
        // it doesn't stop two partials of $60 each against a $100 deposit
        // from totalling $120. In live mode with a `cs_`-prefixed
        // stripeReference, Stripe's own Refund.create call would reject
        // the second one with `amount_too_large` and the @Transactional
        // would roll back — so the live happy path stays safe. But two
        // production paths sidestep that:
        //   1) `dev_`-prefixed deposits (created when the platform ran
        //      without Stripe keys, then later got them) skip the
        //      Stripe call entirely (line guarded by isLive() + cs_),
        //      so the second admin click silently double-debits the
        //      wallet and writes a second REFUND row.
        //   2) Any future deposit path that lands a non-`cs_` reference
        //      (manual ops adjustment, alternate processor) inherits the
        //      same gap.
        // Sum prior REFUND rows for this deposit and reject up front if
        // the new amount would push past the original.
        def alreadyRefunded = (transactionRepository.sumRefundsByDeposit(tx.id) ?: BigDecimal.ZERO)
            .setScale(2, java.math.RoundingMode.HALF_UP)
        def remaining = tx.amount - alreadyRefunded
        if (remaining < BigDecimal.ZERO) remaining = BigDecimal.ZERO
        if (amount > remaining) {
            throw new IllegalArgumentException(
                "Refund amount \$${amount} exceeds remaining refundable balance \$${remaining} on deposit #${tx.id} " +
                "(original \$${tx.amount}, already refunded \$${alreadyRefunded})"
            )
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

        // Debit the wallet and record the refund as its own transaction.
        def wallet = walletRepository.findById(tx.walletId)
                .orElseThrow { new NoSuchElementException("Wallet not found") }
        // The Stripe refund above has already moved money out of the
        // platform's Stripe balance. If the wallet has since been drained
        // below the refund amount we must NOT throw — that would leave
        // Stripe debited with no REFUND row in the ledger. Mirror
        // handleRefundCreated: clamp the wallet debit at the available
        // balance (never negative), still record the REFUND for the full
        // amount so the ledger reflects what left Stripe, and log the gap
        // as an auditable shortfall for admins to chase.
        def debit = amount.min(wallet.balance)
        if (debit < BigDecimal.ZERO) debit = BigDecimal.ZERO
        wallet.balance = wallet.balance - debit
        if (wallet.balance < BigDecimal.ZERO) wallet.balance = BigDecimal.ZERO
        walletRepository.save(wallet)
        if (debit < amount) {
            log.warn("refundDeposit ${depositTxId}: wallet ${wallet.id} only had \$${debit} of the " +
                "\$${amount} refunded via Stripe — \$${amount - debit} shortfall for manual reconciliation")
        }

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
        // Resolve the wallet owner so the refund audit row carries BOTH
        // the acting admin and the affected user. Pre-fix this logged
        // null/null — an irreversible real-money op with no accountable
        // admin on record, invisible to the audit-by-actor/by-subject
        // filters and blank in the CSV export.
        Long subjectUserId = null
        try {
            def uname = wallet.username ?: ''
            if (uname.startsWith('steam_')) {
                subjectUserId = steamUserRepository?.findBySteamId64(uname.substring('steam_'.length()))?.id
            }
        } catch (Exception ignore) {}
        try {
            auditService?.log(AuditService.REFUND_ISSUED, adminUserId, subjectUserId, refundTx.id,
                "Refunded \$${amount} of deposit ${tx.id} (stripeRef=${refundId}, wallet debit=\$${debit})")
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
        // Missing tx → 404 NotFoundException, not a bare NoSuchElementException
        // (which has no GlobalExceptionHandler mapping and fell through to a
        // 500 on a plain wrong id). Cross-wallet attempt → 403 Forbidden,
        // not IllegalState (a 400) — it is an authorization failure. (2026-05-21)
        def tx = transactionRepository.findById(txId)
            .orElseThrow { new NotFoundException("Transaction", txId) }
        if (tx.walletId != walletId) {
            throw new ForbiddenException("Not your withdrawal")
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
            .orElseThrow { new NotFoundException("Wallet", walletId) }
        // Atomic claim against a concurrent admin-side reject (wave 127).
        // Pre-fix, this method's findById-read + unconditional credit was
        // racing AdminService.rejectWithdrawal at the same-instant: both
        // loaded the same PENDING row, both credited wallet.balance +=
        // tx.amount, and the wallet ended up double-refunded — the user
        // got their money back twice from one withdrawal request while
        // only the last-committing path's tx.status overwrite stuck. The
        // conditional UPDATE flips PENDING→CANCELLED only when the row
        // is still PENDING and returns 1 to the winning caller; the
        // losing caller sees 0 and must bail BEFORE the wallet credit.
        // Same shape as claimExpirePending (sweepStalePendingDeposits
        // multi-pod race, wave 126).
        int claimed = transactionRepository.claimCancelPendingWithdrawal(tx.id)
        if (claimed == 0) {
            // Lost the race — admin's reject (or some other terminal flip)
            // landed first. Re-read the row to surface an accurate status
            // to the user so the SPA's withdrawal-row UI shows the actual
            // resolution instead of a stale PENDING.
            def latest = transactionRepository.findById(tx.id).orElse(null)
            def now = latest?.status ?: tx.status
            log.info("cancelPendingWithdrawal lost race on tx ${tx.id} — current status=${now}")
            throw new IllegalStateException(
                "Withdrawal is ${now}, not PENDING — cannot cancel. " +
                "If it already paid out, contact support for a reversal."
            )
        }
        // Credit the amount back exactly as requestWithdrawal debited it.
        wallet.balance = wallet.balance + (tx.amount ?: BigDecimal.ZERO)
        walletRepository.save(wallet)
        // Status flip is already persisted by the conditional UPDATE above;
        // re-stamp the in-memory copy so the audit/log lines below see the
        // accurate value, append the user-cancel suffix to the description
        // (matches the legacy persisted shape), and bump updatedAt.
        tx.status = 'CANCELLED'
        tx.description = ((tx.description ?: '') + ' · cancelled by user').take(500)
        // Bump updatedAt to the cancellation moment. Every OTHER tx-status
        // mutation site in this file (handleChargebackClosed:1031,
        // markCheckoutCompleted:1203, markCheckoutExpired:1256) and in
        // AdminService (clearDisputeHold:317, approveWithdrawal:541,
        // rejectWithdrawal:585) bumps updatedAt next to the status flip;
        // omitting it here left the CANCELLED row carrying its original
        // PENDING-creation timestamp, so wallet-history "recently
        // updated" views and audit-window queries (filtered by
        // updatedAt) silently missed the cancel event.
        tx.updatedAt = System.currentTimeMillis()
        transactionRepository.save(tx)
        // Resolve the wallet owner so the self-cancel audit row carries
        // them as BOTH actor AND subject — pre-fix this logged null/null,
        // which meant the row was invisible to ProfileController's
        // /security-activity feed (filters via auditLogRepository.bySubject
        // on subjectUserId = uid). A user who cancelled their own pending
        // withdrawal never saw the action in their security history. Same
        // bug pattern this file already fixed for refundDeposit at line 373;
        // cancelPendingWithdrawal was the matching outlier.
        Long ownerUserId = null
        try {
            def uname = wallet.username ?: ''
            if (uname.startsWith('steam_')) {
                ownerUserId = steamUserRepository?.findBySteamId64(uname.substring('steam_'.length()))?.id
            }
        } catch (Exception ignore) {}
        try {
            auditService?.log(AuditService.WITHDRAW_SELF_CANCELLED, ownerUserId, ownerUserId, tx.id,
                "User cancelled pending withdrawal \$${tx.amount} from wallet ${wallet.username}")
        } catch (Exception ignore) {}
        log.info("User cancelled pending withdrawal ${tx.id} from wallet ${walletId}")
        [id: tx.id, status: tx.status, newBalance: wallet.balance]
    }

    /* ── STRIPE CONNECT ONBOARDING + KYC ─────────────────────────────
     * Creates (or reuses) a Stripe Connect Express account for the
     * wallet's owner and returns a hosted onboarding AccountLink URL.
     * The Express onboarding flow IS Stripe's KYC / identity step — the
     * user supplies their legal name, DOB, address, and a payout bank
     * account / debit card, and Stripe verifies them. Once Stripe clears
     * the account it fires `account.updated` with payouts_enabled=true,
     * which our webhook mirrors onto wallet.payoutsEnabled.
     *
     * Idempotent on the account id: if the wallet already has a
     * stripeConnectAccountId we DON'T create a second account — we just
     * mint a fresh link for it (onboarding links are single-use + short-
     * lived, so we generate one on every call). Stores the connected-
     * account id on the wallet the first time so the destination is
     * stable for the eventual Transfer.
     */
    @Transactional
    Map createConnectOnboardingLink(Long walletId) {
        def wallet = walletRepository.findById(walletId)
                .orElseThrow { new NoSuchElementException("Wallet $walletId not found") }

        if (!isLive()) {
            // Dev mode (no real Stripe keys): do NOT pretend to onboard a
            // real account. Mark the wallet as a SIMULATED connected
            // account so the rest of the UI/flow is exercisable locally,
            // but flag live=false and a non-acct_ reference so nothing
            // downstream mistakes it for a real Stripe account. We DON'T
            // flip payoutsEnabled here — local testers can still see the
            // "onboarding required" state; an admin/dev can flip it via
            // the DB if they want to exercise the payout-enabled branch.
            if (!wallet.stripeConnectAccountId) {
                wallet.stripeConnectAccountId = "dev_acct_${walletId}_${System.currentTimeMillis()}"
                walletRepository.save(wallet)
            }
            log.info("[DEV MODE] simulated Connect onboarding for wallet ${walletId} (no real Stripe account created)")
            return [onboardingUrl: connectRefreshUrl, live: false, simulated: true,
                    accountId: wallet.stripeConnectAccountId]
        }

        // Create the Express connected account the first time. Express =
        // Stripe-hosted onboarding + dashboard; `transfers` capability is
        // what we need to push payouts to it. card_payments is NOT
        // requested — sellers only RECEIVE money here, they don't charge
        // cards. Email pre-fills the onboarding form when we have it.
        String accountId = wallet.stripeConnectAccountId
        if (!accountId) {
            try {
                def acctParamsBuilder = com.stripe.param.AccountCreateParams.builder()
                    .setType(com.stripe.param.AccountCreateParams.Type.EXPRESS)
                    .setCountry(connectCountry)
                    .setCapabilities(
                        com.stripe.param.AccountCreateParams.Capabilities.builder()
                            .setTransfers(
                                com.stripe.param.AccountCreateParams.Capabilities.Transfers.builder()
                                    .setRequested(true)
                                    .build())
                            .build())
                    .putMetadata("walletId", walletId.toString())
                def ownerEmail = resolveOwnerEmail(wallet)
                if (ownerEmail) acctParamsBuilder.setEmail(ownerEmail)
                def account = com.stripe.model.Account.create(acctParamsBuilder.build())
                accountId = account.id
                wallet.stripeConnectAccountId = accountId
                walletRepository.save(wallet)
                log.info("Created Stripe Connect Express account ${accountId} for wallet ${walletId}")
            } catch (Exception e) {
                log.error("Stripe Connect account creation failed for wallet ${walletId}: ${e.message}")
                throw new IllegalStateException("Could not start payout onboarding — Stripe error, try again", e)
            }
        }

        // Mint a fresh onboarding AccountLink. These are single-use and
        // expire quickly, so we generate one per call rather than caching.
        try {
            def linkParams = com.stripe.param.AccountLinkCreateParams.builder()
                .setAccount(accountId)
                .setRefreshUrl(connectRefreshUrl)
                .setReturnUrl(connectReturnUrl)
                .setType(com.stripe.param.AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build()
            def link = com.stripe.model.AccountLink.create(linkParams)
            log.info("Generated Connect onboarding link for wallet ${walletId} (account ${accountId})")
            return [onboardingUrl: link.url, live: true, simulated: false, accountId: accountId]
        } catch (Exception e) {
            log.error("Stripe Connect AccountLink creation failed for wallet ${walletId} (account ${accountId}): ${e.message}")
            throw new IllegalStateException("Could not generate the onboarding link — Stripe error, try again", e)
        }
    }

    /**
     * Connect onboarding status for the wallet — drives the SPA's
     * "Set up payouts" vs "Payouts enabled" UI. Returns the persisted
     * mirror flags PLUS, in live mode when an account exists, a fresh
     * Stripe re-read so the UI reflects ground truth even if an
     * `account.updated` webhook was missed (e.g. webhook secret rotated).
     * The fresh read also opportunistically syncs wallet.payoutsEnabled
     * so a missed webhook self-heals on the next status poll.
     */
    @Transactional
    Map connectStatus(Long walletId) {
        def wallet = walletRepository.findById(walletId)
                .orElseThrow { new NoSuchElementException("Wallet $walletId not found") }
        boolean hasAccount = wallet.stripeConnectAccountId != null
        boolean payoutsEnabled = Boolean.TRUE.equals(wallet.payoutsEnabled)

        if (!isLive()) {
            // Dev mode — report the persisted flags only; never call Stripe.
            return [
                live:              false,
                hasAccount:        hasAccount,
                payoutsEnabled:    payoutsEnabled,
                onboardingNeeded:  !payoutsEnabled,
                accountId:         wallet.stripeConnectAccountId,
                simulated:         true
            ]
        }

        // Live + an account exists → re-read Stripe for ground truth and
        // self-heal the persisted flag if a webhook was missed.
        if (hasAccount && wallet.stripeConnectAccountId?.startsWith('acct_')) {
            try {
                def account = com.stripe.model.Account.retrieve(wallet.stripeConnectAccountId)
                boolean stripeSaysEnabled = Boolean.TRUE.equals(account.payoutsEnabled)
                if (stripeSaysEnabled != payoutsEnabled) {
                    wallet.payoutsEnabled = stripeSaysEnabled
                    walletRepository.save(wallet)
                    log.info("connectStatus self-healed wallet ${walletId} payoutsEnabled ${payoutsEnabled} → ${stripeSaysEnabled} from Stripe")
                    payoutsEnabled = stripeSaysEnabled
                }
                return [
                    live:             true,
                    hasAccount:       true,
                    payoutsEnabled:   payoutsEnabled,
                    onboardingNeeded: !payoutsEnabled,
                    chargesEnabled:   Boolean.TRUE.equals(account.chargesEnabled),
                    detailsSubmitted: Boolean.TRUE.equals(account.detailsSubmitted),
                    accountId:        wallet.stripeConnectAccountId,
                    simulated:        false
                ]
            } catch (Exception e) {
                // Stripe read failed — fall back to the persisted mirror
                // rather than 500-ing a status poll.
                log.warn("connectStatus Stripe re-read failed for wallet ${walletId}: ${e.message}")
            }
        }

        [
            live:             true,
            hasAccount:       hasAccount,
            payoutsEnabled:   payoutsEnabled,
            onboardingNeeded: !payoutsEnabled,
            accountId:        wallet.stripeConnectAccountId,
            simulated:        false
        ]
    }

    /** Resolve the wallet owner's email (for pre-filling Connect
     *  onboarding). Best-effort — returns null on any miss. */
    private String resolveOwnerEmail(Wallet w) {
        if (w == null || steamUserRepository == null) return null
        try {
            def uname = w.username ?: ''
            if (!uname.startsWith('steam_')) return null
            def owner = steamUserRepository.findBySteamId64(uname.substring('steam_'.length()))
            return owner?.email
        } catch (Exception ignore) {
            return null
        }
    }

    @Transactional
    Transaction requestWithdrawal(Long walletId, BigDecimal amount, String destinationRef) {
        def wallet = walletRepository.findById(walletId)
                .orElseThrow { new NoSuchElementException("Wallet $walletId not found") }

        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new IllegalArgumentException("Amount must be positive")
        }
        // Normalise to whole cents — the WithdrawRequest DTO constrains the
        // min/max but not the scale, so a {"amount": 12.999} body would
        // debit the wallet by an odd-scale value the UI never displays and
        // leave a sub-cent drift between the ledger row and the balance.
        // Done before the balance check so the comparison is also 2dp-clean.
        amount = amount.setScale(2, java.math.RoundingMode.HALF_UP)

        if (wallet.balance < amount) {
            throw new IllegalStateException("Insufficient balance: have \$${wallet.balance}, need \$${amount}")
        }

        // ── Connect-onboarding gate (live mode) ──────────────────────
        // A real money-out movement REQUIRES an onboarded, payouts-enabled
        // Stripe Connect account to send the funds to. If the user hasn't
        // completed onboarding / KYC, REJECT with a clear, branchable code
        // (CONNECT_ONBOARDING_REQUIRED) BEFORE touching the wallet balance
        // — never silently stub a "manual" PENDING row that no payout API
        // will ever fulfil (the old behaviour). The SPA maps this code to
        // a "Set up payouts" call-to-action that hits
        // /api/wallet/connect/onboard. In dev mode (no Stripe keys) we
        // skip this gate and fall through to the clearly-simulated path
        // below.
        if (isLive() && !Boolean.TRUE.equals(wallet.payoutsEnabled)) {
            throw new com.sboxmarket.exception.BadRequestException("CONNECT_ONBOARDING_REQUIRED",
                "Set up payouts before withdrawing. Connect a payout account (a one-time identity + bank/debit-card setup) from the Wallet page, then try again.")
        }

        // Debit the wallet first, then FLUSH so the @Version optimistic-lock
        // UPDATE executes (and takes the row write-lock) BEFORE the Stripe
        // Transfer — never at outer-tx commit, which is AFTER the Transfer.
        // This flush is load-bearing for money safety: if a concurrent wallet
        // write (another withdrawal, or a buy crediting this seller) bumped
        // @Version, the flush throws ObjectOptimisticLockingFailureException
        // HERE, before any money moves, so the @Transactional rolls back a
        // debit that never paired with a payout (controller maps it to
        // WITHDRAW_RACE). Without the early flush the version check fired only
        // at commit — AFTER Transfer.create — so a race-loss would roll back
        // the debit while the real money had ALREADY left the platform,
        // restoring the balance AND keeping the payout (free money out the
        // door, deliberately triggerable by racing a wallet credit). The
        // Transfer is still created after the (now durable) debit so a Stripe
        // rejection also rolls the debit back — never an orphan payout.
        wallet.balance = wallet.balance - amount
        walletRepository.save(wallet)
        walletRepository.flush()

        // ── Real payout via Stripe Connect Transfer (live mode) ──────
        // Move `amount` from the platform balance to the seller's
        // connected account. The Transfer id is the authoritative
        // money-movement reference we store on the tx. In the Express
        // flow with automatic payouts (Stripe's default), this Transfer
        // lands in the connected account's Stripe balance and Stripe's
        // own scheduled payout then moves it to the seller's bank/card —
        // so a separate Payout.create is NOT required and would in fact
        // fail (it would have to run on the connected account, which we
        // leave to Stripe's automatic schedule). We therefore create the
        // Transfer only and leave the bank settlement to Stripe.
        String stripeRef = destinationRef ?: "manual"
        String txStatus
        String txDescription
        if (isLive()) {
            try {
                long amountCents = (amount * 100).longValue()
                // Idempotency key bucketed by wallet + amount + minute so a
                // fast double-submit that slipped past the @Version guard
                // can't create two Transfers (mirrors createDepositSession).
                def idemKey = "wd_${walletId}_${amountCents}_${System.currentTimeMillis().intdiv(60_000)}"
                def reqOpts = RequestOptions.builder().setIdempotencyKey(idemKey).build()
                def transferParams = com.stripe.param.TransferCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase())
                    .setDestination(wallet.stripeConnectAccountId)
                    .putMetadata("walletId", walletId.toString())
                    .putMetadata("type", "WITHDRAW")
                    .build()
                def transfer = com.stripe.model.Transfer.create(transferParams, reqOpts)
                stripeRef = transfer.id
                // The Transfer succeeded — funds have left the platform
                // balance. Mark COMPLETED; Stripe handles the downstream
                // bank settlement on its automatic payout schedule.
                txStatus = "COMPLETED"
                txDescription = "Withdrawal via Stripe Connect (transfer ${transfer.id} → ${wallet.stripeConnectAccountId})"
                log.info("Stripe Connect transfer ${transfer.id} created for wallet ${walletId}: \$${amount} → ${wallet.stripeConnectAccountId}")
            } catch (Exception e) {
                // Transfer failed — let it propagate so the @Transactional
                // rolls back the wallet debit (no money left, no orphan
                // row). The controller surfaces a retryable error.
                log.error("Stripe Connect transfer FAILED for wallet ${walletId} (\$${amount} → ${wallet.stripeConnectAccountId}): ${e.message}")
                throw new IllegalStateException("Payout could not be created — Stripe error. Your balance was not charged; try again.", e)
            }
        } else {
            // Dev mode (no Stripe keys): clearly mark the withdrawal as
            // SIMULATED — it does NOT move real money. Same shape as
            // devModeDeposit's "dev_…" marker so nothing downstream
            // mistakes it for a real payout.
            stripeRef = "dev_payout_${System.currentTimeMillis()}"
            txStatus = "COMPLETED"
            txDescription = "Withdrawal (dev-mode SIMULATED — no real payout)"
        }

        def tx = new Transaction(
            walletId:        walletId,
            type:            "WITHDRAW",
            status:          txStatus,
            amount:          amount,
            currency:        currency.toUpperCase(),
            stripeReference: stripeRef,
            description:     txDescription
        )
        transactionRepository.save(tx)

        // Resolve the wallet owner so the requested-withdraw audit row
        // carries them as BOTH actor AND subject — pre-fix this logged
        // null/null, which meant the row was invisible to
        // ProfileController's /security-activity feed (filters via
        // auditLogRepository.bySubject on subjectUserId = uid).
        // WITHDRAW_REQUESTED is explicitly white-listed on that feed,
        // so a user who initiated a money-out movement had no record of
        // it in their own security history — exactly the surface the
        // feed exists to expose (session-hijack-driven withdraws).
        // Same null/null bug pattern this file already fixed for
        // refundDeposit (line 373) and cancelPendingWithdrawal (line 498).
        Long ownerUserId = null
        try {
            def uname = wallet.username ?: ''
            if (uname.startsWith('steam_')) {
                ownerUserId = steamUserRepository?.findBySteamId64(uname.substring('steam_'.length()))?.id
            }
        } catch (Exception ignore) {}
        try {
            auditService?.log(AuditService.WITHDRAW_REQUESTED, ownerUserId, ownerUserId, tx.id,
                "Withdrawal \$${amount} requested from wallet ${wallet.username} → ${destinationRef}")
        } catch (Exception ignore) {}
        log.info("Withdrawal \$${amount} from wallet $walletId → ${tx.status}")
        tx
    }

    /* ── WEBHOOK HANDLER ─────────────────────────────────
     * Called by StripeWebhookController when Stripe posts to /api/stripe/webhook. */
    // noRollbackFor — claimStripeEvent (below) catches a
    // DataIntegrityViolationException when a sibling pod's INSERT into
    // processed_stripe_events lands first on a cross-pod webhook-retry race.
    // Without this hint, Spring's DIVE translator marks this @Transactional
    // rollback-only the moment the constraint trips — so even on the
    // duplicate-skip path the transaction can't commit cleanly. The claim
    // row is written inside THIS transaction by design (see
    // claimStripeEvent's "claim-then-skip" note), so the violation is
    // expected and benign. Same fix shape as
    // FraudAnalysisService.sweepAndPushFraudSignals (V70) and waves 136-140.
    @Transactional(noRollbackFor = [org.springframework.dao.DataIntegrityViolationException])
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

        // Dedupe by Stripe event id (batch 476; multi-pod-hardened wave
        // 147). Stripe retries webhooks on transient 5xx (and on network
        // failures); without dedupe, a retry of charge.dispute.created
        // re-spams every admin and a retry of any deposit/refund event
        // logs a duplicate audit row. The handlers are each independently
        // row-idempotent on the tx status / stripeReference they set — so
        // the MONEY path is safe with or without this gate — but the gate
        // keeps the notification + audit side effects from re-firing.
        // Returning early ACKs 200 so Stripe stops retrying.
        //
        // Wave 147: the gate is now a DB-backed CLAIM
        // (claimStripeEvent → processed_stripe_events) instead of the
        // old per-JVM seenEventIds.contains check. The per-JVM set is
        // blind to sibling pods, so a retry routed to a DIFFERENT pod than
        // the original saw an empty set and re-ran the side-effecting
        // handler — the exact multi-pod gap this fix closes. The DB row is
        // the authoritative cross-pod gate; seenEventIds stays as a
        // fast-path cache (see claimStripeEvent).
        //
        // Batch 657 invariant preserved: a handler that throws must NOT
        // leave the event marked processed, or Stripe's retry is wrongly
        // skipped and a paid-for deposit never lands. The claim row is
        // written inside THIS @Transactional, so a thrown handler rolls
        // the claim row back too — Stripe's retry then finds no claim and
        // genuinely re-runs. (Previously the id was recorded only AFTER
        // the switch succeeded; transactional atomicity now provides the
        // same guarantee while letting the claim be taken up front, which
        // is what makes it multi-pod safe.) Re-running a duplicate is
        // harmless: completeDeposit / failTransaction / the dispute +
        // refund handlers all short-circuit on the status they already set.
        if (!claimStripeEvent(event.id, event.type)) {
            log.info("Stripe webhook ${event.id} already processed (cross-pod claim) — skipping")
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
                //
                // Audit fix: do NOT wrap in a swallow-everything try/catch.
                // A transient DB / notification-service failure was getting
                // logged-then-eaten, then markProcessed below recorded the
                // event id, then the controller ACKed 200 — and Stripe
                // never retried. The card-testing alert was lost forever
                // and the next attack cycle started clean. Let transient
                // exceptions propagate so the controller returns 500 and
                // Stripe retries. Re-runs are safe: the per-wallet alert
                // dedupe (cardTestAlertedAt) in handlePaymentIntentFailed
                // short-circuits repeat fan-outs within the window.
                def pi = event.dataObjectDeserializer.object.orElse(null)
                if (pi != null) handlePaymentIntentFailed(pi as com.stripe.model.PaymentIntent)
                break
            case "refund.created":
                // Dashboard-refund sync (batch 498). Previously a bare log.info —
                // if ops manually refunded from the Stripe Dashboard (instead of
                // via our /api/admin/refund endpoint), the wallet stayed
                // credited even though Stripe had clawed back the money,
                // letting the user withdraw funds that no longer existed in
                // our Stripe balance. Now the webhook reconciles the ledger.
                //
                // Audit fix: removed the swallow-everything try/catch wrapper.
                // A transient DB failure mid-refund was being logged-then-eaten,
                // then markProcessed recorded the event id, and the controller
                // ACKed 200 — Stripe never retried, the wallet stayed credited,
                // and Stripe's balance was debited. Real money loss. Let
                // transient exceptions propagate to the controller's 500 path
                // for Stripe retry. handleRefundCreated is idempotent on the
                // refund id (existing-by-stripeReference check at line 672),
                // so retries are safe.
                def refund = (Refund) event.dataObjectDeserializer.object.orElse(null)
                if (refund != null) handleRefundCreated(refund)
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
            //
            // Audit fix: removed the swallow-everything try/catch wrapper
            // (same reasoning as refund.created / payment_intent.payment_failed).
            // A transient DB failure mid-chargeback was being eaten and the
            // event marked processed, so the deposit row never flipped to
            // DISPUTED and admins were never notified. The handler's own
            // isFirstObservation gate (line 872) makes retries safe.
            case "charge.dispute.created":
                def disputeOpened = (Dispute) event.dataObjectDeserializer.object.orElse(null)
                if (disputeOpened != null) handleChargebackOpened(disputeOpened)
                break
            case "charge.dispute.closed":
                // Dispute resolved by Stripe (batch 495). If we won the
                // dispute, auto-clear the hold so the user's withdraw
                // lock lifts and the tx reverts to COMPLETED. If we
                // lost, keep DISPUTED and notify admins so they can
                // decide on a wallet clawback before the user drains
                // the now-under-water balance.
                //
                // Audit fix: removed the swallow-everything try/catch
                // wrapper. The WON branch's `tx.status == 'DISPUTED'` gate
                // (line 1004) and the LOST branch's status check make the
                // handler idempotent under retry.
                def disputeClosed = (Dispute) event.dataObjectDeserializer.object.orElse(null)
                if (disputeClosed != null) handleChargebackClosed(disputeClosed)
                break
            case "account.updated":
                // Stripe Connect onboarding progress. Fired whenever a
                // connected account's state changes — including when the
                // user finishes KYC and the account becomes able to
                // receive payouts. We mirror `account.payouts_enabled`
                // onto wallet.payoutsEnabled so requestWithdrawal's
                // CONNECT_ONBOARDING_REQUIRED gate lifts the moment Stripe
                // clears the seller. Idempotent: re-delivery of the same
                // state is a no-op (handleAccountUpdated only saves on a
                // real change).
                def account = (com.stripe.model.Account) event.dataObjectDeserializer.object.orElse(null)
                if (account != null) handleAccountUpdated(account)
                break
            default:
                // Unknown / not-yet-handled event type. Log + fall through
                // so the controller still returns 200 OK (Stripe won't
                // retry an event we don't care about). Critically, this
                // path does NOT throw — Stripe sends new event types
                // periodically as their API evolves, and a 500 on an
                // unrecognised type would put us into an unwanted retry
                // loop forever.
                log.debug("Ignoring Stripe event: ${event.type}")
        }

        // Wave 147: the durable cross-pod claim was already taken UP FRONT
        // by claimStripeEvent (which also warmed the seenEventIds fast-path
        // cache), and that claim row lives inside THIS @Transactional — so
        // if any case above threw, control never reaches here AND the claim
        // row rolls back with the rest of the transaction, leaving Stripe's
        // retry free to genuinely re-process the event (BUG-1 invariant,
        // batch 657, now upheld by transactional atomicity rather than by
        // deferring the record). A retry of an already-applied event is
        // harmless: completeDeposit / failTransaction / the dispute +
        // refund handlers all short-circuit on the tx status they already
        // set. The redundant markProcessed below is a cheap no-op (the id
        // is already cached) kept only to make the success path's intent
        // explicit at the bottom of the switch.
        markProcessed(event.id)
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
     *
     * Searches BOTH 'COMPLETED' and 'DISPUTED' deposits. A deposit is
     * 'COMPLETED' when the dispute first opens, but handleChargebackOpened
     * immediately flips it to 'DISPUTED'. If this lookup only scanned
     * 'COMPLETED' rows, then:
     *   1. `charge.dispute.closed` (WON) could never find the row → the
     *      withdrawal hold would never auto-lift even though the user won.
     *   2. A Stripe retry of `charge.dispute.created` would see tx==null
     *      → isFirstObservation==true → re-audit + re-spam every admin.
     * The " [pi:<id>]" tag is appended at completeDeposit time and is
     * never stripped when the row flips to DISPUTED, so it survives the
     * status transition and the exact-substring match still holds.
     */
    private Transaction findDepositByPaymentIntent(String paymentIntentId) {
        if (!paymentIntentId) return null
        def rows = []
        rows.addAll(transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'COMPLETED') ?: [])
        rows.addAll(transactionRepository.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'DISPUTED') ?: [])
        return rows.find { (it.description ?: '').contains("[pi:${paymentIntentId}]") }
    }

    /**
     * Resolve the SteamUser.id that owns a given Wallet via the
     * `username = "steam_<steamId64>"` chain — null on any failure
     * (non-steam wallet, missing SteamUser row, lookup error). Used
     * by the Stripe-webhook audit + notify code paths so every
     * money-movement audit row carries the affected user as
     * subjectUserId — making the row visible on the user's own
     * /security-activity feed (filters via
     * auditLogRepository.bySubject on subjectUserId = uid).
     *
     * Centralised here to deduplicate the four-or-five places in
     * this file that previously inlined the same steam_<id> → user
     * lookup. AdminService has its own walletOwnerId helper that
     * does an extra walletRepository round-trip; here the caller
     * already has the Wallet in scope, so we just take it directly.
     */
    private Long resolveSteamOwnerId(Wallet w) {
        if (w == null || steamUserRepository == null) return null
        try {
            def uname = w.username ?: ''
            if (!uname.startsWith('steam_')) return null
            def owner = steamUserRepository.findBySteamId64(uname.substring('steam_'.length()))
            return owner?.id
        } catch (Exception ignore) {
            return null
        }
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

        // Resolve the wallet owner so the audit row is visible to the
        // user's /security-activity feed (filters via
        // auditLogRepository.bySubject on subjectUserId = uid). Pre-fix
        // (null, null) — same shape as WITHDRAW_REQUESTED + DEPOSIT_COMPLETE
        // + TRADE_AUTO_RELEASED, closed in commits de40340 + c13cd2c.
        // REFUND_ISSUED is a money-out event from the user's wallet
        // (we debit and clamp at 0) — they MUST see it on their security
        // feed or a hostile refund (Stripe dashboard misclick, or a
        // stolen-card refund-and-keep) would be undetectable from their
        // side. Actor stays null because the action is Stripe-driven
        // (no human in our app initiated it).
        Long ownerId = resolveSteamOwnerId(wallet)
        try {
            auditService?.log(AuditService.REFUND_ISSUED, null, ownerId, refundTx.id,
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
     * Stripe Connect `account.updated` handler. When a seller finishes
     * (or makes progress in) Express onboarding, Stripe fires this with
     * the updated connected-account object. We resolve the matching
     * wallet by its stored connected-account id and mirror Stripe's
     * `payouts_enabled` flag onto `wallet.payoutsEnabled` — the gate
     * requestWithdrawal checks before creating a real Transfer.
     *
     * Idempotent: only saves when the flag actually changes, so a Stripe
     * retry (or a burst of account.updated events that don't move the
     * payouts flag) is a no-op. Tolerant of accounts we don't own
     * (no matching wallet → log + return) so an unrelated account.updated
     * never 500s the webhook into a retry loop.
     */
    @Transactional
    void handleAccountUpdated(com.stripe.model.Account account) {
        if (account == null) return
        def accountId = account.id
        if (!accountId) {
            log.warn("account.updated received with no account id — ignoring")
            return
        }
        def wallet = walletRepository.findByStripeConnectAccountId(accountId)
        if (wallet == null) {
            // Either an account.updated for an account we didn't create,
            // or one whose wallet link hasn't persisted yet. Nothing to
            // reconcile — no-op so Stripe gets a 200 and stops retrying.
            log.info("account.updated for ${accountId} — no matching wallet, ignoring")
            return
        }
        boolean stripeSaysEnabled = Boolean.TRUE.equals(account.payoutsEnabled)
        boolean current = Boolean.TRUE.equals(wallet.payoutsEnabled)
        if (stripeSaysEnabled == current) {
            log.debug("account.updated for ${accountId}: payoutsEnabled already ${current} — no change")
            return
        }
        wallet.payoutsEnabled = stripeSaysEnabled
        walletRepository.save(wallet)
        log.info("account.updated: wallet ${wallet.id} (account ${accountId}) payoutsEnabled ${current} → ${stripeSaysEnabled}")
        // Tell the user their payouts just turned on so they know they can
        // withdraw — only on the false→true edge (the enablement moment),
        // and best-effort so a bell failure can't roll back the flag flip.
        if (stripeSaysEnabled && notificationService != null) {
            try {
                def ownerId = resolveSteamOwnerId(wallet)
                if (ownerId != null) {
                    notificationService.safePush(ownerId, 'PAYOUTS_ENABLED',
                        "Payouts enabled",
                        "Your payout account is verified — you can now withdraw your balance from the Wallet page.",
                        wallet.id, '/wallet')
                }
            } catch (Exception e) {
                log.warn("PAYOUTS_ENABLED notify failed for wallet ${wallet.id}: ${e.message}")
            }
        }
    }

    /**
     * Card-testing detector (batch 501; multi-pod-hardened wave 148). Each
     * `payment_intent.payment_failed` is recorded against the wallet id
     * stamped in the PI metadata at Checkout-Session creation time. When a
     * wallet's failure count in the trailing CARD_TEST_WINDOW_MS hits
     * CARD_TEST_THRESHOLD, fan out a `CARD_TESTING_DETECTED` notification to
     * every admin so they can preemptively freeze the wallet before the
     * attacker finds a working card.
     *
     * WAVE 148 — cross-pod count. Pre-fix the failure COUNT lived ONLY in the
     * per-JVM {@link #recentFailuresByWallet} map. On a multi-pod deploy
     * Stripe load-balances webhook deliveries, so an attacker spreading
     * declines across pods accumulated only a fraction of the count on any
     * single pod, never crossed the per-pod threshold, and the alert never
     * fired — distributed card-testing evaded detection. Now each failure
     * INSERTs a {@link com.sboxmarket.model.WalletPaymentFailure} row and the
     * threshold is evaluated against
     * {@code countByWalletIdAndFailedAtAfter(walletId, cutoff)} — a DB
     * aggregate summed across EVERY pod's rows, so it sees the full cross-pod
     * volume. The in-memory map survives as a fast-path cache only (it short-
     * circuits the alert path on a pod that has ALREADY alerted this window).
     *
     * CROSS-POD ALERT DEDUP. Even with a shared count, two pods that both
     * push a wallet over the threshold in the same window would each fan the
     * bell to every admin (N pods = N× the noise). The alert is therefore
     * gated behind a cluster-wide claim in the existing fraud_signal_claims
     * ledger (V70), signature {@code card_test_alert:<walletId>:<windowBucket>}:
     * whichever pod's INSERT wins fans out, the losers bail (catching the
     * UNIQUE-index DataIntegrityViolationException, same shape as
     * {@link #claimStripeEvent} / FraudAnalysisService.sweepAndPushFraudSignals).
     * The per-JVM {@link #cardTestAlertedAt} map is the fast-path layer in
     * front of that claim.
     *
     * FAILURE ISOLATION. Every DB op here (insert, count, prune, claim) is
     * wrapped so a fraud-tracking failure can NEVER break Stripe webhook
     * handling — a transient DB error degrades the count to the in-memory
     * fast-path rather than 500-ing the webhook. (Note: handleWebhookEvent
     * calls this OUTSIDE any money mutation; this method touches neither the
     * fee/money math nor the deposit/withdraw paths — only the fraud COUNT.)
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

        // ── Fast-path cache (per-JVM) ─────────────────────────────────────
        // Append + prune in one critical section per wallet. The list is
        // small (a single attack rarely exceeds 20 attempts before we alert
        // and admins act), so an O(n) prune is fine. This is now a CACHE in
        // front of the DB count, not the source of truth.
        def cached = recentFailuresByWallet.compute(walletId, { _, existing ->
            def list = existing ?: new java.util.ArrayList<Long>()
            list.add(now)
            list.removeIf { (it as Long) < windowStart }
            list
        })

        // ── Authoritative cross-pod count (DB, isolated tx) ───────────────
        // INSERT this failure, then COUNT this wallet's failures in the
        // window across ALL pods, then best-effort prune old rows. All three
        // run inside trackFailureAndCount's REQUIRES_NEW transaction so a
        // fraud-tracking failure — even one that would mark a transaction
        // rollback-only — is fully contained and can never break the
        // surrounding webhook transaction. On any DB error we fall back to
        // the per-JVM cache size (degraded single-process behaviour) rather
        // than throwing. When the repo is unwired (unit test / context-less
        // build) the cache size IS the count — identical to the pre-fix
        // posture.
        long dbCount = trackFailureAndCount(walletId, now, windowStart)
        long failureCount = (dbCount >= 0L) ? dbCount : (long) cached.size()

        log.warn("payment_intent.payment_failed wallet=${walletId} pi=${pi.id} reason=${pi.lastPaymentError?.code ?: 'unknown'} (${failureCount} failures in last ${CARD_TEST_WINDOW_MS / 60_000L}min, cross-pod)")
        if (failureCount < CARD_TEST_THRESHOLD) return

        // ── Per-wallet alert dedupe (per-JVM fast-path) ───────────────────
        // Only fire once per window on THIS pod — short-circuits the cluster
        // claim round-trip for the common single-pod case.
        def lastAlertedAt = cardTestAlertedAt.get(walletId)
        if (lastAlertedAt != null && lastAlertedAt > windowStart) return

        // ── Cluster-wide alert claim (cross-pod dedup) ────────────────────
        // Reuse the fraud_signal_claims ledger (V70) so the bell fans out
        // exactly ONCE per wallet per window across the whole cluster. Bucket
        // the window so the signature is stable for the duration of one
        // attack window. Loser pods (and re-deliveries) bail before the
        // fan-out. Fully isolated: a claim DB error fails OPEN to the per-JVM
        // dedupe (better to occasionally double-alert on a DB blip than to
        // miss a card-testing alert), and never breaks the webhook.
        if (!claimCardTestAlert(walletId, now)) return
        cardTestAlertedAt.put(walletId, now)

        // Fan out to admins. Same shape as chargeback fan-out — role-
        // indexed query, per-row safePush, kind="CARD_TESTING_DETECTED".
        if (notificationService != null && steamUserRepository != null) {
            try {
                def reason = pi.lastPaymentError?.code ?: 'unknown'
                // Batch 632: safePush — a single admin push failure must
                // not break the fan-out for the rest of the admin pool.
                steamUserRepository.findByRole('ADMIN').each { admin ->
                    notificationService.safePush(admin.id, 'CARD_TESTING_DETECTED',
                        "⚠ Card-testing on wallet ${walletId}",
                        "${failureCount} declined deposit attempts in the last ${CARD_TEST_WINDOW_MS / 60_000L}min " +
                            "(latest: ${reason}). Consider freezing the wallet via the Users tab before the attacker finds a working card.",
                        walletId,
                        '/admin?tab=users')
                }
            } catch (Exception e) {
                log.warn("CARD_TESTING_DETECTED admin fan-out failed for wallet ${walletId}: ${e.message}")
            }
        }
    }

    /**
     * Cluster-wide claim for the CARD_TESTING_DETECTED alert (wave 148).
     * Returns {@code true} when THIS pod won the right to fan out the alert
     * for {@code walletId} in the current window, {@code false} when a
     * sibling pod (or a prior delivery on any pod) already alerted this
     * window — in which case the caller suppresses the duplicate fan-out.
     *
     * Reuses the existing {@link com.sboxmarket.repository.FraudSignalClaimRepository}
     * (V70 fraud_signal_claims) rather than adding a second claim table — the
     * signature {@code card_test_alert:<walletId>:<windowBucket>} namespaces
     * the card-test alert away from the fraud sweeper's signatures. Same
     * "existence check then authoritative INSERT, catch DataIntegrityViolation
     * as a lost race" shape as {@link #claimStripeEvent}.
     *
     * Degraded posture when the repo is unwired (unit test / context-less
     * build): return {@code true} so the per-JVM {@link #cardTestAlertedAt}
     * dedupe is the sole gate — identical to the single-process pre-fix
     * behaviour. A transient DB error also fails OPEN (return {@code true}):
     * better to occasionally double-alert on a DB blip than to silently
     * swallow a card-testing alert, and the UNIQUE index still backstops a
     * true duplicate on the INSERT.
     */
    private boolean claimCardTestAlert(Long walletId, long now) {
        if (fraudSignalClaimRepository == null) return true
        long windowBucket = now.intdiv(CARD_TEST_WINDOW_MS)
        String signature = "card_test_alert:${walletId}:${windowBucket}".toString()
        try {
            if (fraudSignalClaimRepository.existsBySignature(signature)) return false
        } catch (Exception e) {
            // Fail open — fall through to the authoritative INSERT, which the
            // UNIQUE index still backstops.
            log.warn("Card-test alert claim existence-check failed for wallet ${walletId}: ${e.message}")
        }
        try {
            // INSERT inside its own REQUIRES_NEW transaction so a UNIQUE-index
            // DataIntegrityViolation on a cross-pod race is fully contained —
            // it can't mark the surrounding webhook transaction rollback-only.
            runIsolated {
                fraudSignalClaimRepository.save(new com.sboxmarket.model.FraudSignalClaim(
                    signature: signature, claimedAt: now))
            }
            return true
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Lost the cross-pod race — a sibling pod claimed this wallet's
            // window first and owns the fan-out. Suppress the duplicate.
            log.info("Card-test alert for wallet ${walletId} claimed by sibling pod — suppressing duplicate fan-out")
            return false
        } catch (Exception e) {
            // Any other DB error: fail open so we don't lose the alert.
            log.warn("Card-test alert claim insert failed for wallet ${walletId}: ${e.message}")
            return true
        }
    }

    /**
     * Record this failure + return the authoritative cross-pod windowed count
     * (wave 148). INSERTs a {@link com.sboxmarket.model.WalletPaymentFailure}
     * row, COUNTs this wallet's failures strictly after {@code windowStart}
     * across ALL pods, and best-effort prunes rows older than
     * {@link #CARD_TEST_FAILURE_RETENTION_MS}. All three statements run inside
     * ONE REQUIRES_NEW transaction (see {@link #runIsolated}) so a
     * fraud-tracking failure — including a caught exception that would
     * otherwise mark a transaction rollback-only — is fully contained and can
     * never poison the surrounding webhook transaction.
     *
     * Returns the cross-pod count, or {@code -1} when the count is
     * unavailable (repo unwired, or a DB error) so the caller falls back to
     * the per-JVM fast-path cache size. NEVER throws — the spec requires a
     * fraud-tracking failure to never break Stripe webhook handling.
     */
    private long trackFailureAndCount(Long walletId, long now, long windowStart) {
        if (walletPaymentFailureRepository == null) return -1L
        long count = -1L
        try {
            count = runIsolated {
                walletPaymentFailureRepository.save(new com.sboxmarket.model.WalletPaymentFailure(
                    walletId: walletId, failedAt: now))
                long c = walletPaymentFailureRepository.countByWalletIdAndFailedAtAfter(walletId, windowStart)
                // Best-effort retention prune (point 3) — drop rows older than
                // the retention horizon so the table stays bounded by
                // active-attack volume, not uptime. Inside the same isolated
                // tx; a prune failure rolls back only this isolated tx (its
                // own catch below degrades the count), never the webhook.
                try {
                    walletPaymentFailureRepository.deleteByFailedAtBefore(now - CARD_TEST_FAILURE_RETENTION_MS)
                } catch (Exception pe) {
                    log.warn("Card-test failure retention prune failed: ${pe.message}")
                }
                c
            }
        } catch (Exception e) {
            // Degrade to the in-memory fast-path count — never 500 the webhook
            // on a fraud-tracking DB blip.
            log.warn("Card-test failure tracking DB op failed for wallet ${walletId}: ${e.message}")
            return -1L
        }
        return count
    }

    /**
     * Run {@code work} in a fresh REQUIRES_NEW transaction so its DB effects
     * (and any rollback-marking) are isolated from the caller's transaction
     * (wave 148; same mechanism as AuditService's deferred-write template).
     * When no {@link #transactionManager} is wired (unit tests / context-less
     * builds), runs {@code work} inline — the degraded single-process posture
     * the specs exercise. Propagates whatever {@code work} throws so the
     * caller can distinguish a lost-race DataIntegrityViolation from other
     * errors.
     */
    private <T> T runIsolated(groovy.lang.Closure<T> work) {
        if (transactionManager == null) {
            return work.call()
        }
        def tt = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        tt.propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
        return (T) tt.execute({ status -> work.call() } as org.springframework.transaction.support.TransactionCallback)
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
        // Cents → dollars as exact BigDecimal (batch 657 fix). The old
        // `dispute.amount / 100.0` was a double divide and could render
        // a $49.99 dispute as "$49.99000000000001" in the audit log and
        // the admin/user notifications. movePointLeft(2) keeps it exact,
        // matching the deposit/refund display paths.
        def amount = dispute.amount != null ? new BigDecimal(dispute.amount).movePointLeft(2) : BigDecimal.ZERO
        if (!isFirstObservation) {
            log.info("CHARGEBACK retry — dispute=${dispute.id} already on file (tx=${tx?.id}), skipping audit + notify")
            return
        }
        log.error("CHARGEBACK opened — Stripe dispute=${dispute.id} amount=\$${amount} reason=${dispute.reason} charge=${chargeId} matchedTx=${tx?.id}")
        // Resolve the wallet owner so the audit row is visible on the
        // user's /security-activity feed. Pre-fix (null, null) — same
        // shape closed for sibling deposit/withdraw events in commits
        // de40340 + c13cd2c. A chargeback is the single most important
        // event for the user to see in their own audit feed (account
        // compromise indicator — somebody used their stolen card to
        // fund the wallet, now the legitimate card-holder is reversing
        // it). Falls back to null when the tx couldn't be matched (line
        // 1026 above — the unmatched-chargeback path still logs the
        // alert to admins). Best-effort: a missing tx/wallet must not
        // block the chargeback flow.
        Long chargebackSubject = null
        if (tx?.walletId != null) {
            try {
                def cbWallet = walletRepository.findById(tx.walletId).orElse(null)
                chargebackSubject = resolveSteamOwnerId(cbWallet)
            } catch (Exception ignore) { /* tolerant */ }
        }
        try {
            auditService?.log('CHARGEBACK_OPENED', null, chargebackSubject, tx?.id,
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
        // Cents → dollars as exact BigDecimal (batch 657 fix) — see the
        // same change in handleChargebackOpened. Prevents a $X.99 dispute
        // amount printing as $X.99000000000001 in the closed-dispute log
        // and the admin clawback-review notification.
        def amount = dispute.amount != null ? new BigDecimal(dispute.amount).movePointLeft(2) : BigDecimal.ZERO
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
            // Resolve the wallet owner so the user can see the auto-
            // resolution on their /security-activity feed. Same null
            // null fix-shape as the CHARGEBACK_OPENED edge a few lines
            // up. The dispute-cleared event matters most when the
            // user has been blocked from withdrawing — their feed
            // should be the first place that signals "you're unblocked".
            Long clearedSubject = null
            if (tx?.walletId != null) {
                try {
                    def cWallet = walletRepository.findById(tx.walletId).orElse(null)
                    clearedSubject = resolveSteamOwnerId(cWallet)
                } catch (Exception ignore) { /* tolerant */ }
            }
            try {
                auditService?.log('DISPUTE_CLEARED', null, clearedSubject, tx?.id,
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
            log.warn("confirm-deposit called with unknown sessionId=${redactSession(sessionId)}")
            throw new IllegalStateException("Unknown deposit session")
        }
        // Only PENDING deposits should be credited. Pre-fix this only
        // short-circuited on COMPLETED — every OTHER non-PENDING state
        // (DISPUTED / FAILED / EXPIRED / CANCELLED) fell through to the
        // wallet-credit path, which double-credits a tx that has already
        // transitioned out of PENDING. Concrete reproducer for the
        // DISPUTED case (the real-money hole): user deposits $100, the
        // checkout.session.completed webhook credits the wallet and flips
        // tx to COMPLETED, user files a chargeback, handleChargebackOpened
        // flips tx to DISPUTED (wallet stays at $100 — we don't auto-debit
        // on dispute-open). Then the synchronous /api/wallet/confirm-deposit
        // path runs from a re-loaded success_url tab (or any duplicate
        // delivery across an event-id-cache reset, e.g. a server restart):
        // tx.status is DISPUTED, the old check `== "COMPLETED"` returned
        // false, completeDeposit re-verified the (still paid) Stripe
        // session, and added another $100 to the wallet — the attacker
        // walks away with $200 in their wallet while Stripe is about to
        // claw back the original $100 via the chargeback. Treating any
        // non-PENDING tx as already-handled closes the gap and keeps the
        // idempotent-replay contract: a tx leaves PENDING exactly once,
        // and only the PENDING→COMPLETED edge credits the wallet.
        if (tx.status != "PENDING") {
            log.info("completeDeposit short-circuit: tx ${tx.id} already in terminal state ${tx.status} (sessionId=${redactSession(sessionId)})")
            return
        }
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
                // TRANSIENT failure — a Stripe API outage / timeout. Retrying
                // WILL succeed once Stripe recovers, so this must NOT be
                // thrown as IllegalState/IllegalArgument: StripeWebhookController
                // ACKs those exception types with 200 (treating them as
                // permanent domain failures), which would make Stripe stop
                // retrying the checkout.session.completed event — and a user
                // who genuinely paid would never see their wallet credited.
                // A plain RuntimeException falls through the webhook
                // controller's catch-all to a 500, so Stripe retries with
                // backoff; on the synchronous /confirm-deposit path it maps
                // to a 500 too (correct — a Stripe outage is a server fault,
                // not a client error), and the user can retry.
                log.warn("Stripe session retrieve failed for ${redactSession(sessionId)} (transient — retryable): ${e.message}")
                throw new RuntimeException("Stripe session could not be verified — temporary Stripe error, retry", e)
            }
            if (session == null) {
                throw new IllegalStateException("Stripe session not found")
            }
            def paymentStatus = session.paymentStatus  // 'paid' | 'unpaid' | 'no_payment_required'
            if (!'paid'.equalsIgnoreCase(paymentStatus)) {
                log.warn("confirm-deposit refused: session ${redactSession(sessionId)} payment_status=${paymentStatus}")
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
            // Currency must be USD. The amount check above is a bare cents
            // equality with no currency dimension, and the wallet ledger is
            // dollars. We only ever create USD sessions, so this rejects only
            // a tampered/future non-USD session whose minor-unit total happens
            // to equal expectedCents while representing a different real value
            // (e.g. a zero-decimal currency). Defense-in-depth beside the
            // amount / payment_status guards.
            if (session.currency != null && !'usd'.equalsIgnoreCase(session.currency as String)) {
                log.error("confirm-deposit refused: session currency=${session.currency} != usd")
                throw new IllegalStateException("Currency mismatch")
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

        // Resolve the wallet owner up-front so we can pass them as the
        // audit subject AND as the notification recipient. Pre-fix the
        // audit row was logged with (null, null, tx.id) — same shape
        // as the WITHDRAW_REQUESTED bug closed in commit de40340 — so a
        // user's own DEPOSIT_COMPLETE row was invisible to
        // ProfileController /security-activity (filters via
        // auditLogRepository.bySubject on subjectUserId = uid and
        // explicitly white-lists DEPOSIT_COMPLETE). A user whose
        // session was hijacked to fund the attacker's wallet via a
        // stolen-card deposit had no record of those credits in their
        // own security history feed — exactly the audit surface the
        // feed exists to expose. Lookup follows the same
        // Wallet.username → SteamUser.id chain the notification block
        // below already used; lifted up so both the audit and the
        // push share one round-trip and one fallback branch.
        Long ownerUserId = null
        if (steamUserRepository != null) {
            try {
                def uname = wallet.username ?: ''
                if (uname.startsWith('steam_')) {
                    def owner = steamUserRepository.findBySteamId64(uname.substring('steam_'.length()))
                    ownerUserId = owner?.id
                }
            } catch (Exception ignore) { /* tolerant — audit/push survive a null id */ }
        }
        try {
            auditService?.log(AuditService.DEPOSIT_COMPLETE, ownerUserId, ownerUserId, tx.id,
                "Deposit \$${tx.amount} credited to wallet ${wallet.username} (stripe=${sessionId})")
        } catch (Exception ignore) {}
        // Notification push (batch 457) — closes the silent-success gap
        // where a user who deposited and switched tabs got NO signal that
        // their wallet had been credited. Bell + tab-title now show the
        // event the moment Stripe's webhook lands. Resolves user via
        // Wallet.username = "steam_<steamId64>" → SteamUser. Failure-
        // tolerant: a notify miss is logged and the deposit still
        // completes (the wallet balance + tx row are the source of truth).
        if (notificationService != null && ownerUserId != null) {
            // Batch 632: safePush — deposit money is already credited above;
            // a push failure must not roll back the wallet credit + audit row.
            try {
                notificationService.safePush(ownerUserId, 'DEPOSIT_COMPLETE',
                    "Deposit complete · +\$${tx.amount?.toPlainString() ?: '0.00'}",
                    "New balance: \$${wallet.balance?.toPlainString() ?: '0.00'}",
                    tx.id,
                    '/wallet')
            } catch (Exception e) {
                log.warn("Deposit-complete push failed for tx=${tx.id}: ${e.message}")
            }
        }
        log.info("Deposit \$${tx.amount} credited to wallet ${tx.walletId} (session ${redactSession(sessionId)})")
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
        // Match the live path — credit whole cents only (see createDepositSession).
        amount = amount.setScale(2, java.math.RoundingMode.HALF_UP)
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
    // NOT @Transactional — wave 126 migrated this from a single shared outer
    // tx (whole-batch saveAll inside one tx) to per-row atomic conditional
    // UPDATEs. Two reasons:
    //
    //  1. Multi-pod race: `findStalePending` was read concurrently by every
    //     pod's 4-hourly sweeper, and both pods saw the SAME `status=PENDING`
    //     row. The old code then ran the DEPOSIT_EXPIRED bell push on BOTH
    //     pods before either pod's `saveAll` committed — user received "your
    //     deposit expired" TWICE for one stale Stripe session. The fix is a
    //     per-row claimExpirePending UPDATE that flips status PENDING→EXPIRED
    //     only WHERE status is still PENDING and returns 1 to the winning
    //     pod / 0 to the losing pod (or 0 if a Stripe webhook landed
    //     concurrently and completed the deposit).
    //  2. Out-of-band Stripe-webhook race: a webhook landing between sweeper
    //     read and the UPDATE flips the row to CONFIRMED / DISPUTED. The old
    //     unconditional `saveAll(stale)` would have stomped that with
    //     EXPIRED — wiping a real wallet credit's audit row AND firing a
    //     misleading "deposit expired" notification on top of credited
    //     balance. The conditional UPDATE skips those rows entirely.
    //
    // Per-row work runs in its own implicit auto-commit tx so one bad row
    // can't poison sibling rows in the batch.
    @Scheduled(fixedDelay = 4L * 60L * 60L * 1000L, initialDelay = 5L * 60L * 1000L)
    void sweepStalePendingDeposits() {
        def cutoff = System.currentTimeMillis() - (48L * 60L * 60L * 1000L)
        def stale = transactionRepository.findStalePending('DEPOSIT', cutoff)
        if (stale.isEmpty()) return
        log.info("Deposit sweeper: ${stale.size()} candidate stale PENDING deposit(s); racing for claims")

        int fired = 0
        for (Transaction tx : stale) {
            try {
                // Multi-pod / out-of-band claim. See class-level comment
                // above for the two race classes this closes.
                int claimed = transactionRepository.claimExpirePending(tx.id)
                if (claimed == 0) {
                    log.debug("Deposit-expire claim lost for tx=${tx.id} — sibling pod or webhook completion")
                    continue
                }
                fired++

                if (notificationService == null || steamUserRepository == null) continue
                try {
                    def wallet = walletRepository.findById(tx.walletId).orElse(null)
                    if (wallet == null) continue
                    def uname = wallet.username ?: ''
                    if (!uname.startsWith('steam_')) continue
                    def steamId = uname.substring('steam_'.length())
                    def user = steamUserRepository.findBySteamId64(steamId)
                    if (user == null) continue
                    notificationService.safePush(user.id, 'DEPOSIT_EXPIRED',
                        "Deposit expired",
                        "Your \$${tx.amount?.toPlainString() ?: '0.00'} deposit was auto-expired after 48h without completion. If you still want to top up, start a fresh deposit.",
                        tx.id,
                        '/wallet')
                } catch (Exception e) {
                    log.warn("Deposit-expired lookup failed for tx=${tx.id}: ${e.message}")
                }
            } catch (Exception e) {
                log.warn("Deposit sweeper failed on tx=${tx.id}: ${e.message}")
            }
        }
        log.info("Deposit sweeper: fired ${fired} of ${stale.size()} candidates (rest claimed by sibling pods or completed by webhook)")
    }
}
