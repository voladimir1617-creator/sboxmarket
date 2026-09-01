package com.sboxmarket.config

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Fail-fast guard for the `prod` profile.
 *
 * Background: application-prod.yml templates every secret as a bare
 * `${VAR}` with NO default. Spring resolves those placeholders lazily —
 * the first bean that needs one (Stripe, the datasource, …) explodes with
 * a cryptic `Could not resolve placeholder 'STRIPE_SECRET_KEY'` buried in
 * a 60-line autoconfig stack trace. An operator who fat-fingered one env
 * var on a deploy gets an opaque crash instead of a clear "you forgot X".
 *
 * This bean (prod-only) reads each mandatory secret straight off the
 * {@link Environment} on {@link ApplicationReadyEvent}, aggregates EVERY
 * problem into one list, logs a single FATAL line naming exactly which
 * vars are missing / still set to a known dev placeholder, and aborts the
 * JVM with {@code System.exit(1)}. One readable failure, not a stack trace
 * treasure hunt.
 *
 * Two classes of violation:
 *
 *   1) **Missing** — the env var is absent or blank, so the corresponding
 *      `${VAR}` would fail placeholder resolution (or silently run on a
 *      blank secret). Reported as "missing".
 *
 *   2) **Dangerous placeholder** — the var IS set but to a value that must
 *      never reach production:
 *        - APP_UNSUBSCRIBE_SECRET == the committed dev placeholder
 *          (`dev-only-do-not-use-in-production-7f3a9c`, see
 *          EmailService.groovy:64). Anyone reading the public source could
 *          forge one-click unsubscribe tokens for any user.
 *        - STRIPE_SECRET_KEY starting with `sk_test_` — a Stripe TEST key
 *          in prod means Checkout "succeeds" but no real money ever moves;
 *          every deposit is fake. Refuse to start.
 *
 * Why a separate method ({@link #findViolations}) instead of doing it all
 * in the event handler: the detection logic is a pure function of the
 * Environment and is unit-tested directly (ProdConfigValidatorSpec) with a
 * mock Environment — no real {@code System.exit} in tests. The handler is
 * the only place the process actually dies.
 */
@Component
@Profile('prod')
@Slf4j
class ProdConfigValidator {

    /** The publicly-committed dev fallback for the unsubscribe HMAC secret.
     *  Mirrors the @Value default in EmailService.groovy:64. If this string
     *  is what's live in prod, unsubscribe tokens are forgeable by anyone
     *  with the source — refuse to start. */
    static final String DEV_UNSUBSCRIBE_PLACEHOLDER = 'dev-only-do-not-use-in-production-7f3a9c'

    /** Stripe test-mode secret keys start with this prefix. A test key in
     *  prod silently processes zero real charges. */
    static final String STRIPE_TEST_KEY_PREFIX = 'sk_test_'

    /** The publicly-committed dev fallback for the Stripe webhook signing
     *  secret (application.yml). If this is what's live in prod, anyone with
     *  the source can forge `checkout.session.completed` events that pass
     *  Webhook.constructEvent and credit wallets — refuse to start. Mirrors
     *  the sk_test_ / DEV_UNSUBSCRIBE_PLACEHOLDER guards. */
    static final String STRIPE_WEBHOOK_PLACEHOLDER = 'whsec_replace_me'

    /** Prefixes a *live* Stripe secret may carry. `rk_live_` (a restricted
     *  key, scoped to only the permissions this deployment needs) is a
     *  legitimate — arguably better — production choice than a full-access
     *  `sk_live_`, so both are accepted. */
    static final List<String> STRIPE_LIVE_SECRET_PREFIXES = ['sk_live_', 'rk_live_'].asImmutable()

    /** Live publishable keys. This one is not secret (it ships to the browser),
     *  but a `pk_test_` here still means the Checkout the customer sees is a
     *  test-mode Checkout. */
    static final String STRIPE_LIVE_PUBLISHABLE_PREFIX = 'pk_live_'

    /** Stripe webhook signing secrets. */
    static final String STRIPE_WEBHOOK_PREFIX = 'whsec_'

    /** The substring {@code StripeService.isLive()} keys off:
     *  {@code secretKey && !secretKey.contains('replace_me')}.
     *
     *  The shape check above and isLive() ask different questions, and there
     *  is exactly one family of values where they DISAGREE — and it fails
     *  OPEN. `sk_live_replace_me…` starts with a live prefix and is long
     *  enough, so the shape check passes and prod boots reporting a clean
     *  config; but isLive() sees `replace_me` and returns FALSE, so
     *  createDepositSession falls straight through to devModeDeposit and the
     *  deployment credits wallets against no payment — the exact free-money
     *  path this validator exists to close, reached THROUGH a green boot.
     *
     *  No real Stripe key contains this marker, so rejecting it cannot lock
     *  out a legitimate deploy. Pinned by ProdScaffoldingUnreachableSpec,
     *  which asserts the two checks can never disagree. */
    static final String STRIPE_DEV_KEY_MARKER = 'replace_me'

    /** Minimum number of characters AFTER the prefix. Real Stripe keys and
     *  signing secrets carry far more than this; the threshold exists to catch
     *  a truncated copy-paste (`sk_live_abc`) and short human placeholders
     *  (`whsec_dummy`), not to police Stripe's exact key length — which Stripe
     *  has changed before and may change again. */
    static final int STRIPE_MIN_BODY_CHARS = 16

    /**
     * True when {@code value} starts with one of {@code prefixes} AND carries at
     * least {@link #STRIPE_MIN_BODY_CHARS} characters after it.
     *
     * Deliberately a shape check, not a validity check: only Stripe can say
     * whether a well-formed key is a real one. The point is to convert the
     * failures we CAN detect locally from a first-charge failure into a boot
     * failure.
     */
    private static boolean wellFormedStripeValue(String value, List<String> prefixes, int minBody) {
        prefixes.any { String p -> value.startsWith(p) && (value.length() - p.length()) >= minBody }
    }

    /** Every env var that application-prod.yml templates as a bare `${VAR}`
     *  with no default and that the app cannot meaningfully run without.
     *  (STEAM_API_KEY, ACTUATOR_PORT, COOKIE_SECURE, SWAGGER_ENABLED, LOG_FILE,
     *  SPRING_DATASOURCE_DRIVER all HAVE defaults in the yaml, so they're not
     *  here.)
     *
     *  APP_PUBLIC_URL is the one exception that DOES have a default
     *  (`http://localhost:${server.port}` in application.yml). It's listed
     *  anyway because that default is actively dangerous in prod: EmailService
     *  absolutizes every email CTA against it and StripeService uses it for the
     *  Stripe Connect onboarding return/refresh URLs, so an unset value ships
     *  `http://localhost:8080` links in real mail and breaks seller onboarding.
     *  Better to fail-fast than to send customers broken localhost links. */
    static final List<String> REQUIRED_VARS = [
        'SPRING_DATASOURCE_URL',
        'SPRING_DATASOURCE_USERNAME',
        'SPRING_DATASOURCE_PASSWORD',
        'CORS_ALLOWED_ORIGINS',
        'ADMIN_BOOTSTRAP_STEAM_IDS',
        'STRIPE_SECRET_KEY',
        'STRIPE_PUBLISHABLE_KEY',
        'STRIPE_WEBHOOK_SECRET',
        'STRIPE_SUCCESS_URL',
        'STRIPE_CANCEL_URL',
        'STEAM_REALM',
        'STEAM_RETURN_URL',
        'APP_UNSUBSCRIBE_SECRET',
        'APP_PUBLIC_URL',
    ].asImmutable()

    @Autowired
    Environment environment

    /**
     * Pure check — returns a list of human-readable violation strings, one
     * per problem, in a stable order. Empty list == config is production-safe.
     *
     * Does NOT touch System.exit / throw — that's the event handler's job —
     * so it is trivially unit-testable against a mock Environment.
     */
    List<String> findViolations() {
        List<String> violations = []

        // 1) Presence — every required var must be set and non-blank.
        for (String var : REQUIRED_VARS) {
            String value = environment.getProperty(var)
            if (value == null || value.trim().isEmpty()) {
                violations.add("${var} is missing (set this environment variable)".toString())
            }
        }

        // 2) Dangerous-but-present placeholders. Only meaningful when the
        //    var is actually set; a missing var is already reported above,
        //    so guard on non-blank to avoid a duplicate/confusing message.
        String unsubscribe = environment.getProperty('APP_UNSUBSCRIBE_SECRET')
        if (unsubscribe != null && unsubscribe == DEV_UNSUBSCRIBE_PLACEHOLDER) {
            violations.add('APP_UNSUBSCRIBE_SECRET is the committed dev placeholder ' +
                "'${DEV_UNSUBSCRIBE_PLACEHOLDER}' — refusing to start (unsubscribe tokens would be forgeable)".toString())
        }

        String stripeKey = environment.getProperty('STRIPE_SECRET_KEY')
        if (stripeKey != null && stripeKey.startsWith(STRIPE_TEST_KEY_PREFIX)) {
            violations.add("STRIPE_SECRET_KEY is a Stripe TEST key (starts with '${STRIPE_TEST_KEY_PREFIX}') " +
                '— refusing to start (no real charges would be processed in production)'.toString())
        } else if (stripeKey != null && !stripeKey.trim().isEmpty() &&
                   !wellFormedStripeValue(stripeKey, STRIPE_LIVE_SECRET_PREFIXES, STRIPE_MIN_BODY_CHARS)) {
            // THE isLive() TRAP. StripeService.isLive() is only
            // `secretKey && !secretKey.contains("replace_me")`, so every value
            // caught here — the publishable key pasted into the secret slot, a
            // truncated key, "changeme" — reads as LIVE. Without this check the
            // app boots, reports itself live, and fails at the FIRST REAL
            // CHARGE, in front of a paying customer. Fail at boot instead.
            //
            // The value is never echoed: it may be a real secret. Only its
            // length is reported, which is what diagnoses a truncated paste.
            violations.add('STRIPE_SECRET_KEY is not a well-formed LIVE Stripe secret key — refusing to start. ' +
                "Expected a value starting with ${STRIPE_LIVE_SECRET_PREFIXES.join(' or ')} followed by at least " +
                "${STRIPE_MIN_BODY_CHARS} characters; got ${stripeKey.length()} characters total. " +
                '(The value is not shown here because it may be a real secret.) ' +
                'Note StripeService.isLive() would have accepted this and failed at the first real charge.'.toString())
        } else if (stripeKey != null && stripeKey.contains(STRIPE_DEV_KEY_MARKER)) {
            // The one input where the shape check and isLive() DISAGREE, and
            // it disagrees in the dangerous direction: live-shaped enough to
            // boot, but isLive() is false, so createDepositSession falls
            // through to devModeDeposit and credits wallets against no
            // payment — free money on a deployment that booted clean.
            violations.add("STRIPE_SECRET_KEY contains '${STRIPE_DEV_KEY_MARKER}' — refusing to start. " +
                'It is shaped like a live key, so this validator would otherwise pass it, but ' +
                'StripeService.isLive() tests for exactly this substring and would report the ' +
                'deployment as NOT live — routing every deposit into devModeDeposit, which credits ' +
                'the wallet against no payment.'.toString())
        }

        String publishableKey = environment.getProperty('STRIPE_PUBLISHABLE_KEY')
        if (publishableKey != null && !publishableKey.trim().isEmpty() &&
            !wellFormedStripeValue(publishableKey, [STRIPE_LIVE_PUBLISHABLE_PREFIX], STRIPE_MIN_BODY_CHARS)) {
            violations.add("STRIPE_PUBLISHABLE_KEY is not a well-formed LIVE publishable key (expected " +
                "'${STRIPE_LIVE_PUBLISHABLE_PREFIX}…') — refusing to start (the Checkout shown to customers " +
                'would be a test-mode Checkout, or the secret key has been pasted into the publishable slot)'.toString())
        }

        String webhookSecret = environment.getProperty('STRIPE_WEBHOOK_SECRET')
        if (webhookSecret != null && webhookSecret == STRIPE_WEBHOOK_PLACEHOLDER) {
            violations.add("STRIPE_WEBHOOK_SECRET is the committed placeholder " +
                "'${STRIPE_WEBHOOK_PLACEHOLDER}' — refusing to start (an attacker with the " +
                'source could forge Stripe webhook events and credit wallets)'.toString())
        } else if (webhookSecret != null && !webhookSecret.trim().isEmpty() &&
                   !wellFormedStripeValue(webhookSecret, [STRIPE_WEBHOOK_PREFIX], STRIPE_MIN_BODY_CHARS)) {
            // This is the one that bites SILENTLY, and docker-compose.yml's own
            // default (`whsec_dummy`) used to sail straight through: it is not
            // the `whsec_replace_me` placeholder, so nothing rejected it.
            //
            // A wrong signing secret does not stop a charge — it stops the
            // CREDIT. Webhook.constructEvent throws, handleWebhookEvent raises
            // SecurityException, the controller returns 400, and Stripe stops
            // retrying. The customer's card is charged and their wallet is
            // never credited, with no error anywhere the customer can see.
            violations.add("STRIPE_WEBHOOK_SECRET is not a well-formed Stripe signing secret (expected " +
                "'${STRIPE_WEBHOOK_PREFIX}…' plus at least ${STRIPE_MIN_BODY_CHARS} characters) — " +
                'refusing to start (signature verification would reject EVERY event, so a paid deposit ' +
                'would charge the card and never credit the wallet)'.toString())
        }

        // A present-but-localhost APP_PUBLIC_URL (e.g. copy-pasted from the
        // local-prod env file) passes the presence check above but is just as
        // broken in prod as an unset one — every absolutized email link and the
        // Stripe Connect onboarding URLs would point at the operator's box.
        String publicUrl = environment.getProperty('APP_PUBLIC_URL')
        if (publicUrl != null && (publicUrl.contains('localhost') || publicUrl.contains('127.0.0.1'))) {
            violations.add("APP_PUBLIC_URL points at localhost ('${publicUrl}') — refusing to start " +
                '(email CTAs and Stripe onboarding return URLs would be unreachable localhost links)'.toString())
        }

        return violations
    }

    /**
     * Runs once the context is fully up. Aggregates all violations into a
     * single ERROR line and aborts the process so the operator sees one
     * clear FATAL message instead of a downstream placeholder stack trace.
     */
    @EventListener(ApplicationReadyEvent)
    void validateOnReady() {
        List<String> violations = findViolations()
        if (!violations.isEmpty()) {
            log.error('FATAL: production config validation failed — refusing to start:\n  - {}',
                      violations.join('\n  - '))
            // Abort. ApplicationReadyEvent fires after the web server is
            // listening, so exit hard rather than throwing (a thrown
            // exception here would be logged but the JVM would keep running
            // and serve traffic on broken config). exit(1) is the
            // unambiguous "deploy is misconfigured" signal for the
            // orchestrator (Docker/k8s restart-loops it into view).
            System.exit(1)
        }
        log.info('Prod config validation passed: all {} required secrets present and no dev placeholders detected.',
                 REQUIRED_VARS.size())
    }
}
