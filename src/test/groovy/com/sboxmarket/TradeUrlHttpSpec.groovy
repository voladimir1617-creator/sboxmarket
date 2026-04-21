package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * HTTP-level pinning for PUT /api/profile/trade-url (batch 555).
 *
 * Covers the ownership check: the `partner=` param in a Steam trade URL
 * is the Steam ID32 (accountid), and the signed-in user's `steamId64`
 * should equal `76561197960265728 + partner_id`. Any mismatch means the
 * user is pasting someone else's trade URL — items would ship to the
 * wrong account. We reject with a 400 TRADE_URL_NOT_YOURS before the
 * persistence step so a stolen-session attacker can't redirect future
 * purchases.
 *
 * Also pins the two earlier validators (invalid shape, partner collision)
 * are still tight after batch 555's insertion.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TradeUrlHttpSpec extends Specification {

    // Steam ID magic constant: steamId64 = 76561197960265728 + accountid
    static final long STEAMID64_BASE = 76561197960265728L

    @Autowired ApplicationContext ctx

    MockMvc             mockMvc
    SteamUserRepository steamUserRepository

    SteamUser       me
    MockHttpSession session
    long            myPartnerId

    def setup() {
        mockMvc             = ctx.getBean(MockMvc)
        steamUserRepository = ctx.getBean(SteamUserRepository)

        def uniq = String.valueOf(System.nanoTime())
        // Construct a valid Steam ID64 whose derived partner id won't
        // collide with seed data (leading "76561199" makes this a
        // current-era Steam account rather than the 76561197 range
        // used elsewhere in the fixture).
        long sid64 = Long.parseLong("76561199" + uniq.substring(uniq.length() - 9))
        myPartnerId = sid64 - STEAMID64_BASE
        me = steamUserRepository.save(new SteamUser(
            steamId64:   String.valueOf(sid64),
            displayName: "TradeUrlSpec-${uniq}"
        ))
        session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, me.id)
    }

    // ── Happy path ──────────────────────────────────────────────

    def "PUT /trade-url accepts a URL whose partner matches the user's Steam ID64"() {
        given:
        def goodUrl = "https://steamcommunity.com/tradeoffer/new/?partner=${myPartnerId}&token=abcDEF12"
        def body    = """{"tradeUrl":"${goodUrl}"}"""

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.put('/api/profile/trade-url')
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn()

        then:
        result.response.status == 200
        result.response.contentAsString.contains("partner=${myPartnerId}")
        and: "the row actually persisted"
        steamUserRepository.findById(me.id).get().tradeUrl ==
            "https://steamcommunity.com/tradeoffer/new/?partner=${myPartnerId}&token=abcDEF12"
    }

    // ── Ownership check (batch 555) ─────────────────────────────

    def "PUT /trade-url rejects a URL whose partner id doesn't match the user's Steam ID64"() {
        given: "a partner id that belongs to a DIFFERENT Steam account"
        long foreignPartnerId = myPartnerId + 5000L
        def badUrl = "https://steamcommunity.com/tradeoffer/new/?partner=${foreignPartnerId}&token=abcDEF12"
        def body = """{"tradeUrl":"${badUrl}"}"""

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.put('/api/profile/trade-url')
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('TRADE_URL_NOT_YOURS')
        and: "the row is NOT persisted (defensive rejection)"
        steamUserRepository.findById(me.id).get().tradeUrl == null
    }

    // ── Legacy validators still hold ────────────────────────────

    def "PUT /trade-url rejects a malformed URL with INVALID_TRADE_URL"() {
        given:
        def body = '{"tradeUrl":"https://not-steam-community/foo"}'

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.put('/api/profile/trade-url')
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('INVALID_TRADE_URL')
    }

    def "PUT /trade-url with empty string clears the URL"() {
        given:
        me.tradeUrl = "https://steamcommunity.com/tradeoffer/new/?partner=${myPartnerId}&token=oldTok01"
        steamUserRepository.save(me)
        def body = '{"tradeUrl":""}'

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.put('/api/profile/trade-url')
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn()

        then:
        result.response.status == 200
        steamUserRepository.findById(me.id).get().tradeUrl == null
    }
}
