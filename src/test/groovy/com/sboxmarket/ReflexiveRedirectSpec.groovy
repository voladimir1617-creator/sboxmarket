package com.sboxmarket

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Pins every reflexive URL → canonical-route 302 redirect registered in
 * `WebConfig.addViewControllers`. Without this spec, a future refactor
 * that drops or rewires an `addRedirectViewController` call lands on
 * production silently — the only signal would be a typed-URL user
 * landing on the SPA "Page Not Found" view, which the operator may not
 * notice for weeks.
 *
 * Covers the lap-K family of redirects:
 *   - Auth aliases:   /login, /signin, /sign-in, /signup, /sign-up, /register
 *   - Logout aliases: /logout, /signout, /sign-out
 *   - Shopping:       /buy, /shop, /browse
 *   - Profile:        /me, /account, /preferences, /trades
 *   - Fees doc:       /fees, /pricing, /fees-and-pricing
 *   - Database:       /database, /loadouts, /notification, /buyorders, /orders, /watch
 *   - Legal:          /refunds, /trade-safety, /safety, /disclaimer, /risk,
 *                     /acceptable-use, /aup, /responsible-disclosure, /security,
 *                     /tos, /eula, /refund-policy, /home, /about, /contact
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReflexiveRedirectSpec extends Specification {

    @Autowired MockMvc mockMvc

    @Unroll
    def "GET #from → 302 #to"() {
        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get(from)).andReturn()

        then:
        r.response.status in [301, 302, 303, 307, 308]
        r.response.getHeader('Location') == to

        where:
        from                          | to
        // Auth aliases all funnel into the single Steam OpenID redirector.
        '/login'                      | '/api/auth/steam/login'
        '/signin'                     | '/api/auth/steam/login'
        '/sign-in'                    | '/api/auth/steam/login'
        '/signup'                     | '/api/auth/steam/login'
        '/sign-up'                    | '/api/auth/steam/login'
        '/register'                   | '/api/auth/steam/login'
        // Logout aliases land on home (POST /api/auth/logout is the real
        // logout; this just covers typed-URL muscle memory).
        '/logout'                     | '/'
        '/signout'                    | '/'
        '/sign-out'                   | '/'
        // Shopping muscle memory.
        '/buy'                        | '/market'
        '/shop'                       | '/market'
        '/browse'                     | '/market'
        // Profile aliases.
        '/me'                         | '/profile/personal'
        '/account'                    | '/profile/personal'
        '/preferences'                | '/settings'
        '/trades'                     | '/profile/trades'
        // Fees / pricing — footer link points to /faq?q=platform+fee
        // already; reflexive URLs match.
        '/fees'                       | '/faq?q=platform+fee'
        '/pricing'                    | '/faq?q=platform+fee'
        '/fees-and-pricing'           | '/faq?q=platform+fee'
        // Plural / singular drift from real route names.
        '/database'                   | '/db'
        '/loadouts'                   | '/loadout'
        '/notification'               | '/notifications'
        '/buyorders'                  | '/buy-orders'
        '/orders'                     | '/buy-orders'
        '/watch'                      | '/watchlist'
        // Legal aliases (docs live at /legal/<doc>.html).
        '/refunds'                    | '/legal/refunds.html'
        '/refund-policy'              | '/legal/refunds.html'
        '/trade-safety'               | '/legal/trade-safety.html'
        '/safety'                     | '/legal/trade-safety.html'
        '/disclaimer'                 | '/legal/disclaimer.html'
        '/risk'                       | '/legal/disclaimer.html'
        '/acceptable-use'             | '/legal/acceptable-use.html'
        '/aup'                        | '/legal/acceptable-use.html'
        '/responsible-disclosure'     | '/legal/responsible-disclosure.html'
        '/security'                   | '/legal/responsible-disclosure.html'
        '/tos'                        | '/legal/terms.html'
        '/eula'                       | '/legal/terms.html'
        // Convenience top-level redirects.
        '/home'                       | '/'
        '/about'                      | '/help'
        '/contact'                    | '/support'
    }
}
