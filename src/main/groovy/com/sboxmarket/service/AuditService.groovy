package com.sboxmarket.service

import com.sboxmarket.model.AuditLog
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Central place where every privileged action is recorded to the audit
 * table. Services call {@code audit.log(...)} AT THE END of a successful
 * operation — never before, so failed attempts don't pollute the trail.
 *
 * The service automatically picks up the current request's IP and
 * user-agent via {@code RequestContextHolder} when one is available
 * (everything running inside a servlet thread). Scheduled jobs call with
 * null metadata and that's fine.
 */
@Service
@Slf4j
class AuditService {

    // Event-type constants — keep in sync with AdminModal filter UI.
    static final String DEPOSIT_COMPLETE    = 'DEPOSIT_COMPLETE'
    static final String WITHDRAW_REQUESTED  = 'WITHDRAW_REQUESTED'
    static final String WITHDRAW_APPROVED   = 'WITHDRAW_APPROVED'
    static final String WITHDRAW_REJECTED   = 'WITHDRAW_REJECTED'
    static final String REFUND_ISSUED       = 'REFUND_ISSUED'
    static final String LISTING_PURCHASED   = 'LISTING_PURCHASED'
    static final String LISTING_FORCE_CANCELLED = 'LISTING_FORCE_CANCELLED'
    static final String USER_BANNED         = 'USER_BANNED'
    static final String USER_UNBANNED       = 'USER_UNBANNED'
    static final String ADMIN_GRANTED       = 'ADMIN_GRANTED'
    static final String ADMIN_REVOKED       = 'ADMIN_REVOKED'
    static final String CSR_CREDIT          = 'CSR_CREDIT'
    static final String ADMIN_CREDIT        = 'ADMIN_CREDIT'
    static final String API_KEY_MINTED      = 'API_KEY_MINTED'
    static final String API_KEY_REVOKED     = 'API_KEY_REVOKED'

    @Autowired AuditLogRepository auditLogRepository
    @Autowired SteamUserRepository steamUserRepository

    @Transactional
    AuditLog log(String eventType, Long actorUserId, Long subjectUserId, Long resourceId, String summary) {
        def actor = actorUserId ? steamUserRepository.findById(actorUserId).orElse(null) : null
        def subject = subjectUserId ? steamUserRepository.findById(subjectUserId).orElse(null) : null
        def req = currentRequest()
        def entry = new AuditLog(
            actorUserId:   actorUserId,
            actorName:     actor?.displayName,
            subjectUserId: subjectUserId,
            subjectName:   subject?.displayName,
            eventType:     eventType,
            resourceId:    resourceId,
            summary:       summary?.take(500),
            ipAddress:     req ? clientIp(req) : null,
            userAgent:     req ? (req.getHeader('User-Agent') ?: '').take(120) : null
        )
        auditLogRepository.save(entry)
    }

    // LIMIT is now in SQL via PageRequest instead of .take(500) after
    // loading every row. Same 500-row cap the admin UI renders.
    private static final int PAGE_SIZE = 500
    private static final PageRequest PAGE = PageRequest.of(0, PAGE_SIZE)

    List<AuditLog> recent()                         { auditLogRepository.recent(PAGE) }
    List<AuditLog> byActor(Long uid)                { auditLogRepository.byActor(uid, PAGE) }
    List<AuditLog> bySubject(Long uid)              { auditLogRepository.bySubject(uid, PAGE) }
    List<AuditLog> byEvent(String eventType)        { auditLogRepository.byEvent(eventType, PAGE) }

    private static HttpServletRequest currentRequest() {
        def attr = RequestContextHolder.getRequestAttributes()
        (attr instanceof ServletRequestAttributes) ? ((ServletRequestAttributes) attr).request : null
    }

    private static String clientIp(HttpServletRequest req) {
        def cf = req.getHeader('CF-Connecting-IP')
        if (cf) return cf.trim().take(64)
        def xff = req.getHeader('X-Forwarded-For')
        if (xff) return xff.split(',')[0].trim().take(64)
        (req.remoteAddr ?: '').take(64)
    }
}
