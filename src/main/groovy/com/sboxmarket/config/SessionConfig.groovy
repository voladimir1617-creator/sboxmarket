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
// 2026-05-03 — DISABLED. Spring Session JDBC was the source of THREE site
// outages in 24h: every poisoned cookie value carried a 0x00 NUL byte
// straight into the JDBC bind parameter, Postgres rejected the SELECT
// with SQLSTATE 22021, every visitor 500'd, and /error 500'd too because
// it ran the same SessionRepositoryFilter — Tomcat fell through to its
// stub error page. Cookie sanitizer + V60 CHECK constraints couldn't
// fully close the gap (Tomcat strips NULs from getCookies() but leaves
// them in the raw Cookie header that Spring Session also reads). Going
// to in-memory Tomcat sessions until we can either (a) move the session
// table to BYTEA columns where NUL is legal, or (b) rewrite the
// sanitizer to ALWAYS rebuild the Cookie header (commit 3c12680
// attempted this but only when 'dirty' was true — Tomcat-pre-stripped
// NULs slip through that gate).
//
// In-memory cost: sessions don't survive `docker restart sbox-app`.
// Acceptable until the bug is closed root-and-branch. Operator already
// understands TRUNCATE = re-login; container restart now has the same
// effect.
//
// To re-enable: uncomment + restore @EnableJdbcHttpSession AND remove
// SPRING_SESSION_STORE_TYPE=none from deploy/run-local.sh. Verify the
// sanitizer in SessionCookieSanitizerFilter.groovy ALWAYS installs the
// HttpServletRequestWrapper (not gated on dirty) so the rebuilt Cookie
// header is what Spring Session sees.
//@Configuration
//@Profile('prod')
//@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 365 * 24 * 60 * 60)
//class SessionConfig {
//}
