package com.sboxmarket

import com.sboxmarket.controller.AnnouncementController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Announcement
import com.sboxmarket.service.AnnouncementService
import com.sboxmarket.service.security.AdminAuthorization
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Direct coverage for the sitewide announcement banner endpoints. The
 * public `current()` must be cache-safe (shared 30s CDN cache, no
 * viewer-specific fields) and return `{announcement: null}` on empty
 * state. Admin endpoints must gate on session + ADMIN role; bad
 * `expiresAt` input must surface a BadRequestException rather than
 * silent drop or a 500.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class AnnouncementControllerSpec extends Specification {

    AnnouncementService  announcementService = Mock()
    AdminAuthorization   adminAuthorization  = Mock()

    @Subject
    AnnouncementController controller = new AnnouncementController(
        announcementService: announcementService,
        adminAuthorization : adminAuthorization
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    def "current() returns {announcement: null} when no banner is live"() {
        when:
        def resp = controller.current()

        then:
        1 * announcementService.current() >> null
        resp.statusCode.value() == 200
        resp.body.announcement == null
        resp.headers.getFirst('Cache-Control')?.contains('max-age=30')
    }

    def "current() projects the live banner to the public shape (no extra fields)"() {
        given:
        def row = new Announcement(
            id: 7L,
            message: 'Scheduled maintenance at 03:00 UTC',
            severity: 'WARN',
            createdAt: 1700_000_000_000L,
            expiresAt: 1800_000_000_000L,
            // Fields that must NOT leak into the public response:
            active: true,
            createdByUserId: 42L
        )

        when:
        def resp = controller.current()

        then:
        1 * announcementService.current() >> row
        def a = resp.body.announcement
        a.id == 7L
        a.message == 'Scheduled maintenance at 03:00 UTC'
        a.severity == 'WARN'
        a.createdAt == 1700_000_000_000L
        a.expiresAt == 1800_000_000_000L

        and: 'only whitelisted fields are exposed — no active / createdByUserId'
        a.keySet() == ['id','message','severity','createdAt','expiresAt'] as Set

        and: 'shared cache header is set for CDN fan-out'
        resp.headers.getFirst('Cache-Control')?.contains('max-age=30')
        resp.headers.getFirst('Cache-Control')?.contains('public')
    }

    def "listAll() throws UnauthorizedException when there's no session user"() {
        when:
        controller.listAll(req)

        then:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        thrown(UnauthorizedException)
        0 * adminAuthorization.requireAdmin(_)
        0 * announcementService.listAll()
    }

    def "listAll() gates on ADMIN role via AdminAuthorization"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L) >> { throw new UnauthorizedException('not admin') }

        when:
        controller.listAll(req)

        then:
        thrown(UnauthorizedException)
        0 * announcementService.listAll()
    }

    def "listAll() returns the service's history once ADMIN check passes"() {
        given:
        def rows = [new Announcement(id: 1L), new Announcement(id: 2L)]

        when:
        def resp = controller.listAll(req)

        then:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)
        1 * announcementService.listAll() >> rows
        resp.statusCode.value() == 200
        resp.body == rows
    }

    def "create() rejects a non-numeric expiresAt with INVALID_EXPIRES_AT"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)

        when:
        controller.create([message: 'x', severity: 'INFO', expiresAt: 'tomorrow'], req)

        then: 'BadRequestException carries a stable code the client can render'
        BadRequestException e = thrown()
        e.code == 'INVALID_EXPIRES_AT'
        0 * announcementService.create(_, _, _, _)
    }

    def "create() with a numeric expiresAt delegates to the service"() {
        given:
        def row = new Announcement(id: 9L, message: 'x', severity: 'INFO')
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)

        when:
        def resp = controller.create([message: 'x', severity: 'INFO', expiresAt: 1700_000_000_000L], req)

        then:
        1 * announcementService.create(100L, 'x', 'INFO', 1700_000_000_000L) >> row
        resp.body.is(row)
    }

    def "create() with null expiresAt delegates cleanly (banner never expires)"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)

        when:
        controller.create([message: 'Welcome back', severity: 'INFO'], req)

        then:
        1 * announcementService.create(100L, 'Welcome back', 'INFO', null) >> new Announcement()
    }

    def "create() throws UnauthorizedException for an anonymous caller — no banner is posted"() {
        when:
        controller.create([message: 'spam', severity: 'INFO'], req)

        then:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        thrown(UnauthorizedException)
        0 * adminAuthorization.requireAdmin(_)
        0 * announcementService.create(_, _, _, _)
    }

    def "create() is blocked for a signed-in NON-admin user"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L) >> { throw new UnauthorizedException('not admin') }

        when:
        controller.create([message: 'I am not an admin', severity: 'INFO'], req)

        then: 'the ADMIN gate fires before the service is ever touched'
        thrown(UnauthorizedException)
        0 * announcementService.create(_, _, _, _)
    }

    def "create() handles a missing body without an NPE — passes nulls through to the service"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)

        when: 'body is null — the ?. navigation must not throw'
        controller.create(null, req)

        then: 'the service is reached with null message/severity; it will 400 there'
        1 * announcementService.create(100L, null, null, null) >> new Announcement()
        noExceptionThrown()
    }

    def "deactivate() passes the uid + banner id through after ADMIN check"() {
        given:
        def row = new Announcement(id: 5L, active: Boolean.FALSE)

        when:
        def resp = controller.deactivate(5L, req)

        then:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)
        1 * announcementService.deactivate(100L, 5L) >> row
        resp.body.is(row)
    }

    def "deactivate() throws UnauthorizedException for an anonymous caller"() {
        when:
        controller.deactivate(5L, req)

        then:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
        thrown(UnauthorizedException)
        0 * adminAuthorization.requireAdmin(_)
        0 * announcementService.deactivate(_, _)
    }

    def "deactivate() is blocked for a signed-in NON-admin user"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L) >> { throw new UnauthorizedException('not admin') }

        when:
        controller.deactivate(5L, req)

        then: 'a non-admin cannot pull down a live banner'
        thrown(UnauthorizedException)
        0 * announcementService.deactivate(_, _)
    }

    // ── real-exception authorization contract (regression) ───────────
    //
    // The tests above simulate a denied admin check by throwing
    // UnauthorizedException from the AdminAuthorization mock. The REAL
    // AdminAuthorization.requireAdmin throws ForbiddenException (→ HTTP
    // 403) for a signed-in non-admin and for an unknown user. These
    // regression tests pin that genuine contract: the controller must
    // NOT catch/swallow the service authz exception — it propagates so
    // GlobalExceptionHandler maps it to 403. If a future refactor wraps
    // requireAdminUser in a try/catch and lets the request through,
    // these fail.

    def "listAll() propagates the REAL ForbiddenException (HTTP 403) for a non-admin"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L) >> { throw new ForbiddenException('Admin privileges required') }

        when:
        controller.listAll(req)

        then: 'admin gate fires before the service; 403 is surfaced unmodified'
        thrown(ForbiddenException)
        0 * announcementService.listAll()
    }

    def "create() propagates the REAL ForbiddenException (HTTP 403) for a non-admin — no banner posted"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L) >> { throw new ForbiddenException('Admin privileges required') }

        when:
        controller.create([message: 'unauthorized banner', severity: 'CRITICAL'], req)

        then: 'a signed-in non-admin cannot create a sitewide banner'
        thrown(ForbiddenException)
        0 * announcementService.create(_, _, _, _)
    }

    def "deactivate() propagates the REAL ForbiddenException (HTTP 403) for a non-admin"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L) >> { throw new ForbiddenException('Admin privileges required') }

        when:
        controller.deactivate(5L, req)

        then: 'a signed-in non-admin cannot pull down a live banner'
        thrown(ForbiddenException)
        0 * announcementService.deactivate(_, _)
    }

    // ── domain-error pass-through (regression) ───────────────────────

    def "deactivate() propagates a NotFoundException for an unknown banner id"() {
        given: 'admin check passes, but the service cannot find the row'
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)
        1 * announcementService.deactivate(100L, 999L) >> { throw new NotFoundException('Announcement', 999L) }

        when:
        controller.deactivate(999L, req)

        then: 'the controller does not swallow the domain 404 — it reaches the handler'
        thrown(NotFoundException)
    }

    def "create() propagates the service BadRequestException for an empty/too-short message"() {
        given: 'admin check passes; the service rejects the sanitized message'
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)
        1 * announcementService.create(100L, 'ab', 'INFO', null) >> {
            throw new BadRequestException('INVALID_MESSAGE', 'Message must be at least 3 characters')
        }

        when:
        controller.create([message: 'ab', severity: 'INFO'], req)

        then: 'the structured 400 from the service is surfaced unmodified — not a 500'
        BadRequestException e = thrown()
        e.code == 'INVALID_MESSAGE'
    }

    // ── expiresAt input-validation hardening (regression) ────────────

    def "create() rejects a non-numeric-stringy expiresAt with INVALID_EXPIRES_AT — never reaches the service"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)

        when: 'expiresAt is a JSON value whose toString() is not a valid millisecond Long'
        controller.create([message: 'Maintenance window', severity: 'INFO', expiresAt: badValue], req)

        then: 'a structured 400 is thrown before the service is touched — no 500'
        BadRequestException e = thrown()
        e.code == 'INVALID_EXPIRES_AT'
        0 * announcementService.create(_, _, _, _)

        where: 'malformed expiresAt shapes a direct API caller could send'
        badValue << ['tomorrow', 'true', '12.5', '', '   ', [nested: 1], [1, 2], 'NaN']
    }

    def "create() accepts a numeric expiresAt supplied as a string and coerces it to Long"() {
        given: 'a direct API caller can send the timestamp as a JSON string'
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * adminAuthorization.requireAdmin(100L)

        when:
        controller.create([message: 'Maintenance window', severity: 'INFO',
                           expiresAt: '1700000000000'], req)

        then: 'string-form numeric expiresAt is parsed and forwarded as a Long'
        1 * announcementService.create(100L, 'Maintenance window', 'INFO', 1700000000000L) >> new Announcement()
    }

    def "current() still sets the shared CDN cache header on the empty-state response"() {
        when:
        def resp = controller.current()

        then: 'an absent banner is still cacheable — public, max-age=30'
        1 * announcementService.current() >> null
        resp.headers.getFirst('Cache-Control')?.contains('public')
        resp.headers.getFirst('Cache-Control')?.contains('max-age=30')
    }
}
