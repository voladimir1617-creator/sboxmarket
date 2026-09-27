package com.sboxmarket.config

import org.springframework.core.env.Environment

/**
 * <b>May this deployment fabricate money-path state — a wallet credit against
 * no payment, or a payout destination Stripe never issued?</b>
 *
 * <h3>The hole this closes, which the dev-login gate did NOT close</h3>
 *
 * {@code POST /api/wallet/deposit} on a {@link MoneyMode#SIMULATED} deployment
 * routes into {@code StripeService.devModeDeposit}, which adds the requested
 * amount straight onto {@code wallet.balance} and writes a {@code COMPLETED}
 * DEPOSIT row. <b>Up to $5,000 per wallet per rolling 24 hours, against no
 * payment of any kind.</b>
 *
 * Commit {@code a52b4ad} shut {@code /api/auth/steam/dev-login}, and the commit
 * message said plainly what that did not reach:
 *
 * <blockquote>The door that was closed here was the shortcut INTO an account,
 * not the free-credit door behind it.</blockquote>
 *
 * <b>Steam sign-in is not a shortcut — it is the site's real front door, and it
 * is open to everyone.</b> {@code SteamAuthService} creates a wallet for any new
 * {@code steamId64} that completes OpenID. So on a published SIMULATED
 * deployment, a stranger signs in the ordinary way, opens the wallet page, and
 * credits themselves — with every account-takeover door correctly shut behind
 * them. Closing dev-login moved the attacker from "be any existing user" to "be
 * a new user with $5,000/day", which is not the same hole and is not much
 * smaller.
 *
 * <h3>SIMULATED is a reading, not an authorisation</h3>
 *
 * {@link MoneyMode#devFallbackAuthorized()} is {@code this == SIMULATED}, and
 * SIMULATED is the correct classification of a placeholder key — that reading is
 * not the defect and is not changed here. The defect is that a CLASSIFICATION
 * was being used as a GRANT. "No Stripe account is wired" is a statement about
 * configuration; it is the default state of every box nobody has configured yet,
 * including the one that is about to be published. Being un-forbidden is not the
 * same as being asked for.
 *
 * <h3>The shape is {@link DevLoginGate}'s, deliberately</h3>
 *
 * Same conjunction, same channel, same reader, same fail-closed default — a
 * second convention for "who authorised this?" would be a third answer to a
 * question that already has one too many.
 *
 * <ol>
 *   <li><b>The deployment must be affirmatively SIMULATED.</b> Checked FIRST,
 *       from the mode the CALLER already computed, so an opt-in set months ago
 *       cannot re-open the door on a box that has since acquired a real key,
 *       and so the operator is told the more serious of the two reasons.</li>
 *   <li><b>An affirmative, named opt-in.</b> {@link #OPT_IN_ENV_VAR} must be
 *       exactly {@code true} (trimmed, case-insensitive) in the PROCESS
 *       ENVIRONMENT.</li>
 * </ol>
 *
 * <h3>Why the PROCESS environment, and not a Spring property or a profile</h3>
 *
 * Verbatim the argument in {@link DevLoginGate}: {@code env.getProperty(...)}
 * resolves from {@code application.yml}, {@code application-<profile>.yml}, a
 * mounted config file or a bundled {@code .properties} — every one of which is
 * committed once and then INHERITED by deployments that never re-decided
 * anything. That is exactly how a development affordance goes live. A
 * {@code dev}/{@code test} profile is the same mistake wearing a different hat:
 * a profile is the thing that gets copied forward, and this repo has already
 * lost four guards to one {@code SPRING_PROFILES_ACTIVE}.
 *
 * An entry in the {@code systemEnvironment} property source cannot be
 * committed. A JVM {@code -D} flag does not reach it either — that is
 * {@code systemProperties}, a different source. One channel, one name, one
 * answer to "who could have opened this".
 *
 * <h3>Why the mode is passed IN rather than read from the Environment</h3>
 *
 * {@code StripeService} answers the money question from the
 * {@code stripe.secret-key} value it actually holds
 * ({@code MoneyMode.ofKey(secretKey)}). If this gate re-derived the mode from an
 * {@link Environment} it would be a SECOND copy of "what mode is this?" sitting
 * beside the branch it guards, free to disagree with it — which is the precise
 * defect {@link MoneyMode} was created to delete. The gate adds the opt-in to
 * the caller's answer; it never forms its own.
 *
 * {@link #refusalReason(Environment)} exists for callers that hold only an
 * Environment, and derives the mode through the one authority,
 * {@link MoneyMode#of(Environment)}, inside a catch — see below.
 *
 * <h3>"I cannot tell" is CLOSED, in every direction</h3>
 *
 * A null mode, a null {@link Environment}, an {@link Environment} that is not
 * {@link org.springframework.core.env.ConfigurableEnvironment}, a missing
 * {@code systemEnvironment} property source, a source that THROWS, an absent
 * variable, a blank one, and any value that is not literally {@code true}
 * ({@code yes}, {@code 1}, {@code on}, a typo) all resolve to CLOSED.
 *
 * The throwing case is the one that bit the last pass: {@code MoneyMode.of}
 * threw out from INSIDE {@code LiveMoneyGuard} before {@link DevLoginGate}'s own
 * catch could see it, so an unreadable environment refused by ACCIDENT with a
 * 500 rather than by decision. {@link #refusalReason(Environment)} therefore
 * wraps the classification itself, not only the opt-in read.
 *
 * @see DevLoginGate
 * @see MoneyMode#devFallbackAuthorized()
 */
class DevCreditGate {

    /**
     * The one name that can authorise an in-process fabricated credit, read
     * from the PROCESS ENVIRONMENT only.
     *
     * <pre>
     *   # bash
     *   SBOX_DEV_CREDIT_ENABLED=true java -jar build/libs/sboxmarket-1.0.0.jar
     *   # PowerShell
     *   $env:SBOX_DEV_CREDIT_ENABLED = "true"; java -jar ...
     * </pre>
     *
     * Deliberately a DIFFERENT name from {@link DevLoginGate#OPT_IN_ENV_VAR}:
     * they authorise different things, and a QA harness that needs a session
     * has not thereby asked for a money printer. One variable answering two
     * questions is the failure {@link MoneyMode} was written after.
     *
     * It must NOT be added to {@code application.yml}, {@code docker-compose.yml},
     * the {@code Dockerfile} or {@code deploy/skinbox.env.example} — pinned by
     * {@code DevCreditRequiresOptInSpec}, over comment-stripped text.
     */
    static final String OPT_IN_ENV_VAR = 'SBOX_DEV_CREDIT_ENABLED'

    /** The only value that authorises. Anything else — including {@code yes},
     *  {@code 1}, {@code on} and blank — is a refusal. Same rule as
     *  {@link DevLoginGate#OPT_IN_VALUE}. */
    static final String OPT_IN_VALUE = 'true'

    /** Common prefix of every refusal, so a log scrape or a probe that only
     *  wants to know "did this guard speak?" can match one substring and keep
     *  working if a further reason is ever added. Distinct from
     *  {@link DevLoginGate#REFUSAL_PREFIX} so the two gates are tellable apart
     *  in one log line. */
    static final String REFUSAL_PREFIX = 'dev-credit disabled:'

    /** Refusal body when nothing about the deployment forbids it but nobody
     *  asked for it either — <b>the DEFAULT answer on a fresh checkout</b>. It
     *  names the variable, because a shut door that does not say why is the
     *  "absence read as success" defect this repo keeps paying for. */
    static final String REASON_NOT_AUTHORIZED =
        REFUSAL_PREFIX + ' no ' + OPT_IN_ENV_VAR + '=' + OPT_IN_VALUE +
        ' opt-in in the process environment'

    /**
     * Refusal body when the deployment is not affirmatively SIMULATED — it can
     * move real money ({@link MoneyMode#LIVE}), it talks to a real Stripe test
     * account ({@link MoneyMode#TEST}), or it cannot be classified at all
     * ({@link MoneyMode#INDETERMINATE}).
     *
     * The mode is NAMED in the string, because the existing
     * {@code ProdScaffoldingUnreachableSpec} contract is that a caller reaching
     * the fabricated credit on a non-simulated deployment is told <i>which</i>
     * deployment it is, and because "refused" without "as what" is the same
     * silent absence again.
     */
    static String reasonNotSimulated(MoneyMode mode) {
        REFUSAL_PREFIX + ' ' + (mode == null ? 'unclassified' : mode.name()) +
            ' deployment — only ' + MoneyMode.SIMULATED.name() +
            ' may fabricate money-path state'
    }

    /**
     * The decision. True only when BOTH conditions hold.
     *
     * Defined as "there is no reason to refuse" rather than as its own copy of
     * the rule, so the answer and the explanation cannot drift apart.
     */
    static boolean devCreditAuthorized(MoneyMode mode, Environment env) {
        refusalReason(mode, env) == null
    }

    /**
     * {@code null} when the fabricated credit is authorised; otherwise the
     * reason it is refused, which is also what gets logged and surfaced.
     *
     * Order is load-bearing: the mode check runs FIRST, so an opt-in left set
     * on a box that later acquires a live Stripe key cannot re-open the door.
     */
    static String refusalReason(MoneyMode mode, Environment env) {
        if (mode == null || !mode.devFallbackAuthorized()) return reasonNotSimulated(mode)
        if (!optInGranted(env)) return REASON_NOT_AUTHORIZED
        return null
    }

    /**
     * The Environment-only form, for a caller that holds no key of its own.
     *
     * The classification is wrapped because {@link MoneyMode#of(Environment)}
     * reads property sources and a broken source THROWS — the exact gap that
     * made {@link DevLoginGate} refuse by accident with a 500 instead of by
     * decision. An environment that cannot be read is the strongest possible
     * "I cannot tell", so it gets the strongest refusal.
     *
     * Caught as {@link Throwable} deliberately: a half-initialised property
     * source can fail in ways that are not {@link Exception} (a
     * {@code NoClassDefFoundError}), and none of them are a reason to mint
     * money.
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
    static boolean devCreditAuthorized(Environment env) {
        refusalReason(env) == null
    }

    /**
     * Has someone affirmatively asked, in THIS process's environment?
     *
     * Split out so the opt-in can be tested on its own — and so it is obvious
     * that it is only ever consulted AFTER the mode check, never instead of it.
     *
     * The read itself is {@link DevLoginGate#processEnvValue} — the same one
     * function, not a copy. Two implementations of "what counts as the process
     * environment" would be two chances to disagree about the only property
     * that makes either opt-in un-inheritable.
     */
    static boolean optInGranted(Environment env) {
        String raw = DevLoginGate.processEnvValue(env, OPT_IN_ENV_VAR)
        raw != null && raw.trim().equalsIgnoreCase(OPT_IN_VALUE)
    }
}
