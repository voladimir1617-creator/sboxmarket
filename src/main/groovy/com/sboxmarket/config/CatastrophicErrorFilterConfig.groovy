package com.sboxmarket.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Explicit servlet-filter registration for {@link CatastrophicErrorFilter}.
 *
 * Same gotcha as the other session-related filters in this package:
 * `@Component @Order` on a Filter does not control its position in the
 * Servlet filter chain — Spring Boot wraps the filter in a default-order
 * FilterRegistrationBean. We register explicitly here so the filter
 * runs FIRST, ahead of every other filter (Spring Session, MVC, security)
 * and can therefore catch any catastrophic exception thrown anywhere
 * below it in the chain, including from filters and dispatcher code.
 *
 * Order = HIGHEST_PRECEDENCE (Integer.MIN_VALUE).  Cannot go lower than
 * MIN_VALUE, so we bumped {@link SessionCookieSanitizerConfig} to
 * `HIGHEST_PRECEDENCE + 1` so this filter is unambiguously first. The
 * cookie sanitizer no longer collides on the same order, and the chain
 * is now strictly:
 *
 *   1. CatastrophicErrorFilter      (HIGHEST_PRECEDENCE)
 *   2. SessionCookieSanitizerFilter (HIGHEST_PRECEDENCE + 1)
 *   3. OutageObservabilityFilter    (HIGHEST_PRECEDENCE + 6)
 *   4. SessionAttributeNulStripFilter (HIGHEST_PRECEDENCE + 100)
 *   5. ... business filters
 *   6. Spring Session (when enabled): SessionRepositoryFilter
 *      (`Integer.MIN_VALUE + 50`, sits between #2 and #6)
 */
@Configuration
class CatastrophicErrorFilterConfig {

    @Bean
    FilterRegistrationBean<CatastrophicErrorFilter> catastrophicErrorFilterRegistration(
            CatastrophicErrorFilter filter) {
        FilterRegistrationBean<CatastrophicErrorFilter> reg = new FilterRegistrationBean<>(filter)
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE)
        reg.setName('catastrophicErrorFilter')
        reg.addUrlPatterns('/*')
        reg
    }
}
