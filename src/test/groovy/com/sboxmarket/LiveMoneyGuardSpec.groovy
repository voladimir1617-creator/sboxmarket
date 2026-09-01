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

    /**
     * FLIPPED 2026-09-01. This used to assert {@code !isRealMoney(null)} —
     * "a context-less caller must not be locked out of its own tests".
     *
     * That is the same permissive default that every hole in this guard's
     * history came from: a MISSING answer read as "no". A null Environment in
     * production means the guard was never wired, and the safe reading of "I
     * cannot tell" is "assume real money and shut the door". Tests that need a
     * decision pass a real Environment — {@code ProdScaffoldingUnreachableSpec}
     * and {@code MoneyModeSpec} both drive a StandardEnvironment.
     */
    def "a null Environment is a missing answer, and a missing answer is real money"() {
        expect: "the door shuts when the guard cannot see its own configuration"
        LiveMoneyGuard.isRealMoney(null)
    }

    @Unroll
    def "a RESTRICTED live key (#label) is real money — the prefix this guard used to miss"() {
        expect: "ProdConfigValidator blesses rk_live_ as a production key; so must this"
        LiveMoneyGuard.isRealMoney(env([], key))

        and: """recognised AS LIVE, not merely as unclassifiable.

             Without this line the case is decoration: deleting `rk_live_` from
             the prefix list makes the key INDETERMINATE, which ALSO returns
             true from isRealMoney — so the assertion above passes with the bug
             reintroduced. Verified by mutation on 2026-09-01."""
        com.sboxmarket.config.MoneyMode.of(env([], key)) == com.sboxmarket.config.MoneyMode.LIVE

        where:
        label            | key
        'plain'          | 'rk_live_' + ('0' * 24)
        'with whitespace'| '  rk_live_' + ('0' * 24) + '  '
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

    /**
     * REWRITTEN 2026-09-01. This used to pass a {@code Stub(Environment)} with
     * the {@code dev} profile and assert a session was minted — i.e. it asserted
     * that "not real money" was SUFFICIENT to open the door.
     *
     * It is not, any more, and that is the point of {@link com.sboxmarket.config.DevLoginGate}:
     * not-real-money is a statement about MONEY, not a grant of permission. The
     * door now also needs a named opt-in in the process environment, so the case
     * has to supply one. What this spec still owns is the OTHER half — that
     * {@code LiveMoneyGuard} does not veto an ordinary dev box — which is why it
     * survives here rather than being deleted into
     * {@code DevLoginRequiresOptInSpec}.
     */
    def "dev-login still works on an ordinary local dev box that asked for it"() {
        given: "the endpoint is real QA scaffolding — locking it down with no way in would just get it removed"
        def user = new SteamUser(id: 42L, displayName: 'dev', sessionEpoch: 3L)
        def devEnv = SpecEnvs.optedIn(['dev'], ['STRIPE_SECRET_KEY': 'sk_test_replace_me'])
        def controller = controllerFor(devEnv, user)
        HttpSession session = Mock()
        HttpServletRequest req = Mock() { getSession(true) >> session }
        HttpServletResponse resp = Mock()

        when:
        controller.devLogin(42L, '/profile', req, resp)

        then: "a session IS minted"
        1 * session.setAttribute(SteamAuthController.SESSION_USER_ID, 42L)
        1 * resp.sendRedirect('/profile')

        and: "and the money guard is what did NOT stop it"
        !LiveMoneyGuard.isRealMoney(devEnv)
    }

    def "the same ordinary dev box is refused when nobody asked for the door"() {
        given: '''the pair to the case above, and the actual regression: identical
                  deployment, identical money classification, no opt-in.'''
        def user = new SteamUser(id: 42L, displayName: 'dev', sessionEpoch: 3L)
        def devEnv = SpecEnvs.env(['dev'], ['STRIPE_SECRET_KEY': 'sk_test_replace_me'])
        def controller = controllerFor(devEnv, user)
        HttpServletRequest req = Mock()
        HttpServletResponse resp = Mock()

        when:
        def result = controller.devLogin(42L, '/profile', req, resp)

        then: 'no session, and the money guard is NOT the reason'
        result.statusCode == HttpStatus.NOT_FOUND
        result.body.error == com.sboxmarket.config.DevLoginGate.REASON_NOT_AUTHORIZED
        !LiveMoneyGuard.isRealMoney(devEnv)
        0 * req.getSession(_)
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
