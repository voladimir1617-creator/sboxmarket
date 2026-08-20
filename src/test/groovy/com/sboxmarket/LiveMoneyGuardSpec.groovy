package com.sboxmarket

import com.sboxmarket.config.LiveMoneyGuard
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Unroll

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession

/**
 * The dangerous scaffolding must not depend on ONE environment variable.
 *
 * {@code /api/auth/steam/dev-login} takes a user id and nothing else and hands
 * back a valid session for that user — no password, no OpenID assertion, no
 * token. It is unauthenticated impersonation of every account on the platform,
 * admins included.
 *
 * Its gate was {@code env.activeProfiles.contains('prod')}. So is the demo
 * seeder's ({@code @Profile("!prod")}), so is {@link
 * com.sboxmarket.config.ProdConfigValidator}'s, and so is the override that
 * replaces application.yml's DEFAULT admin-bootstrap Steam id with a mandatory
 * env var. One missing, misspelled, or orchestrator-stripped
 * {@code SPRING_PROFILES_ACTIVE} loses all four at once — silently, on a box
 * that boots normally and takes real card payments.
 *
 * {@link LiveMoneyGuard} adds a second lock keyed on a fact that travels in its
 * own variable and cannot be forgotten: a {@code sk_live_} Stripe secret key is
 * not a hint about intent, it is the definition of a deployment that can charge
 * a real card.
 */
class LiveMoneyGuardSpec extends Specification {

    private Environment env(List<String> profiles, String stripeKey) {
        Stub(Environment) {
            getActiveProfiles() >> (profiles as String[])
            getProperty('stripe.secret-key') >> stripeKey
            getProperty('STRIPE_SECRET_KEY') >> stripeKey
        }
    }

    // ── The guard itself ────────────────────────────────────────────

    @Unroll
    def "isRealMoney is #expected for profiles=#profiles key=#key"() {
        expect:
        LiveMoneyGuard.isRealMoney(env(profiles, key)) == expected

        where:
        profiles   | key                    || expected
        ['prod']   | null                   || true    // profile alone still counts
        ['prod']   | 'sk_test_abc'          || true
        []         | 'sk_live_abc123'       || true    // THE GAP: live key, no profile
        ['dev']    | 'sk_live_abc123'       || true
        ['default']| 'sk_live_abc123'       || true
        []         | 'sk_test_abc'          || false   // ordinary local dev
        []         | 'sk_test_replace_me'   || false
        []         | null                   || false
        []         | ''                     || false
        ['dev']    | null                   || false
    }

    def "a live key is detected even with surrounding whitespace"() {
        expect: "an env var pasted with a trailing newline is still a live key"
        LiveMoneyGuard.isRealMoney(env([], '  sk_live_abc  '))
    }

    def "a null Environment is not treated as real money"() {
        expect: "a context-less caller must not be locked out of its own tests"
        !LiveMoneyGuard.isRealMoney(null)
    }

    // ── dev-login ───────────────────────────────────────────────────

    private SteamAuthController controllerFor(Environment e, SteamUser user = null) {
        SteamUserRepository repo = Mock() {
            findById(_) >> Optional.ofNullable(user)
            findAll() >> (user ? [user] : [])
        }
        new SteamAuthController(env: e, steamUserRepository: repo)
    }

    def "dev-login is a 404 when a live Stripe key is present but the prod profile is NOT"() {
        given: "the exact misconfiguration: real money, no profile"
        def controller = controllerFor(env([], 'sk_live_abc123'),
                                       new SteamUser(id: 1L, displayName: 'victim'))
        HttpServletRequest req = Mock()
        HttpServletResponse resp = Mock()

        when:
        def result = controller.devLogin(1L, '/profile', req, resp)

        then: "no session is minted for anybody"
        result.statusCode == HttpStatus.NOT_FOUND
        0 * req.getSession(_)
        0 * resp.sendRedirect(_)
    }

    def "dev-login is still a 404 under the prod profile alone"() {
        given: "the original gate must not have been weakened by adding the second one"
        def controller = controllerFor(env(['prod'], null), new SteamUser(id: 1L))
        HttpServletRequest req = Mock()
        HttpServletResponse resp = Mock()

        when:
        def result = controller.devLogin(1L, '/profile', req, resp)

        then:
        result.statusCode == HttpStatus.NOT_FOUND
        0 * req.getSession(_)
    }

    def "dev-login still works on an ordinary local dev box"() {
        given: "the endpoint is real QA scaffolding — locking it down everywhere would just get it removed"
        def user = new SteamUser(id: 42L, displayName: 'dev', sessionEpoch: 3L)
        def controller = controllerFor(env(['dev'], 'sk_test_replace_me'), user)
        HttpSession session = Mock()
        HttpServletRequest req = Mock() { getSession(true) >> session }
        HttpServletResponse resp = Mock()

        when:
        controller.devLogin(42L, '/profile', req, resp)

        then: "a session IS minted"
        1 * session.setAttribute(SteamAuthController.SESSION_USER_ID, 42L)
        1 * resp.sendRedirect('/profile')
    }

    // ── the seeder ──────────────────────────────────────────────────

    def "the seed runner consults the live-money guard, not only the profile"() {
        given: "the runner bean's body, read from source"
        def src = new File('src/main/groovy/com/sboxmarket/SboxMarketApplication.groovy').text

        expect: "@Profile(!prod) stays as the primary gate"
        src.contains('@Profile("!prod")')

        and: "with an independent second veto inside it"
        src.contains('LiveMoneyGuard.isRealMoney(env)')

        and: "that returns WITHOUT seeding"
        (src =~ /isRealMoney\(env\)\)\s*\{[\s\S]{0,900}?return\s/).find()
    }
}
