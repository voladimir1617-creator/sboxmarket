package com.sboxmarket

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration
import org.springframework.boot.CommandLineRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import com.sboxmarket.service.SeedService
import groovy.util.logging.Slf4j

/**
 * 2026-05-03 — Spring Session autoconfig HARD-EXCLUDED at the
 * @SpringBootApplication level. Repeated outages (FOUR) have come from
 * NUL-byte poisoned cookies reaching the JdbcIndexedSessionRepository
 * SELECT; even with `spring.session.store-type=none` Spring Boot's
 * SessionAutoConfiguration would still wire JdbcOperationsSessionRepository
 * if spring-session-jdbc remained on the classpath under any property
 * combination. Belt-and-suspenders exclude here makes it impossible for
 * the JDBC store to come back without removing this annotation.
 * Sessions fall back to the in-memory Tomcat session manager — they
 * are lost on container restart, which is the documented trade-off.
 */
@SpringBootApplication(exclude = [SessionAutoConfiguration])
@EnableScheduling
@EnableAsync
@Slf4j
class SboxMarketApplication {

    /**
     * Logger for use INSIDE the @Bean closures below. Callers MUST capture the
     * return value in a local variable before building the closure.
     *
     * {@code @Slf4j} injects a `log` property onto the class, but a
     * {@code @Configuration} class is proxied by Spring CGLIB, and a Groovy
     * closure declared in a @Bean method resolves any bare identifier
     * dynamically against that proxy — where neither the @Slf4j property nor a
     * private static field exists. Both forms fail identically at runtime:
     *   groovy.lang.MissingPropertyException: No such property: log for class:
     *   com.sboxmarket.SboxMarketApplication$$SpringCGLIB$$0
     * thrown out of CommandLineRunner.run, which aborts startup. (A private
     * static field is no better — Groovy property resolution does not see it
     * through the proxy either. Measured, not assumed: both were tried.)
     *
     * A LOCAL variable is captured lexically by the closure and needs no
     * property resolution at all, so it is immune to the proxy.
     *
     * This bug was ALREADY latent: the pre-existing `log.error('SEED REFUSED: …')`
     * in the onStartup closure sat on a branch that only executes when a live
     * Stripe key is configured without the prod profile. That branch is the
     * LiveMoneyGuard safety net — so the one boot where it was meant to warn
     * loudly and carry on is the one boot where it would instead have died with
     * an unrelated-looking Groovy error. Normal dev boots never take that
     * branch, which is why it was never seen.
     *
     * `log` remains correct in `main`, which runs against the real class.
     */
    private static org.slf4j.Logger runnerLog() {
        org.slf4j.LoggerFactory.getLogger(SboxMarketApplication)
    }

    static void main(String[] args) {
        // MUST run BEFORE SpringApplication.run — the first H2 connection Hikari
        // opens during context refresh is what starts the embedded server, and
        // H2 reads h2.bindAddress once at that point. See hardenEmbeddedH2Bind.
        hardenEmbeddedH2Bind()
        def ctx = SpringApplication.run(SboxMarketApplication, args)
        // Read the actual server.port from the Spring environment rather
        // than hardcoding 8080 — the startup banner was misleading when a
        // test or docker deploy overrode SERVER_PORT.
        def env = ctx.getBean(Environment)
        def port = env.getProperty('server.port', '8080')
        def activeProfiles = env.activeProfiles?.join(',') ?: 'default'
        log.info("🎮 SBoxMarket started — http://localhost:${port} (profiles: ${activeProfiles})")
    }

    /**
     * Pin H2's embedded TCP server to loopback.
     *
     * ── The exposure this closes ──────────────────────────────────────────
     * The dev datasource URL carries {@code AUTO_SERVER=TRUE}
     * (application.yml). That flag makes H2 start a TCP server the moment the
     * file database is opened, so a second same-host process can share the
     * live DB. H2's default bind address is the wildcard, and on a
     * multi-homed box the server advertises (and accepts on) a routable
     * interface: measured 2026-09-01 the listener answered on the machine's
     * LAN address (192.168.68.70) and its Tailscale address
     * (100.82.162.44:65359), while the HTTP port 8082 correctly refused both.
     *
     * The database password is EMPTY (username {@code SA}, no password), so
     * the ONLY thing gating a remote connection is the random per-session key
     * H2 writes into {@code data/sboxmarket.lock.db}. Anyone who can read that
     * one local file — a backup, a file share, a directory-read bug, another
     * account on the host — can then connect from anywhere on the LAN or
     * tailnet as {@code SA} with no password and get full read/write on the
     * wallet, listing and session tables. Proven by reading every wallet
     * balance over the port with only the lock-file key.
     *
     * ── Why bind rather than drop AUTO_SERVER ─────────────────────────────
     * {@code h2.bindAddress=127.0.0.1} keeps AUTO_SERVER working for its
     * legitimate same-host use (the server still binds and answers on
     * loopback) while removing the off-box surface entirely. Removing
     * {@code AUTO_SERVER=TRUE} instead would reintroduce a boot-time
     * {@code "Database may be already in use"} lock failure if the watchdog
     * ever overlaps two instances, so the bind is the lower-risk fix.
     *
     * ── Scope ─────────────────────────────────────────────────────────────
     * Inert on the prod (Postgres) profile — no H2 server exists there — and
     * inert for the in-memory {@code mem:} databases the test suite uses,
     * which never open a TCP server. Only set when the operator has not
     * pinned {@code h2.bindAddress} themselves, so an explicit
     * {@code -Dh2.bindAddress=…} still wins.
     *
     * @return the effective {@code h2.bindAddress} after hardening
     */
    static String hardenEmbeddedH2Bind() {
        if (System.getProperty('h2.bindAddress') == null) {
            System.setProperty('h2.bindAddress', '127.0.0.1')
        }
        return System.getProperty('h2.bindAddress')
    }

    /**
     * Demo catalogue. NEVER in production.
     *
     * `SeedService` fabricates six sellers with invented Steam IDs
     * (76561199000000001...), gives them wallets, and lists hundreds of items they do
     * not own. Every guard inside it is `if (count() > 0) return` — an IDEMPOTENCY
     * probe, not a production gate. On a fresh prod database every count is 0, so all
     * of it runs.
     *
     * Measured 2026-08-19 on the running app: 45 of 45 listings belonged to the six
     * seed sellers. Zero real inventory. With live Stripe keys a real buyer would
     * deposit real money, buy an item that does not exist, and wait three days for an
     * auto-cancel that refunds to their WALLET, not their card.
     *
     * Note the wallets those phantom sellers get are deliberate — SeedService says it
     * added them because "EVERY P2P buy of a seeded listing 400d SELLER_WALLET_MISSING".
     * The one thing stopping people buying fictional items was removed on purpose. So
     * the gate has to be here, at the wiring point.
     *
     * <h3>2026-08-31 — the gate is now FAIL-CLOSED (opt-in), not fail-open</h3>
     *
     * The two gates below ({@code @Profile("!prod")} and {@link
     * com.sboxmarket.config.LiveMoneyGuard}) both answer "is this OBVIOUSLY
     * production?". Neither answers "is this a developer box?". The difference
     * is the whole risk: the operator's own instance runs on the {@code default}
     * profile with {@code sk_test_} keys — so BOTH gates pass and the seeder
     * fabricates the entire marketplace. Measured on a fresh boot 2026-08-31:
     * 39 catalogue items, 85 ACTIVE listings, 6 invented sellers holding 50 of
     * them, 56 auction bids, 171 SOLD rows and 3,510 price-history points.
     *
     * That is not a cosmetic problem once a stranger can see the site. The SOLD
     * rows and the 90-day price series are what a real buyer prices against, and
     * the ACTIVE listings are buyable — the invented sellers were given wallets
     * precisely so the buy path would not refuse them.
     *
     * "Not prod" is the wrong question because absence of configuration is the
     * default state of every box that has not been configured yet — including
     * the one the operator is about to put a real item on. So the demo catalogue
     * now requires someone to ASK for it: an explicit {@code sbox.seed.demo-data}
     * property, or an explicitly-activated {@code dev}/{@code test} profile.
     * A bare {@code default}-profile boot seeds NOTHING. Absence of a signal
     * reads as "no", which is the only safe direction for fabricated inventory.
     *
     * @see #demoSeedRequested(org.springframework.core.env.Environment)
     */
    @Bean
    @Profile("!prod")
    CommandLineRunner onStartup(SeedService seedService,
                                org.springframework.core.env.Environment env) {
        def logger = runnerLog()
        return { args ->
            // Second, independent gate. @Profile("!prod") above is the primary
            // one, but it is the SAME switch that guards dev-login, the admin
            // bootstrap default, and ProdConfigValidator — so one missing
            // SPRING_PROFILES_ACTIVE loses all four at once and this runner
            // fabricates sellers and listings on a box that is taking real
            // card payments. A live Stripe key travels in a different
            // environment variable and cannot be lost by the same mistake,
            // so it gets a veto of its own. See LiveMoneyGuard.
            if (com.sboxmarket.config.LiveMoneyGuard.isRealMoney(env)) {
                logger.error('SEED REFUSED: a live Stripe key is configured but the `prod` profile is NOT active ' +
                          '(profiles: {}). This deployment can charge real cards, so the demo catalogue will ' +
                          'not be created. Set SPRING_PROFILES_ACTIVE=prod — dev-login, the default admin ' +
                          'bootstrap id, and the prod config validator are ALL still misconfigured.',
                          env.activeProfiles?.join(',') ?: 'default')
                return
            }
            // THIRD gate, and the only one that is fail-closed: the demo
            // catalogue must be explicitly requested. See the class doc above
            // for why "not prod" was never a sufficient answer.
            if (!demoSeedRequested(env)) {
                logger.info('Demo catalogue NOT seeded — no demo-data opt-in (profiles: {}). ' +
                         'The book will contain only real listings. To populate the fake ' +
                         'marketplace for local UI work, set sbox.seed.demo-data=true ' +
                         '(or activate the `dev` profile).',
                         env.activeProfiles?.join(',') ?: 'default')
                return
            }
            seedService.seed()
        } as CommandLineRunner
    }

    /**
     * Has someone explicitly asked for the fabricated demo catalogue?
     *
     * Static + Environment-only so it is unit-testable against a mock
     * Environment with no Spring context — the same shape as
     * {@link com.sboxmarket.config.LiveMoneyGuard#isRealMoney}.
     *
     * Precedence:
     * <ol>
     *   <li>An explicit {@code sbox.seed.demo-data} property wins outright, in
     *       BOTH directions. {@code false} switches the demo catalogue off even
     *       under the {@code dev}/{@code test} profiles, so a developer can
     *       reproduce the real empty-book experience without inventing a new
     *       profile.</li>
     *   <li>Otherwise the {@code dev} and {@code test} profiles imply yes. Both
     *       have to be activated deliberately, which is the signal we want, and
     *       keeping {@code test} on preserves the existing CI behaviour of the
     *       ~4,459-test suite exactly.</li>
     *   <li>Otherwise NO. Critically this includes the bare {@code default}
     *       profile — the state of every box nobody has configured yet, which is
     *       precisely the box that must not invent inventory.</li>
     * </ol>
     */
    static boolean demoSeedRequested(Environment env) {
        if (env == null) return false
        String explicit = env.getProperty('sbox.seed.demo-data')
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim().equalsIgnoreCase('true')
        }
        def profiles = env.activeProfiles?.toList() ?: []
        return profiles.contains('dev') || profiles.contains('test')
    }

    /**
     * One-shot purge of previously-seeded demo data.
     *
     * The opt-in gate above stops NEW fabrication; it cannot un-fabricate what a
     * previous boot already wrote. The operator's live H2 file already holds a
     * fully-seeded marketplace from before the gate existed, and "delete the
     * database" is not an acceptable instruction once there is a real account,
     * a real wallet balance or a real listing in it.
     *
     * Deliberately NOT {@code @Profile("!prod")}: the purge has to be able to run
     * in whatever profile the operator is actually using, including {@code prod}
     * if a seeded dev database is ever promoted. It is inert unless explicitly
     * asked, and asking is idempotent — a second run finds nothing to delete.
     *
     * Ordered BEFORE nothing in particular; it runs in its own runner so a purge
     * failure cannot take down boot (SeedService.purgeDemoData swallows and logs).
     */
    @Bean
    CommandLineRunner purgeDemoDataOnStartup(SeedService seedService,
                                             Environment env) {
        def logger = runnerLog()
        return { args ->
            String flag = env?.getProperty('sbox.seed.purge-demo-data')
            if (flag == null || !flag.trim().equalsIgnoreCase('true')) return
            logger.warn('sbox.seed.purge-demo-data=true — removing fabricated demo data from this database.')
            seedService.purgeDemoData()
        } as CommandLineRunner
    }

    /**
     * One-shot reset of the fabricated MONEY state — the ledger, not the
     * catalogue. See {@link com.sboxmarket.service.MoneyResetService} for what
     * it removes and {@link com.sboxmarket.config.MoneyResetGate} for who may
     * ask.
     *
     * <h3>Why a CommandLineRunner and not an admin endpoint</h3>
     *
     * An HTTP route — even one behind {@code requireAdmin} — is reachable by
     * anyone who obtains a session, and this repo has spent the last several
     * commits closing exactly that class of door. A runner adds NO network
     * surface at all: the only way to reach it is to already have the ability
     * to set an environment variable on the host and restart the process, which
     * is strictly more privilege than any web attacker gets. It is also the
     * shape the neighbouring one-shot destructive tool already uses
     * ({@code purgeDemoDataOnStartup}), so it is the convention rather than a
     * new one.
     *
     * <h3>Deliberately NOT profile-gated</h3>
     *
     * Same reasoning as the purge above: the tool must be able to run in
     * whatever profile the operator is actually using. It is inert unless
     * explicitly asked, and {@link com.sboxmarket.config.MoneyResetGate} — not
     * the profile — is what refuses on a deployment that is not affirmatively
     * SIMULATED.
     *
     * <h3>The dry run is unconditional</h3>
     *
     * The plan is built and printed on EVERY authorised invocation, including
     * the destructive one, so the log of a real reset always contains the
     * itemised statement of what it was about to do. The operator never gets a
     * deletion whose only record is a total.
     */
    @Bean
    CommandLineRunner moneyResetOnStartup(
            com.sboxmarket.service.MoneyResetService moneyResetService,
            Environment env) {
        def logger = runnerLog()
        return { args ->
            // Absent opt-in is the overwhelmingly common case (every ordinary
            // boot). Return silently rather than logging a refusal on every
            // start — a guard that cries wolf on every boot is a guard nobody
            // reads. A refusal is only interesting once someone has ASKED.
            if (!com.sboxmarket.config.MoneyResetGate.optInGranted(env)) return

            String refusal = com.sboxmarket.config.MoneyResetGate.refusalReason(env)
            if (refusal != null) {
                // Someone asked and was refused — say so loudly and by name.
                logger.error(refusal)
                return
            }

            def plan = moneyResetService.plan()
            plan.mode = com.sboxmarket.config.MoneyMode.of(env)
            plan.executeRequested = com.sboxmarket.config.MoneyResetGate.executeGranted(env)

            // Printed to stdout as well as the log: this is a report a human is
            // meant to READ, and the operator running it by hand should not have
            // to go find a log file to see the answer.
            println moneyResetService.render(plan)
            logger.warn(moneyResetService.render(plan))

            if (!plan.executeRequested) {
                logger.warn(com.sboxmarket.config.MoneyResetGate.REASON_EXECUTE_NOT_REQUESTED)
                return
            }
            def entry = moneyResetService.execute(plan)
            logger.warn("MONEY RESET COMPLETE — audit row id=${entry?.id} eventType=${entry?.eventType}")
        } as CommandLineRunner
    }
}
