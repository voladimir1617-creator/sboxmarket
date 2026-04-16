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
        ResponseEntity.ok(supportService.create(
            uid, user.displayName ?: "Player",
            body.subject as String,
            body.category as String,
            body.body as String
        ))
    }

    @PostMapping("/tickets/{id}/reply")
    ResponseEntity<SupportMessage> reply(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        ResponseEntity.ok(supportService.reply(uid, user.displayName ?: "Player", id, body.body as String))
    }

    @PostMapping("/tickets/{id}/resolve")
    ResponseEntity<SupportTicket> resolve(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(supportService.resolve(requireUser(req), id))
    }
}
