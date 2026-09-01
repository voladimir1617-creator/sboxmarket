package com.sboxmarket

import com.sboxmarket.config.ProdConfigValidator
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.StripeService
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Unroll

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession

/**
 * The two free-money doors must be shut whenever the `prod` profile is active.
 *
 * <h3>Why this spec exists</h3>
 *
 * On 2026-08-31 the public domain served
 * {@code GET /api/auth/steam/dev-login} as a <b>302 with a minted
 * SBOX_SESSION cookie and no credential of any kind</b>, and
 * {@code devModeDeposit} credited up to $5,000/24h against no payment. The
 * cause was not a bug in either guard — it was that the live JVM ran the
 * DEFAULT profile, so both guards were correctly reporting "this is not
 * production". Nothing in the suite asserted what happens when the profile
 * IS prod, so the one config value standing between a public domain and free
 * money had no test behind it.
 *
 * {@link LiveMoneyGuardSpec} already covers the guard's own truth table
 * against a hand-stubbed Environment. This spec is deliberately different in
 * two ways:
 *
 * <ol>
 *   <li>It drives a <b>real {@link StandardEnvironment}</b> with
 *       {@code setActiveProfiles('prod')} rather than a {@code Stub}, so it
 *       exercises Spring's actual profile resolution — the thing that was
 *       wrong in production — instead of an answer we wrote ourselves.</li>
 *   <li>It covers the <b>deposit</b> door, which nothing tested at all, and
 *       which is NOT gated on the profile (see below).</li>
 * </ol>
 *
 * <h3>The two doors close by different mechanisms</h3>
 *
 * <b>dev-login</b> is gated directly:
 * {@code LiveMoneyGuard.isRealMoney(env)} is true as soon as `prod` is in
 * activeProfiles, and the controller returns 404 before touching the session.
 *
 * <b>The dev deposit</b> is NOT. Its only gate is
 * {@code StripeService.isLive()} — a string test on the Stripe secret key
 * ({@code secretKey && !secretKey.contains("replace_me")}). The profile is
 * not consulted anywhere on that path. So "prod ⇒ the dev deposit is dead"
 * is only true <i>transitively</i>: the prod profile makes
 * {@code STRIPE_SECRET_KEY} mandatory, {@link ProdConfigValidator} refuses to
 * let the app run unless that value is a well-formed live key, and a
 * well-formed live key makes {@code isLive()} true.
 *
 * That transitive chain is the actual security property, so it is what this
 * spec pins: <b>every STRIPE_SECRET_KEY the prod validator accepts must leave
 * {@code isLive()} true.</b> If that implication is ever broken, a fully
 * validated prod deployment serves free deposits — which is precisely the
 * incident this file was written after.
 */
class ProdScaffoldingUnreachableSpec extends Specification {

    // ── environments ────────────────────────────────────────────────

    /**
     * A REAL Spring Environment — not a Stub. `setActiveProfiles` is the same
     * call Spring makes when it reads SPRING_PROFILES_ACTIVE, so a change in
     * how profiles resolve shows up here instead of being papered over by a
     * mock that answers whatever we told it to.
     */
    private static Environment realEnv(List<String> profiles, Map<String, Object> props = [:]) {
        def env = new StandardEnvironment()
        if (profiles) env.setActiveProfiles(profiles as String[])
        env.propertySources.addFirst(new MapPropertySource('spec', props))
        env
    }

    /** Instance (not static) — Spock refuses to create Mocks in static scope. */
    private SteamAuthController controllerWithUser(Environment env, SteamUser user) {
        SteamUserRepository repo = Mock() {
            findById(_) >> Optional.ofNullable(user)
            findAll() >> (user ? [user] : [])
        }
        new SteamAuthController(env: env, steamUserRepository: repo)
    }

    // ── DOOR 1: dev-login ───────────────────────────────────────────

    def "NEGATIVE CONTROL: on the default profile dev-login mints a real session for anyone"() {
        given: 'the exact shape of the JVM that was serving the public domain — no profile, no Stripe key'
        def user = new SteamUser(id: 7L, displayName: 'victim', sessionEpoch: 2L)
        def controller = controllerWithUser(realEnv([]), user)
        HttpSession session = Mock()
        HttpServletRequest req = Mock() { getSession(true) >> session }
        HttpServletResponse resp = Mock()

        when: 'an anonymous caller asks to be user 7'
        controller.devLogin(7L, '/profile', req, resp)

        then: 'they get a session as user 7, with no credential of any kind'
        1 * session.setAttribute(SteamAuthController.SESSION_USER_ID, 7L)
        1 * resp.sendRedirect('/profile')
    }

    def "dev-login is DEAD under the prod profile even when the requested user exists"() {
        given: 'the same request, the same existing user — only the profile differs'
        def user = new SteamUser(id: 7L, displayName: 'victim', sessionEpoch: 2L)
        def controller = controllerWithUser(realEnv(['prod']), user)
        HttpServletRequest req = Mock()
        HttpServletResponse resp = Mock()

        when:
        def result = controller.devLogin(7L, '/profile', req, resp)

        then: '404, and — the part that matters — no session is ever created'
        result.statusCode == HttpStatus.NOT_FOUND
        0 * req.getSession(_)
        0 * req.getSession()
        0 * resp.sendRedirect(_)
    }

    def "dev-login is DEAD under prod even with no userId, which otherwise logs in the first user found"() {
        given: 'the no-argument form is the dangerous one: it picks whoever is first in the table'
        def controller = controllerWithUser(realEnv(['prod']), new SteamUser(id: 1L, displayName: 'admin'))
        HttpServletRequest req = Mock()
        HttpServletResponse resp = Mock()

        when:
        def result = controller.devLogin(null, '/profile', req, resp)

        then:
        result.statusCode == HttpStatus.NOT_FOUND
        0 * req.getSession(_)
        0 * resp.sendRedirect(_)
    }

    /**
     * Tonight's actual measurement mistake, encoded so it cannot be repeated.
     *
     * Probing the live endpoint returned `404` and that was briefly read as
     * "the door is shut". It was not: the controller has TWO 404s, and the one
     * that fired was the second — the guard had already been passed and the
     * only reason nothing was minted is that no user carried the id we asked
     * for. A 404 alone is worthless as evidence; the BODY is what separates
     * them.
     */
    def "a 404 from dev-login does not prove the door is shut — the body is what distinguishes them"() {
        given: 'prod, user exists → the guard 404'
        def guarded = controllerWithUser(realEnv(['prod']), new SteamUser(id: 7L))
        and: 'no profile, user absent → the "no seed users" 404'
        def openButEmpty = controllerWithUser(realEnv([]), null)

        when:
        def guardedResult = guarded.devLogin(7L, '/profile', Mock(HttpServletRequest), Mock(HttpServletResponse))
        def openResult = openButEmpty.devLogin(999999999L, '/profile', Mock(HttpServletRequest), Mock(HttpServletResponse))

        then: 'both are 404 — indistinguishable by status code alone'
        guardedResult.statusCode == HttpStatus.NOT_FOUND
        openResult.statusCode == HttpStatus.NOT_FOUND

        and: 'the guard 404 now SAYS SO — a shut door emits a signal of its own'
        guardedResult.body == [error: SteamAuthController.DEV_LOGIN_DISABLED]

        and: 'the open-door 404 names the real reason, and that reason is not the guard'
        openResult.body == [error: SteamAuthController.DEV_LOGIN_NO_SEED_USERS]

        and: 'the two bodies are different, which is the only thing that separates them'
        guardedResult.body != openResult.body
    }

    /**
     * The guard used to answer with an EMPTY 404 — which is also what a missing
     * route, a typo'd path, and a dead server produce. Proving a door is shut
     * on an absence is the repo's "absence read as success" defect; the fix is
     * that the shut door speaks.
     */
    def "the shut door emits a positive signal, not an absence"() {
        given:
        def controller = controllerWithUser(realEnv(['prod']), new SteamUser(id: 7L))

        when:
        def result = controller.devLogin(7L, '/profile', Mock(HttpServletRequest), Mock(HttpServletResponse))

        then: 'there IS a body, and it is the guard that wrote it'
        result.body != null
        result.body.error == SteamAuthController.DEV_LOGIN_DISABLED

        and: 'and it cannot be confused with the decoy that fooled a live probe'
        result.body.error != SteamAuthController.DEV_LOGIN_NO_SEED_USERS
    }

    // ── DOOR 2: the dev deposit ─────────────────────────────────────

    /**
     * A production-shaped value for every var {@link ProdConfigValidator}
     * requires, so a case that varies ONE var is not also tripping the others.
     *
     * The Stripe values are structurally live-shaped but transparently
     * synthetic (constructed at runtime from repeated characters). They are
     * NOT credentials and cannot authenticate to anything — the same
     * convention {@link ProdConfigValidatorSpec} already uses.
     */
    private static Map<String, Object> prodEnvProps() {
        [
            SPRING_DATASOURCE_URL:      'jdbc:postgresql://db.host:5432/skinbox',
            SPRING_DATASOURCE_USERNAME: 'skinbox',
            SPRING_DATASOURCE_PASSWORD: 'a-real-password',
            CORS_ALLOWED_ORIGINS:       'https://skinbox.market',
            ADMIN_BOOTSTRAP_STEAM_IDS:  '76561197960287930',
            STRIPE_SECRET_KEY:          'sk_live_' + ('0' * 24),
            STRIPE_PUBLISHABLE_KEY:     'pk_live_' + ('0' * 24),
            STRIPE_WEBHOOK_SECRET:      'whsec_' + ('0' * 32),
            STRIPE_SUCCESS_URL:         'https://skinbox.market/?deposit=success',
            STRIPE_CANCEL_URL:          'https://skinbox.market/?deposit=cancel',
            STEAM_REALM:                'https://skinbox.market/',
            STEAM_RETURN_URL:           'https://skinbox.market/api/auth/steam/return',
            APP_UNSUBSCRIBE_SECRET:     'a-real-random-prod-secret-9912ff',
            APP_PUBLIC_URL:             'https://skinbox.market',
        ] as Map<String, Object>
    }

    def "NEGATIVE CONTROL: the running default-profile config routes deposits into devModeDeposit"() {
        given: 'application.yml ships stripe.secret-key = sk_test_replace_me when no env var is set'
        def stripe = new StripeService(secretKey: 'sk_test_replace_me')

        expect: 'isLive() is false, which is the ONLY condition createDepositSession checks'
        !stripe.isLive()
    }

    /**
     * THE INVARIANT. If the prod validator lets the app start with a given
     * STRIPE_SECRET_KEY, that key must also make isLive() true — otherwise a
     * fully-validated production deployment silently serves free deposits
     * through devModeDeposit.
     */
    @Unroll
    def "prod-accepted STRIPE_SECRET_KEY leaves the dev deposit path shut: #label"() {
        given: 'a complete prod config that varies only the secret key'
        Map<String, Object> props = prodEnvProps()
        if (key == null) props.remove('STRIPE_SECRET_KEY') else props.STRIPE_SECRET_KEY = key
        def validator = new ProdConfigValidator(environment: realEnv(['prod'], props))

        when: 'we ask whether prod would boot on this key, and whether it opens the dev path'
        boolean prodWouldBoot = validator.findViolations().isEmpty()
        boolean devDepositShut = new StripeService(secretKey: key).isLive()

        then: 'booting implies the dev deposit path is unreachable'
        prodWouldBoot == expectedBoot
        !prodWouldBoot || devDepositShut

        where:
        label                                | key                              || expectedBoot
        'a live secret key'                  | 'sk_live_' + ('0' * 24)          || true
        'a restricted live key'              | 'rk_live_' + ('0' * 24)          || true
        'a test key'                         | 'sk_test_' + ('0' * 24)          || false
        'a truncated live key'               | 'sk_live_abc'                    || false
        'the publishable key in this slot'   | 'pk_live_' + ('0' * 24)          || false
        'a webhook secret in this slot'      | 'whsec_' + ('0' * 32)            || false
        'a human placeholder'                | 'changeme'                       || false
        'the committed dev fallback'         | 'sk_test_replace_me'             || false
        'absent entirely'                    | null                             || false
        // A live-SHAPED key that still contains the isLive() magic substring.
        // Contrived as a paste, but it is the one input for which the shape
        // check and isLive() disagree, and the disagreement fails OPEN.
        'live-shaped but contains replace_me'| 'sk_live_replace_me' + ('0' * 8) || false
    }

    /**
     * Pins the branch this whole argument rests on. Per the repo's recurring
     * "correct logic nobody calls" failure, it is not enough that isLive() is
     * true — createDepositSession must actually be the only way into
     * devModeDeposit, and !isLive() must be the only thing that opens it.
     */
    def "devModeDeposit has exactly one production caller, guarded by devFallbackAuthorized()"() {
        given:
        String src = new File('src/main/groovy/com/sboxmarket/service/StripeService.groovy').text

        expect: 'the fallthrough is inside an AFFIRMATIVE authorisation branch'
        (src =~ /if\s*\(\s*moneyMode\(\)\.devFallbackAuthorized\(\)\s*\)\s*\{[^}]*devModeDeposit\(/).find()

        and: 'NOT inside the negation of isLive(), which is what made an unrecognised key free money'
        !(src =~ /if\s*\(\s*!isLive\(\)\s*\)\s*\{[^}]*devModeDeposit\(/).find()

        and: 'and that is the only call site outside comments'
        src.readLines()
           .findAll { it.contains('devModeDeposit(') }
           .findAll { !it.trim().startsWith('*') && !it.trim().startsWith('//') }
           .size() == 2   // the declaration and the single guarded call
    }

    /**
     * BY CONSTRUCTION, not by the caller. Per this repo's recurring "correct
     * logic nobody calls" failure, a guard that lives only at the call site is
     * one careless future caller away from being bypassed — and this method
     * credits a real wallet balance against no payment.
     *
     * Driven through the METHOD, not through a source scan, so it is the
     * behaviour that is pinned rather than the text.
     */
    @Unroll
    def "devModeDeposit refuses to run at all on a #mode deployment"() {
        given: 'the method is called DIRECTLY, as a future caller that forgot the guard would'
        def stripe = new StripeService(secretKey: key)

        when:
        stripe.devModeDeposit(1L, new BigDecimal('10.00'))

        then: 'it refuses before touching a repository — no wallet lookup, no credit'
        def e = thrown(IllegalStateException)
        e.message.contains(mode)

        and: 'sanity: it refused because of the mode, not because collaborators were unwired'
        stripe.moneyMode().toString() == mode

        where:
        mode            | key
        'LIVE'          | 'sk_live_' + ('0' * 24)
        'LIVE'          | 'rk_live_' + ('0' * 24)
        'TEST'          | 'sk_test_' + ('0' * 24)
        'INDETERMINATE' | 'sk_live_replace_me' + ('0' * 8)
        'INDETERMINATE' | 'pk_live_' + ('0' * 24)
        'INDETERMINATE' | 'changeme'
    }

    /**
     * The half of the invariant commit e11b012 could not reach.
     *
     * {@link ProdConfigValidator} is {@code @Profile('prod')}, so its rejection
     * of {@code sk_live_replace_me…} protects only a prod boot. The deposit
     * door is NOT profile-gated, and the live JVM ran the DEFAULT profile — so
     * the same key still met a false {@code isLive()} and fell through to
     * {@code devModeDeposit}. The fix has to hold with no profile at all.
     */
    @Unroll
    def "the free-deposit path is shut on the DEFAULT profile too: #label"() {
        given: 'no prod profile, so ProdConfigValidator never runs'
        def stripe = new StripeService(secretKey: key)

        expect: 'only an affirmatively-unconfigured deployment may fabricate a credit'
        stripe.moneyMode().devFallbackAuthorized() == fallbackAllowed

        where:
        label                          | key                              || fallbackAllowed
        'the committed placeholder'    | 'sk_test_replace_me'             || true
        'no key at all'                | null                             || true
        'live-shaped with replace_me'  | 'sk_live_replace_me' + ('0' * 8) || false
        'a real live key'              | 'sk_live_' + ('0' * 24)          || false
        'a restricted live key'        | 'rk_live_' + ('0' * 24)          || false
        'the publishable key pasted in'| 'pk_live_' + ('0' * 24)          || false
        'a human placeholder'          | 'changeme'                       || false
    }
}
