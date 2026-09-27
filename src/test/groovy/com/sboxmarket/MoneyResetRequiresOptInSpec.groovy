package com.sboxmarket

import com.sboxmarket.config.MoneyMode
import com.sboxmarket.config.MoneyResetGate
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification
import spock.lang.Unroll

/**
 * <b>Destroying the ledger must be ASKED for twice, on a deployment that has
 * affirmatively said it holds no real money.</b>
 *
 * The mirror image of {@link com.sboxmarket.config.DevCreditGate}: that gate
 * stops money being invented, this one stops it being deleted. The failure
 * modes are symmetric and so are the rules — <b>affirmatively SIMULATED AND an
 * affirmative, named opt-in in the PROCESS ENVIRONMENT</b> — with one addition
 * that {@link MoneyResetGate} makes and DevCreditGate does not: the read-only
 * dry run is gated too, because it prints every wallet balance on the platform
 * and because an unreachable code path cannot be executed by accident.
 *
 * @see MoneyResetGate
 */
class MoneyResetRequiresOptInSpec extends Specification {

    private static final String OPT_IN  = MoneyResetGate.OPT_IN_ENV_VAR
    private static final String EXECUTE = MoneyResetGate.EXECUTE_ENV_VAR

    /** The ordinary local deployment WITH the reset opt-in granted. */
    private static ConfigurableEnvironment resetOptedIn(Map<String, Object> extra = [:]) {
        SpecEnvs.env([], [(OPT_IN): MoneyResetGate.OPT_IN_VALUE] + extra)
    }

    // ── THE DEFAULT IS REFUSAL ──────────────────────────────────────

    def "a fresh checkout with no special environment resets NOTHING"() {
        given: 'the ordinary local deployment, and an EMPTY process environment'
        def env = SpecEnvs.env([], [:])

        expect: 'the tool may not even build a report'
        !MoneyResetGate.resetAuthorized(env)
        MoneyResetGate.refusalReason(env) == MoneyResetGate.REASON_NOT_AUTHORIZED

        and: '''sanity: refused despite the deployment being a perfectly ordinary
                SIMULATED one. That classification is CORRECT and is not the gate.'''
        MoneyMode.of(env) == MoneyMode.SIMULATED
        MoneyMode.of(env).devFallbackAuthorized()
    }

    def "a null Environment — the context-less default — resets nothing"() {
        expect: 'refused'
        !MoneyResetGate.resetAuthorized((Environment) null)

        and: '''and refused as INDETERMINATE, not as "unclassified". MoneyMode.of(null)
                answers INDETERMINATE by design — a missing answer counts as real money —
                so the gate never reaches its own null branch here. The distinction is
                worth pinning: it proves the refusal comes from the ONE classification
                authority rather than from a second copy of the rule inside this gate.'''
        MoneyMode.of((Environment) null) == MoneyMode.INDETERMINATE
        MoneyResetGate.refusalReason((Environment) null) ==
            MoneyResetGate.reasonNotSimulated(MoneyMode.INDETERMINATE)
    }

    // ── ONLY SIMULATED, AND TEST IS REFUSED TOO ─────────────────────

    @Unroll
    def "a #mode deployment is refused even WITH the opt-in granted"() {
        given: 'the operator has asked, in the process environment, on the wrong box'
        def env = resetOptedIn()

        expect: 'the mode veto runs FIRST and names the deployment'
        !MoneyResetGate.resetAuthorized(mode, env)
        MoneyResetGate.refusalReason(mode, env) == MoneyResetGate.reasonNotSimulated(mode)

        and: 'the reason names the mode, so the operator is told WHICH box refused'
        MoneyResetGate.refusalReason(mode, env).contains(mode.name())

        where:
        mode << [MoneyMode.LIVE, MoneyMode.TEST, MoneyMode.INDETERMINATE]
    }

    def "a TEST deployment is refused because its ledger rows are REAL Stripe rows"() {
        given: '''a Stripe TEST-mode key. Nothing here can move real money, so the
                  instinct is that a reset is harmless — but the rows in that ledger
                  came from Stripe, reconcile against a Stripe dashboard, and are the
                  only evidence a payments integration was ever verified.'''
        def env = SpecEnvs.env([], [(OPT_IN): 'true'], ['stripe.secret-key': 'sk_test_' + ('a' * 24)])

        expect:
        MoneyMode.of(env) == MoneyMode.TEST
        !MoneyResetGate.resetAuthorized(env)
        MoneyResetGate.refusalReason(env).contains('TEST')
    }

    def "an opt-in left set does not re-open the door once a LIVE key arrives"() {
        given: '''the exact drift DevCreditGate documents: someone exported the
                  variable months ago, and the box has since acquired a real key.'''
        def env = SpecEnvs.env([], [(OPT_IN): 'true', (EXECUTE): 'true'],
                               ['stripe.secret-key': 'sk_live_' + ('0' * 24)])

        expect: 'the mode check runs first, so the stale opt-in is never even consulted'
        MoneyMode.of(env) == MoneyMode.LIVE
        !MoneyResetGate.resetAuthorized(env)
        MoneyResetGate.refusalReason(env) == MoneyResetGate.reasonNotSimulated(MoneyMode.LIVE)
    }

    // ── "I CANNOT TELL" IS CLOSED, IN EVERY DIRECTION ───────────────

    @Unroll
    def "an opt-in value of #desc does not authorise"() {
        given:
        def env = SpecEnvs.env([], value == null ? [:] : [(OPT_IN): value])

        expect: 'only the literal string true opens it'
        !MoneyResetGate.optInGranted(env)
        !MoneyResetGate.resetAuthorized(env)

        where:
        desc            | value
        'absent'        | null
        'blank'         | ''
        'whitespace'    | '   '
        'yes'           | 'yes'
        'the number 1'  | '1'
        'on'            | 'on'
        'TRUE-ish typo' | 'ture'
        'false'         | 'false'
    }

    @Unroll
    def "an opt-in of '#value' DOES authorise — trimmed, case-insensitive"() {
        given:
        def env = SpecEnvs.env([], [(OPT_IN): value])

        expect:
        MoneyResetGate.optInGranted(env)
        MoneyResetGate.resetAuthorized(env)

        where:
        value << ['true', 'TRUE', ' true ', 'True']
    }

    def "an Environment that is not Configurable cannot grant the opt-in"() {
        given: '''a plain Environment stub. It can answer getProperty, which is
                  exactly the channel the gate deliberately does NOT read.'''
        Environment env = Stub(Environment) {
            getProperty(_) >> 'true'
            getProperty(_, _) >> 'true'
        }

        expect:
        !MoneyResetGate.optInGranted(env)
    }

    def "a THROWING property source refuses by decision, not by a 500"() {
        given: '''the gap that bit DevLoginGate: MoneyMode.of reads property
                  sources, and a half-initialised source throws out from under it.'''
        def env = new StandardEnvironment()
        env.propertySources.addFirst(new MapPropertySource('boom', [:]) {
            @Override Object getProperty(String name) { throw new IllegalStateException('unreadable') }
            @Override boolean containsProperty(String name) { throw new IllegalStateException('unreadable') }
        })

        when: 'the Environment-only form is asked'
        String reason = MoneyResetGate.refusalReason(env)

        then: 'it refuses, and it refuses with the STRONGEST reason'
        noExceptionThrown()
        reason == MoneyResetGate.reasonNotSimulated(null)
    }

    // ── TWO VARIABLES, BECAUSE THERE ARE TWO QUESTIONS ──────────────

    def "the opt-in ALONE is a dry run — it does not authorise execution"() {
        given: 'the operator asked to SEE the plan and nothing more'
        def env = resetOptedIn()

        expect: 'the tool may run'
        MoneyResetGate.resetAuthorized(env)

        and: 'but the destructive step is NOT granted'
        !MoneyResetGate.executeGranted(env)
    }

    def "the execute flag ALONE destroys nothing — it is never consulted without the opt-in"() {
        given: '''a stray SBOX_MONEY_RESET_EXECUTE=true left in a shell profile,
                  with nobody having asked for the tool to run at all.'''
        def env = SpecEnvs.env([], [(EXECUTE): 'true'])

        expect: 'the tool refuses at the first gate, so the flag is inert'
        !MoneyResetGate.resetAuthorized(env)
        MoneyResetGate.refusalReason(env) == MoneyResetGate.REASON_NOT_AUTHORIZED
    }

    def "both variables together, on a SIMULATED box, is the only authorised destruction"() {
        given:
        def env = resetOptedIn([(EXECUTE): 'true'])

        expect:
        MoneyResetGate.resetAuthorized(env)
        MoneyResetGate.executeGranted(env)
    }

    @Unroll
    def "an execute value of #desc does not authorise destruction"() {
        given:
        def env = resetOptedIn(value == null ? [:] : [(EXECUTE): value])

        expect:
        !MoneyResetGate.executeGranted(env)

        where:
        desc         | value
        'absent'     | null
        'blank'      | ''
        'yes'        | 'yes'
        'the number 1' | '1'
        'false'      | 'false'
    }

    def "the two variables have DIFFERENT names — one cannot answer both questions"() {
        expect:
        MoneyResetGate.OPT_IN_ENV_VAR != MoneyResetGate.EXECUTE_ENV_VAR

        and: 'and neither collides with the other two gates in this repo'
        ![com.sboxmarket.config.DevLoginGate.OPT_IN_ENV_VAR,
          com.sboxmarket.config.DevCreditGate.OPT_IN_ENV_VAR]
            .contains(MoneyResetGate.OPT_IN_ENV_VAR)
        ![com.sboxmarket.config.DevLoginGate.OPT_IN_ENV_VAR,
          com.sboxmarket.config.DevCreditGate.OPT_IN_ENV_VAR]
            .contains(MoneyResetGate.EXECUTE_ENV_VAR)
    }

    // ── THE CHANNEL CANNOT BE A COMMITTED ONE ───────────────────────

    def "a resolved PROPERTY cannot grant either variable — only the process environment can"() {
        given: '''the variable set the way application.yml / a mounted config file
                  / a -D flag would set it: as a resolved property, NOT in the
                  systemEnvironment source.'''
        def env = SpecEnvs.env([], [:], [(OPT_IN): 'true', (EXECUTE): 'true'])

        expect: 'getProperty would answer true — and the gate does not ask it'
        env.getProperty(OPT_IN) == 'true'
        !MoneyResetGate.optInGranted(env)
        !MoneyResetGate.executeGranted(env)
        !MoneyResetGate.resetAuthorized(env)
    }

    @Unroll
    def "no committed config file grants a reset variable: #path"() {
        given:
        def f = new File(path)

        expect: 'the file exists, so a renamed/moved file cannot make this vacuous'
        f.exists()

        and: 'and its CONFIGURATION — comments removed — never sets either variable'
        !stripHashComments(f.text).contains(OPT_IN)
        !stripHashComments(f.text).contains(EXECUTE)

        where:
        path << [
            'src/main/resources/application.yml',
            'src/main/resources/application-prod.yml',
            'docker-compose.yml',
            'Dockerfile',
            'deploy/skinbox.env.example',
        ]
    }

    def "the comment stripper is not vacuous — it would catch a real assignment"() {
        given: 'a yml-shaped sample where the variable appears in BOTH positions'
        def sample = """
            # ${OPT_IN}: true   <- a comment, must NOT count
            env:
              ${OPT_IN}: true   # <- configuration, MUST count
        """.stripIndent()

        expect: 'the stripped text still contains the real assignment'
        stripHashComments(sample).contains(OPT_IN)

        and: 'and a comment-only mention is correctly ignored'
        !stripHashComments("# ${OPT_IN}: true").contains(OPT_IN)
    }

    // ── THE ANSWER AND THE EXPLANATION CANNOT DRIFT ─────────────────

    @Unroll
    def "resetAuthorized and refusalReason agree: #label"() {
        given:
        def env = envSupplier.call()

        expect: 'authorized is DEFINED as "no reason to refuse" — they cannot disagree'
        MoneyResetGate.resetAuthorized(mode, env) == (MoneyResetGate.refusalReason(mode, env) == null)

        where:
        label                     | mode                    | envSupplier
        'simulated, no opt-in'    | MoneyMode.SIMULATED     | { SpecEnvs.env([], [:]) }
        'simulated, opted in'     | MoneyMode.SIMULATED     | { resetOptedIn() }
        'live, opted in'          | MoneyMode.LIVE          | { resetOptedIn() }
        'test, opted in'          | MoneyMode.TEST          | { resetOptedIn() }
        'indeterminate, opted in' | MoneyMode.INDETERMINATE | { resetOptedIn() }
        'null mode, opted in'     | null                    | { resetOptedIn() }
    }

    /** Copied deliberately from DevCreditRequiresOptInSpec: the same question
     *  deserves the same reader, and a second implementation would be a second
     *  chance to disagree about what counts as a comment. */
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
}
