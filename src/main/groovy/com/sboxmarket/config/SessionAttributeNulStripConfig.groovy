package com.sboxmarket.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Explicit servlet-filter registration for {@link SessionAttributeNulStripFilter}.
 *
 * Why this exists separately:
 *   `@Component @Order` on a Filter does NOT control its position in the
 *   Servlet filter chain (same gotcha that bit SessionCookieSanitizerFilter).
 *   Spring Boot wraps every auto-detected Filter in a default-order
 *   FilterRegistrationBean that places it AFTER Spring Session's
 *   SessionRepositoryFilter — fine for "after Spring Session" but the
 *   exact position matters: we must wrap the request AFTER
 *   SessionRepositoryFilter has already wrapped it, so request.getSession()
 *   returns Spring Session's JdbcSession (the thing whose setAttribute we
 *   want to intercept on the way to save).
 *
 *   Setting an explicit order of HIGHEST_PRECEDENCE + 100 puts us:
 *     • AFTER SessionCookieSanitizerFilter (HIGHEST_PRECEDENCE = MIN_VALUE)
 *     • AFTER Spring Session's SessionRepositoryFilter
 *       (DEFAULT_ORDER = MIN_VALUE + 50)
 *     • AHEAD of every business filter (CorrelationId @Order(0), etc.)
 */
@Configuration
class SessionAttributeNulStripConfig {

    @Bean
    FilterRegistrationBean<SessionAttributeNulStripFilter> sessionAttributeNulStripRegistration(
            SessionAttributeNulStripFilter filter) {
        FilterRegistrationBean<SessionAttributeNulStripFilter> reg = new FilterRegistrationBean<>(filter)
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 100)
        reg.setName('sessionAttributeNulStripFilter')
        reg.addUrlPatterns('/*')
        reg
    }
}
