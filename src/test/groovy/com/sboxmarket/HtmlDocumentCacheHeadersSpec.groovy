package com.sboxmarket

import com.sboxmarket.config.CorrelationIdFilter
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Every HTML document must revalidate on each visit and must never be
 * stored by a shared cache. The documents name the current `?v=` asset
 * URLs, so the old `private, max-age=3600` on /market (and 300s on the
 * item / stall / loadout pages) kept serving the previous build's shell
 * after a deploy until a hard refresh. The versioned assets keep their
 * year-long immutable cache.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HtmlDocumentCacheHeadersSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired ItemRepository itemRepo
    @Autowired LoadoutRepository loadoutRepo
    @Autowired SteamUserRepository steamUserRepo

    @Shared String uniq = String.valueOf(System.nanoTime())

    private List<String> cacheControl(String path, MockHttpSession session = null) {
        def req = MockMvcRequestBuilders.get(path).accept('text/html')
        if (session != null) req.session(session)
        def r = mockMvc.perform(req).andReturn()
        // `/` and unknown client routes forward to the shell; MockMvc
        // records the forward instead of running it, so the header seen
        // here is the one the browser receives.
        assert r.response.contentType?.startsWith('text/html') || r.response.forwardedUrl != null
        r.response.getHeaders('Cache-Control')
    }

    @Unroll
    def "HTML document #path revalidates and is not stored by shared caches"() {
        expect:
        cacheControl(path) == [CorrelationIdFilter.HTML_CACHE_CONTROL]

        where:
        path << [
            '/', '/market', '/market/', '/search', '/db', '/help', '/loadout',
            '/affiliate', '/faq',
            '/profile', '/wallet', '/cart', '/sell', '/me/stall', '/offers',
            '/buy-orders', '/notifications', '/watchlist', '/support',
            '/settings', '/admin', '/csr',
            '/item/987654321', '/stall/987654321', '/loadout/987654321',
            '/some-spa-route', '/no-such-page-at-all/deeper', '/spa-404',
            '/index.html', '/status.html', '/changelog.html',
            '/legal/terms.html', '/legal/privacy.html', '/legal/refunds.html'
        ]
    }

    @Unroll
    def "HEAD #path answers with the same Cache-Control as GET"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.head(path)).andReturn()

        then:
        r.response.getHeaders('Cache-Control') == [CorrelationIdFilter.HTML_CACHE_CONTROL]

        where:
        path << ['/', '/some-spa-route', '/market', '/watchlist', '/item/987654321', '/index.html']
    }

    def "item, stall and loadout pages for real rows also revalidate"() {
        given:
        def item = itemRepo.save(new Item(name: "CacheSpec-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('4.00'), isListed: true, iconEmoji: '🎩'))
        def user = steamUserRepo.save(new SteamUser(
            steamId64: '76561198' + uniq.substring(uniq.length() - 9), displayName: "CacheSpec-${uniq}"))
        def loadout = loadoutRepo.save(new Loadout(ownerUserId: user.id, ownerName: 'cache-owner',
            name: "CacheSpec-${uniq}", visibility: 'PUBLIC'))

        expect:
        cacheControl('/item/' + item.id) == [CorrelationIdFilter.HTML_CACHE_CONTROL]
        cacheControl('/stall/' + user.id) == [CorrelationIdFilter.HTML_CACHE_CONTROL]
        cacheControl('/loadout/' + loadout.id) == [CorrelationIdFilter.HTML_CACHE_CONTROL]
    }

    @Unroll
    def "versioned asset #path keeps its year-long immutable cache"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get(path)).andReturn()

        then:
        r.response.status == 200
        r.response.getHeaders('Cache-Control') == ['public, max-age=31536000, immutable']

        where:
        path << ['/css/design.css?v=324', '/js/app.js?v=1', '/js/main.js?v=1']
    }

    def "a signed-in session survives loading pages and stays signed in"() {
        given:
        def user = steamUserRepo.save(new SteamUser(
            steamId64: '76561197' + uniq.substring(uniq.length() - 9), displayName: "CacheSession-${uniq}"))
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, user.id)

        when:
        ['/', '/market', '/profile', '/item/987654321'].each { cacheControl(it, session) }
        def me = mockMvc.perform(MockMvcRequestBuilders.get('/api/auth/steam/me').session(session)).andReturn()

        then:
        !session.isInvalid()
        session.getAttribute(SteamAuthController.SESSION_USER_ID) == user.id
        me.response.status == 200
        me.response.contentAsString.contains("CacheSession-${uniq}")
    }
}
