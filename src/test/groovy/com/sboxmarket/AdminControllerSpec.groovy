package com.sboxmarket

import com.sboxmarket.controller.AdminController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.StripeService
import com.sboxmarket.repository.SteamUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * HTTP-adapter coverage for the admin staff panel.
 *
 * Two invariants this spec pins:
 *
 *   1. EVERY endpoint is admin-gated. The private `requireAdmin(req)`
 *      helper 401s an anonymous caller (no session uid) and delegates
 *      the role check to `AdminService.requireAdmin(uid)`, which throws
 *      `ForbiddenException` for a signed-in non-admin. `/check` is the
 *      single deliberate exception — a public probe the frontend uses
 *      to decide whether to render the Admin menu entry; it returns
 *      `{admin:false}` for anon / non-admin instead of throwing.
 *
 *   2. `/deposits/{id}/refund` translates the boundary exceptions from
 *      StripeService. `StripeService.refundDeposit` throws a plain
 *      `java.util.NoSuchElementException` for a missing deposit tx (or
 *      its wallet) — a type with NO GlobalExceptionHandler mapping, so
 *      before this fix a wrong id surfaced as a 500 INTERNAL_ERROR. The
 *      controller now maps it to 404, and the IllegalState/IllegalArgument
 *      "bad refund" signals to structured 400s.
 */
class AdminControllerSpec extends Specification {

    AdminService         adminService        = Mock()
    SteamUserRepository  steamUserRepository = Mock()
    StripeService        stripeService       = Mock()

    @Subject
    AdminController controller = new AdminController(
        adminService:        adminService,
        steamUserRepository: steamUserRepository,
        stripeService:       stripeService)

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    /** Anonymous caller — no uid on the session. */
    private void anonSession() {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
    }

    /** Signed-in admin — `requireAdmin` passes silently. */
    private void adminSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
        1 * adminService.requireAdmin(uid)
    }

    /** Signed-in but NOT an admin — service rejects with ForbiddenException. */
    private void nonAdminSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
        1 * adminService.requireAdmin(uid) >> { throw new ForbiddenException("Admin privileges required") }
    }

    // ── /check is the ONLY endpoint that never 401s ─────────────────

    def "check() returns {admin:false} for an anonymous caller — never throws"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        def resp = controller.check(req)

        then: 'the public probe never calls the role guard'
        0 * adminService.requireAdmin(_)
        resp.statusCode.value() == 200
        resp.body == [admin: false]
    }

    def "check() returns {admin:false} for a signed-in non-admin"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.of(new SteamUser(id: 100L, role: 'USER'))

        when:
        def resp = controller.check(req)

        then:
        0 * adminService.requireAdmin(_)
        resp.body == [admin: false]
    }

    def "check() returns {admin:true} for an actual admin"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 100L
        1 * steamUserRepository.findById(100L) >> Optional.of(new SteamUser(id: 100L, role: 'ADMIN'))

        when:
        def resp = controller.check(req)

        then:
        resp.body == [admin: true]
    }

    def "check() tolerates a stale session whose user row is gone"() {
        given:
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 999L
        1 * steamUserRepository.findById(999L) >> Optional.empty()

        when:
        def resp = controller.check(req)

        then: 'null-safe — no NPE, just {admin:false}'
        resp.body == [admin: false]
    }

    // ── Read endpoints are admin-gated ──────────────────────────────

    def "stats() 401s an anonymous caller before touching the service"() {
        given: anonSession()
        when:  controller.stats(req)
        then:  thrown(UnauthorizedException)
        0 * adminService.requireAdmin(_)
        0 * adminService.dashboardStats()
    }

    def "stats() 403s a signed-in non-admin"() {
        given: nonAdminSession()
        when:  controller.stats(req)
        then:  thrown(ForbiddenException)
        0 * adminService.dashboardStats()
    }

    def "stats() returns the service snapshot for an admin"() {
        given:
        def snap = [users: 12, openTickets: 3]
        adminSession()
        1 * adminService.dashboardStats() >> snap

        when:
        def resp = controller.stats(req)

        then:
        resp.body.is(snap)
    }

    def "systemHealth() is admin-gated"() {
        given: anonSession()
        when:  controller.systemHealth(req)
        then:  thrown(UnauthorizedException)
        0 * adminService.systemHealth()
    }

    def "users() 401s an anonymous caller"() {
        given: anonSession()
        when:  controller.users('q', null, null, req)
        then:  thrown(UnauthorizedException)
        0 * adminService.listUsers(_, _, _)
    }

    def "users() 403s a signed-in non-admin"() {
        given: nonAdminSession()
        when:  controller.users('q', null, null, req)
        then:  thrown(ForbiddenException)
        0 * adminService.listUsers(_, _, _)
    }

    def "users() forwards every filter to the service for an admin"() {
        given:
        def rows = [new SteamUser(id: 1L)]
        adminSession()
        1 * adminService.listUsers('alice', 'ADMIN', true) >> rows

        when:
        def resp = controller.users('alice', 'ADMIN', true, req)

        then:
        resp.body.is(rows)
    }

    def "withdrawals() defaults nothing — passes the status straight through"() {
        given:
        adminSession()
        1 * adminService.listWithdrawals('PENDING') >> []

        when:
        controller.withdrawals('PENDING', req)

        then:
        true
    }

    def "audit() 403s a non-admin before any AuditService call"() {
        given: nonAdminSession()
        when:  controller.audit(null, null, null, null, req)
        then:  thrown(ForbiddenException)
    }

    // ── Destructive endpoints are admin-gated + forward sanitized args ──

    def "ban() 401s anon — no ban side effect"() {
        given: anonSession()
        when:  controller.ban(7L, [reason: 'fraud'], req)
        then:  thrown(UnauthorizedException)
        0 * adminService.banUser(*_)
    }

    def "ban() 403s a non-admin — no ban side effect"() {
        given: nonAdminSession()
        when:  controller.ban(7L, [reason: 'fraud'], req)
        then:  thrown(ForbiddenException)
        0 * adminService.banUser(*_)
    }

    def "ban() forwards admin uid, target id and reason to the service"() {
        given:
        def banned = new SteamUser(id: 7L, role: 'USER')
        adminSession(100L)
        1 * adminService.banUser(100L, 7L, 'chargeback fraud') >> banned

        when:
        def resp = controller.ban(7L, [reason: 'chargeback fraud'], req)

        then:
        resp.body.is(banned)
    }

    def "ban() tolerates a null body — reason forwarded as null"() {
        given:
        adminSession(100L)
        1 * adminService.banUser(100L, 7L, null) >> new SteamUser(id: 7L)

        when:
        controller.ban(7L, null, req)

        then:
        true
    }

    def "credit() requires the admin gate before parsing the amount"() {
        given: anonSession()
        when:  controller.credit(7L, [amount: '50'], req)
        then:  thrown(UnauthorizedException)
        0 * adminService.creditWallet(*_)
    }

    def "credit() rejects a missing amount with a structured 400"() {
        given: adminSession(100L)

        when:
        controller.credit(7L, [note: 'goodwill'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        e.message.contains('required')
        0 * adminService.creditWallet(*_)
    }

    def "credit() rejects a non-numeric amount with a structured 400"() {
        given: adminSession(100L)

        when:
        controller.credit(7L, [amount: 'not-a-number', note: 'x'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        e.message.contains('valid number')
        0 * adminService.creditWallet(*_)
    }

    def "credit() parses a stringified BigDecimal and forwards it"() {
        given:
        def result = [walletId: 9L, newBalance: new BigDecimal('75.00')]
        adminSession(100L)
        1 * adminService.creditWallet(100L, 7L, new BigDecimal('25.00'), 'goodwill') >> result

        when:
        def resp = controller.credit(7L, [amount: '25.00', note: 'goodwill'], req)

        then:
        resp.body.is(result)
    }

    def "freeze() rejects a blank reason with REASON_REQUIRED"() {
        given: adminSession(100L)

        when:
        controller.freeze(7L, [reason: '   '], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'REASON_REQUIRED'
        0 * adminService.freezeWallet(*_)
    }

    def "freeze() forwards a trimmed reason to the service"() {
        given:
        adminSession(100L)
        1 * adminService.freezeWallet(100L, 7L, 'regulatory hold') >> [frozen: true]

        when:
        controller.freeze(7L, [reason: '  regulatory hold  '], req)

        then:
        true
    }

    def "removeListing() is admin-gated and forwards the reason"() {
        given:
        adminSession(100L)
        1 * adminService.forceCancelListing(100L, 5L, 'counterfeit') >> [id: 5L]

        when:
        def resp = controller.removeListing(5L, [reason: 'counterfeit'], req)

        then:
        resp.body == [id: 5L]
    }

    def "removeListing() 403s a non-admin — no force-cancel"() {
        given: nonAdminSession()
        when:  controller.removeListing(5L, [reason: 'x'], req)
        then:  thrown(ForbiddenException)
        0 * adminService.forceCancelListing(*_)
    }

    // ── /deposits/{id}/refund — Stripe boundary exception mapping ────

    def "refundDeposit() is admin-gated before touching Stripe"() {
        given: anonSession()
        when:  controller.refundDeposit(3L, null, req)
        then:  thrown(UnauthorizedException)
        0 * stripeService.refundDeposit(*_)
    }

    def "refundDeposit() 403s a non-admin before touching Stripe"() {
        given: nonAdminSession()
        when:  controller.refundDeposit(3L, null, req)
        then:  thrown(ForbiddenException)
        0 * stripeService.refundDeposit(*_)
    }

    def "refundDeposit() rejects a non-numeric amount with a structured 400"() {
        given: adminSession(100L)

        when:
        controller.refundDeposit(3L, [amount: 'abc'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
        0 * stripeService.refundDeposit(*_)
    }

    def "refundDeposit() forwards a full refund (null amount) to Stripe"() {
        given:
        def result = [refunded: new BigDecimal('40.00')]
        adminSession(100L)
        1 * stripeService.refundDeposit(3L, null) >> result

        when:
        def resp = controller.refundDeposit(3L, null, req)

        then:
        resp.body.is(result)
    }

    def "refundDeposit() forwards a partial refund amount to Stripe"() {
        given:
        adminSession(100L)
        1 * stripeService.refundDeposit(3L, new BigDecimal('10.00')) >> [refunded: new BigDecimal('10.00')]

        when:
        controller.refundDeposit(3L, [amount: '10.00'], req)

        then:
        true
    }

    def "refundDeposit() maps a missing deposit tx (NoSuchElementException) to 404 — not 500"() {
        given:
        adminSession(100L)
        1 * stripeService.refundDeposit(404L, null) >> {
            throw new NoSuchElementException("Transaction 404 not found")
        }

        when:
        controller.refundDeposit(404L, null, req)

        then: 'translated at the boundary — bubbling raw would have been a 500'
        def e = thrown(NotFoundException)
        e.code == 'NOT_FOUND'
    }

    def "refundDeposit() maps an IllegalStateException to a CANNOT_REFUND 400"() {
        given:
        adminSession(100L)
        1 * stripeService.refundDeposit(3L, null) >> {
            throw new IllegalStateException("Only completed deposits can be refunded")
        }

        when:
        controller.refundDeposit(3L, null, req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'CANNOT_REFUND'
    }

    def "refundDeposit() maps an IllegalArgumentException to an INVALID_AMOUNT 400"() {
        given:
        adminSession(100L)
        1 * stripeService.refundDeposit(3L, new BigDecimal('999.00')) >> {
            throw new IllegalArgumentException("Refund amount must be between 0 and \$40")
        }

        when:
        controller.refundDeposit(3L, [amount: '999.00'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
    }

    // ── grant/revoke admin are themselves admin-gated ───────────────

    def "grant() 403s a non-admin — privilege escalation blocked"() {
        given: nonAdminSession()
        when:  controller.grant(7L, req)
        then:  thrown(ForbiddenException)
        0 * adminService.grantAdmin(*_)
    }

    def "grant() forwards admin uid + target id for an admin caller"() {
        given:
        def promoted = new SteamUser(id: 7L, role: 'ADMIN')
        adminSession(100L)
        1 * adminService.grantAdmin(100L, 7L) >> promoted

        when:
        def resp = controller.grant(7L, req)

        then:
        resp.body.is(promoted)
    }

    def "revoke() 401s an anonymous caller"() {
        given: anonSession()
        when:  controller.revoke(7L, req)
        then:  thrown(UnauthorizedException)
        0 * adminService.revokeAdmin(*_)
    }

    // ── force-logout projection ─────────────────────────────────────

    def "forceLogout() returns the {id, sessionEpoch} projection"() {
        given:
        adminSession(100L)
        1 * adminService.forceLogout(100L, 7L) >> new SteamUser(id: 7L, sessionEpoch: 12345L)

        when:
        def resp = controller.forceLogout(7L, req)

        then:
        resp.body == [id: 7L, sessionEpoch: 12345L]
    }

    def "forceLogout() 403s a non-admin"() {
        given: nonAdminSession()
        when:  controller.forceLogout(7L, req)
        then:  thrown(ForbiddenException)
        0 * adminService.forceLogout(*_)
    }
}
