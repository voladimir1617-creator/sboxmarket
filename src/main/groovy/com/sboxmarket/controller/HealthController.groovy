package com.sboxmarket.controller

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

import javax.sql.DataSource

/**
 * App-controlled liveness endpoint. Replaces `/actuator/health`, which is
 * disabled at the actuator level to avoid leaking the framework version to
 * scanners. This one just returns a fixed JSON body so Docker's
 * HEALTHCHECK / k8s readiness probes / load balancer health checks have a
 * 2xx target.
 *
 * Deliberately simple: no DB ping, no bean graph. If Spring started up
 * enough to handle HTTP requests, the process is up. Deeper readiness
 * checks should be their own authed endpoint.
 *
 * The companion /api/version endpoint surfaces the Gradle project version
 * so the UI footer can show "v1.0.0". Kept on this controller to avoid a
 * whole new file; public by design (same safety profile as /api/health).
 */
@RestController
@Slf4j
class HealthController {

    @Value('${info.app.version:1.0.0}')
    String appVersion

    @Autowired(required = false) DataSource dataSource

    // Batch 970 — register both slash variants. Docker HEALTHCHECK,
    // some k8s probe configs, and curl invocations append a trailing
    // slash; Spring Boot 3's PathPatternParser doesn't auto-match it.
    // `RateLimitFilter` already exempts both forms, so accepting both
    // at the controller level closes the loop — probes land on the
    // real handler with no 404 wedge.
    @GetMapping(['/api/health', '/api/health/'])
    ResponseEntity<Map> health() {
        // Batch 824 — explicit `no-store` on the liveness probe. Load
        // balancers + Docker HEALTHCHECK + k8s probes want a fresh read
        // every tick; a stale cached "UP" from an edge would defeat
        // the whole point of the probe. Also stops a friendly proxy
        // (Cloudflare) from caching a response that must reflect real
        // server state.
        ResponseEntity.ok()
            .header('Cache-Control', 'no-store, no-cache, must-revalidate')
            .body([status: 'UP'])
    }

    /**
     * Readiness probe (batch 680). Same public audience as /api/health
     * but also pings the DB so a pod with a broken pool returns 503 and
     * the LB / k8s rolls traffic away. Separate from liveness because:
     *   - Liveness shouldn't depend on external deps (DB restart → pod kill loop).
     *   - Readiness should, so a DB-less pod drops out of rotation until it recovers.
     *
     * Cheap: `SELECT 1` on a borrowed connection, ≤50ms timeout. No
     * bean-graph reflection, no row counts. Scanner-safe — leaks no
     * framework version or schema info.
     */
    @GetMapping(['/api/ready', '/api/ready/'])
    ResponseEntity<Map> ready() {
        // Batch 824 — readiness also no-store. A cached 503 would pin
        // the LB to route-away after the DB recovered; a cached 200
        // would delay evicting traffic from a degraded pod.
        def noStore = 'no-store, no-cache, must-revalidate'
        if (dataSource == null) {
            // Odd, but not fatal — return UP since /api/health is the
            // authoritative liveness signal.
            return ResponseEntity.ok()
                .header('Cache-Control', noStore)
                .body([status: 'UP', db: 'unknown'])
        }
        def ok = false
        try {
            def conn = dataSource.connection
            try {
                ok = conn.isValid(1)   // 1-second timeout inside the driver
            } finally {
                conn.close()
            }
        } catch (Exception e) {
            log.warn("Readiness DB probe failed: ${e.message}")
            ok = false
        }
        if (!ok) {
            return ResponseEntity.status(503)
                .header('Cache-Control', noStore)
                .body([status: 'DOWN', db: 'unreachable'])
        }
        ResponseEntity.ok()
            .header('Cache-Control', noStore)
            .body([status: 'UP', db: 'up'])
    }

    // JVM startup timestamp — captured once at class-load time. Returned
    // alongside `version` so ops can tell "is this the pod that was
    // redeployed 3 hours ago or a lingering old replica" at a glance
    // without crawling logs. Batch 866.
    private static final long STARTUP_AT = System.currentTimeMillis()

    @GetMapping(['/api/version', '/api/version/'])
    ResponseEntity<Map> version() {
        // Version is a build-time constant; it only changes on a
        // redeploy (batch 758). 10-minute public cache turns most of
        // the SPA's repeated boot-time version probes into browser
        // cache hits without risking a long-stale banner — the deploy
        // pipeline always rebuilds the image so cache buster isn't
        // needed.
        //
        // Batch 866 — also surface `startupAt` (JVM process start, not
        // request time) so ops / status pages can display deploy age
        // without needing a separate /actuator endpoint. Same 10-min
        // cache: the timestamp is stable across the lifetime of a pod,
        // so caching is safe and saves repeat round-trips.
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=600')
            .body([version: appVersion, startupAt: STARTUP_AT])
    }

    /**
     * Cookie-aware healthcheck (added 2026-05-03 after the FOURTH NUL-byte
     * session outage in 24h).
     *
     * The previous outages were invisible to /api/health and /api/ready
     * because both endpoints are anonymous: they never touch
     * SPRING_SESSION, so a Postgres SQLSTATE 22021 on every authed
     * request returned 200 here. The Boss QA worker shipped 79 design
     * tweaks during the 24h while logged-in users 500'd, because the
     * uptime monitor pinged the wrong probe.
     *
     * What this does (PURE READ-ONLY synthetic probe — does NOT
     * persist anything to the session store):
     *   1. Bind a known-GOOD UUID into the same SELECT shape Spring
     *      Session JDBC executes (`SELECT 1 FROM SPRING_SESSION WHERE
     *      SESSION_ID = ?`).  Verifies the table is reachable, the
     *      schema matches, and the JDBC pool is alive.
     *   2. Bind a known-POISONED but printable UUID-shaped value (a
     *      stale UUID that no real session ever used).  This mirrors
     *      what JdbcIndexedSessionRepository.findById sees when a
     *      sanitised cookie reaches the SELECT — Postgres must return
     *      zero rows without throwing.  The unsanitised NUL-byte
     *      shape is covered by the deploy gate in run-local.sh which
     *      replays it via raw TCP end-to-end against / and /market.
     *
     * Cloudflare uptime monitoring should target THIS endpoint — it
     * trips the moment the table is missing, the schema drifts, the
     * pool is exhausted, or the SELECT shape changes underneath us.
     * Both probes run on the same connection so a flaky pool can't
     * pass one and fail the other.
     *
     * No-store: identical reasoning to /api/ready — a cached 200 would
     * pin the LB to "healthy" right through the next outage.
     */
    @GetMapping(['/api/health/cookie-aware', '/api/health/cookie-aware/'])
    ResponseEntity<Map> cookieAware() {
        def noStore = 'no-store, no-cache, must-revalidate'
        if (dataSource == null) {
            return ResponseEntity.status(503)
                .header('Cache-Control', noStore)
                .body([status: 'DOWN', reason: 'no-datasource'])
        }
        // Known-good 36-char UUID, formatted exactly like Spring Session's
        // PRIMARY_ID/SESSION_ID columns (CHAR(36)).  The all-zeros UUID
        // is universally unused by real sessions but legal as a bind.
        final String GOOD_UUID = '00000000-0000-0000-0000-000000000000'
        // Known-poisoned: a UUID-shaped value matching the historic
        // poisoned-cookie payload (same one the deploy gate replays in
        // its `stale-uuid` probe). UUID-shaped, well-formed, but a
        // session that should never exist — exercises the SELECT bind
        // path that JdbcIndexedSessionRepository.findById walks every
        // request.
        final String POISONED  = '11111111-2222-3333-4444-555555555555'
        // Probe SQL — same SELECT shape as JdbcIndexedSessionRepository's
        // findById path.  We don't actually need rows back; we just need
        // the bind to succeed without throwing.
        final String SQL = 'SELECT 1 FROM SPRING_SESSION WHERE SESSION_ID = ?'

        def conn = null
        try {
            conn = dataSource.connection
            // Probe 1: known-good UUID. Must not throw.
            try {
                def ps = conn.prepareStatement(SQL)
                try {
                    ps.setString(1, GOOD_UUID)
                    def rs = ps.executeQuery()
                    try { /* drain */ rs.next() } finally { rs.close() }
                } finally {
                    ps.close()
                }
            } catch (Exception e) {
                log.warn("cookie-aware probe FAIL on known-good UUID: ${e.class.name}: ${e.message}")
                return ResponseEntity.status(503)
                    .header('Cache-Control', noStore)
                    .body([status: 'DOWN', reason: 'good-uuid-threw',
                           exception: e.class.name, message: (e.message ?: '')])
            }
            // Probe 2: poisoned (stale) UUID. Must return zero rows and
            // not throw — this is the shape a sanitised inbound cookie
            // produces when Spring Session looks up a non-existent
            // session id.  If Postgres throws here, the SPRING_SESSION
            // table is in a degraded state (constraint mismatch, schema
            // drift, etc.) and authed traffic will follow.
            try {
                def ps = conn.prepareStatement(SQL)
                try {
                    ps.setString(1, POISONED)
                    def rs = ps.executeQuery()
                    try { rs.next() } finally { rs.close() }
                } finally {
                    ps.close()
                }
            } catch (Exception e) {
                log.warn("cookie-aware probe FAIL on poisoned UUID: ${e.class.name}: ${e.message}")
                return ResponseEntity.status(503)
                    .header('Cache-Control', noStore)
                    .body([status: 'DOWN', reason: 'poisoned-uuid-threw',
                           exception: e.class.name, message: (e.message ?: '')])
            }
        } catch (Exception e) {
            // Couldn't even acquire a connection — fall through as 503.
            log.warn("cookie-aware probe FAIL on connection acquire: ${e.class.name}: ${e.message}")
            return ResponseEntity.status(503)
                .header('Cache-Control', noStore)
                .body([status: 'DOWN', reason: 'connection-failed',
                       exception: e.class.name, message: (e.message ?: '')])
        } finally {
            try { if (conn != null) conn.close() } catch (Exception ignored) {}
        }
        ResponseEntity.ok()
            .header('Cache-Control', noStore)
            .body([status: 'UP', probes: ['good-uuid', 'poisoned-uuid']])
    }
}
