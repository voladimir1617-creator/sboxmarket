package com.sboxmarket

import com.sboxmarket.controller.HealthController
import org.springframework.http.HttpStatus
import org.springframework.mock.env.MockEnvironment
import spock.lang.Specification
import spock.lang.Subject

import javax.sql.DataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Unit coverage for the app-controlled liveness / readiness / version
 * trio. These endpoints back Docker HEALTHCHECK, k8s probes, the SPA's
 * footer "vX.Y.Z" display, and the ops pod-age check — every one of
 * them being wrong has a real on-call cost.
 *
 * Invariants we lock down here:
 *
 *   - `/api/health` always returns `{status: UP}` + `Cache-Control:
 *     no-store` so a load-balancer never caches a stale UP signal for
 *     a degraded pod.
 *   - `/api/ready` returns 200 + `db: up` when the DB pings, 503 +
 *     `db: unreachable` when the driver's `isValid()` throws, and 200
 *     + `db: unknown` when the DataSource bean is absent (odd config
 *     but not fatal — liveness stays authoritative).
 *   - `/api/version` returns the injected `appVersion` and a stable
 *     startup timestamp (snapshot captured at class-load time).
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class HealthControllerSpec extends Specification {

    @Subject
    HealthController controller = new HealthController(appVersion: '9.9.9-test')

    def "health() returns UP with no-store cache header"() {
        when:
        def resp = controller.health()

        then:
        resp.statusCode == HttpStatus.OK
        resp.body.status == 'UP'

        and: 'no-store keeps a stale UP from masking a degraded pod'
        resp.headers.getFirst('Cache-Control')?.contains('no-store')
    }

    def "ready() returns UP + db:up when the DB probe succeeds"() {
        given:
        Connection conn = Mock()
        DataSource ds = Mock()
        controller.dataSource = ds

        when:
        def resp = controller.ready()

        then:
        1 * ds.getConnection() >> conn
        1 * conn.isValid(1)   >> true
        1 * conn.close()
        resp.statusCode == HttpStatus.OK
        resp.body.status == 'UP'
        resp.body.db == 'up'
        resp.headers.getFirst('Cache-Control')?.contains('no-store')
    }

    def "ready() returns 503 + db:unreachable when the probe fails"() {
        given:
        DataSource ds = Mock()
        controller.dataSource = ds

        when:
        def resp = controller.ready()

        then: 'DB is throwing on connection acquisition'
        1 * ds.getConnection() >> { throw new SQLException('connection refused') }
        resp.statusCode == HttpStatus.SERVICE_UNAVAILABLE
        resp.body.status == 'DOWN'
        resp.body.db == 'unreachable'
    }

    def "ready() returns 503 when isValid() reports false"() {
        given:
        Connection conn = Mock()
        DataSource ds = Mock()
        controller.dataSource = ds

        when:
        def resp = controller.ready()

        then:
        1 * ds.getConnection() >> conn
        1 * conn.isValid(1)   >> false
        1 * conn.close()
        resp.statusCode == HttpStatus.SERVICE_UNAVAILABLE
        resp.body.db == 'unreachable'
    }

    def "ready() without a DataSource bean falls back to UP + db:unknown"() {
        given: 'a weird config with the pool bean missing'
        controller.dataSource = null

        when:
        def resp = controller.ready()

        then: 'liveness stays authoritative; readiness does not false-negative'
        resp.statusCode == HttpStatus.OK
        resp.body.status == 'UP'
        resp.body.db == 'unknown'
    }

    def "cookieAware() reports UP when JDBC sessions are intentionally disabled"() {
        given:
        controller.environment = new MockEnvironment()
            .withProperty('spring.session.store-type', 'none')
        controller.dataSource = null

        when:
        def resp = controller.cookieAware()

        then:
        resp.statusCode == HttpStatus.OK
        resp.body.status == 'UP'
        resp.body.sessionStore == 'tomcat-memory'
        resp.body.probes == ['jdbc-session-disabled']
        resp.headers.getFirst('Cache-Control')?.contains('no-store')
    }

    def "cookieAware() runs both SPRING_SESSION probes when JDBC sessions are explicitly enabled"() {
        given:
        Connection conn = Mock()
        PreparedStatement goodPs = Mock()
        PreparedStatement poisonedPs = Mock()
        ResultSet goodRs = Mock()
        ResultSet poisonedRs = Mock()
        DataSource ds = Mock()
        controller.dataSource = ds
        controller.environment = new MockEnvironment()
            .withProperty('spring.session.store-type', 'jdbc')

        when:
        def resp = controller.cookieAware()

        then:
        1 * ds.getConnection() >> conn
        2 * conn.prepareStatement('SELECT 1 FROM SPRING_SESSION WHERE SESSION_ID = ?') >>> [goodPs, poisonedPs]
        1 * goodPs.setString(1, '00000000-0000-0000-0000-000000000000')
        1 * goodPs.executeQuery() >> goodRs
        1 * goodRs.next() >> false
        1 * goodRs.close()
        1 * goodPs.close()
        1 * poisonedPs.setString(1, '11111111-2222-3333-4444-555555555555')
        1 * poisonedPs.executeQuery() >> poisonedRs
        1 * poisonedRs.next() >> false
        1 * poisonedRs.close()
        1 * poisonedPs.close()
        1 * conn.close()
        resp.statusCode == HttpStatus.OK
        resp.body.status == 'UP'
        resp.body.probes == ['good-uuid', 'poisoned-uuid']
    }

    def "cookieAware() returns 503 when JDBC sessions are enabled but the session table probe throws"() {
        given:
        Connection conn = Mock()
        PreparedStatement ps = Mock()
        DataSource ds = Mock()
        controller.dataSource = ds
        controller.environment = new MockEnvironment()
            .withProperty('spring.session.store-type', 'jdbc')

        when:
        def resp = controller.cookieAware()

        then:
        1 * ds.getConnection() >> conn
        1 * conn.prepareStatement('SELECT 1 FROM SPRING_SESSION WHERE SESSION_ID = ?') >> ps
        1 * ps.setString(1, '00000000-0000-0000-0000-000000000000')
        1 * ps.executeQuery() >> { throw new SQLException('table missing') }
        1 * ps.close()
        1 * conn.close()
        resp.statusCode == HttpStatus.SERVICE_UNAVAILABLE
        resp.body.status == 'DOWN'
        resp.body.reason == 'good-uuid-threw'

        and: 'public body must NOT echo the raw JDBC exception (schema/SQLSTATE leak)'
        // The 503 body is reachable by anonymous scanners. The safe
        // hand-written `reason` code is the only failure detail allowed
        // out; the full SQLException class + message lives in the log.
        !resp.body.containsKey('exception')
        !resp.body.containsKey('message')
    }

    def "version() surfaces the injected appVersion"() {
        when:
        def resp = controller.version()

        then:
        resp.statusCode == HttpStatus.OK
        resp.body.version == '9.9.9-test'
        resp.headers.getFirst('Cache-Control')?.contains('max-age=600')
    }

    def "version() hides startupAt from anonymous callers under the hardened prod posture"() {
        given: 'verbose-errors=false — the prod default (application-prod.yml pins it)'
        controller.verboseDetails = false

        when:
        def resp = controller.version()

        then: 'public body carries ONLY version; the per-pod deploy-age fingerprint is withheld'
        resp.statusCode == HttpStatus.OK
        resp.body.version == '9.9.9-test'
        !resp.body.containsKey('startupAt')
    }

    def "version() exposes startupAt to ops when verbose detail is opted in"() {
        given: 'SECURITY_VERBOSE_ERRORS=true — incident-debug / non-prod posture'
        controller.verboseDetails = true

        when:
        def resp = controller.version()

        then: 'startupAt reappears for the status page / ops uptime display'
        resp.statusCode == HttpStatus.OK
        resp.body.version == '9.9.9-test'
        (resp.body.startupAt as long) > 0
    }
}
