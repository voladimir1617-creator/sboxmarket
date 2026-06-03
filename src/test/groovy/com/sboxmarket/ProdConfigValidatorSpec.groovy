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
            STRIPE_SECRET_KEY:          'sk_live_abc123',
            STRIPE_PUBLISHABLE_KEY:     'pk_live_abc123',
            STRIPE_WEBHOOK_SECRET:      'whsec_abc123',
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

    def "the dev placeholder constant matches the value committed in EmailService"() {
        expect: 'guard against the constant drifting from EmailService.groovy:64'
        ProdConfigValidator.DEV_UNSUBSCRIBE_PLACEHOLDER == 'dev-only-do-not-use-in-production-7f3a9c'
    }
}
