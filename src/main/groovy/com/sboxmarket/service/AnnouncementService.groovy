package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Announcement
import com.sboxmarket.repository.AnnouncementRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * CRUD around the sitewide announcement banner. One row is "current" at a
 * time — picked by the `active=true AND not-expired AND newest-first` query.
 * Message text is sanitized the same way support-ticket bodies are so an
 * XSS payload can't ride through the admin UI into every user's banner.
 */
@Service
@Slf4j
class AnnouncementService {

    private static final Set<String> ALLOWED_SEVERITY = ['INFO','WARN','CRITICAL'] as Set

    /** Per-tick scope cap on the hourly sweep. Expired-but-active
     *  announcements are typically a handful per cycle, but this is a
     *  defensive in-memory clamp in case an ops mishap (admin posts
     *  thousands with past expiresAt for testing, sweep was disabled
     *  for weeks) ever produces a huge backlog. Remaining rows roll
     *  over to the next hourly tick — the active=true filter in
     *  findExpiredButActive means already-deactivated rows fall out
     *  of the candidate set naturally so the rollover never re-
     *  processes the same row. */
    static final int SWEEP_BATCH_LIMIT = 500

    @Autowired AnnouncementRepository announcementRepository
    @Autowired TextSanitizer textSanitizer
    @Autowired(required = false) AuditService auditService

    /** Public read-path — whatever banner is currently live, or null. */
    Announcement current() {
        def rows = announcementRepository.findCurrent(System.currentTimeMillis())
        rows.isEmpty() ? null : rows[0]
    }

    List<Announcement> listAll() {
        announcementRepository.findAllForAdmin()
    }

    @Transactional
    Announcement create(Long adminUserId, String message, String severity, Long expiresAt) {
        def cleanMessage = textSanitizer.medium(message ?: '')
        if (!cleanMessage || cleanMessage.length() < 3) {
            throw new BadRequestException("INVALID_MESSAGE", "Message must be at least 3 characters")
        }
        if (cleanMessage.length() > 500) cleanMessage = cleanMessage.substring(0, 500)
        def sev = (severity ?: 'INFO').toUpperCase()
        if (!ALLOWED_SEVERITY.contains(sev)) {
            throw new BadRequestException("INVALID_SEVERITY", "Severity must be INFO, WARN, or CRITICAL")
        }
        def row = new Announcement(
            message:         cleanMessage,
            severity:        sev,
            active:          true,
            expiresAt:       expiresAt,
            createdByUserId: adminUserId
        )
        def saved = announcementRepository.save(row)
        auditService?.log(AuditService.ANNOUNCEMENT_CREATED, adminUserId, null, saved.id,
            "Posted ${sev}: ${cleanMessage.take(120)}")
        saved
    }

    @Transactional
    Announcement deactivate(Long adminUserId, Long id) {
        def row = announcementRepository.findById(id)
            .orElseThrow { new NotFoundException("Announcement", id) }
        row.active = false
        announcementRepository.save(row)
        auditService?.log(AuditService.ANNOUNCEMENT_DEACTIVATED, adminUserId, null, id, null)
        row
    }

    /**
     * Auto-deactivate announcements whose `expiresAt` has passed (batch 582).
     * The public `findCurrent` already filters expired rows out at read
     * time, but the admin history tab still shows `active=true` on them
     * — confusing when an admin wants to see "what's actually live".
     * Sweeper flips those rows to `active=false` so admin UI matches
     * reality. Runs hourly with a 15-minute offset so it doesn't
     * collide with the other sweepers on container start.
     */
    // NOT @Transactional — the per-row try/catch + repository.save() pattern
    // below is the classic Spring rollback-only leak (wave 23 closed the same
    // bug in best-effort fan-outs). Spring Data's save() proxy marks the
    // SHARED outer tx as rollback-only the moment any inner save throws —
    // even when the user's try/catch absorbs the exception — so when
    // sweepExpired returns normally the commit throws UnexpectedRollbackException
    // and every "successful" row in the batch ALSO rolls back. The method has
    // zero cross-row invariant (it's N independent active=false flips), so the
    // correct posture is "no outer transaction" — each save() runs in its own
    // implicit tx and a single bad row only loses that row.
    @Scheduled(fixedDelay = 60L * 60L * 1000L, initialDelay = 15L * 60L * 1000L)
    void sweepExpired() {
        def now = System.currentTimeMillis()
        def rows
        try {
            rows = announcementRepository.findExpiredButActive(now)
        } catch (Exception e) {
            log.warn("Expired-announcement query failed: ${e.message}")
            return
        }
        if (rows == null || rows.isEmpty()) return
        int eligible = rows.size()
        // Defensive in-memory clamp (see SWEEP_BATCH_LIMIT). fixedDelay
        // serialises ticks per scheduled method so the sweep can't
        // overlap itself, and successfully-flipped rows drop out of the
        // active=true filter on the next pass so leftover rows resume
        // cleanly without re-touching the already-processed ones. Per-
        // row save lives in its own try/catch so one bad row never
        // aborts the batch.
        if (eligible > SWEEP_BATCH_LIMIT) {
            rows = rows.take(SWEEP_BATCH_LIMIT)
        }
        int closed = 0
        rows.each { row ->
            try {
                row.active = false
                announcementRepository.save(row)
                closed++
            } catch (Exception e) {
                log.warn("Failed to auto-deactivate announcement ${row.id}: ${e.message}")
            }
        }
        def backlog = eligible > SWEEP_BATCH_LIMIT
            ? " (eligible=${eligible}, capped at ${SWEEP_BATCH_LIMIT} — backlog will drain across subsequent ticks)"
            : ""
        log.info("Announcement sweeper: auto-deactivated ${closed} of ${rows.size()} expired row(s)${backlog}")
    }
}
