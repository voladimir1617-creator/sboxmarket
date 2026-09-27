package com.sboxmarket.config

import org.springframework.core.env.Environment

/**
 * <b>May this deployment DESTROY money-path state — delete every transaction
 * row and zero every wallet balance?</b>
 *
 * <h3>Why a destructive tool needs a gate at least as strong as the one on the
 * thing that created the mess</h3>
 *
 * {@link DevCreditGate} guards fabrication: minting money that was never paid.
 * This guards the inverse operation, and the inverse is not automatically
 * safer. A reset that runs where real money lives deletes the ledger a customer
 * would be refunded from and zeroes balances the platform actually owes. There
 * is no Stripe call to fail and no webhook to replay it from — the money-path
 * history IS the record. <b>So this gate is deliberately the STRICTER of the
 * two: it refuses on everything {@link DevCreditGate} refuses on, and it also
 * refuses the READ-ONLY dry run on a non-simulated deployment.</b>
 *
 * <h3>Why even the dry run is gated on SIMULATED</h3>
 *
 * The dry run mutates nothing, so the instinct is to let it run anywhere — an
 * operator on a live box might want to see what a reset WOULD do. Both halves
 * of that instinct are wrong here:
 *
 * <ul>
 *   <li>The report prints <b>every wallet balance on the platform</b> to stdout
 *       and the log file. On a live deployment that is a customer-balance dump
 *       into whatever ships the logs — the same class of leak as the H2 port,
 *       arriving by a route nobody would think to audit.</li>
 *   <li>A reachable code path is one bug from an executed one. The two-variable
 *       split below is a good control, but it is a control INSIDE the tool. If
 *       the tool cannot be entered at all on a live box, no bug in the split can
 *       matter. Refuse outright means refuse outright, not "refuse the last
 *       step".</li>
 * </ul>
 *
 * The cost of that strictness is real and small: an operator who genuinely wants
 * to see the plan against production data can read it off a restored backup on a
 * simulated box, which is where a rehearsal of a destructive operation belongs
 * anyway.
 *
 * <h3>Two variables, because there are two questions</h3>
 *
 * {@link #OPT_IN_ENV_VAR} answers "may this tool run at all?" and yields a DRY
 * RUN. {@link #EXECUTE_ENV_VAR} answers "and may it actually delete?".
 * Verbatim {@link DevCreditGate}'s own reasoning for not reusing
 * {@link DevLoginGate}'s variable: <i>one variable answering two questions is
 * the failure {@link MoneyMode} was written after</i>. An operator who asked to
 * SEE the plan has not thereby asked to execute it, and the whole deliverable
 * here is that he gets to read the plan and then decide.
 *
 * Note the asymmetry that makes the default safe: {@link #OPT_IN_ENV_VAR} alone
 * is harmless (it prints), and {@link #EXECUTE_ENV_VAR} alone does NOTHING —
 * the execute flag is only ever consulted after the opt-in has already been
 * granted, so a stray {@code SBOX_MONEY_RESET_EXECUTE=true} left in a shell
 * profile cannot destroy anything on its own.
 *
 * <h3>Why the PROCESS environment, and not a Spring property or a profile</h3>
 *
 * Verbatim the argument in {@link DevCreditGate} and {@link DevLoginGate}:
 * {@code env.getProperty(...)} resolves from {@code application.yml},
 * {@code application-<profile>.yml}, a mounted config file or a bundled
 * {@code .properties} — every one of which is committed once and then INHERITED
 * by deployments that never re-decided anything.
 *
 * That argument is <b>load-bearing for this class specifically</b>, because the
 * repo already contains the counter-example. {@code purgeDemoDataOnStartup} in
 * {@code SboxMarketApplication} is the same one-shot-destructive shape and it
 * reads {@code sbox.seed.purge-demo-data} as a PROPERTY. That is defensible for
 * a demo CATALOGUE — the blast radius is invented listings. It would not be
 * defensible here, where the blast radius is the ledger, so this tool does not
 * copy that precedent's channel, only its shape.
 *
 * <h3>"I cannot tell" is CLOSED, in every direction</h3>
 *
 * A null mode, a null {@link Environment}, an {@link Environment} that is not
 * {@link org.springframework.core.env.ConfigurableEnvironment}, a missing
 * {@code systemEnvironment} property source, a source that THROWS, an absent
 * variable, a blank one, and any value that is not literally {@code true} all
 * resolve to CLOSED — for the dry run as well as for the execution.
 *
 * @see DevCreditGate
 * @see MoneyMode#devFallbackAuthorized()
 */
class MoneyResetGate {

    /**
     * The one name that lets the reset tool RUN, read from the PROCESS
     * ENVIRONMENT only. On its own it produces a DRY RUN and changes nothing.
     *
     * <pre>
     *   # bash — dry run, the default and the deliverable
     *   SBOX_MONEY_RESET_ENABLED=true java -jar build/libs/sboxmarket-1.0.0.jar
     *   # PowerShell
     *   $env:SBOX_MONEY_RESET_ENABLED = "true"; java -jar ...
     * </pre>
     *
     * It must NOT be added to {@code application.yml}, {@code docker-compose.yml},
     * the {@code Dockerfile} or {@code deploy/skinbox.env.example} — pinned by
     * {@code MoneyResetRequiresOptInSpec}, over comment-stripped text.
     */
    static final String OPT_IN_ENV_VAR = 'SBOX_MONEY_RESET_ENABLED'

    /**
     * The SECOND name, which turns the dry run into a destructive run. Only
     * consulted once {@link #OPT_IN_ENV_VAR} has already been granted.
     *
     * Same committed-config prohibition as above, and for a stronger reason.
     */
    static final String EXECUTE_ENV_VAR = 'SBOX_MONEY_RESET_EXECUTE'

    /** The only value that authorises, for BOTH variables. Anything else —
     *  including {@code yes}, {@code 1}, {@code on} and blank — is a refusal.
     *  Same rule as {@link DevCreditGate#OPT_IN_VALUE}. */
    static final String OPT_IN_VALUE = 'true'

    /** Common prefix of every refusal, distinct from {@link DevCreditGate#REFUSAL_PREFIX}
     *  and {@link DevLoginGate#REFUSAL_PREFIX} so all three gates are tellable
     *  apart in one log line. */
    static final String REFUSAL_PREFIX = 'money-reset refused:'

    /** Refusal body when nothing forbids it but nobody asked — <b>the DEFAULT
     *  answer on a fresh checkout</b>. Names the variable, because a shut door
     *  that does not say why is the "absence read as success" defect this repo
     *  keeps paying for. */
    static final String REASON_NOT_AUTHORIZED =
        REFUSAL_PREFIX + ' no ' + OPT_IN_ENV_VAR + '=' + OPT_IN_VALUE +
        ' opt-in in the process environment'

    /**
     * Refusal body when the deployment is not affirmatively SIMULATED — it can
     * move real money ({@link MoneyMode#LIVE}), it talks to a real Stripe test
     * account ({@link MoneyMode#TEST}), or it cannot be classified at all
     * ({@link MoneyMode#INDETERMINATE}).
     *
     * TEST is refused as firmly as LIVE, and that is not an oversight. A Stripe
     * TEST-mode deployment has REAL webhook-delivered rows in its ledger — they
     * came from Stripe, they reconcile against a Stripe dashboard, and they are
     * the only thing a payments integration can be verified against before it
     * goes live. Deleting them is destroying evidence, not fiction.
     */
    static String reasonNotSimulated(MoneyMode mode) {
        REFUSAL_PREFIX + ' ' + (mode == null ? 'unclassified' : mode.name()) +
            ' deployment — only ' + MoneyMode.SIMULATED.name() +
            ' may have its money state reset'
    }

    /** Refusal body when the tool may run but the destructive step was not
     *  asked for. This is the SUCCESS path of the deliverable, not an error:
     *  it is what the operator sees under the dry-run report. */
    static final String REASON_EXECUTE_NOT_REQUESTED =
        REFUSAL_PREFIX + ' no ' + EXECUTE_ENV_VAR + '=' + OPT_IN_VALUE +
        ' in the process environment — DRY RUN only, nothing was changed'

    /**
     * May the tool run at all (i.e. produce a dry-run report)?
     *
     * Defined as "there is no reason to refuse" rather than as its own copy of
     * the rule, so the answer and the explanation cannot drift apart.
     */
    static boolean resetAuthorized(MoneyMode mode, Environment env) {
        refusalReason(mode, env) == null
    }

    /**
     * {@code null} when the tool may run; otherwise the reason it is refused,
     * which is also what gets logged and printed.
     *
     * Order is load-bearing: the mode check runs FIRST, so an opt-in left set on
     * a box that later acquires a live Stripe key cannot re-open the door, and
     * so the operator is told the more serious of the two reasons.
     */
    static String refusalReason(MoneyMode mode, Environment env) {
        if (mode == null || !mode.devFallbackAuthorized()) return reasonNotSimulated(mode)
        if (!optInGranted(env)) return REASON_NOT_AUTHORIZED
        return null
    }

    /**
     * The Environment-only form, for a caller that holds no key of its own —
     * which is every caller here, since the tool runs from a
     * {@code CommandLineRunner} and not from inside {@code StripeService}.
     *
     * The classification is wrapped because {@link MoneyMode#of(Environment)}
     * reads property sources and a broken source THROWS — the exact gap that
     * made {@link DevLoginGate} refuse by accident with a 500 instead of by
     * decision. Caught as {@link Throwable} deliberately: a half-initialised
     * property source can fail in ways that are not {@link Exception}, and none
     * of them are a reason to start deleting a ledger.
     */
    static String refusalReason(Environment env) {
        MoneyMode mode
        try {
            mode = MoneyMode.of(env)
        } catch (Throwable unreadable) {
            return reasonNotSimulated(null)
        }
        return refusalReason(mode, env)
    }

    /** @see #refusalReason(Environment) */
    static boolean resetAuthorized(Environment env) {
        refusalReason(env) == null
    }

    /**
     * Has someone affirmatively asked for the DESTRUCTIVE run, in THIS process's
     * environment?
     *
     * <b>This is never consulted on its own.</b> {@code MoneyResetService.run}
     * calls it only after {@link #resetAuthorized(Environment)} has already
     * returned true, so the SIMULATED check and the opt-in both stand in front
     * of it. Exposed separately so it can be tested in isolation, and so it is
     * obvious at the call site that it is the second of two questions.
     */
    static boolean executeGranted(Environment env) {
        String raw = DevLoginGate.processEnvValue(env, EXECUTE_ENV_VAR)
        raw != null && raw.trim().equalsIgnoreCase(OPT_IN_VALUE)
    }

    /**
     * Has someone affirmatively asked for the tool to run, in THIS process's
     * environment?
     *
     * The read itself is {@link DevLoginGate#processEnvValue} — the same one
     * function, not a copy. Two implementations of "what counts as the process
     * environment" would be two chances to disagree about the only property that
     * makes any of these opt-ins un-inheritable.
     */
    static boolean optInGranted(Environment env) {
        String raw = DevLoginGate.processEnvValue(env, OPT_IN_ENV_VAR)
        raw != null && raw.trim().equalsIgnoreCase(OPT_IN_VALUE)
    }
}
