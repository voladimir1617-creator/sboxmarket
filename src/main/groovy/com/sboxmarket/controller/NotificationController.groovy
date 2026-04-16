package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Notification
import com.sboxmarket.service.NotificationService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/notifications")
@Slf4j
class NotificationController {

    @Autowired NotificationService notificationService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<Map> list(HttpServletRequest req) {
        def uid = requireUser(req)
        ResponseEntity.ok([
            items: notificationService.listFor(uid),
            unread: notificationService.countUnread(uid)
        ])
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Map> read(@PathVariable Long id, HttpServletRequest req) {
        notificationService.markRead(requireUser(req), id)
        ResponseEntity.ok([ok: true])
    }

    @PostMapping("/read-all")
    ResponseEntity<Map> readAll(HttpServletRequest req) {
        notificationService.markAllRead(requireUser(req))
        ResponseEntity.ok([ok: true])
    }
}
