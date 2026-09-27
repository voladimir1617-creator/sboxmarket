package com.sboxmarket

import com.sboxmarket.config.ProdConfigValidator
import org.springframework.core.env.Environment
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for the prod fail-fast config guard.
 *
 * The guard's detection logic lives in findViolations(), a pure function of
 * the Spring {@link Environment}, deliberately separated from the
 * ApplicationReadyEvent handler that calls System.exit(1). That separation is
 * what makes these tests possible: we exercise findViolations() against a
 * mock Environment and assert on the returned violation list — we never let
 * the real System.exit fire (which would kill the test JVM).
 *
 * Scenarios:
 *   1) all required vars present, no placeholders   → no violations
 *   2) one required var missing                     → one violation naming it
 *   3) STRIPE_SECRET_KEY is an sk_test_ key         → violation, refuse to start
 *   4) APP_UNSUBSCRIBE_SECRET is the dev placeholder → violation, refuse to start
 */
class ProdConfigValidatorSpec extends Specification {

    Environment environment = Mock()

    @Subject
    ProdConfigValidator validator = new ProdConfigValidator(environment: environment)

    /** A full set of plausible, production-shaped values for every required
     *  var. Tests mutate one entry to drive a specific failure. */
    private static Map<String, String> validEnv() {
        [
            SPRING_DATASOURCE_URL:      'jdbc:postgresql://db.host:5432/skinbox',
            SPRING_DATASOURCE_USERNAME: 'skinbox',
            SPRING_DATASOURCE_PASSWORD: 's3cr3t',
            CORS_ALLOWED_ORIGINS:       'https://skinbox.market',
            ADMIN_BOOTSTRAP_STEAM_IDS:  '76561197960287930',
            // Structurally live-shaped but transparently synthetic (all-zero
            // bodies). These are NOT credentials and cannot authenticate to
            // anything; they exist only to satisfy the shape checks below so
            // that a test driving one specific failure isn't also tripping the
            // others. Length matters: the guard rejects a truncated key.
            STRIPE_SECRET_KEY:          'sk_live_' + ('0' * 24),
            STRIPE_PUBLISHABLE_KEY:     'pk_live_' + ('0' * 24),
            STRIPE_WEBHOOK_SECRET:      'whsec_' + ('0' * 32),
            STRIPE_SUCCESS_URL:         'https://skinbox.market/deposit/success',
            STRIPE_CANCEL_URL:          'https://skinbox.market/deposit/cancel',
            STEAM_REALM:                'https://skinbox.market',
            STEAM_RETURN_URL:           'https://skinbox.market/auth/steam/return',
            APP_UNSUBSCRIBE_SECRET:     'a-real-random-prod-secret-9912ff',
            APP_PUBLIC_URL:             'https://skinbox.market',
        ]
    }

    /** Wire the mock Environment to answer getProperty(name) from a map. */
    private void stubEnv(Map<String, String> values) {
        environment.getProperty(_ as String) >> { String name -> values[name] }
    }

    def "all required vars present and no dev placeholders → no violations"() {
        given:
        stubEnv(validEnv())

        expect:
        validator.findViolations().isEmpty()
    }

    def "a missing required var (#missing) → a single violation naming it"() {
        given: 'one required var blanked out'
        Map<String, String> env = validEnv()
        env.remove(missing)
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then: 'exactly that var is reported as missing'
        violations.size() == 1
        violations[0].contains(missing)
        violations[0].toLowerCase().contains('missing')

        where:
        missing << ['STRIPE_SECRET_KEY', 'SPRING_DATASOURCE_URL', 'STEAM_RETURN_URL']
    }

    def "a blank (whitespace-only) required var counts as missing"() {
        given:
        Map<String, String> env = validEnv()
        env.CORS_ALLOWED_ORIGINS = '   '
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then:
        violations.size() == 1
        violations[0].contains('CORS_ALLOWED_ORIGINS')
    }

    def "multiple missing vars are all aggregated, not just the first"() {
        given:
        Map<String, String> env = validEnv()
        env.remove('STRIPE_SECRET_KEY')
        env.remove('STRIPE_WEBHOOK_SECRET')
        env.remove('STEAM_REALM')
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then:
        violations.size() == 3
        violations.any { it.contains('STRIPE_SECRET_KEY') }
        violations.any { it.contains('STRIPE_WEBHOOK_SECRET') }
        violations.any { it.contains('STEAM_REALM') }
    }

    def "STRIPE_SECRET_KEY that is a test key (sk_test_) → refuse to start"() {
        given: 'a present-but-test Stripe key; everything else valid'
        Map<String, String> env = validEnv()
        env.STRIPE_SECRET_KEY = 'sk_test_51HxYzFAKEtestkey'
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then: 'flagged as a test key, refusing to start — and NOT as missing'
        violations.size() == 1
        violations[0].contains('STRIPE_SECRET_KEY')
        violations[0].contains('sk_test_')
        violations[0].toLowerCase().contains('refusing to start')
    }

    def "APP_UNSUBSCRIBE_SECRET equal to the committed dev placeholder → refuse to start"() {
        given:
        Map<String, String> env = validEnv()
        env.APP_UNSUBSCRIBE_SECRET = ProdConfigValidator.DEV_UNSUBSCRIBE_PLACEHOLDER
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then:
        violations.size() == 1
        violations[0].contains('APP_UNSUBSCRIBE_SECRET')
        violations[0].toLowerCase().contains('refusing to start')
    }

    def "APP_PUBLIC_URL pointing at localhost → refuse to start"() {
        given: 'a present-but-localhost public URL (copy-pasted from local-prod); everything else valid'
        Map<String, String> env = validEnv()
        env.APP_PUBLIC_URL = badUrl
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then: 'flagged as a localhost URL, refusing to start — and NOT as missing'
        violations.size() == 1
        violations[0].contains('APP_PUBLIC_URL')
        violations[0].toLowerCase().contains('refusing to start')

        where:
        badUrl << ['http://localhost:8080', 'http://127.0.0.1:8082', 'https://localhost']
    }

    def "STRIPE_WEBHOOK_SECRET equal to the committed placeholder → refuse to start"() {
        given: 'the webhook secret left at the published placeholder; everything else valid'
        Map<String, String> env = validEnv()
        env.STRIPE_WEBHOOK_SECRET = ProdConfigValidator.STRIPE_WEBHOOK_PLACEHOLDER
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then: 'flagged as the forgeable-webhook placeholder, refusing to start — not as missing'
        violations.size() == 1
        violations[0].contains('STRIPE_WEBHOOK_SECRET')
        violations[0].toLowerCase().contains('refusing to start')
    }

    def "the dev placeholder constant matches the value committed in EmailService"() {
        expect: 'guard against the constant drifting from EmailService.groovy:64'
        ProdConfigValidator.DEV_UNSUBSCRIBE_PLACEHOLDER == 'dev-only-do-not-use-in-production-7f3a9c'
    }

    /* ── The isLive() trap ────────────────────────────────────────────
     * StripeService.isLive() is only:
     *
     *     secretKey && !secretKey.contains("replace_me")
     *
     * so ANY non-blank value that doesn't contain that literal flips the
     * service into live mode. Before these guards, the sk_test_ check was
     * the ONLY structural check on the key — which left a whole family of
     * plausible-but-wrong values that boot "live" and fail at the FIRST
     * REAL CHARGE, i.e. in front of a paying customer:
     *
     *   - the publishable key pasted into the secret slot (easy to do:
     *     the dashboard shows both together, and only one is secret)
     *   - the webhook signing secret pasted into the secret slot
     *   - a truncated copy-paste (`sk_live_` + a few chars)
     *   - a human placeholder: "changeme", "TODO", "dummy"
     *
     * Failing at boot instead is the whole point of this class. */
    def "STRIPE_SECRET_KEY that is live-reading but structurally wrong (#badKey) → refuse to start"() {
        given: 'a non-blank secret key that isLive() would happily accept'
        Map<String, String> env = validEnv()
        env.STRIPE_SECRET_KEY = badKey
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then: 'rejected at boot, naming the var — never left to fail at the first charge'
        violations.size() == 1
        violations[0].contains('STRIPE_SECRET_KEY')
        violations[0].toLowerCase().contains('refusing to start')

        where:
        badKey << [
            'pk_live_' + ('0' * 24),   // publishable key in the secret slot
            'whsec_' + ('0' * 32),     // webhook secret in the secret slot
            'sk_live_abc',             // truncated copy-paste
            'sk_live_',                // prefix only
            'changeme',
            'TODO',
            'dummy',
            'your-secret-key-here',
        ]
    }

    /* The compose file's own default. ProdConfigValidator rejected
     * `whsec_replace_me` but NOT `whsec_dummy`, so the documented
     * `docker compose up` path booted a "live" app whose every webhook
     * failed signature verification — the card is charged and the wallet is
     * NEVER credited, because completeDeposit only ever runs off a verified
     * event. Silent, and on the money path. */
    def "STRIPE_WEBHOOK_SECRET that is a dummy or truncated value (#badSecret) → refuse to start"() {
        given:
        Map<String, String> env = validEnv()
        env.STRIPE_WEBHOOK_SECRET = badSecret
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then:
        violations.size() == 1
        violations[0].contains('STRIPE_WEBHOOK_SECRET')
        violations[0].toLowerCase().contains('refusing to start')

        where:
        badSecret << [
            'whsec_dummy',             // the docker-compose.yml default
            'whsec_',
            'sk_live_' + ('0' * 24),   // secret key in the webhook slot
            'changeme',
        ]
    }

    def "STRIPE_PUBLISHABLE_KEY that is a test or junk key (#badKey) → refuse to start"() {
        given:
        Map<String, String> env = validEnv()
        env.STRIPE_PUBLISHABLE_KEY = badKey
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then:
        violations.size() == 1
        violations[0].contains('STRIPE_PUBLISHABLE_KEY')
        violations[0].toLowerCase().contains('refusing to start')

        where:
        badKey << [
            'pk_test_dummy',           // the docker-compose.yml default
            'pk_live_abc',             // truncated
            'sk_live_' + ('0' * 24),   // SECRET key in the publishable slot
            'changeme',
        ]
    }

    /* A restricted key (rk_live_) is a legitimate production choice — it is
     * how you give a deployment only the scopes it needs. The guard must not
     * force the operator onto a full-access key. */
    def "a live restricted key (rk_live_) is accepted"() {
        given:
        Map<String, String> env = validEnv()
        env.STRIPE_SECRET_KEY = 'rk_live_' + ('0' * 24)
        stubEnv(env)

        expect:
        validator.findViolations().isEmpty()
    }

    /* Pins the exact docker-compose.yml defaults. If someone re-adds a
     * plausible default for the secret key, or relaxes these, this fails. */
    def "the docker-compose defaults cannot boot a prod instance"() {
        given: 'the compose defaults for the two Stripe vars that HAVE defaults'
        Map<String, String> env = validEnv()
        env.STRIPE_PUBLISHABLE_KEY = 'pk_test_dummy'
        env.STRIPE_WEBHOOK_SECRET = 'whsec_dummy'
        stubEnv(env)

        when:
        List<String> violations = validator.findViolations()

        then: 'both are refused — neither silently reaches a real customer'
        violations.size() == 2
        violations.any { it.contains('STRIPE_PUBLISHABLE_KEY') }
        violations.any { it.contains('STRIPE_WEBHOOK_SECRET') }
    }
}
