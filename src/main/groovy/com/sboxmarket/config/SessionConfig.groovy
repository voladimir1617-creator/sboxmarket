package com.sboxmarket.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession

/**
 * Persistent HTTP sessions backed by the existing Postgres datasource.
 *
 * Operator demand (2026-05-01): "MAKE IT SO SESSION IS NOT GETTING EXPIRED
 * EVERY 2 MINUTES IT SHOULD NEVER EXPIRE ACTUALLY". Sessions now persist:
 *   - Across container restarts (table-backed).
 *   - For 365 days of inactivity (effectively never expires for any
 *     real user — the actual signing-out happens via explicit logout).
 *
 * Why @Profile("prod"):
 *   Spring Session's autoconfig kicks in whenever spring-session-jdbc
 *   is on the classpath, even with `store-type=none`. Gating the
 *   @EnableJdbcHttpSession behind the prod profile means tests run with
 *   the legacy in-memory Tomcat session manager unchanged, while prod
 *   gets persistence.
 *
 * Schema bootstrap:
 *   spring.session.jdbc.initialize-schema=always in application-prod.yml
 *   tells Spring Session to run the postgresql.sql DDL idempotently on
 *   first boot. Subsequent boots see the table exists and no-op.
 *
 * Session timeout:
 *   365 days (was 30 days). Operator does NOT want sessions expiring
 *   underneath them during the active grind; setting this to one year
 *   gives them effectively-permanent sessions.
 *
 * Cleanup-cron:
 *   Still runs every 15 minutes (configured in application-prod.yml)
 *   so genuinely abandoned/expired sessions don't accumulate forever.
 */
@Configuration
@Profile('prod')
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 365 * 24 * 60 * 60)
class SessionConfig {
}
