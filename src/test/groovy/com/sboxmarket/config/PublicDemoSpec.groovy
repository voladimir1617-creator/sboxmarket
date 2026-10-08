package com.sboxmarket.config

import jakarta.servlet.FilterChain
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.yaml.snakeyaml.Yaml
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The public demo (SPRING_PROFILES_ACTIVE=demo, render.yaml) is the one copy
 * of the site strangers can reach that is not the money deployment. These pin
 * the two things that keep it safe: the route block and the startup refusal.
 */
class PublicDemoSpec extends Specification {

    private static ConfigurableEnvironment envWith(Map<String, Object> processEnv,
                                                   Map<String, Object> props = [:],
                                                   List<String> profiles = ['demo']) {
        ConfigurableEnvironment env = new StandardEnvironment()
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, processEnv))
        env.propertySources.addFirst(new MapPropertySource('demo-yml',
            ['sbox.public-demo.enabled': 'true'] + props))
        env.setActiveProfiles(profiles as String[])
        env
    }

    @Unroll
    def "demo blocks #path"() {
        expect:
        PublicDemoFilter.isBlocked(path)

        where:
        path << [
            '/api/admin', '/api/admin/', '/api/admin/stats', '/API/Admin/users/1/credit',
            '//api//admin//stats', '/api/csr/tickets', '/api/database',
            '/api/stripe/webhook', '/api/auth/steam/dev-login',
            '/api/wallet/deposit', '/api/wallet/confirm-deposit',
            '/api/wallet/withdraw', '/api/wallet/withdraw/7/cancel',
            '/api/wallet/connect/onboard', '/api/wallet/connect/status',
            '/h2-console', '/actuator/health',
        ]
    }

    @Unroll
    def "demo keeps the plain market open: #path"() {
        expect:
        !PublicDemoFilter.isBlocked(path)

        where:
        path << [
            '/', '/api/health', '/api/listings', '/api/items/3', '/api/wallet',
            '/api/wallet/transactions', '/api/auth/steam/login', '/api/auth/steam/me',
            '/api/cart', '/api/administrator-notes', '/api/wallet/deposits-help',
        ]
    }

    def "the filter answers 404 before the chain and passes everything else through"() {
        given:
        def filter = new PublicDemoFilter(enabled: true)
        def chain = Mock(FilterChain)

        when:
        def blockedResp = new MockHttpServletResponse()
        filter.doFilter(new MockHttpServletRequest('POST', '/api/wallet/withdraw'), blockedResp, chain)

        then:
        blockedResp.status == 404
        blockedResp.contentAsString.contains('NOT_AVAILABLE_ON_DEMO')
        0 * chain.doFilter(_, _)

        when:
        def okResp = new MockHttpServletResponse()
        filter.doFilter(new MockHttpServletRequest('GET', '/api/listings'), okResp, chain)

        then:
        1 * chain.doFilter(_, _)
        okResp.status == 200
    }

    def "a clean demo environment passes the startup check"() {
        expect:
        PublicDemoGuard.findViolations(envWith([:])).isEmpty()
        PublicDemoGuard.findViolations(envWith([:], ['stripe.secret-key': ''])).isEmpty()
        PublicDemoGuard.findViolations(envWith([STRIPE_SECRET_KEY: 'sk_test_abc'])).isEmpty()
    }

    @Unroll
    def "the demo refuses to start when #why"() {
        expect:
        !PublicDemoGuard.findViolations(env).isEmpty()

        where:
        why                          | env
        'dev-login is opted in'      | envWith([SBOX_DEV_LOGIN_ENABLED: 'true'])
        'dev-credit is opted in'     | envWith([SBOX_DEV_CREDIT_ENABLED: 'true'])
        'money reset is opted in'    | envWith([SBOX_MONEY_RESET_ENABLED: 'true'])
        'a live Stripe key is set'   | envWith([STRIPE_SECRET_KEY: 'sk_live_' + 'x' * 24])
        'a restricted live key'      | envWith([:], ['stripe.secret-key': 'rk_live_' + 'x' * 24])
        'prod is active too'         | envWith([:], [:], ['demo', 'prod'])
        'the H2 console is on'       | envWith([:], ['spring.h2.console.enabled': 'true'])
        'swagger is on'              | envWith([:], ['springdoc.swagger-ui.enabled': 'true'])
        'the route block is off'     | envWith([:], ['sbox.public-demo.enabled': 'false'])
    }

    def "application-demo.yml seeds the demo, wires no Stripe and turns the route block on"() {
        given:
        Map yml = new Yaml().load(getClass().getResourceAsStream('/application-demo.yml'))

        expect:
        yml.sbox.'public-demo'.enabled == true
        yml.sbox.seed.'demo-data' == '${SBOX_SEED_DEMO_DATA:true}'
        yml.stripe.'secret-key' == ''
        yml.spring.h2.console.enabled == false
        yml.app.'steam-price-sync'.enabled == false
        yml.server.port == '${PORT:${SERVER_PORT:8082}}'
    }
}
