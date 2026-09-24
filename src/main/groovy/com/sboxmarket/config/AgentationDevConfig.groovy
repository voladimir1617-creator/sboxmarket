package com.sboxmarket.config

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment

/**
 * The ONLY place the Agentation dev toolbar is wired in — and the whole class
 * is skipped unless {@link AgentationDevGate.Enabled} matches.
 *
 * That is the load-bearing property: when the opt-in is absent (every fresh
 * checkout, every container, CI, and the operator's own default launch) Spring
 * never processes this class, so no {@link AgentationAssetFilter} and no
 * {@link AgentationInjectionFilter} bean is created, {@code /__agentation/**}
 * is an ordinary 404, and the HTML is byte-identical to a tree without this
 * feature. There is no "registered but disabled" state to misconfigure.
 *
 * Pinned by {@code AgentationIsAbsentByDefaultSpec}.
 */
@Configuration
@Conditional(AgentationDevGate.Enabled)
class AgentationDevConfig {

    /** Shown in the toolbar's copied markdown so a pasted note says which app
     *  it came from. */
    static final String APP_NAME = 'SkinBox'

    @Bean
    AgentationAssetFilter agentationAssetFilter(Environment env) {
        new AgentationAssetFilter(DevLoginGate.processEnvValue(env, AgentationAssetFilter.DIR_ENV_VAR))
    }

    @Bean
    FilterRegistrationBean<AgentationAssetFilter> agentationAssetFilterRegistration(AgentationAssetFilter filter) {
        FilterRegistrationBean<AgentationAssetFilter> reg = new FilterRegistrationBean<>(filter)
        // Ahead of the business filters (rate limiting, CSRF, presence): these
        // are static dev assets, not part of the product's request surface.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10)
        reg.setName('agentationAssetFilter')
        reg.addUrlPatterns(AgentationDevGate.ASSET_PREFIX + '*')
        reg
    }

    @Bean
    AgentationInjectionFilter agentationInjectionFilter() {
        new AgentationInjectionFilter(APP_NAME)
    }

    @Bean
    FilterRegistrationBean<AgentationInjectionFilter> agentationInjectionFilterRegistration(
            AgentationInjectionFilter filter) {
        FilterRegistrationBean<AgentationInjectionFilter> reg = new FilterRegistrationBean<>(filter)
        // LOWEST_PRECEDENCE: the response wrapper has to be installed closest to
        // the handler so it captures the rendered body, and so the security and
        // observability filters above it see an ordinary response.
        reg.setOrder(Ordered.LOWEST_PRECEDENCE)
        reg.setName('agentationInjectionFilter')
        reg.addUrlPatterns('/*')
        reg
    }
}
