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
    @Autowired com.sboxmarket.repository.ItemRepository itemRepository

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

    /** System-health snapshot — JVM memory, thread count, Hikari pool
     *  state, DB connection health, and a sprinkling of last-run
     *  metadata for the scheduled jobs. Useful for ops to eyeball
     *  "is everything humming" without jumping to a separate
     *  observability stack. Admin-gated because the counters reveal
     *  internal container state. */
    @GetMapping("/health")
    ResponseEntity<Map> systemHealth(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.systemHealth())
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

    // ── Catalogue items (staff override) ────────────────────────────

    /** Override a catalogue item's display fields without waiting for the
     *  next SCMM sync. Admins use this to fix a stale image, correct a
     *  wrong rarity classification, or manually set a price point for an
     *  item the upstream feed doesn't know about. Validates upper bounds
     *  and sanitises string inputs. */
    @PutMapping("/items/{id}")
    @org.springframework.transaction.annotation.Transactional
    ResponseEntity<Map> updateItem(@PathVariable Long id,
                                   @RequestBody Map body,
                                   HttpServletRequest req) {
        def adminUid = requireAdmin(req)
        def item = itemRepository.findById(id)
            .orElseThrow { new com.sboxmarket.exception.NotFoundException("Item", id) }
        if (body.containsKey('steamPrice')) {
            def raw = body.steamPrice
            if (raw == null || raw.toString().isEmpty()) {
                item.steamPrice = null
            } else {
                BigDecimal p
                try { p = new BigDecimal(raw.toString()) }
                catch (NumberFormatException ignored) {
                    throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE", "steamPrice must be a number")
                }
                if (p < BigDecimal.ZERO) throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE", "steamPrice must be ≥ 0")
                if (p > new BigDecimal("100000")) throw new com.sboxmarket.exception.BadRequestException("PRICE_TOO_HIGH", 'steamPrice must not exceed $100,000')
                item.steamPrice = p
            }
        }
        if (body.containsKey('rarity')) {
            def r = (body.rarity as String ?: '').trim()
            if (r.length() > 40) throw new com.sboxmarket.exception.BadRequestException("INVALID_RARITY", "rarity too long")
            item.rarity = r.isEmpty() ? 'Standard' : r
        }
        if (body.containsKey('imageUrl')) {
            def u = (body.imageUrl as String ?: '').trim()
            if (u.length() > 500) throw new com.sboxmarket.exception.BadRequestException("INVALID_URL", "imageUrl too long")
            if (u && !u.startsWith('https://')) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_URL", "imageUrl must be an https:// URL")
            }
            item.imageUrl = u.isEmpty() ? null : u
        }
        if (body.containsKey('accentColor')) {
            def c = (body.accentColor as String ?: '').trim()
            if (c && !(c ==~ /#[0-9A-Fa-f]{3,8}/)) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_COLOR", "accentColor must be a #hex string")
            }
            item.accentColor = c.isEmpty() ? null : c
        }
        itemRepository.save(item)
        auditService?.log('ITEM_EDITED', adminUid, null, item.id,
            "Admin edited ${item.name}: " + body.keySet().join(','))
        ResponseEntity.ok([
            id:          item.id,
            name:        item.name,
            rarity:      item.rarity,
            steamPrice:  item.steamPrice,
            imageUrl:    item.imageUrl,
            accentColor: item.accentColor
        ])
    }

    // ── Users ───────────────────────────────────────────────────────

    @GetMapping("/users")
    ResponseEntity<List<SteamUser>> users(@RequestParam(required = false) String search,
                                          HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listUsers(search))
    }

    /** Users CSV export — used by ops for reporting, tax auditing, and
     *  ad-hoc data pulls. Identical filter surface as /users so the CSV
     *  represents what the admin is currently looking at. No PII beyond
     *  what the admin panel already renders. */
    @GetMapping(value = "/users.csv", produces = "text/csv")
    ResponseEntity<String> usersCsv(@RequestParam(required = false) String search,
                                    HttpServletRequest req) {
        requireAdmin(req)
        def rows = adminService.listUsers(search)
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = { String v ->
            if (v == null) return ''
            if (v.contains(',') || v.contains('"') || v.contains('\n')) {
                return '"' + v.replace('"', '""') + '"'
            }
            v
        }
        def sb = new StringBuilder()
        sb.append('id,steamId64,displayName,role,banned,email,emailVerified,createdAt,lastLoginAt\n')
        rows.each { u ->
            sb.append(u.id ?: '').append(',')
              .append(esc(u.steamId64 ?: '')).append(',')
              .append(esc(u.displayName ?: '')).append(',')
              .append(esc(u.role ?: 'USER')).append(',')
              .append((u.banned ? 'true' : 'false')).append(',')
              .append(esc(u.email ?: '')).append(',')
              .append((u.emailVerified ? 'true' : 'false')).append(',')
              .append(df.format(new Date(u.createdAt ?: 0))).append(',')
              .append(df.format(new Date(u.lastLoginAt ?: 0))).append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-users-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
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

    @PostMapping("/users/{id}/grant-csr")
    ResponseEntity<SteamUser> grantCsr(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.grantCsr(uid, id))
    }

    @PostMapping("/users/{id}/revoke-csr")
    ResponseEntity<SteamUser> revokeCsr(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.revokeCsr(uid, id))
    }

    /** Admin-only 2FA reset for a locked-out user. */
    @PostMapping("/users/{id}/reset-2fa")
    ResponseEntity<Map> reset2fa(@PathVariable Long id,
                                 @RequestBody(required = false) Map body,
                                 HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.reset2faFor(uid, id, body?.note as String))
    }

    /** Users who have requested account deletion (GDPR). */
    @GetMapping("/users/deletion-requests")
    ResponseEntity<List<Map>> deletionRequests(HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.listDeletionRequests())
    }

    /** Read staff-only internal notes on a user. */
    @GetMapping("/users/{id}/notes")
    ResponseEntity<Map> readNotes(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.readAdminNotes(uid, id))
    }

    /** Write/update staff-only internal notes on a user. */
    @PutMapping("/users/{id}/notes")
    ResponseEntity<Map> writeNotes(@PathVariable Long id,
                                   @RequestBody Map body,
                                   HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.writeAdminNotes(uid, id, body?.notes as String))
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

    /** User-reported listings queue, sorted by report count DESC. */
    @GetMapping("/listings/reported")
    ResponseEntity<List<Map>> reportedListings(@RequestParam(required = false) Integer limit,
                                               HttpServletRequest req) {
        requireAdmin(req)
        ResponseEntity.ok(adminService.findReportedListings(limit ?: 50))
    }

    /** Dismiss reports without cancelling — admin reviewed and found no issue. */
    @PostMapping("/listings/{id}/dismiss-reports")
    ResponseEntity<Map> dismissReports(@PathVariable Long id,
                                       @RequestBody(required = false) Map body,
                                       HttpServletRequest req) {
        def uid = requireAdmin(req)
        ResponseEntity.ok(adminService.dismissListingReports(uid, id, body?.note as String))
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

    /** Audit log CSV export — honors the same event/actor/subject filters
     *  as the JSON endpoint. Dumps up to the last 500 rows depending on
     *  what the underlying AuditService returns. Every field is
     *  quote-escaped so commas / quotes / newlines inside the
     *  `description` column don't corrupt the file. */
    @GetMapping(value = "/audit.csv", produces = "text/csv")
    ResponseEntity<String> auditCsv(@RequestParam(required = false) String event,
                                    @RequestParam(required = false) Long actor,
                                    @RequestParam(required = false) Long subject,
                                    HttpServletRequest req) {
        requireAdmin(req)
        def rows
        if (event)        rows = auditService.byEvent(event)
        else if (actor)   rows = auditService.byActor(actor)
        else if (subject) rows = auditService.bySubject(subject)
        else              rows = auditService.recent()

        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def esc = { Object raw ->
            def v = raw == null ? '' : raw.toString()
            if (v.contains(',') || v.contains('"') || v.contains('\n')) {
                return '"' + v.replace('"', '""') + '"'
            }
            v
        }
        def sb = new StringBuilder()
        sb.append('id,createdAt,eventType,actorUserId,subjectUserId,resourceId,description,ip,userAgent\n')
        (rows ?: []).each { r ->
            sb.append(r.id ?: '').append(',')
              .append(df.format(new Date(r.createdAt ?: 0))).append(',')
              .append(esc(r.eventType)).append(',')
              .append(r.actorUserId ?: '').append(',')
              .append(r.subjectUserId ?: '').append(',')
              .append(r.resourceId ?: '').append(',')
              .append(esc(r.description)).append(',')
              .append(esc(r.ip)).append(',')
              .append(esc(r.userAgent)).append('\n')
        }
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-audit-${df.format(new Date()).replace(':','-')}.csv\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
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
