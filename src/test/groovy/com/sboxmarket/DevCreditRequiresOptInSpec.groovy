package com.sboxmarket

import com.sboxmarket.config.DevCreditGate
import com.sboxmarket.config.DevLoginGate
import com.sboxmarket.config.MoneyMode
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import org.springframework.core.env.Environment
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification
import spock.lang.Unroll

/**
 * <b>Minting credit against no payment must be ASKED for, not merely
 * un-forbidden.</b>
 *
 * <h3>The state this closes, which was live and was not a bug</h3>
 *
 * Commit {@code a52b4ad} closed {@code /api/auth/steam/dev-login} and said in
 * its own message what that did not reach:
 *
 * <blockquote>The door that was closed here was the shortcut INTO an account,
 * not the free-credit door behind it.</blockquote>
 *
 * With the committed {@code sk_test_replace_me} placeholder the deployment
 * classifies {@link MoneyMode#SIMULATED}, {@code devFallbackAuthorized()} is
 * true, and {@code createDepositSession} returned straight into
 * {@code devModeDeposit} — <b>up to $5,000 per wallet per rolling 24 hours,
 * credited against no payment</b>.
 *
 * Every guard was working. Nothing was misclassified: a placeholder key really
 * does mean "no Stripe account is wired here". <b>What was wrong is that a
 * CLASSIFICATION was being read as a GRANT.</b>
 *
 * And unlike dev-login, there is no shortcut to close: <b>Steam sign-in is the
 * site's real front door and it is open to everyone.</b>
 * {@code SteamAuthService.upsert} creates a wallet for any {@code steamId64}
 * that completes OpenID. So on a published SIMULATED deployment a stranger
 * signs in the ordinary way and credits themselves, with every
 * account-takeover door correctly shut behind them.
 *
 * <h3>The rule</h3>
 *
 * The same conjunction {@link DevLoginGate} uses, through the same channel,
 * with the same fail-closed default: <b>affirmatively SIMULATED AND an
 * affirmative, named opt-in in the PROCESS ENVIRONMENT</b>. Everything else —
 * absent, blank, unparseable, unreadable, or supplied through a channel that
 * could be committed to this repository — is CLOSED.
 *
 * @see DevCreditGate
 */
class DevCreditRequiresOptInSpec extends Specification {

    private static final String OPT_IN = DevCreditGate.OPT_IN_ENV_VAR

    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()

    /** A live-SHAPED Stripe key built at runtime from repeated characters. It
     *  is not a credential and cannot authenticate to anything — the convention
     *  every spec in this repo uses. */
    private static String liveShapedKey() { 'sk_live_' + ('0' * 24) }

    /** The deployment the operator actually runs: the committed placeholder
     *  key, no profile, and whatever process environment the case supplies. */
    private StripeService svc(Environment env = null, String key = 'sk_test_replace_me') {
        new StripeService(
            walletRepository      : walletRepository,
            transactionRepository : transactionRepository,
            environment           : env,
            secretKey             : key,
            publishableKey        : 'pk_test_replace_me',
            webhookSecret         : 'whsec_replace_me',
            successUrl            : 'http://localhost/ok',
            cancelUrl             : 'http://localhost/cancel',
            currency              : 'usd',
            connectRefreshUrl     : 'http://localhost/wallet?connect=refresh',
            connectCountry        : 'US',
            dailyDepositCap       : new BigDecimal('5000'))
    }

    // ── THE REGRESSION ──────────────────────────────────────────────

    /**
     * The exact shape of the JVM that was one published port away from handing
     * $5,000 a day to anyone with a Steam account: default profile, the
     * committed placeholder key, nobody having asked for anything.
     */
    def "a fresh checkout with no special environment credits NOTHING"() {
        given: 'the ordinary local deployment, and an EMPTY process environment'
        def service = svc(SpecEnvs.env([], [:]))

        when: 'a signed-in stranger asks for the daily maximum'
        service.createDepositSession(500L, new BigDecimal('5000'))

        then: 'it is refused with a branchable code, not credited'
        def e = thrown(BadRequestException)
        e.code == 'DEV_CREDIT_NOT_AUTHORIZED'

        and: 'and refused BEFORE any wallet is read or written — no balance moved, no row exists'
        0 * walletRepository.findByIdForUpdate(_)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)

        and: '''sanity: it refused despite the deployment being a perfectly ordinary
                SIMULATED one. That classification is CORRECT and is not what changed.'''
        service.moneyMode() == MoneyMode.SIMULATED
        service.moneyMode().devFallbackAuthorized()
    }

    def "a null Environment — the context-less default — credits nothing either"() {
        given: 'no Environment at all, which is what an unwired service holds'
        def service = svc(null)

        when:
        service.createDepositSession(500L, new BigDecimal('10'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'DEV_CREDIT_NOT_AUTHORIZED'
        0 * walletRepository.save(_)
    }

    /**
     * BY CONSTRUCTION, not by the caller. Per this repo's recurring "correct
     * logic nobody calls" failure, a guard that lives only at the call site is
     * one careless future caller away from being bypassed — and this method
     * credits a real wallet balance against no payment.
     */
    def "devModeDeposit called DIRECTLY on an un-opted-in SIMULATED box refuses, naming the variable"() {
        given: 'a future caller that forgot the branch'
        def service = svc(SpecEnvs.env([], [:]))

        when:
        service.devModeDeposit(500L, new BigDecimal('10'))

        then: 'it refuses before touching a repository'
        def e = thrown(IllegalStateException)
        e.message.contains(DevCreditGate.REASON_NOT_AUTHORIZED)
        e.message.contains(OPT_IN)
        0 * walletRepository.findByIdForUpdate(_)
        0 * walletRepository.save(_)
    }

    def "the simulated Connect onboarding will not fabricate a dev_acct_ reference either"() {
        given:
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO)
        walletRepository.findById(500L) >> Optional.of(wallet)
        def service = svc(SpecEnvs.env([], [:]))

        when:
        service.createConnectOnboardingLink(500L)

        then: 'the same refusal — a fabricated payout destination is money-path state too'
        def e = thrown(BadRequestException)
        e.code == 'DEV_CREDIT_NOT_AUTHORIZED'

        and: 'and the wallet is untouched'
        wallet.stripeConnectAccountId == null
        0 * walletRepository.save(_)
    }

    // ── THE OPT-IN ──────────────────────────────────────────────────

    def "with the opt-in set in the process environment, the simulated deposit works exactly as before"() {
        given: '''the path is real local-QA scaffolding — a gate nobody can pass just gets
                  deleted, and then the wallet UI is untestable without a Stripe account.'''
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('100.00'))
        walletRepository.findByIdForUpdate(500L) >> Optional.of(wallet)
        transactionRepository.sumDepositsSince(500L, _) >> BigDecimal.ZERO
        walletRepository.save(_) >> { args -> args[0] }
        def saved = null
        transactionRepository.save(_) >> { args -> def t = args[0]; t.id = 1L; saved = t; t }
        def service = svc(SpecEnvs.creditOptedIn())

        when:
        def result = service.createDepositSession(500L, new BigDecimal('50'))

        then: 'the credit lands, unchanged'
        wallet.balance == new BigDecimal('150.00')
        result.live == false
        result.newBalance == new BigDecimal('150.00')
        saved.type == 'DEPOSIT'
        saved.status == 'COMPLETED'
    }

    @Unroll
    def "opt-in value #desc -> authorized=#expected"() {
        expect: 'only the literal "true" opens it; every other value is a refusal'
        DevCreditGate.devCreditAuthorized(MoneyMode.SIMULATED, SpecEnvs.env([], processEnv)) == expected

        where:
        desc                        | processEnv                   || expected
        'exactly true'              | [(OPT_IN): 'true']           || true
        'TRUE (case-insensitive)'   | [(OPT_IN): 'TRUE']           || true
        'True'                      | [(OPT_IN): 'True']           || true
        'padded with whitespace'    | [(OPT_IN): '  true  ']       || true
        'false'                     | [(OPT_IN): 'false']          || false
        'yes'                       | [(OPT_IN): 'yes']            || false
        'the number 1'              | [(OPT_IN): '1']              || false
        'on'                        | [(OPT_IN): 'on']             || false
        'enabled'                   | [(OPT_IN): 'enabled']        || false
        'a typo'                    | [(OPT_IN): 'ture']           || false
        'blank'                     | [(OPT_IN): '']               || false
        'whitespace only'           | [(OPT_IN): '   ']            || false
        'absent entirely'           | [:]                          || false
        'a differently-named var'   | ['SBOX_DEV_CREDIT': 'true']  || false
    }

    /**
     * <b>The two doors are separately authorised, and that is deliberate.</b>
     *
     * The e2e suite and every QA harness set {@code SBOX_DEV_LOGIN_ENABLED} to
     * get a session. If that one variable also opened the free-credit path,
     * then closing dev-login would have moved the money printer behind a
     * variable that every harness already sets — one boolean answering two
     * questions, which is the exact failure {@link MoneyMode} was written after.
     */
    def "the dev-LOGIN opt-in does not open the dev-CREDIT door"() {
        given: 'the QA harness environment: a session was asked for, credit was not'
        def env = SpecEnvs.optedIn()
        def service = svc(env)

        expect: 'the names are different, so one act cannot grant the other'
        DevCreditGate.OPT_IN_ENV_VAR != DevLoginGate.OPT_IN_ENV_VAR

        and: 'the login door is open ...'
        DevLoginGate.devLoginAuthorized(env)

        and: '... and the credit door is not'
        !service.devCreditAuthorized()
        service.devCreditRefusal() == DevCreditGate.REASON_NOT_AUTHORIZED
    }

    def "and the reverse: the dev-CREDIT opt-in does not open the dev-LOGIN door"() {
        given:
        def env = SpecEnvs.creditOptedIn()

        expect:
        DevCreditGate.devCreditAuthorized(MoneyMode.SIMULATED, env)
        !DevLoginGate.devLoginAuthorized(env)
    }

    // ── "I CANNOT TELL" IS CLOSED ───────────────────────────────────

    def "a null Environment is refused"() {
        expect:
        !DevCreditGate.devCreditAuthorized(MoneyMode.SIMULATED, null)
        DevCreditGate.refusalReason(MoneyMode.SIMULATED, null) == DevCreditGate.REASON_NOT_AUTHORIZED

        and: 'and through the Environment-only form it is the STRONGER refusal, because MoneyMode.of(null) is INDETERMINATE'
        !DevCreditGate.devCreditAuthorized(null)
        DevCreditGate.refusalReason((Environment) null) ==
            DevCreditGate.reasonNotSimulated(MoneyMode.INDETERMINATE)
    }

    def "a null MoneyMode is refused as unclassified, not waved through"() {
        expect:
        !DevCreditGate.devCreditAuthorized(null, SpecEnvs.creditOptedIn())
        DevCreditGate.refusalReason(null, SpecEnvs.creditOptedIn()) ==
            DevCreditGate.reasonNotSimulated(null)
        DevCreditGate.reasonNotSimulated(null).startsWith(DevCreditGate.REFUSAL_PREFIX)
    }

    def "an Environment that is not configurable cannot grant the opt-in"() {
        given: 'a bare Stub answers getProperty and nothing else — it has no process environment to read'
        Environment stub = Stub(Environment) {
            getActiveProfiles() >> ([] as String[])
            getProperty(_) >> { String n -> n == OPT_IN ? 'true' : null }
        }

        expect: 'even though it would happily answer "true", there is no systemEnvironment source to trust'
        !DevCreditGate.devCreditAuthorized(MoneyMode.SIMULATED, stub)
        DevCreditGate.refusalReason(MoneyMode.SIMULATED, stub) == DevCreditGate.REASON_NOT_AUTHORIZED
    }

    def "an Environment with no systemEnvironment property source is refused"() {
        given:
        def env = SpecEnvs.env([], [(OPT_IN): 'true'])
        env.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)

        expect: 'the source we require is gone, so we cannot tell — which is CLOSED'
        !DevCreditGate.devCreditAuthorized(MoneyMode.SIMULATED, env)
    }

    /**
     * The gap the last pass found by writing this case: an unreadable property
     * source threw out of {@code MoneyMode.of} — BEFORE the gate's own catch —
     * so the refusal happened by accident, as whatever 500 the caller's error
     * handling produced, rather than by decision. A guard must return an
     * answer, not a stack trace.
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

        and: 'no exception escapes the Environment-only form ...'
        thrownBy { DevCreditGate.refusalReason(env) } == null
        !DevCreditGate.devCreditAuthorized(env)

        and: '''... and the answer is the STRONGEST refusal: an environment that throws is the
                most complete form of "I cannot tell", and nothing unknown may mint money.'''
        DevCreditGate.refusalReason(env) == DevCreditGate.reasonNotSimulated(null)

        and: 'and the two-arg form, which a caller reaches with its own mode, is shut too'
        !DevCreditGate.devCreditAuthorized(MoneyMode.SIMULATED, env)
    }

    def "the running service refuses rather than 500s when its Environment throws"() {
        given: 'the whole point: the refusal is a decision the money path makes, not an accident'
        def env = SpecEnvs.env([], [:])
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new PropertySource<Object>(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new Object()) {
                @Override Object getProperty(String name) { throw new IllegalStateException('unreadable') }
            })

        when:
        svc(env).createDepositSession(500L, new BigDecimal('10'))

        then: 'a BadRequestException with our code — not an IllegalStateException from the source'
        def e = thrown(BadRequestException)
        e.code == 'DEV_CREDIT_NOT_AUTHORIZED'
        0 * walletRepository.save(_)
    }

    /** Helper: run a closure and return whatever it threw (or null). */
    private static Throwable thrownBy(Closure c) {
        try { c.call(); return null } catch (Throwable t) { return t }
    }

    // ── THE OPT-IN CANNOT OUTRANK THE MODE GATE ─────────────────────

    @Unroll
    def "the opt-in does NOT open the credit path on a #label deployment"() {
        given: 'someone set the variable on this box months ago, and the key has since changed'
        def service = svc(SpecEnvs.creditOptedIn(), key)

        expect: 'sanity: this really is the mode we think it is'
        service.moneyMode() == mode

        and: 'the mode check runs FIRST, and it wins'
        !service.devCreditAuthorized()
        service.devCreditRefusal() == DevCreditGate.reasonNotSimulated(mode)
        service.devCreditRefusal() != DevCreditGate.REASON_NOT_AUTHORIZED

        and: 'and the refusal NAMES the deployment, so the operator is told the more serious reason'
        service.devCreditRefusal().contains(mode.name())

        where:
        label                        | key                              || mode
        'live-key'                   | liveShapedKey()                  || MoneyMode.LIVE
        'restricted-live-key'        | 'rk_live_' + ('0' * 24)          || MoneyMode.LIVE
        'test-key'                   | 'sk_test_' + ('0' * 24)          || MoneyMode.TEST
        'unclassifiable-key'         | 'changeme'                       || MoneyMode.INDETERMINATE
        'live-shaped-placeholder'    | 'sk_live_replace_me' + ('0' * 8) || MoneyMode.INDETERMINATE
        'publishable-key-in-the-slot'| 'pk_live_' + ('0' * 24)          || MoneyMode.INDETERMINATE
    }

    @Unroll
    def "devModeDeposit still refuses by MODE on a #mode box even with the opt-in granted"() {
        given: 'the opt-in cannot buy what the mode forbids'
        def service = svc(SpecEnvs.creditOptedIn(), key)

        when:
        service.devModeDeposit(1L, new BigDecimal('10.00'))

        then:
        def e = thrown(IllegalStateException)
        e.message.contains(mode.name())
        0 * walletRepository.findByIdForUpdate(_)

        where:
        mode                    | key
        MoneyMode.LIVE          | 'sk_live_' + ('0' * 24)
        MoneyMode.TEST          | 'sk_test_' + ('0' * 24)
        MoneyMode.INDETERMINATE | 'changeme'
    }

    // ── THE OPT-IN CANNOT ARRIVE THROUGH A COMMITTABLE CHANNEL ──────

    /**
     * The whole reason the gate reads one property source instead of the merged
     * view. A resolved Spring property can come from {@code application.yml}, a
     * profile yml, a mounted config file or a bundled {@code .properties} — all
     * committed once and then inherited forever by deployments that never
     * re-decided anything. A {@code dev}/{@code test} PROFILE is the same
     * mistake in a different hat, and this repo has already lost four guards to
     * one {@code SPRING_PROFILES_ACTIVE}.
     */
    @Unroll
    def "a #channel cannot open the credit door, even carrying the exact variable name and value"() {
        given: 'an EMPTY process environment, and the opt-in supplied some other way'
        def env = SpecEnvs.env(profiles, [:], props)

        expect: 'the merged property view says "true" ...'
        env.getProperty(lookup) == 'true'

        and: '... and the gate is still shut, because that is not the process environment'
        !DevCreditGate.devCreditAuthorized(MoneyMode.SIMULATED, env)
        DevCreditGate.refusalReason(MoneyMode.SIMULATED, env) == DevCreditGate.REASON_NOT_AUTHORIZED

        and: 'and the money path agrees — the rule is not merely present, it is in force'
        !svc(env).devCreditAuthorized()

        where:
        channel                           | profiles | props                                 | lookup
        'yml key in the env-var spelling' | []       | [(OPT_IN): 'true']                    | OPT_IN
        'yml key in dotted spelling'      | []       | ['sbox.dev-credit.enabled': 'true']   | 'sbox.dev-credit.enabled'
        'a relaxed-binding alias'         | []       | ['sbox.dev.credit.enabled': 'true']   | 'sbox.dev.credit.enabled'
        'a dev-profile yml'               | ['dev']  | [(OPT_IN): 'true']                    | OPT_IN
        'a test-profile yml'              | ['test'] | [(OPT_IN): 'true']                    | OPT_IN
    }

    def "a JVM -D system property cannot open the credit door either"() {
        given: 'systemProperties is a different source from systemEnvironment'
        def env = SpecEnvs.env([], [:])
        System.setProperty(OPT_IN, 'true')

        when:
        boolean authorized = DevCreditGate.devCreditAuthorized(MoneyMode.SIMULATED, env)

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
     * This is not hypothetical caution. Two passes ago a spec matched its own
     * explanatory comment, which meant a "fix" written only inside a comment
     * would have passed it. Here the risk runs the other way (a warning comment
     * naming the variable would FAIL a raw-text scan and push someone to delete
     * the warning), and the answer is the same: assert over configuration,
     * never over commentary.
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
    def "no committed config file grants the credit opt-in: #path"() {
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
    def "devCreditAuthorized and refusalReason agree: #label"() {
        given:
        def env = envSupplier.call()

        expect: 'authorized is DEFINED as "no reason to refuse" — the two can never disagree'
        DevCreditGate.devCreditAuthorized(mode, env) == (DevCreditGate.refusalReason(mode, env) == null)

        where:
        label                    | mode                     | envSupplier
        'simulated, no opt-in'   | MoneyMode.SIMULATED      | { SpecEnvs.env([], [:]) }
        'simulated, opted in'    | MoneyMode.SIMULATED      | { SpecEnvs.creditOptedIn() }
        'live, opted in'         | MoneyMode.LIVE           | { SpecEnvs.creditOptedIn() }
        'test, opted in'         | MoneyMode.TEST           | { SpecEnvs.creditOptedIn() }
        'indeterminate, opted in'| MoneyMode.INDETERMINATE  | { SpecEnvs.creditOptedIn() }
        'null environment'       | MoneyMode.SIMULATED      | { null }
        'null mode'              | null                     | { SpecEnvs.creditOptedIn() }
    }

    def "every refusal is recognisable as this guard, and tellable apart from the login guard"() {
        expect: 'both refusals share one prefix, so a log scrape can match the guard as a class'
        DevCreditGate.REASON_NOT_AUTHORIZED.startsWith(DevCreditGate.REFUSAL_PREFIX)
        DevCreditGate.reasonNotSimulated(MoneyMode.LIVE).startsWith(DevCreditGate.REFUSAL_PREFIX)

        and: 'they are distinct from each other, so the reason is recoverable'
        DevCreditGate.REASON_NOT_AUTHORIZED != DevCreditGate.reasonNotSimulated(MoneyMode.LIVE)

        and: 'and neither can be confused with the dev-login guard speaking'
        !DevCreditGate.REFUSAL_PREFIX.startsWith(DevLoginGate.REFUSAL_PREFIX)
        !DevLoginGate.REFUSAL_PREFIX.startsWith(DevCreditGate.REFUSAL_PREFIX)

        and: 'the not-authorized reason names the variable, so the refusal is actionable'
        DevCreditGate.REASON_NOT_AUTHORIZED.contains(OPT_IN)
        DevCreditGate.REASON_NOT_AUTHORIZED.contains(DevCreditGate.OPT_IN_VALUE)
    }

    // ── THE MONEY PATH ACTUALLY CALLS THE GATE ──────────────────────

    /**
     * Per this repo's recurring "correct logic nobody calls" failure: the right
     * rule existing is not the same as the money path calling it. Belt to the
     * behavioural cases above, asserted over COMMENT-STRIPPED source so a fix
     * written inside a comment cannot satisfy it.
     */
    private static String strippedStripeSource() {
        stripHashComments(
            new File('src/main/groovy/com/sboxmarket/service/StripeService.groovy').text
                .replaceAll(/(?s)\/\*.*?\*\//, '')      // block + javadoc comments
                .replaceAll(/(?m)^\s*\/\/.*$/, ''))     // line comments
    }

    def "SELF-TEST: the source stripper removes commentary from the file actually scanned"() {
        given:
        String raw = new File('src/main/groovy/com/sboxmarket/service/StripeService.groovy').text
        String stripped = strippedStripeSource()

        expect: 'the real file does carry javadoc and line comments ...'
        raw.contains('/**')
        raw.contains('// ')

        and: '... and the stripped form does not, so the scans below read code only'
        !stripped.contains('/**')
        stripped.length() < raw.length()

        and: 'a sentence that exists only in a comment is gone'
        raw.contains('THE FREE-MONEY DOOR')
        !stripped.contains('THE FREE-MONEY DOOR')
    }

    def "createDepositSession asks the GATE before it can reach the fabricated credit"() {
        given:
        String src = strippedStripeSource()
        int depositAt = src.indexOf('Map createDepositSession(')

        expect: 'the method exists in real code, not only in prose'
        depositAt >= 0

        and: 'the gate is consulted, and the fabricated credit is only reachable after it'
        int gateAt = src.indexOf('devCreditAuthorized(', depositAt)
        int mintAt = src.indexOf('devModeDeposit(', depositAt)
        gateAt > depositAt
        mintAt > gateAt

        and: '''and the OLD condition — the mode alone — no longer guards it anywhere.
                `devFallbackAuthorized()` classifies the configuration; it is not a grant.'''
        !(src =~ /if\s*\(\s*moneyMode\(\)\.devFallbackAuthorized\(\)\s*\)\s*\{[^}]*devModeDeposit\(/).find()
        !(src =~ /if\s*\(\s*!isLive\(\)\s*\)\s*\{[^}]*devModeDeposit\(/).find()
    }

    def "devModeDeposit re-asserts the gate itself, so a caller that forgets it cannot mint"() {
        given:
        String src = strippedStripeSource()
        int bodyAt = src.indexOf('Map devModeDeposit(')

        expect:
        bodyAt >= 0

        and: 'the method consults the gate before it reads a wallet'
        int gateAt  = src.indexOf('devCreditRefusal(', bodyAt)
        int walletAt = src.indexOf('findByIdForUpdate(', bodyAt)
        gateAt > bodyAt
        walletAt > gateAt
    }

    def "the fabricated Connect reference is written behind the same gate"() {
        given:
        String src = strippedStripeSource()
        int onboardAt = src.indexOf('Map createConnectOnboardingLink(')

        expect:
        onboardAt >= 0

        and: 'the gate is consulted before the dev_acct_ string is built'
        int gateAt  = src.indexOf('devCreditAuthorized(', onboardAt)
        int writeAt = src.indexOf('"dev_acct_', onboardAt)
        gateAt > onboardAt
        writeAt > gateAt

        and: 'and the write re-asserts it on its own account'
        int reassertAt = src.indexOf('devCreditRefusal(', onboardAt)
        reassertAt > gateAt
        reassertAt < writeAt
    }

    /**
     * The gate reads ONE property source, and that is the entire design. A
     * future edit to {@code env.getProperty(...)} would silently make the
     * opt-in inheritable from {@code application.yml} again, with every
     * behavioural case above still passing on a real StandardEnvironment.
     */
    def "the opt-in is read from the systemEnvironment source and nowhere else"() {
        given:
        String gate = stripHashComments(
            new File('src/main/groovy/com/sboxmarket/config/DevCreditGate.groovy').text
                .replaceAll(/(?s)\/\*.*?\*\//, '')
                .replaceAll(/(?m)^\s*\/\/.*$/, ''))

        expect: 'it delegates to the one shared reader rather than resolving a property'
        gate.contains('DevLoginGate.processEnvValue(')
        !gate.contains('env.getProperty(')
        !gate.contains('System.getenv(')

        and: 'and that reader is itself pinned to the systemEnvironment source'
        String reader = stripHashComments(
            new File('src/main/groovy/com/sboxmarket/config/DevLoginGate.groovy').text
                .replaceAll(/(?s)\/\*.*?\*\//, '')
                .replaceAll(/(?m)^\s*\/\/.*$/, ''))
        reader.contains('StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME')
    }
}
