package com.sboxmarket.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

/**
 * Explicit servlet-filter registration for {@link OutageObservabilityFilter}.
 *
 * Same gotcha that bit {@link SessionCookieSanitizerFilter} and
 * {@link SessionAttributeNulStripFilter}: `@Component @Order` on a
 * `OncePerRequestFilter` does NOT control its position in the Servlet
 * filter chain. Spring Boot wraps the bean in a default-order
 * `FilterRegistrationBean` that runs AFTER Spring Session's
 * `SessionRepositoryFilter`. Without an explicit registration the
 * docstring promise — "A `@ControllerAdvice` would miss exceptions thrown
 * from filters that run BEFORE Spring MVC (e.g. anything inside
 * `SessionRepositoryFilter` itself)" — silently broke: a PSQLException
 * thrown FROM Spring Session never reached the observability filter and
 * never logged an `OUTAGE-SIGNAL` line, defeating the entire reason the
 * filter exists.
 *
 * Order = `HIGHEST_PRECEDENCE + 6`, matching the value the @Order
 * annotation tried to set and the chain diagram in
 * {@link CatastrophicErrorFilterConfig}. Concretely:
 *   1. CatastrophicErrorFilter      (HIGHEST_PRECEDENCE)
 *   2. SessionCookieSanitizerFilter (HIGHEST_PRECEDENCE + 1)
 *   3. OutageObservabilityFilter    (HIGHEST_PRECEDENCE + 6)   <-- here
 *   4. SessionAttributeNulStripFilter (HIGHEST_PRECEDENCE + 100)
 *   5. Spring Session SessionRepositoryFilter (MIN_VALUE + 50)
 */
@Configuration
class OutageObservabilityFilterConfig {

    @Bean
    FilterRegistrationBean<OutageObservabilityFilter> outageObservabilityFilterRegistration(
            OutageObservabilityFilter filter) {
        FilterRegistrationBean<OutageObservabilityFilter> reg = new FilterRegistrationBean<>(filter)
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 6)
        reg.setName('outageObservabilityFilter')
        reg.addUrlPatterns('/*')
        reg
    }
}
