package com.sboxmarket

import com.sboxmarket.config.LiveMoneyGuard
import com.sboxmarket.config.MoneyMode
import com.sboxmarket.config.ProdConfigValidator
import com.sboxmarket.service.StripeService
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification
import spock.lang.Unroll

/**
 * "Is this deployment handling real money?" must have ONE answer.
 *
 * <h3>The defect this pins</h3>
 *
 * Three guards answered that question independently and disagreed, and every
 * disagreement failed toward the permissive branch:
 *
 * <ol>
 *   <li>{@code StripeService.isLive()} — a SUBSTRING TEST
 *       ({@code secretKey && !secretKey.contains('replace_me')}) gating 15
 *       money-path call sites.</li>
 *   <li>{@code LiveMoneyGuard.isRealMoney} — {@code prod} profile OR a
 *       {@code sk_live_} prefix.</li>
 *   <li>{@code ProdConfigValidator} — a {@code sk_live_}/{@code rk_live_}
 *       prefix plus a minimum length.</li>
 * </ol>
 *
 * Three known holes came out of that, and each has a case below:
 * {@code sk_live_replace_me…} (validated clean, credited wallets for free),
 * {@code rk_live_…} (charging real cards while dev-login stayed open), and
 * {@code pk_live_…} in the secret slot (real Stripe calls attempted AND the
 * scaffolding open).
 *
 * <h3>What is asserted</h3>
 *
 * The truth table below is the whole decision. Everything else in the codebase
 * is a projection of it, and the last three features here assert exactly that:
 * that {@code isLive()}, {@code isRealMoney()} and the prod validator all
 * derive from this one classification rather than restating it.
 *
 * <b>No value in this file is a credential.</b> Every "key" is built from
 * repeated characters at runtime and can authenticate to nothing.
 */
class MoneyModeSpec extends Specification {

    /** Live-SHAPED but transparently synthetic. Not a secret of any kind. */
    private static String synthetic(String prefix, int bodyChars = 24) {
        prefix + ('0' * bodyChars)
    }

    /**
     * A REAL Spring Environment — not a Stub — so profile resolution is
     * Spring's own. Both property names are set because the two readers use
     * different ones: {@code MoneyMode} prefers the resolved
     * {@code stripe.secret-key} (so it sees a value from a mounted file or a
     * secrets manager), while {@code ProdConfigValidator} reads the
     * {@code STRIPE_SECRET_KEY} variable it tells operators to set.
     */
    private static Environment realEnv(List<String> profiles, String key) {
        def env = new StandardEnvironment()
        if (profiles) env.setActiveProfiles(profiles as String[])
        Map<String, Object> props = [:]
        if (key != null) {
            props['stripe.secret-key'] = key
            props['STRIPE_SECRET_KEY'] = key
        }
        env.propertySources.addFirst(new MapPropertySource('spec', props))
        env
    }

    // ── the truth table ─────────────────────────────────────────────

    @Unroll
    def "ofKey classifies #label as #expected"() {
        expect:
        MoneyMode.ofKey(key) == expected

        where:
        label                                  | key                                     || expected
        // ── SIMULATED: an affirmative "there is no Stripe here" ──────
        'an absent key'                        | null                                    || MoneyMode.SIMULATED
        'a blank key'                          | ''                                      || MoneyMode.SIMULATED
        'a whitespace-only key'                | '   '                                   || MoneyMode.SIMULATED
        'the committed application.yml default'| 'sk_test_replace_me'                    || MoneyMode.SIMULATED
        'the publishable placeholder'          | 'pk_test_replace_me'                    || MoneyMode.SIMULATED

        // ── LIVE: real cards ─────────────────────────────────────────
        'a live secret key'                    | synthetic('sk_live_')                   || MoneyMode.LIVE
        'a RESTRICTED live key'                | synthetic('rk_live_')                   || MoneyMode.LIVE
        'a live key with stray whitespace'     | '  ' + synthetic('sk_live_') + '\n'     || MoneyMode.LIVE
        // Short live keys stay LIVE here on purpose: length is the prod
        // validator's concern (it refuses to BOOT on a truncated paste). This
        // classifier only has to be right about MODE, and being wrong toward
        // "live" is the safe direction.
        'a truncated live key'                 | 'sk_live_abc'                           || MoneyMode.LIVE

        // ── TEST: Stripe is real, the money is not ───────────────────
        'a Stripe test key'                    | synthetic('sk_test_')                   || MoneyMode.TEST
        'a restricted test key'                | synthetic('rk_test_')                   || MoneyMode.TEST
        'a short test key'                     | 'sk_test_abc'                           || MoneyMode.TEST

        // ── INDETERMINATE: we cannot tell, so nothing is authorised ──
        // HOLE 2 (2026-08-31): passed the hardened prod shape check — right
        // prefix, long enough — so prod booted reporting a clean config, while
        // isLive() saw the substring and routed every deposit into
        // devModeDeposit. Free wallet credit on a validated deployment.
        'a live prefix carrying replace_me'    | 'sk_live_replace_me' + ('0' * 8)        || MoneyMode.INDETERMINATE
        'a restricted-live replace_me'         | 'rk_live_replace_me' + ('0' * 8)        || MoneyMode.INDETERMINATE
        // The publishable key pasted into the secret slot: isLive() said LIVE
        // (no `replace_me`) so real Stripe calls were attempted, while
        // LiveMoneyGuard said not-real-money so dev-login stayed open.
        'the publishable key in this slot'     | synthetic('pk_live_')                   || MoneyMode.INDETERMINATE
        'a webhook secret in this slot'        | synthetic('whsec_', 32)                 || MoneyMode.INDETERMINATE
        'a human placeholder'                  | 'changeme'                              || MoneyMode.INDETERMINATE
        'an unrecognised prefix'               | 'sk_prod_' + ('0' * 24)                 || MoneyMode.INDETERMINATE
    }

    // ── the two questions, kept apart ───────────────────────────────

    @Unroll
    def "#mode: handlesRealMoney=#real stripeConfigured=#stripe devFallback=#dev"() {
        expect:
        mode.handlesRealMoney()      == real
        mode.stripeConfigured()      == stripe
        mode.devFallbackAuthorized() == dev

        where:
        mode                       || real  | stripe | dev
        MoneyMode.LIVE             || true  | true   | false
        // A test key is a REAL key against a test account: Stripe is called,
        // nothing is fabricated in-process, and no real money is at risk — so
        // the QA scaffolding stays usable. One boolean could not say this.
        MoneyMode.TEST             || false | true   | false
        MoneyMode.SIMULATED        || false | false  | true
        // THE FAIL-SAFE. A missing answer counts as real money (every door
        // shuts) and authorises NOTHING — not a charge, not a free credit.
        MoneyMode.INDETERMINATE    || true  | false  | false
    }

    def "devFallbackAuthorized is NOT the negation of stripeConfigured"() {
        given: 'the inversion that was the bug: !isLive() was read as "run the fake deposit"'
        def notConfigured = MoneyMode.values().findAll { !it.stripeConfigured() }

        expect: 'two modes are "not configured"'
        notConfigured.toSet() == [MoneyMode.SIMULATED, MoneyMode.INDETERMINATE].toSet()

        and: 'but only ONE of them may fabricate a credit'
        notConfigured.findAll { it.devFallbackAuthorized() } == [MoneyMode.SIMULATED]
    }

    // ── profiles ────────────────────────────────────────────────────

    @Unroll
    def "of(env) is #expected for profiles=#profiles key=#label"() {
        expect:
        MoneyMode.of(realEnv(profiles, key)) == expected

        where:
        label                | profiles    | key                              || expected
        'live'               | []          | 'sk_live_' + ('0' * 24)          || MoneyMode.LIVE
        'live'               | ['dev']     | 'sk_live_' + ('0' * 24)          || MoneyMode.LIVE
        'live'               | ['prod']    | 'sk_live_' + ('0' * 24)          || MoneyMode.LIVE
        'restricted live'    | []          | 'rk_live_' + ('0' * 24)          || MoneyMode.LIVE
        'test'               | []          | 'sk_test_' + ('0' * 24)          || MoneyMode.TEST
        'placeholder'        | []          | 'sk_test_replace_me'             || MoneyMode.SIMULATED
        'absent'             | []          | null                             || MoneyMode.SIMULATED
        // The prod profile can only ever RESTRICT. It never manufactures a
        // LIVE out of a key that is not live — a prod box with a blank or
        // test-mode key is production configured wrong, not a dev laptop.
        'absent'             | ['prod']    | null                             || MoneyMode.INDETERMINATE
        'test'               | ['prod']    | 'sk_test_' + ('0' * 24)          || MoneyMode.INDETERMINATE
        'placeholder'        | ['prod']    | 'sk_test_replace_me'             || MoneyMode.INDETERMINATE
        'live+replace_me'    | ['prod']    | 'sk_live_replace_me' + ('0' * 8) || MoneyMode.INDETERMINATE
    }

    def "a null Environment is a MISSING ANSWER and therefore counts as real money"() {
        given: 'every hole in this file\'s history failed toward the permissive branch'

        expect: 'the unknown case is INDETERMINATE, not SIMULATED'
        MoneyMode.of(null) == MoneyMode.INDETERMINATE

        and: 'so the dangerous scaffolding is SHUT, not open'
        MoneyMode.of(null).handlesRealMoney()
        !MoneyMode.of(null).devFallbackAuthorized()

        and: 'and no Stripe call is attempted either — refusing is the only safe act'
        !MoneyMode.of(null).stripeConfigured()
    }

    // ── everything else is a projection of the above ────────────────

    @Unroll
    def "StripeService.isLive() is exactly MoneyMode.stripeConfigured(): #label"() {
        expect: 'isLive() is no longer an independent rule, it is a view of the one decision'
        new StripeService(secretKey: key).isLive() == MoneyMode.ofKey(key).stripeConfigured()

        and: 'and the mode the service reports is the mode the classifier assigns'
        new StripeService(secretKey: key).moneyMode() == MoneyMode.ofKey(key)

        where:
        label                     | key
        'absent'                  | null
        'placeholder'             | 'sk_test_replace_me'
        'live'                    | 'sk_live_' + ('0' * 24)
        'restricted live'         | 'rk_live_' + ('0' * 24)
        'test'                    | 'sk_test_' + ('0' * 24)
        'live carrying replace_me'| 'sk_live_replace_me' + ('0' * 8)
        'publishable in the slot' | 'pk_live_' + ('0' * 24)
        'changeme'                | 'changeme'
    }

    @Unroll
    def "LiveMoneyGuard.isRealMoney() is exactly MoneyMode.handlesRealMoney(): #label"() {
        given:
        def env = realEnv(profiles, key)

        expect:
        LiveMoneyGuard.isRealMoney(env) == MoneyMode.of(env).handlesRealMoney()

        where:
        label                      | profiles | key
        'absent, no profile'       | []       | null
        'placeholder, no profile'  | []       | 'sk_test_replace_me'
        'test key, no profile'     | []       | 'sk_test_' + ('0' * 24)
        'live key, no profile'     | []       | 'sk_live_' + ('0' * 24)
        'restricted live, no prof' | []       | 'rk_live_' + ('0' * 24)
        'live+replace_me, no prof' | []       | 'sk_live_replace_me' + ('0' * 8)
        'publishable in the slot'  | []       | 'pk_live_' + ('0' * 24)
        'prod, no key'             | ['prod'] | null
    }

    /**
     * THE HOLE THAT WAS ONLY HALF-CLOSED.
     *
     * Commit e11b012 made {@code ProdConfigValidator} reject
     * {@code sk_live_replace_me…}. But that validator is
     * {@code @Profile('prod')} — it does not run on any other profile — and
     * the deposit door is not profile-gated. So on the DEFAULT profile (which
     * is what the live JVM was actually running) the same key still met a
     * false {@code isLive()} and fell through to {@code devModeDeposit}.
     */
    @Unroll
    def "a live-shaped key carrying replace_me opens NO door on ANY profile: #profiles"() {
        given: 'the key that booted a clean prod and still credited wallets for free'
        String key = 'sk_live_replace_me' + ('0' * 8)
        def env = realEnv(profiles, key)

        expect: 'the deposit fallback is not authorised — this is the fix, and it is profile-independent'
        !MoneyMode.ofKey(key).devFallbackAuthorized()
        !new StripeService(secretKey: key).moneyMode().devFallbackAuthorized()

        and: 'no Stripe charge is attempted on a key we cannot identify either'
        !new StripeService(secretKey: key).isLive()

        and: 'and it counts as real money, so dev-login and the demo seeder are shut'
        LiveMoneyGuard.isRealMoney(env)

        where:
        profiles << [[], ['default'], ['dev'], ['test'], ['prod']]
    }

    /**
     * The {@code rk_live_} disagreement, which nothing had caught: the prod
     * validator explicitly blesses a restricted live key as "arguably better"
     * than {@code sk_live_}, and {@code LiveMoneyGuard} knew only
     * {@code sk_live_}. A deployment charging real cards on a restricted key
     * therefore served credential-free account takeover at
     * {@code /api/auth/steam/dev-login}.
     */
    def "a RESTRICTED live key is real money everywhere, not only inside the prod validator"() {
        given:
        String key = 'rk_live_' + ('0' * 24)

        expect: 'the prod validator accepts it'
        new ProdConfigValidator(environment: realEnv(['prod'], key))
            .findViolations().every { !it.contains('STRIPE_SECRET_KEY') }

        and: 'the money guard agrees — it used to say false here, off by one prefix'
        LiveMoneyGuard.isRealMoney(realEnv([], key))

        and: 'and the deposit path charges rather than fabricating'
        new StripeService(secretKey: key).isLive()
        !new StripeService(secretKey: key).moneyMode().devFallbackAuthorized()
    }

    /**
     * The one list. These constants used to be three copies in three files;
     * two of them drifted. Asserting identity (not equality of contents)
     * catches a future copy being reintroduced.
     */
    def "the prod validator reads its live-key rule FROM MoneyMode rather than restating it"() {
        expect:
        ProdConfigValidator.STRIPE_LIVE_SECRET_PREFIXES.is(MoneyMode.LIVE_PREFIXES)
        ProdConfigValidator.STRIPE_DEV_KEY_MARKER == MoneyMode.DEV_MARKER

        and: 'and the restricted-key prefix is in it — the one LiveMoneyGuard was missing'
        MoneyMode.LIVE_PREFIXES.containsAll(['sk_live_', 'rk_live_'])
    }
}
