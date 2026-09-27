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

    /**
     * Controller-side input cap for `/read-batch` and `/delete-batch`.
     * Matches the service-side cap in NotificationService.markReadByIds
     * / deleteReadByIds — we trim the raw collection BEFORE parsing
     * each token into a Long so a hostile client posting a 2MB JSON
     * array (~250k numeric ids — under BodySizeLimitFilter's 2MB cap)
     * can't make us do 500x more Long.valueOf parses + a
     * findAll/unique double-pass over the entire 250k list inside the
     * service before its own `.take(500)` finally lands. We only ever
     * use the first 500 ids regardless, so dropping the rest at the
     * door is the cheapest correct thing to do.
     */
    static final int MAX_BATCH_IDS = 500

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<Map> list(HttpServletRequest req,
                              @RequestParam(required = false) Integer limit) {
        def uid = requireUser(req)
        // Batch 1026 — optional `?limit=N` (1..100) param lets the nav
        // bell dropdown request only 12 rows instead of 100, shaving
        // ~85% off the payload for the dropdown's single code path.
        // Omitted / invalid falls through to the original 100-row cap
        // so the full /notifications modal page keeps its rich view.
        int cap = limit != null ? Math.min(Math.max(limit.intValue(), 1), 100) : 100
        ResponseEntity.ok([
            items: notificationService.listFor(uid, cap),
            unread: notificationService.countUnread(uid)
        ])
    }

    /** Cheap unread-count for the 25-second nav-bell poll. Previously the
     *  bell hit `/api/notifications` every 25s which returned the full
     *  100-row item list on every tick — 100 rows × every 25 seconds per
     *  signed-in user. Users never saw those rows until they clicked the
     *  bell. Now the bell polls this endpoint (single indexed COUNT),
     *  and the dropdown fetches the full list lazily on click. */
    @GetMapping('/unread-count')
    ResponseEntity<Map> unreadCount(HttpServletRequest req) {
        def uid = requireUser(req)
        ResponseEntity.ok([unread: notificationService.countUnread(uid)])
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Map> read(@PathVariable Long id, HttpServletRequest req) {
        notificationService.markRead(requireUser(req), id)
        ResponseEntity.ok([ok: true])
    }

    /** Flip a notification back to unread — "I'll deal with this later"
     *  without losing the row. Batch 365. */
    @PostMapping("/{id}/unread")
    ResponseEntity<Map> unread(@PathVariable Long id, HttpServletRequest req) {
        notificationService.markUnread(requireUser(req), id)
        ResponseEntity.ok([ok: true])
    }

    @PostMapping("/read-all")
    ResponseEntity<Map> readAll(HttpServletRequest req) {
        notificationService.markAllRead(requireUser(req))
        ResponseEntity.ok([ok: true])
    }

    /**
     * Batch 635 — filter-scoped "Mark visible read". The client sends
     * the list of ids currently visible on the notifications page (post
     * mute + type-filter + search) so the bulk flip only touches rows
     * the user actually intended to clear. Silently skips bad / foreign
     * ids. Returns `flipped` so the UI can surface "Marked N read".
     */
    @PostMapping("/read-batch")
    ResponseEntity<Map> readBatch(@RequestBody(required = false) Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def raw = body?.ids
        if (!(raw instanceof Collection)) {
            return ResponseEntity.ok([flipped: 0])
        }
        // Cap the raw collection BEFORE parsing — see MAX_BATCH_IDS docs.
        // Using take() on a List slice / Iterable doesn't copy the tail;
        // the dropped suffix is never touched by Long.valueOf at all.
        def ids = []
        raw.take(MAX_BATCH_IDS).each {
            try { if (it != null) ids << Long.valueOf(it.toString()) }
            catch (NumberFormatException ignored) { /* drop bad token */ }
        }
        def flipped = notificationService.markReadByIds(uid, ids)
        ResponseEntity.ok([flipped: flipped])
    }

    /** Delete every READ notification the caller owns — unread rows stay. */
    @PostMapping("/clear-read")
    ResponseEntity<Map> clearRead(HttpServletRequest req) {
        def deleted = notificationService.deleteAllRead(requireUser(req))
        ResponseEntity.ok([deleted: deleted])
    }

    /**
     * Batch 636 — filter-scoped "Clear visible read". Takes `{ids: […]}`
     * and deletes only those rows that are (a) owned by the caller and
     * (b) already read. Mirrors `/read-batch` for the scoped-delete
     * side of the house. Returns `{deleted: N}`.
     */
    @PostMapping("/delete-batch")
    ResponseEntity<Map> deleteBatch(@RequestBody(required = false) Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def raw = body?.ids
        if (!(raw instanceof Collection)) {
            return ResponseEntity.ok([deleted: 0])
        }
        // Same controller-side cap as read-batch — see MAX_BATCH_IDS.
        def ids = []
        raw.take(MAX_BATCH_IDS).each {
            try { if (it != null) ids << Long.valueOf(it.toString()) }
            catch (NumberFormatException ignored) { /* drop bad token */ }
        }
        def deleted = notificationService.deleteReadByIds(uid, ids)
        ResponseEntity.ok([deleted: deleted])
    }

    /** Delete a single notification. Silent no-op when the id is wrong
     *  or belongs to another user — we don't leak which. */
    @DeleteMapping("/{id}")
    ResponseEntity<Map> deleteOne(@PathVariable Long id, HttpServletRequest req) {
        notificationService.deleteOne(requireUser(req), id)
        ResponseEntity.ok([ok: true, id: id])
    }
}
