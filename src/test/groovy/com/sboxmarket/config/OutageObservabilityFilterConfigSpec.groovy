package com.sboxmarket.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.Ordered
import spock.lang.Specification

/**
 * Regression for the silent filter-order bug: {@link OutageObservabilityFilter}
 * carries a `@Component @Order(HIGHEST_PRECEDENCE + 6)` annotation, but
 * for raw Servlet filters that annotation does NOT control chain
 * position — Spring Boot wraps the bean in a default-order
 * `FilterRegistrationBean` that runs AFTER Spring Session's
 * `SessionRepositoryFilter`. Without the explicit registration here, a
 * PSQLException thrown FROM `SessionRepositoryFilter` would never reach
 * the observability filter and never log the grep-able OUTAGE-SIGNAL line
 * the on-call docstring promises.
 *
 * This spec pins the registration: it MUST exist and it MUST be ordered
 * ahead of Spring Session's default (`MIN_VALUE + 50`).
 */
class OutageObservabilityFilterConfigSpec extends Specification {

    def "registration places the filter ahead of Spring Session's SessionRepositoryFilter"() {
        given:
        def config = new OutageObservabilityFilterConfig()
        def filter = new OutageObservabilityFilter()

        when:
        FilterRegistrationBean<OutageObservabilityFilter> reg =
                config.outageObservabilityFilterRegistration(filter)

        then:
        reg != null
        reg.filter.is(filter)
        reg.urlPatterns == ['/*'] as Set
        reg.order == Ordered.HIGHEST_PRECEDENCE + 6
        // Spring Session's SessionRepositoryFilter registers at
        // Integer.MIN_VALUE + 50 by default — strictly greater than
        // ours, so our filter wraps it (catches its exceptions).
        reg.order < Integer.MIN_VALUE + 50
        // Belt-and-braces: must run AFTER CatastrophicErrorFilter
        // (HIGHEST_PRECEDENCE) and SessionCookieSanitizerFilter
        // (HIGHEST_PRECEDENCE + 1) so those wrap our throws.
        reg.order > Ordered.HIGHEST_PRECEDENCE + 1
    }
}
