package com.sboxmarket.config

import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * <b>May this deployment serve a credential-free login?</b>
 *
 * <h3>The hole this closes</h3>
 *
 * {@code GET /api/auth/steam/dev-login} takes a user id and NOTHING ELSE and
 * hands back a one-year session as that user. No password, no OpenID
 * assertion, no token. It is total account takeover of every account on the
 * platform, admins included, for anyone who can reach the URL.
 *
 * Until 2026-09-01 its only gate was {@link LiveMoneyGuard#isRealMoney} — i.e.
 * "this is not obviously a real-money deployment". On the operator's own box
 * that gate PASSES: no live Stripe key is configured, so {@link MoneyMode}
 * correctly classifies the deployment {@link MoneyMode#SIMULATED} and the door
 * opens by design. Measured the same day: 302, a one-year session cookie, and
 * the account it lands on holds $30,375.18.
 *
 * <b>That was not a bug — it was a premise.</b> The SIMULATED contract assumes
 * nobody but the developer can reach the port, and the {@code 127.0.0.1} bind
 * in {@code application.yml} is what enforces it. The premise fails because
 * there is a path BESIDE that lock: {@code ~/.cloudflared/config.yml} maps
 * {@code skinbox.market -> http://localhost:8082}, and <b>a tunnel connects
 * FROM loopback</b>, so the bind does not stop it. DNS already points at that
 * tunnel. One process dying is the whole distance between a laptop affordance
 * and credential-free takeover published to the internet.
 *
 * So "SIMULATED" cannot be the authorisation. Being un-forbidden is not the
 * same as being asked for, and a door this dangerous has to be ASKED for.
 *
 * <h3>The shape, which is not new here</h3>
 *
 * This is the same fail-closed opt-in
 * {@code SboxMarketApplication.demoSeedRequested} already uses for the demo
 * catalogue, for the same reason: <i>absence of configuration is the default
 * state of every box nobody has configured yet</i>, including the one that is
 * about to be published. Absence must read as NO.
 *
 * <h3>Two conditions, and both must hold</h3>
 *
 * <ol>
 *   <li><b>No real money.</b> {@link MoneyMode#handlesRealMoney()} must be
 *       false — {@link MoneyMode#LIVE} and {@link MoneyMode#INDETERMINATE} both
 *       shut the door, exactly as before. This is checked FIRST, so the opt-in
 *       can never re-open a door the money classification closed: an operator
 *       who set the variable months ago and later pastes in a live Stripe key
 *       does not thereby publish account takeover.</li>
 *   <li><b>An affirmative, named opt-in.</b> The {@link #OPT_IN_ENV_VAR}
 *       <i>process environment variable</i> must be exactly {@code true}
 *       (trimmed, case-insensitive).</li>
 * </ol>
 *
 * <h3>Why the PROCESS environment, and not a Spring property</h3>
 *
 * Deliberately NOT {@code env.getProperty(...)}. A resolved Spring property can
 * come from {@code application.yml}, {@code application-<profile>.yml}, a
 * mounted config file or a bundled properties file — all of which are things
 * that get COMMITTED once and then copied forward into every deployment that
 * inherits them. That is precisely how a dev affordance ends up live: nobody
 * re-decides it, they inherit it.
 *
 * An entry in the {@code systemEnvironment} property source cannot be
 * committed. It is set by a test harness that is about to run, or by a
 * developer at a shell who meant it, on that one process. That is the "act with
 * a name on it" this gate requires, and it is why the check reads that one
 * property source rather than the merged view.
 *
 * A JVM {@code -D} flag also does not open it: {@code systemProperties} is a
 * different source. One channel, one name, so "who could have opened this
 * door?" has exactly one answer.
 *
 * <h3>"I cannot tell" is CLOSED, in every direction</h3>
 *
 * A null {@link Environment}, an {@link Environment} that is not a
 * {@link ConfigurableEnvironment}, a missing {@code systemEnvironment} property
 * source, a source that throws, an absent variable, a blank one, and any value
 * that is not literally {@code true} ({@code yes}, {@code 1}, {@code on}, a
 * typo) all resolve to CLOSED. There is no input this class does not have a
 * shut answer for.
 *
 * @see com.sboxmarket.SboxMarketApplication#demoSeedRequested(org.springframework.core.env.Environment)
 * @see MoneyMode
 */
class DevLoginGate {

    /**
     * The one name that can open the credential-free login, read from the
     * PROCESS ENVIRONMENT only.
     *
     * <pre>
     *   # bash
     *   SBOX_DEV_LOGIN_ENABLED=true java -cp build/libs/sboxmarket-1.0.0.jar ...
     *   # PowerShell
     *   $env:SBOX_DEV_LOGIN_ENABLED = "true"; java -cp ...
     * </pre>
     *
     * It must NOT be added to {@code application.yml}, {@code docker-compose.yml},
     * the {@code Dockerfile} or {@code deploy/skinbox.env.example} — pinned by
     * {@code DevLoginRequiresOptInSpec}, over comment-stripped text.
     */
    static final String OPT_IN_ENV_VAR = 'SBOX_DEV_LOGIN_ENABLED'

    /** The only value that authorises. Anything else — including {@code yes},
     *  {@code 1}, {@code on} and blank — is a refusal. Same rule as
     *  {@code sbox.seed.demo-data}. */
    static final String OPT_IN_VALUE = 'true'

    /** Common prefix of every refusal, so a probe that only wants to know
     *  "did the guard speak?" can match one substring and keep working if a
     *  further reason is ever added. */
    static final String REFUSAL_PREFIX = 'dev-login disabled:'

    /** Refusal body when the deployment can move real money ({@link MoneyMode#LIVE}
     *  or {@link MoneyMode#INDETERMINATE}). <b>Byte-for-byte the string the
     *  RUNBOOK, the operator's watchdog probe and the existing specs already
     *  assert on — do not reword it.</b> */
    static final String REASON_REAL_MONEY = 'dev-login disabled: real-money deployment'

    /** Refusal body when no real money is at stake but nobody asked for the
     *  door either — the DEFAULT answer on a fresh checkout. It names the
     *  variable, because a shut door that does not say why is the "absence read
     *  as success" defect this repo keeps paying for. */
    static final String REASON_NOT_AUTHORIZED =
        'dev-login disabled: no ' + OPT_IN_ENV_VAR + '=' + OPT_IN_VALUE +
        ' opt-in in the process environment'

    /**
     * The decision. True only when BOTH conditions hold.
     *
     * Defined as "there is no reason to refuse" rather than as its own copy of
     * the rule, so the answer and the explanation cannot drift apart — the
     * defect {@link MoneyMode} was written after, where two guards each held
     * their own copy of "is this production?" and disagreed.
     */
    static boolean devLoginAuthorized(Environment env) {
        refusalReason(env) == null
    }

    /**
     * {@code null} when the door may open; otherwise the reason it is shut,
     * which is also the response body.
     *
     * Order is load-bearing: the money check runs FIRST so an opt-in set on a
     * box that later acquires a live Stripe key cannot re-open the door, and so
     * the operator is told the more serious of the two reasons.
     */
    static String refusalReason(Environment env) {
        // A null Environment falls through here on purpose: MoneyMode.of(null)
        // is INDETERMINATE, which handlesRealMoney(). No special case, and no
        // way to reach the opt-in check without an Environment to read.
        boolean realMoney
        try {
            realMoney = LiveMoneyGuard.isRealMoney(env)
        } catch (Throwable unreadable) {
            // An Environment that THROWS is the strongest possible form of "I
            // cannot tell", so it gets the strongest refusal. Letting it
            // propagate would leave the outcome to whatever the caller's
            // exception handling happens to be — a 500 today, which refuses by
            // accident rather than by decision, and which says nothing about
            // the door. A guard must return an answer, not a stack trace.
            //
            // Caught as Throwable deliberately: a property source can fail in
            // ways that are not Exception (a NoClassDefFoundError from a
            // half-initialised source), and none of them are a reason to open.
            return REASON_REAL_MONEY
        }
        if (realMoney) return REASON_REAL_MONEY
        if (!optInGranted(env)) return REASON_NOT_AUTHORIZED
        return null
    }

    /**
     * Has someone affirmatively asked, in THIS process's environment?
     *
     * Split out from {@link #refusalReason} so the opt-in can be tested on its
     * own — and so it is obvious that it is only ever consulted after the money
     * check, never instead of it.
     */
    static boolean optInGranted(Environment env) {
        String raw = processEnvValue(env, OPT_IN_ENV_VAR)
        raw != null && raw.trim().equalsIgnoreCase(OPT_IN_VALUE)
    }

    /**
     * Read one variable from the PROCESS ENVIRONMENT — the
     * {@code systemEnvironment} property source, which is Spring's live view of
     * {@code System.getenv()} — and from nowhere else.
     *
     * Returns {@code null} for every "cannot tell": a non-configurable
     * Environment (a bare test stub), a missing source, an absent variable, or
     * a lookup that throws. Callers treat {@code null} as NO.
     *
     * <b>Shared, on purpose.</b> {@link DevCreditGate#optInGranted} calls this
     * exact method rather than carrying its own copy. "What counts as the
     * process environment" is the single property that makes an opt-in
     * un-inheritable, and two implementations of it would be two chances to
     * disagree about it — the "correct logic nobody calls" failure this repo
     * keeps paying for. One reader, both gates.
     */
    static String processEnvValue(Environment env, String name) {
        if (!(env instanceof ConfigurableEnvironment)) return null
        try {
            PropertySource<?> ps = ((ConfigurableEnvironment) env).propertySources
                    ?.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            if (ps == null) return null
            Object v = ps.getProperty(name)
            return v == null ? null : v.toString()
        } catch (Exception ignore) {
            // An unreadable environment is an unknown answer, and an unknown
            // answer is a shut door.
            return null
        }
    }
}
