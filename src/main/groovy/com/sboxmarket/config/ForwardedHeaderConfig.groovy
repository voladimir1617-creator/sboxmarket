package com.sboxmarket.config

import jakarta.servlet.DispatcherType
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.ForwardedHeaderFilter

/**
 * Replaces the ForwardedHeaderFilter Spring Boot registers for
 * `server.forward-headers-strategy: framework` with
 * {@link TrustedForwardedHeaderFilter}. Boot's own registration is
 * `@ConditionalOnMissingFilterBean(ForwardedHeaderFilter)`, so it backs off
 * when this bean exists; order and dispatcher types match Boot's.
 */
@Configuration
@ConditionalOnProperty(value = 'server.forward-headers-strategy', havingValue = 'framework')
class ForwardedHeaderConfig {

    // Declared as FilterRegistrationBean<ForwardedHeaderFilter> (not the
    // subclass) because Boot's back-off matches that exact generic type. The
    // bean keeps Boot's name so that, if the back-off ever stopped matching,
    // startup fails on the duplicate instead of silently running both.
    @Bean
    FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter(ClientIpResolver ipResolver) {
        def reg = new FilterRegistrationBean<ForwardedHeaderFilter>(new TrustedForwardedHeaderFilter(ipResolver))
        reg.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR)
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE)
        reg
    }
}
