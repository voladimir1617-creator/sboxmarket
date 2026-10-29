package com.sboxmarket

import com.sboxmarket.config.MoneyMode
import com.sboxmarket.service.StripeService
import org.springframework.mock.env.MockEnvironment
import spock.lang.Specification

/**
 * StripeService.moneyMode() decides whether a deposit is charged, simulated or
 * refused. Under the prod profile it must follow MoneyMode.of(env): only a live
 * key is an answer. Before, it asked ofKey(secretKey) alone, so a prod box whose
 * resolved key was a placeholder reported SIMULATED and, with the dev-credit
 * opt-in set, credited deposits against no payment.
 */
class ProdMoneyModeIgnoresNonLiveKeySpec extends Specification {

    private static StripeService service(String key, String... profiles) {
        def env = new MockEnvironment()
        if (profiles) env.setActiveProfiles(profiles)
        new StripeService(secretKey: key, environment: env)
    }

    def "under prod a non-live key (#key) is INDETERMINATE, never simulated or test"() {
        expect:
        service(key, 'prod').moneyMode() == MoneyMode.INDETERMINATE
        !service(key, 'prod').moneyMode().devFallbackAuthorized()

        where:
        key << ['sk_test_replace_me', 'sk_' + 'test_unit_fake_not_a_key', '', null]
    }

    def "under prod a live key stays LIVE"() {
        expect:
        service('sk_' + 'live_unit_fake_not_a_key', 'prod').moneyMode() == MoneyMode.LIVE
    }

    def "off prod the key alone decides, as before"() {
        expect:
        service('sk_test_replace_me').moneyMode() == MoneyMode.SIMULATED
        service('sk_' + 'test_unit_fake_not_a_key').moneyMode() == MoneyMode.TEST
        new StripeService(secretKey: 'sk_test_replace_me').moneyMode() == MoneyMode.SIMULATED
    }
}
