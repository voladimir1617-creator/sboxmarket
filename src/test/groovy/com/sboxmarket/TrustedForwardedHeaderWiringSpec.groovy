package com.sboxmarket

import com.sboxmarket.config.TrustedForwardedHeaderFilter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.filter.ForwardedHeaderFilter
import spock.lang.Specification

/**
 * With the prod profile's `forward-headers-strategy: framework`, the one
 * ForwardedHeaderFilter in the context is ours, not Boot's stock one.
 */
@SpringBootTest(properties = ['server.forward-headers-strategy=framework'])
@ActiveProfiles("test")
class TrustedForwardedHeaderWiringSpec extends Specification {

    @Autowired ApplicationContext ctx

    def "the only forwarded-header filter registered is TrustedForwardedHeaderFilter"() {
        when:
        def filters = ctx.getBeansOfType(FilterRegistrationBean).values()*.filter
            .findAll { it instanceof ForwardedHeaderFilter }

        then:
        filters.size() == 1
        filters[0] instanceof TrustedForwardedHeaderFilter
    }
}
