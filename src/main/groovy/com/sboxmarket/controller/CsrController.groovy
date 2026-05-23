package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.service.CsrService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Customer Service Representative endpoints. Gated by `CsrService.requireCsr`,
 * which accepts either CSR or ADMIN roles. Admins inherit every CSR capability
 * so there's no need to hit both `/api/csr/*` and `/api/admin/*`.
 *
 * Deliberately lives in a separate controller from AdminController so the
 * two surfaces can be versioned, rate-limited, or firewalled independently.
 */
@RestController
@RequestMapping("/api/csr")
@Slf4j
class CsrController {

    @Autowired CsrService csrService

    private Long requireCsr(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        csrService.requireCsr(uid)
        uid
    }

    // ── Dashboard + role check ─────────────────────────────────────

    /** Frontend probe — shows/hides the CSR menu entry. Never 403s. */
    @GetMapping("/check")
    ResponseEntity<Map> check(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        ResponseEntity.ok([csr: csrService.isCsr(uid)])
    }

    @GetMapping("/stats")
    ResponseEntity<Map> stats(HttpServletRequest req) {
        requireCsr(req)
        ResponseEntity.ok(csrService.dashboardStats())
    }

    // ── User lookup ────────────────────────────────────────────────

    @GetMapping("/users/lookup")
    ResponseEntity<Map> lookup(@RequestParam String q, HttpServletRequest req) {
        requireCsr(req)
        ResponseEntity.ok(csrService.lookupUser(q))
    }

    // ── Tickets ────────────────────────────────────────────────────

    @GetMapping("/tickets")
    ResponseEntity<List<Map>> tickets(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String search,
                                      HttpServletRequest req) {
        requireCsr(req)
        ResponseEntity.ok(csrService.listTickets(status, search))
    }

    @GetMapping("/tickets/{id}")
    ResponseEntity<Map> getTicket(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireCsr(req)
        ResponseEntity.ok(csrService.getTicket(uid, id))
    }

    @PostMapping("/tickets/{id}/reply")
    ResponseEntity<Map> reply(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireCsr(req)
        // Upstream cap — CsrService.reply runs body through the support
        // multiline sanitizer (2000-char truncation). Reject overlong
        // payloads at the boundary so the CSR endpoint can't be used to
        // burn parser memory on multi-MB inputs.
        com.sboxmarket.util.InputLimits.requireMax(body, 'body',
            com.sboxmarket.util.InputLimits.LONG_TEXT,
            'BODY_TOO_LONG', 'body')
        def msg = csrService.reply(uid, id, body.body as String)
        ResponseEntity.ok([id: msg.id, body: msg.body])
    }

    @PostMapping("/tickets/{id}/close")
    ResponseEntity<Map> close(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireCsr(req)
        def t = csrService.close(uid, id)
        ResponseEntity.ok([id: t.id, status: t.status])
    }

    // ── Goodwill credit ────────────────────────────────────────────

    @PostMapping("/users/{id}/goodwill")
    ResponseEntity<Map> goodwill(@PathVariable Long id,
                                 @RequestBody Map body,
                                 HttpServletRequest req) {
        def uid = requireCsr(req)
        if (body?.amount == null) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount is required")
        }
        BigDecimal amount
        try { amount = new BigDecimal(body.amount.toString()) }
        catch (NumberFormatException ignored) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount must be a valid number")
        }
        // Upstream cap on the audit note — CsrService.issueGoodwillCredit
        // runs note through textSanitizer.medium (500-char truncation)
        // and requires it. Reject obviously oversized notes at the door
        // so a CSR session can't be used to flood the audit log with
        // megabytes of fluff per goodwill grant.
        com.sboxmarket.util.InputLimits.requireMax(body, 'note',
            com.sboxmarket.util.InputLimits.MEDIUM_TEXT,
            'NOTE_TOO_LONG', 'note')
        ResponseEntity.ok(csrService.issueGoodwillCredit(uid, id, amount, body.note as String))
    }

    // ── Flag listing for admin review ──────────────────────────────

    @PostMapping("/listings/{id}/flag")
    ResponseEntity<Map> flag(@PathVariable Long id,
                             @RequestBody(required = false) Map body,
                             HttpServletRequest req) {
        def uid = requireCsr(req)
        // Upstream cap — CsrService.flagListing runs reason through
        // textSanitizer.cleanShort (80-char truncation). Reject obviously
        // oversized inputs at the door before they hit the sanitizer.
        com.sboxmarket.util.InputLimits.requireMax(body, 'reason',
            com.sboxmarket.util.InputLimits.SHORT_LABEL,
            'REASON_TOO_LONG', 'reason')
        ResponseEntity.ok(csrService.flagListing(uid, id, body?.reason as String))
    }
}
