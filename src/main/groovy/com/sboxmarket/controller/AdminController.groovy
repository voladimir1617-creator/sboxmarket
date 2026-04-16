package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.SboxApiService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Admin-only endpoints. Every route calls `AdminService.requireAdmin` before
 * doing anything else — the `role` column on SteamUser is the single source
 * of truth. There is no separate admin password or second login.
 */
@RestController
@RequestMapping("/api/admin")
@Slf4j
class AdminController {

    @Autowired AdminService adminService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired SboxApiService sboxApiService
    @Autowired com.sboxmarket.service.StripeService stripeService
    @Autowired com.sboxmarket.service.AuditService auditService
    @Autowired com.sboxmarket.service.AdminSimulatorService adminSimulatorService
    @Autowired com.sboxmarket.service.FraudAnalysisService fraudAnalysisService

    private Long requireAdmin(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        adminService.requireAdmin(uid)
        uid
    }

    // ── Dashboard ───────────────────────────────────────────────────

    @GetMapping("/stats")
    ResponseEntity<Map> stats(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.dashboardStats())
    }

    /** Lightweight probe the frontend uses to decide whether to show the
     *  Admin menu entry — returns {admin: true} or {admin: false}. */
    @GetMapping("/check")
    ResponseEntity<Map> check(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) return ResponseEntity.ok([admin: false])
        def user = steamUserRepository.findById(uid).orElse(null)
        ResponseEntity.ok([admin: user?.role == 'ADMIN'])
    }

    // Admin bootstrap is ONLY via the server-side env var
    // `ADMIN_BOOTSTRAP_STEAM_IDS` (comma-separated Steam64s) or by an
    // existing admin promoting another user through the Users tab.
    // There is deliberately NO self-service "claim" endpoint — exposing
    // one (even gated by "only if no admin exists") creates a first-user
    // race on every fresh deploy.

    // ── Withdrawals ─────────────────────────────────────────────────

    @GetMapping("/withdrawals")
    ResponseEntity<List<Map>> withdrawals(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listWithdrawals(status))
    }

    @PostMapping("/withdrawals/{id}/approve")
    ResponseEntity<Map> approveWithdrawal(@PathVariable Long id,
                                          @RequestBody(required = false) Map body,
                                          HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.approveWithdrawal(uid, id, body?.payoutRef as String))
    }

    @PostMapping("/withdrawals/{id}/reject")
    ResponseEntity<Map> rejectWithdrawal(@PathVariable Long id,
                                         @RequestBody(required = false) Map body,
                                         HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.rejectWithdrawal(uid, id, body?.reason as String))
    }

    // ── Users ───────────────────────────────────────────────────────

    @GetMapping("/users")
    ResponseEntity<List<SteamUser>> users(@RequestParam(required = false) String search,
                                          HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listUsers(search))
    }

    @PostMapping("/users/{id}/ban")
    ResponseEntity<SteamUser> ban(@PathVariable Long id,
                                  @RequestBody(required = false) Map body,
                                  HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.banUser(uid, id, body?.reason as String))
    }

    @PostMapping("/users/{id}/unban")
    ResponseEntity<SteamUser> unban(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.unbanUser(uid, id))
    }

    @PostMapping("/users/{id}/grant-admin")
    ResponseEntity<SteamUser> grant(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.grantAdmin(uid, id))
    }

    @PostMapping("/users/{id}/revoke-admin")
    ResponseEntity<SteamUser> revoke(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.revokeAdmin(uid, id))
    }

    @PostMapping("/users/{id}/credit")
    ResponseEntity<Map> credit(@PathVariable Long id,
                               @RequestBody Map body,
                               HttpServletRequest req) {
        def uid = requireAdmin(req)
        if (body?.amount == null) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount is required")
        }
        BigDecimal amt
        try { amt = new BigDecimal(body.amount.toString()) }
        catch (NumberFormatException ignored) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount must be a valid number")
        }
        ResponseEntity.ok(adminService.creditWallet(uid, id, amt, body.note as String))
    }

    // ── Listings moderation ─────────────────────────────────────────

    @PostMapping("/listings/{id}/remove")
    ResponseEntity<Map> removeListing(@PathVariable Long id,
                                      @RequestBody(required = false) Map body,
                                      HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.forceCancelListing(uid, id, body?.reason as String))
    }

    // ── Support ─────────────────────────────────────────────────────

    @GetMapping("/tickets")
    ResponseEntity<List<Map>> tickets(@RequestParam(required = false) String status,
                                      HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listAllTickets(status))
    }

    @GetMapping("/tickets/{id}")
    ResponseEntity<Map> ticket(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.getTicket(uid, id))
    }

    @PostMapping("/tickets/{id}/reply")
    ResponseEntity<Map> reply(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireAdmin(req)
        def msg = adminService.staffReply(uid, id, body.body as String)
        ResponseEntity.ok([id: msg.id, body: msg.body])
    }

    @PostMapping("/tickets/{id}/close")
    ResponseEntity<Map> close(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        def t = adminService.closeTicket(uid, id)
        ResponseEntity.ok([id: t.id, status: t.status])
    }

    // ── Audit log ───────────────────────────────────────────────────

    @GetMapping("/audit")
    ResponseEntity<List> audit(@RequestParam(required = false) String event,
                               @RequestParam(required = false) Long actor,
                               @RequestParam(required = false) Long subject,
                               HttpServletRequest req) {
        requireAdmin(req)
        def rows
        if (event)   rows = auditService.byEvent(event)
        else if (actor)   rows = auditService.byActor(actor)
        else if (subject) rows = auditService.bySubject(subject)
        else              rows = auditService.recent()
        ResponseEntity.ok(rows)
    }

    // ── Fraud signals ───────────────────────────────────────────────
    //
    // Read-only rollup of AuditLog rows into triage signals — multiple
    // IPs per user, shared IPs across accounts, rapid withdraw-after-
    // deposit, high-velocity purchases. No new schema; just a query over
    // the last 24h of audit history. Admin UI polls this to surface
    // actionable patterns without requiring a data-warehouse layer.

    @GetMapping("/fraud")
    ResponseEntity<List<Map>> fraud(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(fraudAnalysisService.computeSignals())
    }

    // ── Trade moderation ────────────────────────────────────────────

    @GetMapping("/trades")
    ResponseEntity<List> trades(@RequestParam(required = false, defaultValue = "ALL") String state,
                                HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listTrades(state))
    }

    @PostMapping("/trades/{id}/release")
    ResponseEntity<Map> releaseTrade(@PathVariable Long id,
                                     @RequestBody(required = false) Map body,
                                     HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.forceReleaseTrade(uid, id, body?.reason as String))
    }

    @PostMapping("/trades/{id}/cancel")
    ResponseEntity<Map> cancelTrade(@PathVariable Long id,
                                    @RequestBody(required = false) Map body,
                                    HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.forceCancelTrade(uid, id, body?.reason as String))
    }

    // ── Stripe refunds (admin-only) ─────────────────────────────────

    @PostMapping("/deposits/{id}/refund")
    ResponseEntity<Map> refundDeposit(@PathVariable Long id,
                                      @RequestBody(required = false) Map body,
                                      HttpServletRequest req) {
        requireAdmin(req)
        BigDecimal amount = null
        if (body?.amount != null) {
            try { amount = new BigDecimal(body.amount.toString()) }
            catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount must be a valid number")
            }
        }
        ResponseEntity.ok(stripeService.refundDeposit(id, amount))
    }

    // ── Simulator (seed fake listings for QA) ───────────────────────

    @PostMapping("/simulate/listings")
    ResponseEntity<Map> simulateListings(@RequestBody(required = false) Map body,
                                         HttpServletRequest req) {
        def uid = requireAdmin(req)
        int count
        try { count = (body?.count ?: 20) as int }
        catch (Exception ignored) { count = 20 }
        if (count < 1) count = 1
        if (count > 200) count = 200
        ResponseEntity.ok(adminSimulatorService.simulateListings(uid, count))
    }

    @PostMapping("/simulate/clear")
    ResponseEntity<Map> clearSimulated(HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminSimulatorService.clearSimulated(uid))
    }

    @GetMapping("/simulate/count")
    ResponseEntity<Map> countSimulated(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminSimulatorService.countSimulated())
    }

    // ── Legacy sync endpoint (kept — still used) ────────────────────

    @PostMapping("/sync-scmm")
    ResponseEntity<Map> syncScmm(HttpServletRequest req) {
        requireAdmin(req)
        try {
            def result = sboxApiService.syncFromScmm()
            ResponseEntity.ok(result)
        } catch (Exception e) {
            log.error("SCMM sync failed", e)
            ResponseEntity.status(500).body([error: 'Sync failed — check server logs'])
        }
    }
}
