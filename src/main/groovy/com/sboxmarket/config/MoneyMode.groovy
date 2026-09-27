package com.sboxmarket.config

import org.springframework.core.env.Environment

/**
 * <b>The single answer to "what is this deployment allowed to do with money?"</b>
 *
 * <h3>Why this type exists</h3>
 *
 * Three guards used to answer that question independently, and they disagreed:
 *
 * <ol>
 *   <li>{@code StripeService.isLive()} — {@code secretKey && !secretKey.contains('replace_me')}.
 *       A SUBSTRING TEST, gating 15 money-path call sites.</li>
 *   <li>{@link LiveMoneyGuard#isRealMoney} — {@code prod} profile OR the key starts
 *       with {@code sk_live_}.</li>
 *   <li>{@link ProdConfigValidator} — a prefix + minimum-length shape check over
 *       {@code sk_live_} / {@code rk_live_}.</li>
 * </ol>
 *
 * Every disagreement found so far failed toward the PERMISSIVE branch:
 *
 * <ul>
 *   <li><b>{@code sk_live_replace_me…}</b> — passes (3), so a prod box boots
 *       reporting a clean configuration; fails (1), so
 *       {@code createDepositSession} falls through to {@code devModeDeposit} and
 *       credits wallets against no payment. <i>Free money on a deployment whose
 *       config validated.</i> (Commit e11b012 closed this at prod BOOT only —
 *       {@code ProdConfigValidator} is {@code @Profile('prod')}, so off-profile
 *       the same key still opened the door.)</li>
 *   <li><b>{@code rk_live_…}</b> — a restricted live key, which (3) explicitly
 *       blesses as "arguably better" than {@code sk_live_}, and which (2) does
 *       not recognise at all. A deployment charging real cards with a restricted
 *       key therefore served {@code /api/auth/steam/dev-login} to strangers.</li>
 *   <li><b>{@code pk_live_…} pasted into the secret slot</b> — (1) says LIVE (no
 *       {@code replace_me}) so real Stripe calls are attempted and fail at the
 *       first charge, while (2) says not-real-money so the scaffolding stays
 *       open.</li>
 * </ul>
 *
 * <h3>The two questions, kept apart on purpose</h3>
 *
 * The old boolean conflated them, which is <i>why</i> it could not be made safe:
 *
 * <ul>
 *   <li><b>"Can real money move here?"</b> — gates the dangerous scaffolding
 *       (dev-login, the demo seeder, the fabricated deposit).
 *       {@link #handlesRealMoney()}.</li>
 *   <li><b>"Should we talk to Stripe at all?"</b> — gates the API calls, the
 *       pass-through fees and the ledger postings. {@link #stripeConfigured()}.</li>
 * </ul>
 *
 * A Stripe TEST key answers <i>no</i> to the first and <i>yes</i> to the second.
 * One boolean cannot express that, so it always got one of them wrong.
 *
 * <h3>A missing answer means LIVE</h3>
 *
 * {@link #INDETERMINATE} is the value for "a key is configured and it is not
 * something we recognise" — a truncated paste, the wrong key in the slot, a
 * live prefix carrying a dev marker, a {@code prod} profile with a non-live key,
 * a null {@link Environment}. It counts as real money for
 * {@link #handlesRealMoney()} (so every door SHUTS) and it is not
 * {@link #stripeConfigured()} (so nothing is charged) and it is not
 * {@link #devFallbackAuthorized()} (so nothing is credited for free). The money
 * path refuses and says why. Being wrong in that direction costs a failed
 * deposit; being wrong in the other direction is what handed strangers $5,000
 * a day.
 *
 * <h3>Invariant, pinned by ProdScaffoldingUnreachableSpec</h3>
 *
 * <b>Every {@code STRIPE_SECRET_KEY} {@link ProdConfigValidator} accepts must
 * classify {@link #LIVE} here.</b> That is enforced by construction, not by
 * agreement: the validator requires {@code ofKey(key) == LIVE} as one of its own
 * conditions, so the two can no longer drift apart the way (1) and (3) did.
 */
enum MoneyMode {

    /** A live-mode Stripe key is configured (or {@code prod} is active with one).
     *  Real cards are charged. All dev scaffolding is dead. */
    LIVE,

    /** A Stripe TEST-mode key is configured. Stripe IS called — against test
     *  mode — so nothing must be faked in-process, but no real money exists,
     *  so the local QA scaffolding stays usable. */
    TEST,

    /** No Stripe account is wired at all: the key is absent, blank, or one of
     *  the committed {@code …replace_me} placeholders. Stripe is never called
     *  and the in-process simulated deposit is authorised. The ordinary
     *  developer-laptop state. */
    SIMULATED,

    /** A key IS set and we cannot say what it is — or the signals contradict
     *  each other. Nothing is authorised: no Stripe call, no simulated credit,
     *  no scaffolding. This is the fail-safe value and the reason a missing
     *  answer can never be mistaken for a dev box. */
    INDETERMINATE

    /** Prefixes of a Stripe LIVE-mode secret. {@code rk_live_} is a restricted
     *  key — scoped to only the permissions this deployment needs — and is a
     *  legitimate, arguably better, production choice, so it counts as live
     *  everywhere rather than only inside the prod validator. */
    static final List<String> LIVE_PREFIXES = ['sk_live_', 'rk_live_'].asImmutable()

    /** Prefixes of a Stripe TEST-mode secret. */
    static final List<String> TEST_PREFIXES = ['sk_test_', 'rk_test_'].asImmutable()

    /** The marker every committed placeholder in this repo carries
     *  ({@code application.yml} ships {@code sk_test_replace_me}). Its presence
     *  is an affirmative "this is not a real key" — EXCEPT when it is wearing a
     *  live prefix, which is a contradiction rather than a placeholder. */
    static final String DEV_MARKER = 'replace_me'

    /** The Spring profile that declares production. */
    static final String PROD_PROFILE = 'prod'

    /**
     * Can real money move on this deployment? Gates every piece of dangerous
     * scaffolding: {@code /api/auth/steam/dev-login}, the demo-catalogue seeder,
     * and the fabricated deposit.
     *
     * {@link #INDETERMINATE} counts as YES. That is the whole point: the door
     * shuts when we cannot tell.
     */
    boolean handlesRealMoney() { this == LIVE || this == INDETERMINATE }

    /**
     * Should this deployment call the Stripe API? True for both key modes —
     * a test key is a real key against a test account, and faking a deposit
     * behind someone's back because their key says {@code test} would be the
     * same defect in a different costume.
     */
    boolean stripeConfigured() { this == LIVE || this == TEST }

    /**
     * May the in-process simulated deposit / simulated Connect onboarding run?
     *
     * <b>Only {@link #SIMULATED}.</b> Note this is NOT {@code !stripeConfigured()}
     * — that inversion is exactly the bug: {@link #INDETERMINATE} is also
     * "not configured", and treating the two the same is what let a
     * live-shaped key credit wallets for free.
     */
    boolean devFallbackAuthorized() { this == SIMULATED }

    /**
     * Classify a Stripe secret key on its own, with no knowledge of profiles.
     * This is the form {@code StripeService} uses — it holds the injected
     * {@code stripe.secret-key} value and no {@link Environment}.
     *
     * Order matters. The dev marker is checked BEFORE the live prefix so that
     * {@code sk_live_replace_me…} — the value that passed a hardened prod
     * validator while opening the free-deposit path — lands on
     * {@link #INDETERMINATE} rather than on either happy branch.
     */
    static MoneyMode ofKey(String secretKey) {
        String key = secretKey?.trim()
        // Absent or blank is an ANSWER, not a missing one: there is no Stripe
        // account here, so no money can move and the simulated path is the
        // honest behaviour. Every other unrecognised value is INDETERMINATE.
        if (!key) return SIMULATED

        boolean live = LIVE_PREFIXES.any { key.startsWith(it) }
        boolean test = TEST_PREFIXES.any { key.startsWith(it) }

        if (key.contains(DEV_MARKER)) {
            // A live prefix plus a dev marker is a CONTRADICTION. One of the
            // two signals is a lie and we cannot tell which, so authorise
            // nothing. (`sk_live_replace_me…`, found 2026-08-31.)
            return live ? INDETERMINATE : SIMULATED
        }
        if (live) return LIVE
        if (test) return TEST
        // A key is set and it is none of the above: a truncated paste
        // (`sk_live_abc` is caught by the validator's length check, not here),
        // the publishable key in the secret slot, a webhook secret in the
        // secret slot, `changeme`. Refuse rather than guess.
        return INDETERMINATE
    }

    /**
     * Classify a whole deployment: the resolved {@code stripe.secret-key} plus
     * the active Spring profiles.
     *
     * Reads the resolved PROPERTY rather than the raw {@code STRIPE_SECRET_KEY}
     * environment variable so it sees the value the app is ACTUALLY using —
     * including one supplied by a mounted config file or a secrets manager.
     *
     * The {@code prod} profile can only ever make the answer MORE restrictive.
     * It never manufactures a {@link #LIVE} out of a key that is not live: a
     * prod box whose key is blank, test-mode or unrecognised is a contradiction
     * — production configured wrong — not a developer laptop, so it resolves to
     * {@link #INDETERMINATE} and every door stays shut.
     */
    static MoneyMode of(Environment env) {
        // A null Environment is a MISSING ANSWER, and a missing answer is
        // real money. The previous guard returned "not real money" here so
        // context-less unit tests would not be locked out; that is the same
        // permissive default that every hole in this file's history exploited,
        // and tests that need a decision now pass a real StandardEnvironment.
        if (env == null) return INDETERMINATE

        MoneyMode byKey = ofKey(env.getProperty('stripe.secret-key')
                                ?: env.getProperty('STRIPE_SECRET_KEY'))
        boolean prod = env.activeProfiles?.toList()?.contains(PROD_PROFILE)
        if (!prod) return byKey
        return byKey == LIVE ? LIVE : INDETERMINATE
    }
}
