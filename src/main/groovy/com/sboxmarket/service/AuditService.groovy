package com.sboxmarket.service

import com.sboxmarket.model.AuditLog
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
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
    static final String USER_SIGN_IN        = 'USER_SIGN_IN'
    static final String CSR_GRANTED         = 'CSR_GRANTED'
    static final String CSR_REVOKED         = 'CSR_REVOKED'
    static final String TWOFA_RESET         = 'TWOFA_RESET'
    static final String CSR_CREDIT          = 'CSR_CREDIT'
    static final String ADMIN_CREDIT        = 'ADMIN_CREDIT'
    static final String API_KEY_MINTED      = 'API_KEY_MINTED'
    static final String API_KEY_REVOKED     = 'API_KEY_REVOKED'
    static final String ADMIN_NOTES_UPDATED = 'ADMIN_NOTES_UPDATED'
    static final String TRADE_FORCE_RELEASED = 'TRADE_FORCE_RELEASED'
    static final String TRADE_FORCE_CANCELLED = 'TRADE_FORCE_CANCELLED'
    static final String ITEM_EDITED         = 'ITEM_EDITED'
    static final String TRADE_MESSAGE_DELETED = 'TRADE_MESSAGE_DELETED'
    static final String ANNOUNCEMENT_CREATED = 'ANNOUNCEMENT_CREATED'
    static final String ANNOUNCEMENT_DEACTIVATED = 'ANNOUNCEMENT_DEACTIVATED'
    static final String TRADE_VERIFIED      = 'TRADE_VERIFIED'
    static final String TRADE_DISPUTED      = 'TRADE_DISPUTED'
    static final String TRADE_CANCELLED     = 'TRADE_CANCELLED'
    static final String TRADE_AUTO_CANCELLED = 'TRADE_AUTO_CANCELLED'
    static final String TRADE_AUTO_RELEASED = 'TRADE_AUTO_RELEASED'
    static final String SESSION_LOGOUT_ALL  = 'SESSION_LOGOUT_ALL'
    static final String USER_FORCE_LOGOUT   = 'USER_FORCE_LOGOUT'
    static final String WITHDRAW_SELF_CANCELLED = 'WITHDRAW_SELF_CANCELLED'
    static final String WITHDRAW_REVERSED   = 'WITHDRAW_REVERSED'
    static final String CHARGEBACK_OPENED   = 'CHARGEBACK_OPENED'
    static final String DISPUTE_CLEARED     = 'DISPUTE_CLEARED'
    static final String REVIEW_DELETED_STAFF = 'REVIEW_DELETED_STAFF'
    static final String TICKET_REPLIED      = 'TICKET_REPLIED'
    static final String TICKET_CLOSED       = 'TICKET_CLOSED'

    @Autowired AuditLogRepository auditLogRepository
    @Autowired SteamUserRepository steamUserRepository

    /** Optional so unit tests that build the service with `new
     *  AuditService(...)` (no Spring context) still work — in that case
     *  there is never an active transaction and the deferred work runs
     *  immediately anyway. */
    @Autowired(required = false) PlatformTransactionManager transactionManager

    /**
     * Run {@code work} after the caller's transaction commits — or
     * immediately when there is no active transaction (e.g. a unit test
     * with no Spring proxy, or a non-transactional caller).
     *
     * The audit write is a best-effort side-effect: services call
     * {@code log(...)} at the end of a successful privileged action and
     * wrap it expecting "an audit hiccup must NEVER fail the parent
     * operation". With a plain @Transactional the save() joined the
     * caller's transaction, so a failing repository.save() marked the
     * SHARED transaction rollback-only — the swallowing try/catch let the
     * caller "succeed", then its commit blew up with
     * UnexpectedRollbackException and the real operation was rolled back.
     *
     * Deferring to afterCommit means the deferred write runs AFTER the
     * parent has already durably committed: it can no longer poison the
     * parent, and because the parent's row locks are released post-commit
     * it doesn't extend the lock-hold window either.
     *
     * The deferred write runs in a FRESH REQUIRES_NEW transaction. This is
     * load-bearing: inside an afterCommit callback the original
     * transaction is already committed with "no commit following" — a
     * plain REQUIRED save() would join that spent transaction and never
     * actually commit its INSERT. A new transaction gives the deferred
     * write its own commit. This is safe (unlike REQUIRES_NEW on the
     * service method itself, the rejected prior fix): post-commit the
     * caller holds no row locks, so the new transaction extends no lock
     * window.
     */
    private void deferOrRun(Closure work) {
        if (transactionManager != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override void afterCommit() {
                    try {
                        def tt = new TransactionTemplate(transactionManager)
                        tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                        tt.executeWithoutResult { work() }
                    } catch (Exception e) {
                        log.warn("Deferred best-effort write failed: ${e.message}")
                    }
                }
            })
        } else {
            work()
        }
    }

    AuditLog log(String eventType, Long actorUserId, Long subjectUserId, Long resourceId, String summary) {
        // Actor/subject enrichment is a best-effort display niceness — if
        // it throws, we still want the audit row written with a null name
        // rather than killing the caller's transaction.
        //
        // The enrichment reads run on the caller's transaction (this method
        // is non-@Transactional but Spring Data's findById is itself
        // @Transactional and joins whatever's current). A DB-pool blip /
        // transient timeout / NPE in a custom converter throws past us
        // straight into the caller. Most call sites (StripeService,
        // AdminService, TradeProtectionService.enable, …) invoke
        // `audit.log(...)` at the end of a try block with NO inner catch,
        // so an enrichment throw marks the SHARED transaction rollback-only
        // and the parent's already-successful work is rolled back on commit
        // with UnexpectedRollbackException. Exact same bug class wave 23
        // closed for the SAVE path via deferOrRun — the enrichment reads
        // were missed.
        //
        // Wrap each lookup in try/catch so a transient read failure
        // degrades to "audit row with null name" rather than "money-path
        // transaction silently rolled back". The PK + IP + summary still
        // make the row identifiable from the admin UI; a missing displayName
        // is a far smaller cost than a wallet write being undone.
        def actor = null
        try { actor = actorUserId ? steamUserRepository.findById(actorUserId).orElse(null) : null }
        catch (Exception e) { log.warn("Audit actor enrichment failed for uid=${actorUserId}: ${e.message}") }
        def subject = null
        try { subject = subjectUserId ? steamUserRepository.findById(subjectUserId).orElse(null) : null }
        catch (Exception e) { log.warn("Audit subject enrichment failed for uid=${subjectUserId}: ${e.message}") }
        def req = currentRequest()
        // Belt-and-braces length caps on the persisted name fields.
        // SteamUser.displayName is length=255 but AuditLog.actorName /
        // subjectName are length=80. A user with a 81+ char displayName
        // (Steam policy is 32, but our column allows 255 — historical
        // imports and some Unicode sequences can blow past 80) would
        // overflow the audit row INSERT. The save runs inside a
        // post-commit REQUIRES_NEW transaction (deferOrRun) so the parent
        // op isn't poisoned, but the audit row is silently dropped — the
        // privileged-action trail then has a hole exactly when staff
        // would most want it (long-named user gets banned, no row written).
        // Same defensive .take() already applied to summary / ipAddress /
        // userAgent above.
        def entry = new AuditLog(
            actorUserId:   actorUserId,
            actorName:     actor?.displayName?.take(80),
            subjectUserId: subjectUserId,
            subjectName:   subject?.displayName?.take(80),
            eventType:     eventType,
            resourceId:    resourceId,
            summary:       summary?.take(500),
            ipAddress:     req ? clientIp(req) : null,
            userAgent:     req ? (req.getHeader('User-Agent') ?: '').take(120) : null
        )
        // Defer the save until after the caller's transaction commits, so a
        // failing save() can't mark the caller's transaction rollback-only.
        deferOrRun { auditLogRepository.save(entry) }
        entry
    }

    // LIMIT is now in SQL via PageRequest instead of .take(500) after
    // loading every row. Same 500-row cap the admin UI renders.
    private static final int PAGE_SIZE = 500
    private static final PageRequest PAGE = PageRequest.of(0, PAGE_SIZE)

    List<AuditLog> recent()                         { auditLogRepository.recent(PAGE) }
    List<AuditLog> byActor(Long uid)                { auditLogRepository.byActor(uid, PAGE) }
    List<AuditLog> bySubject(Long uid)              { auditLogRepository.bySubject(uid, PAGE) }
    List<AuditLog> byEvent(String eventType)        { auditLogRepository.byEvent(eventType, PAGE) }

    // Date-filtered variants (batch 556). `since` is wall-clock millis; 0
    // or null falls back to the unbounded recent-first scan so callers
    // that don't care about dates get the legacy behaviour.
    List<AuditLog> recent(Long since)               { since ? auditLogRepository.recentSince(since, PAGE) : recent() }
    List<AuditLog> byActor(Long uid, Long since)    { since ? auditLogRepository.byActorSince(uid, since, PAGE) : byActor(uid) }
    List<AuditLog> bySubject(Long uid, Long since)  { since ? auditLogRepository.bySubjectSince(uid, since, PAGE) : bySubject(uid) }
    List<AuditLog> byEvent(String eventType, Long since) { since ? auditLogRepository.byEventSince(eventType, since, PAGE) : byEvent(eventType) }

    private static HttpServletRequest currentRequest() {
        def attr = RequestContextHolder.getRequestAttributes()
        (attr instanceof ServletRequestAttributes) ? ((ServletRequestAttributes) attr).request : null
    }

    private static String clientIp(HttpServletRequest req) {
        def cf = req.getHeader('CF-Connecting-IP')
        if (cf && !cf.trim().isEmpty()) return cf.trim().take(64)
        def xff = req.getHeader('X-Forwarded-For')
        if (xff) {
            // First non-empty token — a crafted `,`/`,,` header is non-blank
            // but splits to a zero-length array, so the old `split(',')[0]`
            // threw ArrayIndexOutOfBoundsException.
            for (String tok : xff.split(',')) {
                def t = tok?.trim()
                if (t) return t.take(64)
            }
        }
        (req.remoteAddr ?: '').take(64)
    }
}
