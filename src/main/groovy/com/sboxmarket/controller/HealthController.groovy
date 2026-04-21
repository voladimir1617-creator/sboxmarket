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
}
