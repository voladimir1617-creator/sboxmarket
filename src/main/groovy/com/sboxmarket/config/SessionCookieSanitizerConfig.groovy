package com.sboxmarket.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Explicit servlet-filter registration for {@link SessionCookieSanitizerFilter}.
 *
 * Why this exists separately:
 *   `@Component @Order` on a Filter does NOT control its position in the
 *   Servlet filter chain — Spring Boot wraps the filter in a default-order
 *   FilterRegistrationBean that runs AFTER Spring Session's filter, which
 *   defeated the purpose. The bug it was meant to catch (NUL bytes in
 *   inbound SESSION cookies tripping a Postgres "invalid byte sequence
 *   for encoding UTF8: 0x00" 500 on every request) recurred because
 *   Spring Session read the cookie before our sanitizer could clean it.
 *
 *   Wrapping the filter in an explicit FilterRegistrationBean with
 *   `Ordered.HIGHEST_PRECEDENCE` guarantees it runs FIRST, ahead of
 *   Spring Session's `SessionRepositoryFilter` (typically registered at
 *   `Integer.MIN_VALUE + 50`).
 */
@Configuration
class SessionCookieSanitizerConfig {

    @Bean
    FilterRegistrationBean<SessionCookieSanitizerFilter> sessionCookieSanitizerRegistration(
            SessionCookieSanitizerFilter filter) {
        FilterRegistrationBean<SessionCookieSanitizerFilter> reg = new FilterRegistrationBean<>(filter)
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE)
        reg.setName('sessionCookieSanitizerFilter')
        reg.addUrlPatterns('/*')
        reg
    }
}
