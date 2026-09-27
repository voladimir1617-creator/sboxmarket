package com.sboxmarket.controller

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
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

    // Mirrors GlobalExceptionHandler's posture flag. When false (the
    // hardened prod default — application-prod.yml pins it, and that's
    // also the only profile where actuator is killed to hide the
    // framework version), we must not hand internal recon detail to an
    // anonymous caller. Used below to gate `startupAt` out of the public
    // /api/version body. Devs/ops opt back in with
    // SECURITY_VERBOSE_ERRORS=true for a debugging session.
    @Value('${security.verbose-errors:false}')
    boolean verboseDetails

    @Autowired(required = false) DataSource dataSource
    @Autowired(required = false) Environment environment

    // `required = false` for the same reason AdminController wires the admin
    // reporter that way: the Spock specs build this controller field by field,
    // and the endpoint reports its own absence (as 503 — never as 200) instead
    // of NPEing.
    @Autowired(required = false)
    com.sboxmarket.config.BackupFreshnessReporter backupFreshnessReporter

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
        // `startupAt` (JVM process start, not request time) lets ops /
        // status pages show deploy age without a separate /actuator
        // endpoint (batch 866). BUT it is a per-pod recon fingerprint:
        // an anonymous scanner can read off exactly when each replica
        // was last deployed/restarted. That contradicts the whole
        // reason actuator is disabled in prod (hide internal detail
        // from scanners — see class javadoc) and the prod
        // `show-details: never` / `verbose-errors: false` posture.
        //
        // So gate it on the SAME hardening flag GlobalExceptionHandler
        // uses: in prod (verboseDetails=false) the public body carries
        // ONLY `version` — which the SPA footer + status page need and
        // changelog.html already advertises publicly. Ops read uptime
        // off the private actuator management port (ACTUATOR_PORT), or
        // flip SECURITY_VERBOSE_ERRORS=true for an incident-debug
        // session, at which point startupAt reappears here.
        def body = [version: appVersion] as LinkedHashMap
        if (verboseDetails) {
            body.startupAt = STARTUP_AT
        }
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=600')
            .body(body)
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
        if (!jdbcSessionProbeRequired()) {
            return ResponseEntity.ok()
                .header('Cache-Control', noStore)
                .body([status: 'UP', sessionStore: 'tomcat-memory',
                       probes: ['jdbc-session-disabled']])
        }
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
                // Full exception class + message goes to the server log only.
                // The public body carries just the safe hand-written `reason`
                // code — a raw JDBC/Postgres message can leak schema names,
                // SQL state, or internal hostnames to an anonymous scanner
                // (same hardening rationale as GlobalErrorController.safeMessage).
                log.warn("cookie-aware probe FAIL on known-good UUID: ${e.class.name}: ${e.message}")
                return ResponseEntity.status(503)
                    .header('Cache-Control', noStore)
                    .body([status: 'DOWN', reason: 'good-uuid-threw'])
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
                // Full detail to the log; public body stays a safe reason code.
                log.warn("cookie-aware probe FAIL on poisoned UUID: ${e.class.name}: ${e.message}")
                return ResponseEntity.status(503)
                    .header('Cache-Control', noStore)
                    .body([status: 'DOWN', reason: 'poisoned-uuid-threw'])
            }
        } catch (Exception e) {
            // Couldn't even acquire a connection — fall through as 503.
            // Full detail to the log; public body stays a safe reason code.
            log.warn("cookie-aware probe FAIL on connection acquire: ${e.class.name}: ${e.message}")
            return ResponseEntity.status(503)
                .header('Cache-Control', noStore)
                .body([status: 'DOWN', reason: 'connection-failed'])
        } finally {
            try { if (conn != null) conn.close() } catch (Exception ignored) {}
        }
        ResponseEntity.ok()
            .header('Cache-Control', noStore)
            .body([status: 'UP', probes: ['good-uuid', 'poisoned-uuid']])
    }

    /**
     * <b>Backup freshness probe — the only signal an uptime monitor can act
     * on without a human being logged in.</b>
     *
     * <p>{@code deploy/h2-backup.ps1} writes {@code data\h2-backup-status.json}
     * on every run. Until this endpoint, nothing read it — and the one failure
     * that matters most cannot be reported from inside the backup job at all:
     * a scheduler that stopped firing and a quiet machine produce identical
     * evidence. This deployment has the receipt. The old Postgres backup task
     * failed with exit 127 every day from April to September into a log
     * nobody opened.</p>
     *
     * <h3>Why a status code, and why not this one</h3>
     *
     * 200 when the last run verified inside its window <b>and a verified copy
     * of it reached a second physical device</b>; <b>503 for every other
     * state</b> — {@code failed}, {@code stale}, and {@code ok-no-offsite} —
     * because an uptime monitor that reads only status codes is the consumer
     * that alerts with nobody watching.
     *
     * <p>{@code ok-no-offsite} answers 503 deliberately, and the choice is not
     * free. It means the database HAS a current, read-back-verified archive —
     * sitting on the same disk as the database. That was the permanent state of
     * this deployment until 2026-09-02 and it is what a quietly unplugged
     * second drive leaves behind, so it must reach the one consumer that speaks
     * without a human present. The cost of 503 here is a false alarm; the cost
     * of 200 is that the second copy can stop for six months in silence, which
     * is the shape of every incident in {@code deploy/RUNBOOK.md}. The body
     * still carries the distinct word, so a monitor that reads it can tell the
     * cases apart — and the default destination is an INTERNAL disk precisely
     * so this does not fire on a normal day.
     *
     * <p>It is a SEPARATE path from {@code /api/health} and {@code /api/ready}
     * on purpose. Those two are the Docker HEALTHCHECK, the deploy gate and the
     * load-balancer targets, and {@code deploy/nginx.conf} /
     * {@code deploy/edge-nginx.conf} match them with {@code location = } —
     * exact, so a 503 here reaches none of that machinery. <b>A stale backup
     * must never evict a healthy app from rotation.</b> Losing the marketplace
     * because yesterday's archive is missing would turn a data-protection
     * warning into the outage it was meant to prevent.</p>
     *
     * <h3>What it is allowed to say</h3>
     *
     * The state word and nothing else — no archive path, no database path, no
     * row counts, no machine name, no timestamp. Naming the archive tells an
     * anonymous caller which file on this box holds every wallet row; the row
     * counts size the money. Those live on
     * {@code GET /api/admin/security/backup-status} and the admin Health tile,
     * behind the same auth that keeps the ADMIN Steam IDs off the public
     * surface (see {@code UnconfiguredAdminReporter}).
     *
     * <p>The residual leak is one bit — "this deployment's backups are not
     * current" — which is mild uplift to an attacker already on the box and is
     * stated plainly rather than engineered around. It is worth it: the
     * alternative is a signal only a logged-in human can see, on a project
     * whose entire documented failure history is of signals no human looked
     * at.</p>
     */
    @GetMapping(['/api/health/backup', '/api/health/backup/'])
    ResponseEntity<Map> backup() {
        def noStore = 'no-store, no-cache, must-revalidate'
        if (backupFreshnessReporter == null) {
            // The bean is missing (a slice test, or a stripped context). We
            // cannot establish that a backup ran, so this is state 3 — never
            // a 200. An absence read as a pass is the defect the reporter
            // exists to end; it must not be reintroduced by its own wiring.
            return ResponseEntity.status(503)
                .header('Cache-Control', noStore)
                .body([status: 'DOWN', backup: com.sboxmarket.config.BackupFreshnessReporter.STATE_STALE])
        }
        String state = backupFreshnessReporter.publicState()
        if (state == com.sboxmarket.config.BackupFreshnessReporter.STATE_OK) {
            return ResponseEntity.ok()
                .header('Cache-Control', noStore)
                .body([status: 'UP', backup: state])
        }
        ResponseEntity.status(503)
            .header('Cache-Control', noStore)
            .body([status: 'DOWN', backup: state])
    }

    private boolean jdbcSessionProbeRequired() {
        def explicit = environment?.getProperty('sbox.health.cookie-aware.require-jdbc')
        if (explicit != null) {
            return explicit.equalsIgnoreCase('true')
        }
        def storeType = environment?.getProperty('spring.session.store-type')
            ?: System.getenv('SPRING_SESSION_STORE_TYPE')
            ?: ''
        storeType.equalsIgnoreCase('jdbc')
    }
}
