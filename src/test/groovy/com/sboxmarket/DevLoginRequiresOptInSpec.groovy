package com.sboxmarket

import com.sboxmarket.config.DevLoginGate
import com.sboxmarket.config.MoneyMode
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.core.env.Environment
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Unroll

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession

/**
 * The credential-free login must be ASKED for, not merely un-forbidden.
 *
 * <h3>The state this closes, which was live and was not a bug</h3>
 *
 * On 2026-09-01 {@code GET /api/auth/steam/dev-login} on the running app
 * answered <b>302</b>, set a <b>one-year session</b>, and that session
 * authenticated as the user holding <b>$30,375.18</b>.
 *
 * Every guard was working. No live Stripe key is configured, so
 * {@link MoneyMode} classified the deployment {@link MoneyMode#SIMULATED},
 * {@code LiveMoneyGuard.isRealMoney} was correctly false, and the door opened
 * BY DESIGN — that is what SIMULATED means, and four specs plus the whole e2e
 * suite depend on it.
 *
 * What made it dangerous was that its only remaining control was the
 * {@code 127.0.0.1} bind in {@code application.yml}, and there is a path beside
 * that lock: {@code ~/.cloudflared/config.yml} maps
 * {@code skinbox.market -> http://localhost:8082}, and <b>a tunnel connects
 * FROM loopback</b>. DNS already points at that tunnel. So the distance between
 * a laptop affordance and credential-free takeover of every account, admins
 * included, published on the public internet, was one process dying.
 *
 * <h3>The rule</h3>
 *
 * "Not a real-money deployment" is a statement about MONEY, not a grant of
 * permission. The default state of every box nobody has configured yet is
 * exactly the state that must not serve this door — the same argument
 * {@code SeedRequiresOptInSpec} makes about fabricated inventory, and the same
 * fail-closed shape.
 *
 * So the gate is a CONJUNCTION: no real money AND an affirmative, named opt-in
 * in the PROCESS ENVIRONMENT. Everything else — absent, blank, unparseable,
 * unreadable, or supplied through a channel that could be committed to this
 * repository — is CLOSED.
 *
 * @see DevLoginGate
 */
class DevLoginRequiresOptInSpec extends Specification {

    private static final String OPT_IN = DevLoginGate.OPT_IN_ENV_VAR

    /** A live-SHAPED Stripe key built at runtime from repeated characters.
     *  It is not a credential and cannot authenticate to anything — the
     *  convention every spec in this repo uses. */
    private static String liveShapedKey() { 'sk_live_' + ('0' * 24) }

    private SteamAuthController controllerWithUser(Environment env, SteamUser user) {
        SteamUserRepository repo = Mock() {
            findById(_) >> Optional.ofNullable(user)
            findAll() >> (user ? [user] : [])
        }
        new SteamAuthController(env: env, steamUserRepository: repo)
    }

    // ── THE REGRESSION ──────────────────────────────────────────────

    /**
     * The exact shape of the JVM that was serving a one-year session for
     * $30,375.18: default profile, no Stripe key, nobody having asked for
     * anything. Before this change it minted a session here.
     */
    def "a fresh checkout with no special environment refuses to mint a session"() {
        given: 'default profile, no Stripe key, an EMPTY process environment'
        def user = new SteamUser(id: 7L, displayName: 'victim', sessionEpoch: 2L)
        def controller = controllerWithUser(SpecEnvs.env([], [:]), user)
        HttpServletRequest req = Mock()
        HttpServletResponse resp = Mock()

        when: 'an anonymous caller asks to be user 7'
        def result = controller.devLogin(7L, '/profile', req, resp)

        then: 'no session of any kind is created, and no redirect is issued'
        0 * req.getSession(_)
        0 * req.getSession()
        0 * resp.sendRedirect(_)

        and: 'and the shut door SAYS why, rather than answering with an absence'
        result.statusCode == HttpStatus.NOT_FOUND
        result.body.error == DevLoginGate.REASON_NOT_AUTHORIZED

        and: 'sanity: it refused despite the deployment being a perfectly ordinary SIMULATED one'
        MoneyMode.of(SpecEnvs.env([], [:])) == MoneyMode.SIMULATED
    }

    def "the no-userId form — which otherwise logs in whoever is first in the table — is refused too"() {
        given:
        def controller = controllerWithUser(SpecEnvs.env([], [:]),
                                            new SteamUser(id: 1L, displayName: 'admin'))
        HttpServletRequest req = Mock()
        HttpServletResponse resp = Mock()

        when:
        def result = controller.devLogin(null, '/profile', req, resp)

        then:
        result.statusCode == HttpStatus.NOT_FOUND
        result.body.error == DevLoginGate.REASON_NOT_AUTHORIZED
        0 * req.getSession(_)
        0 * resp.sendRedirect(_)
    }

    // ── THE OPT-IN ──────────────────────────────────────────────────

    def "with the opt-in set in the process environment, the QA door works exactly as before"() {
        given: 'the endpoint is real QA scaffolding — a gate nobody can pass just gets deleted'
        def user = new SteamUser(id: 42L, displayName: 'dev', sessionEpoch: 3L)
        def controller = controllerWithUser(SpecEnvs.optedIn(), user)
        HttpSession session = Mock()
        HttpServletRequest req = Mock() { getSession(true) >> session }
        HttpServletResponse resp = Mock()

        when:
        controller.devLogin(42L, '/profile', req, resp)

        then: 'a session IS minted, and the post-login redirect is honoured'
        1 * session.setAttribute(SteamAuthController.SESSION_USER_ID, 42L)
        1 * session.setAttribute(SteamAuthController.SESSION_EPOCH, 3L)
        1 * resp.sendRedirect('/profile')
    }

    @Unroll
    def "opt-in value #desc -> authorized=#expected"() {
        expect: 'only the literal "true" opens it; every other value is a refusal'
        DevLoginGate.devLoginAuthorized(SpecEnvs.env([], processEnv)) == expected

        where:
        desc                        | processEnv                  || expected
        'exactly true'              | [(OPT_IN): 'true']          || true
        'TRUE (case-insensitive)'   | [(OPT_IN): 'TRUE']          || true
        'True'                      | [(OPT_IN): 'True']          || true
        'padded with whitespace'    | [(OPT_IN): '  true  ']      || true
        'false'                     | [(OPT_IN): 'false']         || false
        'yes'                       | [(OPT_IN): 'yes']           || false
        'the number 1'              | [(OPT_IN): '1']             || false
        'on'                        | [(OPT_IN): 'on']            || false
        'enabled'                   | [(OPT_IN): 'enabled']       || false
        'a typo'                    | [(OPT_IN): 'ture']          || false
        'blank'                     | [(OPT_IN): '']              || false
        'whitespace only'           | [(OPT_IN): '   ']           || false
        'absent entirely'           | [:]                         || false
        'a differently-named var'   | ['SBOX_DEV_LOGIN': 'true']  || false
    }

    // ── "I CANNOT TELL" IS CLOSED ───────────────────────────────────

    def "a null Environment is refused, and it is refused as real money"() {
        expect: 'no Environment means no answer, and MoneyMode already says an unknown answer is LIVE'
        !DevLoginGate.devLoginAuthorized(null)
        DevLoginGate.refusalReason(null) == DevLoginGate.REASON_REAL_MONEY
    }

    def "an Environment that is not configurable cannot grant the opt-in"() {
        given: 'a bare Stub answers getProperty and nothing else — it has no process environment to read'
        Environment stub = Stub(Environment) {
            getActiveProfiles() >> ([] as String[])
            getProperty(_) >> { String n -> n == OPT_IN ? 'true' : null }
        }

        expect: 'even though it would happily answer "true", there is no systemEnvironment source to trust'
        !DevLoginGate.devLoginAuthorized(stub)
        DevLoginGate.refusalReason(stub) == DevLoginGate.REASON_NOT_AUTHORIZED
    }

    def "an Environment with no systemEnvironment property source is refused"() {
        given:
        def env = SpecEnvs.env([], [(OPT_IN): 'true'])
        env.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)

        expect: 'the source we require is gone, so we cannot tell — which is CLOSED'
        !DevLoginGate.devLoginAuthorized(env)
    }

    /**
     * Found by writing this case, not by reading the code. An unreadable
     * property source throws out of {@code MoneyMode.of} — inside
     * {@code LiveMoneyGuard.isRealMoney}, which the gate calls FIRST — so the
     * exception escaped the gate's own try/catch entirely and reached the
     * controller. That still refuses (Spring answers 500 and no session is
     * minted), but it refuses BY ACCIDENT: the outcome depends on the caller's
     * error handling, and the door says nothing about itself. A guard must
     * return an answer, not a stack trace.
     */
    def "a property source that THROWS is refused with an answer, not an exception"() {
        given: 'an unreadable environment — a broken source, a half-initialised one, anything'
        def env = SpecEnvs.env([], [:])
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new PropertySource<Object>(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new Object()) {
                @Override Object getProperty(String name) { throw new IllegalStateException('unreadable') }
            })

        expect: 'sanity: the environment really is unreadable, so this case is not vacuous'
        thrownBy { env.getProperty('stripe.secret-key') } instanceof IllegalStateException

        and: 'no exception escapes the gate'
        !DevLoginGate.devLoginAuthorized(env)

        and: '''and the answer is the STRONGEST refusal, not the weakest: an environment
                that throws is the most complete form of "I cannot tell", and MoneyMode
                already rules that an unknown answer is real money.'''
        DevLoginGate.refusalReason(env) == DevLoginGate.REASON_REAL_MONEY
    }

    /** Helper: run a closure and return whatever it threw (or null). */
    private static Throwable thrownBy(Closure c) {
        try { c.call(); return null } catch (Throwable t) { return t }
    }

    def "a systemEnvironment source that throws only on the opt-in lookup is still refused"() {
        given: '''the narrower failure: money classification succeeds, the opt-in read does
                  not. processEnvValue has its own catch, and this is what proves it runs.'''
        def env = SpecEnvs.env([], [:])
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new PropertySource<Object>(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new Object()) {
                @Override Object getProperty(String name) {
                    if (name == OPT_IN) throw new IllegalStateException('unreadable')
                    return null
                }
            })

        expect: 'the money check got through cleanly ...'
        !com.sboxmarket.config.LiveMoneyGuard.isRealMoney(env)

        and: '... and the unreadable opt-in reads as NO'
        !DevLoginGate.devLoginAuthorized(env)
        DevLoginGate.refusalReason(env) == DevLoginGate.REASON_NOT_AUTHORIZED
    }

    // ── THE OPT-IN CANNOT OUTRANK THE MONEY GATE ────────────────────

    @Unroll
    def "the opt-in does NOT re-open the door on a #label deployment"() {
        given: 'someone set the variable on this box months ago, and the deployment has since changed'
        def env = SpecEnvs.env(profiles,
                               [(OPT_IN): 'true'] + (key ? ['STRIPE_SECRET_KEY': key] : [:]))
        def user = new SteamUser(id: 7L, displayName: 'victim')
        def controller = controllerWithUser(env, user)
        HttpServletRequest req = Mock()
        HttpServletResponse resp = Mock()

        when:
        def result = controller.devLogin(7L, '/profile', req, resp)

        then: 'the money classification wins, and it says so'
        result.statusCode == HttpStatus.NOT_FOUND
        result.body.error == DevLoginGate.REASON_REAL_MONEY
        0 * req.getSession(_)
        0 * resp.sendRedirect(_)

        and: 'sanity: this really is the mode we think it is'
        MoneyMode.of(env) == mode

        where:
        label                       | profiles | key                              || mode
        'live-key'                  | []       | liveShapedKey()                  || MoneyMode.LIVE
        'restricted-live-key'       | []       | 'rk_live_' + ('0' * 24)          || MoneyMode.LIVE
        'prod-profile'              | ['prod'] | null                             || MoneyMode.INDETERMINATE
        'prod-profile-with-live-key'| ['prod'] | liveShapedKey()                  || MoneyMode.LIVE
        'unclassifiable-key'        | []       | 'changeme'                       || MoneyMode.INDETERMINATE
        'live-shaped-placeholder'   | []       | 'sk_live_replace_me' + ('0' * 8) || MoneyMode.INDETERMINATE
    }

    def "the money check runs BEFORE the opt-in check, so the operator is told the more serious reason"() {
        given: 'both reasons apply at once: real money AND no opt-in'
        def env = SpecEnvs.env([], ['STRIPE_SECRET_KEY': liveShapedKey()])

        expect:
        DevLoginGate.refusalReason(env) == DevLoginGate.REASON_REAL_MONEY
        DevLoginGate.refusalReason(env) != DevLoginGate.REASON_NOT_AUTHORIZED
    }

    // ── THE OPT-IN CANNOT ARRIVE THROUGH A COMMITTABLE CHANNEL ──────

    /**
     * The whole reason the gate reads one property source instead of the merged
     * view. A resolved Spring property can come from {@code application.yml}, a
     * profile yml, a mounted config file or a bundled {@code .properties} — all
     * of which are committed once and then inherited forever by deployments
     * that never re-decided anything. That is how a dev affordance goes live.
     */
    @Unroll
    def "a #channel cannot open the door, even carrying the exact variable name and value"() {
        given: 'an EMPTY process environment, and the opt-in supplied some other way'
        def env = SpecEnvs.env([], [:], props)

        expect: 'the merged property view says "true" ...'
        env.getProperty(lookup) == 'true'

        and: '... and the gate is still shut, because that is not the process environment'
        !DevLoginGate.devLoginAuthorized(env)
        DevLoginGate.refusalReason(env) == DevLoginGate.REASON_NOT_AUTHORIZED

        where:
        channel                          | props                                | lookup
        'yml key in the env-var spelling'| [(OPT_IN): 'true']                   | OPT_IN
        'yml key in dotted spelling'     | ['sbox.dev-login.enabled': 'true']   | 'sbox.dev-login.enabled'
        'a relaxed-binding alias'        | ['sbox.dev.login.enabled': 'true']   | 'sbox.dev.login.enabled'
    }

    def "a JVM -D system property cannot open the door either"() {
        given: 'systemProperties is a different source from systemEnvironment'
        def env = SpecEnvs.env([], [:])
        System.setProperty(OPT_IN, 'true')

        when:
        boolean authorized = DevLoginGate.devLoginAuthorized(env)

        then: 'one channel, one name — a -D flag is not it'
        env.getProperty(OPT_IN) == 'true'
        !authorized

        cleanup:
        System.clearProperty(OPT_IN)
    }

    // ── NO COMMITTED CONFIG FILE MAY GRANT IT ───────────────────────

    /**
     * Strip {@code #} line comments so an assertion cannot be satisfied — or
     * violated — by PROSE.
     *
     * This is not hypothetical caution. The last spec written in this repo
     * matched its own explanatory comment, which meant a "fix" written only
     * inside a comment would have passed it. Here the risk runs the other way
     * (a warning comment naming the variable would FAIL a raw-text scan and
     * push someone to delete the warning), and the answer is the same: assert
     * over configuration, never over commentary.
     */
    private static String stripHashComments(String text) {
        text.readLines().collect { line ->
            boolean inSingle = false, inDouble = false
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i)
                if (c == ('\'' as char) && !inDouble) inSingle = !inSingle
                else if (c == ('"' as char) && !inSingle) inDouble = !inDouble
                else if (c == ('#' as char) && !inSingle && !inDouble) return line.substring(0, i)
            }
            return line
        }.join('\n')
    }

    def "SELF-TEST: the comment stripper actually strips, so the scan below is not vacuous"() {
        given: 'the token present ONLY inside a comment, and once for real'
        String sample = [
            "# do not set ${OPT_IN}=true here",
            "  key: value   # ${OPT_IN}=true would be wrong",
            "  REAL_${OPT_IN}: true",
            "  quoted: '# not a comment ${OPT_IN}'",
        ].join('\n')

        when:
        String stripped = stripHashComments(sample)

        then: 'both comment occurrences are gone'
        stripped.count(OPT_IN) == 2
        !stripped.contains('do not set')
        !stripped.contains('would be wrong')

        and: 'and a # inside quotes is NOT treated as a comment'
        stripped.contains("'# not a comment ${OPT_IN}'")

        and: 'the un-stripped text would have matched — which is exactly the trap'
        sample.count(OPT_IN) == 4
    }

    @Unroll
    def "no committed config file grants the opt-in: #path"() {
        given:
        def f = new File(path)

        expect: 'the file exists, so a renamed/moved file cannot make this vacuous'
        f.exists()

        and: 'and its CONFIGURATION — comments removed — never sets the variable'
        !stripHashComments(f.text).contains(OPT_IN)

        where:
        path << [
            'src/main/resources/application.yml',
            'src/main/resources/application-prod.yml',
            'docker-compose.yml',
            'Dockerfile',
            'deploy/skinbox.env.example',
        ]
    }

    // ── THE ANSWER AND THE EXPLANATION CANNOT DRIFT ─────────────────

    @Unroll
    def "devLoginAuthorized and refusalReason agree: #label"() {
        given:
        def env = envSupplier.call()

        expect: 'authorized is DEFINED as "no reason to refuse" — the two can never disagree'
        DevLoginGate.devLoginAuthorized(env) == (DevLoginGate.refusalReason(env) == null)

        where:
        label                  | envSupplier
        'default, no opt-in'   | { SpecEnvs.env([], [:]) }
        'default, opted in'    | { SpecEnvs.optedIn() }
        'prod, opted in'       | { SpecEnvs.optedIn(['prod']) }
        'live key, opted in'   | { SpecEnvs.env([], [(OPT_IN): 'true', 'STRIPE_SECRET_KEY': liveShapedKey()]) }
        'null environment'     | { null }
    }

    def "every refusal is tellable from the OTHER 404 on this endpoint"() {
        expect: 'both guard refusals share one prefix, so a probe can match the guard as a class'
        DevLoginGate.REASON_REAL_MONEY.startsWith(DevLoginGate.REFUSAL_PREFIX)
        DevLoginGate.REASON_NOT_AUTHORIZED.startsWith(DevLoginGate.REFUSAL_PREFIX)

        and: 'they are distinct from each other, so the reason is recoverable'
        DevLoginGate.REASON_REAL_MONEY != DevLoginGate.REASON_NOT_AUTHORIZED

        and: 'and neither can be confused with the post-guard 404 that means the door was OPEN'
        !SteamAuthController.DEV_LOGIN_NO_SEED_USERS.startsWith(DevLoginGate.REFUSAL_PREFIX)
        DevLoginGate.REASON_REAL_MONEY != SteamAuthController.DEV_LOGIN_NO_SEED_USERS
        DevLoginGate.REASON_NOT_AUTHORIZED != SteamAuthController.DEV_LOGIN_NO_SEED_USERS

        and: 'the not-authorized reason names the variable, so the refusal is actionable'
        DevLoginGate.REASON_NOT_AUTHORIZED.contains(OPT_IN)
    }

    /**
     * The RUNBOOK, the operator's watchdog probe and two existing specs all
     * assert on this exact string. Rewording it silently breaks a control that
     * lives outside this repository.
     */
    def "the real-money refusal string is unchanged, byte for byte"() {
        expect:
        DevLoginGate.REASON_REAL_MONEY == 'dev-login disabled: real-money deployment'
        SteamAuthController.DEV_LOGIN_DISABLED == DevLoginGate.REASON_REAL_MONEY
        SteamAuthController.DEV_LOGIN_NOT_AUTHORIZED == DevLoginGate.REASON_NOT_AUTHORIZED
    }

    // ── THE MONEY PATH STILL PASSES THE DOOR IT ALWAYS DID ──────────

    def "the controller consults the GATE, not the money guard alone"() {
        given: '''per this repo's "correct logic nobody calls" failure: the right rule
                  existing is not the same as the money path calling it. Belt to the
                  behavioural cases above, asserted over COMMENT-STRIPPED source so a
                  fix written inside a comment cannot satisfy it.'''
        String src = stripHashComments(
            new File('src/main/groovy/com/sboxmarket/controller/SteamAuthController.groovy').text
                .replaceAll(/(?s)\/\*.*?\*\//, '')      // block + javadoc comments
                .replaceAll(/(?m)^\s*\/\/.*$/, ''))     // line comments

        int devLoginAt = src.indexOf('def devLogin(')

        expect: 'the method exists in real code, not only in prose'
        devLoginAt >= 0

        and: 'and it asks the gate before it can reach the session-minting code'
        int gateAt = src.indexOf('DevLoginGate.refusalReason(env)', devLoginAt)
        int mintAt = src.indexOf('SESSION_USER_ID', devLoginAt)
        gateAt > devLoginAt
        mintAt > gateAt
    }
}
