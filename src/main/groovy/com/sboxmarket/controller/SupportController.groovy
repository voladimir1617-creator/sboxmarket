package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.SupportService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/support")
@Slf4j
class SupportController {

    @Autowired SupportService supportService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired com.sboxmarket.service.security.BanGuard banGuard

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping("/tickets")
    ResponseEntity<List<SupportTicket>> list(HttpServletRequest req) {
        ResponseEntity.ok(supportService.listForUser(requireUser(req)))
    }

    @GetMapping("/tickets/{id}")
    ResponseEntity<Map> get(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(supportService.getTicket(requireUser(req), id))
    }

    @PostMapping("/tickets")
    ResponseEntity<SupportTicket> create(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        // Safe-navigate the body — an explicit JSON `null` payload parses
        // to a null Map and would otherwise NPE → 500 here. Matches the
        // body?.field convention every sibling controller already uses;
        // SupportService rejects the resulting null subject/body with a
        // clean 400 (INVALID_SUBJECT / INVALID_BODY).
        ResponseEntity.ok(supportService.create(
            uid, user.displayName ?: "Player",
            body?.subject as String,
            body?.category as String,
            body?.body as String
        ))
    }

    @PostMapping("/tickets/{id}/reply")
    ResponseEntity<SupportMessage> reply(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        ResponseEntity.ok(supportService.reply(uid, user.displayName ?: "Player", id, body?.body as String))
    }

    @PostMapping("/tickets/{id}/resolve")
    ResponseEntity<SupportTicket> resolve(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(supportService.resolve(requireUser(req), id))
    }

    /**
     * Re-open a RESOLVED ticket (batch 858). Before this endpoint a user
     * with follow-up had to open a whole new ticket, losing the thread
     * context that staff had already built. Now a one-click reopen
     * flips status to WAITING_STAFF so the same thread continues —
     * matches CSFloat's explicit "Reopen" affordance.
     */
    @PostMapping("/tickets/{id}/reopen")
    ResponseEntity<SupportTicket> reopen(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(supportService.reopen(requireUser(req), id))
    }

    /**
     * User-on-user report. Opens a FRAUD-category support ticket so
     * the CSR queue picks it up alongside other investigations. The
     * body is auto-generated from the form fields so every report has
     * a consistent shape staff can triage at a glance. Cannot report
     * yourself — returns 400 SELF_REPORT.
     */
    @PostMapping("/report-user/{targetUserId}")
    ResponseEntity<SupportTicket> reportUser(@PathVariable Long targetUserId,
                                             @RequestBody Map body,
                                             HttpServletRequest req) {
        def uid = requireUser(req)
        if (uid == targetUserId) {
            throw new com.sboxmarket.exception.BadRequestException("SELF_REPORT",
                "You can't report yourself")
        }
        // Banned users can still log in to appeal, but they must not be
        // able to spam FRAUD reports — each one fans a notification to
        // every admin/CSR. Gate ONLY this endpoint; a banned user must
        // still be able to file/reply to a normal ticket to appeal.
        banGuard.assertNotBanned(uid)
        def target = steamUserRepository.findById(targetUserId)
            .orElseThrow { new com.sboxmarket.exception.NotFoundException("SteamUser", targetUserId) }
        def me = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def reason = (body?.reason as String ?: 'Not specified').take(80)
        def context = (body?.context as String ?: '').take(1000)
        def subject = "Report of ${target.displayName ?: 'user'} (#${targetUserId}) — ${reason}"
        def ticketBody = """Reporter: ${me.displayName ?: 'user'} (#${uid})
Target:   ${target.displayName ?: 'user'} (#${targetUserId}) Steam ID ${target.steamId64}
Reason:   ${reason}

Context (reporter-provided):
${context.isEmpty() ? '(none)' : context}
"""
        ResponseEntity.ok(supportService.create(
            uid, me.displayName ?: "Player", subject, 'FRAUD', ticketBody
        ))
    }
}
