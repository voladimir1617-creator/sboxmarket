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

    static void main(String[] args) {
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
     */
    @Bean
    @Profile("!prod")
    CommandLineRunner onStartup(SeedService seedService,
                                org.springframework.core.env.Environment env) {
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
                log.error('SEED REFUSED: a live Stripe key is configured but the `prod` profile is NOT active ' +
                          '(profiles: {}). This deployment can charge real cards, so the demo catalogue will ' +
                          'not be created. Set SPRING_PROFILES_ACTIVE=prod — dev-login, the default admin ' +
                          'bootstrap id, and the prod config validator are ALL still misconfigured.',
                          env.activeProfiles?.join(',') ?: 'default')
                return
            }
            seedService.seed()
        } as CommandLineRunner
    }
}
