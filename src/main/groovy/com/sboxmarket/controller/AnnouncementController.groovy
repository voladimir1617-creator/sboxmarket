package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Announcement
import com.sboxmarket.service.AnnouncementService
import com.sboxmarket.service.security.AdminAuthorization
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Sitewide announcement banner endpoints.
 *
 * Public:
 *   GET  /api/announcement            — current banner or null
 *
 * Admin (requires ADMIN role):
 *   GET    /api/admin/announcements        — list everything (history)
 *   POST   /api/admin/announcements        — create a new live banner
 *   DELETE /api/admin/announcements/{id}   — deactivate a banner
 */
@RestController
@Slf4j
class AnnouncementController {

    @Autowired AnnouncementService announcementService
    @Autowired AdminAuthorization adminAuthorization

    private Long requireAdminUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        adminAuthorization.requireAdmin(uid)
        uid
    }

    @GetMapping("/api/announcement")
    ResponseEntity<Map> current() {
        def row = announcementService.current()
        // Batch 820 — `public, max-age=30`. Announcements are global
        // and change rarely (admin posts a banner, users see it).
        // A CDN-shared 30s cache absorbs the homepage poll storm
        // (every client ticks this every 2 minutes) without keeping
        // a stale banner around after deactivation. Safe to share
        // because the response has no viewer-specific fields.
        def cache = 'public, max-age=30'
        if (row == null) {
            return ResponseEntity.ok()
                .header('Cache-Control', cache)
                .body([announcement: null])
        }
        ResponseEntity.ok()
            .header('Cache-Control', cache)
            .body([
                announcement: [
                    id:        row.id,
                    message:   row.message,
                    severity:  row.severity,
                    createdAt: row.createdAt,
                    expiresAt: row.expiresAt
                ]
            ])
    }

    @GetMapping("/api/admin/announcements")
    ResponseEntity<List<Announcement>> listAll(HttpServletRequest req) {
        requireAdminUser(req)
        ResponseEntity.ok(announcementService.listAll())
    }

    @PostMapping("/api/admin/announcements")
    ResponseEntity<Announcement> create(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireAdminUser(req)
        Long expiresAt = null
        if (body?.expiresAt != null) {
            try { expiresAt = Long.valueOf(body.expiresAt.toString()) }
            catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException(
                    "INVALID_EXPIRES_AT", "expiresAt must be a millisecond timestamp")
            }
        }
        def row = announcementService.create(uid, body?.message as String,
            body?.severity as String, expiresAt)
        ResponseEntity.ok(row)
    }

    @DeleteMapping("/api/admin/announcements/{id}")
    ResponseEntity<Announcement> deactivate(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireAdminUser(req)
        ResponseEntity.ok(announcementService.deactivate(uid, id))
    }
}
