package com.sboxmarket

import com.sboxmarket.controller.TradeSafetyController
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * The mode-aware safety page has to be what the URL ACTUALLY serves.
 *
 * {@link CustodyClaimHonestySpec} proves the copy is chosen correctly for each
 * custody mode. That is worth nothing if the request never reaches the code
 * that chooses: {@code /legal/trade-safety.html} was previously served straight
 * off the classpath by the static resource handler registered in
 * {@code WebConfig.addResourceHandlers}. This spec pins that a controller
 * mapping now wins that race, so the placeholders are really substituted for a
 * real HTTP request — and that {@code /api/custody}, which the SPA reads to
 * pick its own product copy, is reachable without authentication.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles('test')
class CustodyClaimWiringSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired TradeSafetyController controller

    def "GET /legal/trade-safety.html is rendered by the controller, not served raw"() {
        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get('/legal/trade-safety.html'))
                         .andReturn().response

        then:
        res.status == 200
        res.contentType?.toLowerCase()?.contains('text/html')

        and: "the placeholders were substituted — proof the controller mapping beats the static resource handler"
        !res.contentAsString.contains('<!--SB:CUSTODY_CALLOUT-->')
        !res.contentAsString.contains('<!--SB:CUSTODY_BOT_RULE-->')

        and: "and with no STEAM_BOT_BASE_URL set — the mode the operator launches in — the page states the true, non-custodial mode"
        !controller.custodial
        res.contentAsString.contains('SkinBox is non-custodial')
    }

    def "the /legal/trade-safety alias forwards through the same renderer"() {
        // WebConfig forwards the extension-less URL onto the .html path; a
        // forward re-enters the dispatcher, so it must land on the controller
        // too rather than on the raw file.
        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get('/legal/trade-safety'))
                         .andReturn().response

        then:
        res.status == 200
        !res.contentAsString.contains('<!--SB:CUSTODY_CALLOUT-->')
    }

    def "GET /api/custody reports the mode the platform is actually in, unauthenticated"() {
        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get('/api/custody')).andReturn().response

        then:
        res.status == 200
        res.contentAsString.contains('"custodial":false')
        res.contentAsString.contains('NON_CUSTODIAL')
    }
}
