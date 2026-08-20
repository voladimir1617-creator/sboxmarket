package com.sboxmarket.config

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * "Can this deployment move real money?" — a second, independent answer.
 *
 * <h3>The single point of failure this removes</h3>
 *
 * Every production safeguard in this codebase keys off ONE fact: whether the
 * {@code prod} Spring profile is active.
 *
 * <ul>
 *   <li>{@code SteamAuthController.devLogin} returns 404 only when
 *       {@code env.activeProfiles.contains('prod')} — otherwise it mints a
 *       full session as ANY user id, with no credential of any kind.</li>
 *   <li>The demo-catalogue seeder is wired {@code @Profile("!prod")}
 *       (SboxMarketApplication.onStartup) — otherwise it fabricates sellers
 *       and lists items nobody owns.</li>
 *   <li>{@link ProdConfigValidator} is {@code @Profile('prod')} — otherwise
 *       none of the missing-secret / test-key / dev-placeholder checks run
 *       at all.</li>
 *   <li>{@code application.yml} ships a DEFAULT admin bootstrap Steam id
 *       ({@code admin.bootstrap-steam-ids}), which
 *       {@code application-prod.yml} overrides with a mandatory env var —
 *       so off-profile, a hardcoded account in the public source
 *       auto-promotes to admin on login.</li>
 * </ul>
 *
 * So a deploy that ships real Stripe keys but forgets, misspells, or has
 * {@code SPRING_PROFILES_ACTIVE} stripped by an orchestrator loses ALL FOUR
 * at once, silently, with the app booting normally and taking real payments.
 * The Dockerfile and compose file both set the variable, which makes this
 * unlikely — and makes it exactly the kind of assumption that is never
 * re-checked until the day something else sets the environment.
 *
 * <h3>The independent signal</h3>
 *
 * A {@code sk_live_} Stripe secret key is not a hint about intent; it is the
 * definition of a deployment that can charge a real card. It travels in its
 * own environment variable, so it cannot be lost by the same mistake that
 * loses the profile. Treating its presence as "this is production" gives the
 * dangerous scaffolding a second lock whose key the operator cannot forget —
 * because without it there is no revenue to protect in the first place.
 *
 * The two conditions are OR'd: {@code prod} profile OR live key. Neither
 * relaxes the other, and the guard can only ever make the app MORE
 * restrictive than the profile alone would.
 *
 * <h3>Deliberately not a fail-closed default</h3>
 *
 * An unset key with no {@code prod} profile reads as "not real money", which
 * keeps every existing local-dev and CI workflow working untouched. The
 * guard's job is to catch the misconfigured PRODUCTION box, where the live
 * key is present by definition; it is not a way to lock down developer
 * laptops, and making it one would only teach people to switch it off.
 */
@Component
@Slf4j
class LiveMoneyGuard {

    /** Prefix of a Stripe LIVE-mode secret key. Test keys are `sk_test_`. */
    static final String LIVE_KEY_PREFIX = 'sk_live_'

    @Autowired
    Environment environment

    /** Instance form for injected callers. */
    boolean isRealMoney() {
        isRealMoney(environment)
    }

    /**
     * Pure check — kept static and Environment-only so it is unit-testable
     * against a mock Environment with no Spring context, the same shape as
     * {@link ProdConfigValidator#findViolations}, and so callers that already
     * hold an Environment (controllers) need not take a new dependency.
     *
     * Reads the resolved {@code stripe.secret-key} property rather than the
     * raw {@code STRIPE_SECRET_KEY} env var so it sees the value the app is
     * ACTUALLY using — including one supplied by a mounted config file or a
     * secrets manager rather than the process environment.
     */
    static boolean isRealMoney(Environment env) {
        if (env == null) return false
        if (env.activeProfiles?.toList()?.contains('prod')) return true
        String key = env.getProperty('stripe.secret-key') ?: env.getProperty('STRIPE_SECRET_KEY')
        return key != null && key.trim().startsWith(LIVE_KEY_PREFIX)
    }
}
