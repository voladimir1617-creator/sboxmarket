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
