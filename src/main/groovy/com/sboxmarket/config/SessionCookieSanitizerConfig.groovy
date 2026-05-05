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
 *   `Ordered.HIGHEST_PRECEDENCE + 1` puts us second-from-the-front,
 *   immediately after {@link CatastrophicErrorFilter} (which sits at
 *   `HIGHEST_PRECEDENCE` so it can wrap any catastrophic exception
 *   from anything below it, this sanitizer included), and well ahead
 *   of Spring Session's `SessionRepositoryFilter` (typically registered
 *   at `Integer.MIN_VALUE + 50`).  Bumped from raw HIGHEST_PRECEDENCE
 *   on 2026-05-03 to disambiguate from CatastrophicErrorFilter — at
 *   the same order, FilterRegistrationBean's tie-break is not stable
 *   across boots and we need the catastrophic catch to be strictly
 *   first.
 */
@Configuration
class SessionCookieSanitizerConfig {

    @Bean
    FilterRegistrationBean<SessionCookieSanitizerFilter> sessionCookieSanitizerRegistration(
            SessionCookieSanitizerFilter filter) {
        FilterRegistrationBean<SessionCookieSanitizerFilter> reg = new FilterRegistrationBean<>(filter)
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 1)
        reg.setName('sessionCookieSanitizerFilter')
        reg.addUrlPatterns('/*')
        reg
    }
}
