package com.sboxmarket

import org.yaml.snakeyaml.Yaml
import spock.lang.Specification

/**
 * Pins the prod actuator posture (security review 2026-10-15, finding 4).
 *
 * The management port used to listen on every interface with every
 * endpoint enabled. Compose never published it, but a bare-host install
 * would have served health details and metrics to anyone who could reach
 * the box. Now it binds to loopback unless an operator names another
 * address, and only the three allowlisted endpoints are switched on.
 */
class ActuatorProdBindSpec extends Specification {

    private Map management() {
        def stream = getClass().getResourceAsStream('/application-prod.yml')
        assert stream != null
        (new Yaml().load(stream) as Map).get('management') as Map
    }

    private static String defaultOf(Object raw) {
        def s = raw?.toString()
        def m = s =~ /^\$\{[A-Z_]+:(.*)\}$/
        m.matches() ? m.group(1) : s
    }

    def "the prod management port binds to loopback by default"() {
        expect:
        defaultOf((management().server as Map).address) == '127.0.0.1'
    }

    def "prod actuator endpoints are off unless named"() {
        given:
        def endpoints = management().endpoints as Map
        def endpoint  = management().endpoint as Map

        expect: 'nothing is enabled by default'
        endpoints.'enabled-by-default' == false

        and: 'only health, info and metrics are switched back on and exposed'
        ['health', 'info', 'metrics'].every { (endpoint[it] as Map)?.enabled == true }
        ((endpoints.web as Map).exposure as Map).include == 'health,info,metrics'
    }
}
