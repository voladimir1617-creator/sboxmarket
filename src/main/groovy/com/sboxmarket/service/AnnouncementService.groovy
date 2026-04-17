package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Announcement
import com.sboxmarket.repository.AnnouncementRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
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
        auditService?.log('ANNOUNCEMENT_CREATED', adminUserId, null, saved.id,
            "Posted ${sev}: ${cleanMessage.take(120)}")
        saved
    }

    @Transactional
    Announcement deactivate(Long adminUserId, Long id) {
        def row = announcementRepository.findById(id)
            .orElseThrow { new NotFoundException("Announcement", id) }
        row.active = false
        announcementRepository.save(row)
        auditService?.log('ANNOUNCEMENT_DEACTIVATED', adminUserId, null, id, null)
        row
    }
}
