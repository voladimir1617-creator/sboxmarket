package com.sboxmarket

import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import spock.lang.Specification
import spock.lang.Subject

import java.lang.reflect.Method

/**
 * AuditService is thin — persist an append-only row + fan-out queries.
 * Behaviour worth pinning:
 *   - actor/subject display names are enriched via SteamUserRepository
 *   - summary is hard-capped at 500 chars
 *   - filter queries delegate to the right repository method
 */
class AuditServiceSpec extends Specification {

    AuditLogRepository  auditLogRepository  = Mock()
    SteamUserRepository steamUserRepository = Mock()

    // Real resolver (default trusted-proxies = loopback only). MockHttpServletRequest's
    // default remoteAddr is 127.0.0.1, so by default the forwarded headers ARE honoured;
    // a test that wants them IGNORED sets a non-loopback remoteAddr.
    com.sboxmarket.config.ClientIpResolver clientIpResolver = new com.sboxmarket.config.ClientIpResolver()

    @Subject
    AuditService service = new AuditService(
        auditLogRepository : auditLogRepository,
        steamUserRepository: steamUserRepository,
        clientIpResolver   : clientIpResolver
    )

    def "log enriches actor + subject with display names"() {
        given:
        steamUserRepository.findById(1L)  >> Optional.of(new SteamUser(id: 1L,  displayName: 'Alice'))
        steamUserRepository.findById(20L) >> Optional.of(new SteamUser(id: 20L, displayName: 'Bob'))
        auditLogRepository.save(_) >> { args -> def a = args[0]; a.id = 1L; a }

        when:
        def entry = service.log('USER_BANNED', 1L, 20L, null, 'no good')

        then:
        entry.actorUserId == 1L
        entry.actorName == 'Alice'
        entry.subjectUserId == 20L
        entry.subjectName == 'Bob'
        entry.eventType == 'USER_BANNED'
        entry.summary == 'no good'
    }

    def "log caps summary at 500 chars"() {
        given:
        auditLogRepository.save(_) >> { args -> args[0] }
        def longSummary = 'x' * 2000

        when:
        def entry = service.log('TEST', null, null, null, longSummary)

        then:
        entry.summary.length() == 500
    }

    def "log accepts null actor + subject without enriching"() {
        given:
        auditLogRepository.save(_) >> { args -> args[0] }

        when:
        def entry = service.log('SYSTEM_EVENT', null, null, null, 'ran')

        then:
        entry.actorUserId == null
        entry.subjectUserId == null
        entry.actorName == null
        entry.subjectName == null
        0 * steamUserRepository.findById(_)
    }

    def "log persists the row exactly once"() {
        given:
        auditLogRepository.save(_) >> { args -> args[0] }

        when:
        service.log('TEST', null, null, null, 'x')

        then:
        1 * auditLogRepository.save(_)
    }

    // ── Query delegations ─────────────────────────────────────────

    def "recent() delegates to repository.recent(Pageable) with a 500-row cap"() {
        given:
        // LIMIT is now enforced in SQL via PageRequest, so the service no
        // longer needs an in-memory .take(500) — but the 500-row cap still
        // applies because the PageRequest passed in is of size 500.
        auditLogRepository.recent(_) >> { args ->
            def pageable = args[0]
            pageable.pageSize == 500
            (1..pageable.pageSize).collect { i -> new AuditLog(id: i as long) }
        }

        when:
        def result = service.recent()

        then:
        result.size() == 500
    }

    def "byActor() delegates to repository.byActor(uid, Pageable)"() {
        given:
        auditLogRepository.byActor(10L, _) >> [new AuditLog(id: 1L, actorUserId: 10L)]

        when:
        def result = service.byActor(10L)

        then:
        result.size() == 1
        result[0].actorUserId == 10L
    }

    def "bySubject() delegates to repository.bySubject(uid, Pageable)"() {
        given:
        auditLogRepository.bySubject(20L, _) >> [new AuditLog(id: 1L, subjectUserId: 20L)]

        when:
        def result = service.bySubject(20L)

        then:
        result.size() == 1
    }

    def "byEvent() delegates to repository.byEvent(event, Pageable)"() {
        given:
        auditLogRepository.byEvent('USER_BANNED', _) >> [new AuditLog(id: 1L, eventType: 'USER_BANNED')]

        when:
        def result = service.byEvent('USER_BANNED')

        then:
        result.size() == 1
    }

    // ── Date-bounded overloads (batch 556) ──────────────────────────

    def "recent(since) delegates to repository.recentSince when since is set"() {
        given:
        auditLogRepository.recentSince(5000L, _) >> [new AuditLog(id: 1L)]

        when:
        def result = service.recent(5000L)

        then:
        result.size() == 1
        0 * auditLogRepository.recent(_)
    }

    def "recent(null) falls through to the unbounded recent() (legacy shape)"() {
        given:
        auditLogRepository.recent(_) >> [new AuditLog(id: 1L)]

        when:
        def result = service.recent((Long) null)

        then:
        result.size() == 1
        0 * auditLogRepository.recentSince(*_)
    }

    def "byActor(uid, since) routes to the date-bounded repo method"() {
        given:
        auditLogRepository.byActorSince(10L, 5000L, _) >> [new AuditLog(id: 1L, actorUserId: 10L)]

        when:
        def result = service.byActor(10L, 5000L)

        then:
        result.size() == 1
        0 * auditLogRepository.byActor(*_)
    }

    def "byEvent(event, since) routes to the date-bounded repo method"() {
        given:
        auditLogRepository.byEventSince('USER_BANNED', 5000L, _) >> [new AuditLog(id: 1L, eventType: 'USER_BANNED')]

        when:
        def result = service.byEvent('USER_BANNED', 5000L)

        then:
        result.size() == 1
        0 * auditLogRepository.byEvent(_, _)
    }

    // ── Support-ticket event constants (staff ticket actions) ───────

    def "TICKET_REPLIED / TICKET_CLOSED constants carry their literal event names"() {
        expect:
        AuditService.TICKET_REPLIED == 'TICKET_REPLIED'
        AuditService.TICKET_CLOSED  == 'TICKET_CLOSED'
    }

    // ── afterCommit deferral (P1 transaction-poisoning fix) ──────────

    def "log is NOT @Transactional — it must not join the caller's transaction"() {
        when:
        // Regression pin for the P1 bug: log() used to be
        // @Transactional(REQUIRED), so a failing auditLogRepository.save()
        // marked the CALLER's shared transaction rollback-only and the
        // swallowing try/catch at the ~40 call sites let the caller
        // "succeed" — then the caller's commit threw
        // UnexpectedRollbackException and the real operation was rolled
        // back. The fix defers the save to afterCommit, so log() itself
        // carries no @Transactional.
        Method m = AuditService.getMethod('log',
            String, Long, Long, Long, String)

        then:
        m.getAnnotation(Transactional) == null
    }

    def "AuditService has the deferOrRun afterCommit helper"() {
        expect:
        // The deferral helper is the mechanism that moves the best-effort
        // save out of the caller's transaction. Pin its presence so a
        // future refactor can't silently drop it.
        AuditService.getDeclaredMethods().any { it.name == 'deferOrRun' }
    }

    def "log returns the built audit entry even when the save is deferred"() {
        given:
        // With no active transaction (unit test, no Spring proxy)
        // deferOrRun runs the save immediately — but the contract is that
        // log() returns the populated entity on every path; the ~40 call
        // sites are fire-and-forget so a deferred save can't hand back a
        // persisted id synchronously.
        auditLogRepository.save(_) >> { args -> def a = args[0]; a.id = 77L; a }

        when:
        def entry = service.log('TEST', null, null, 9L, 'summary')

        then:
        entry != null
        entry.eventType == 'TEST'
        entry.resourceId == 9L
        entry.summary == 'summary'
    }

    // ── clientIp resolution (fraud-detection IP correlation) ────────
    //
    // FraudAnalysisService.detectMultipleIpsPerUser correlates by
    // (actorUserId, ipAddress) on the audit-log rows AuditService writes.
    // If clientIp ever returns a wrong / blank IP, the fraud sweeper
    // misses the multi-IP signal entirely. These specs pin the resolution
    // chain — CF-Connecting-IP > first non-blank X-Forwarded-For token >
    // remoteAddr — and the documented crafted-XFF (`,`/`,,`) regression
    // guard.

    /** Bind a MockHttpServletRequest into RequestContextHolder so the
     *  private `currentRequest()` lookup inside AuditService.log finds it.
     *  Caller must reset the holder in cleanup to avoid leaking across
     *  specs. */
    private void bindRequest(MockHttpServletRequest req) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req))
    }

    def cleanup() {
        RequestContextHolder.resetRequestAttributes()
    }

    /** Invoke the package-private static `clientIp` via reflection so
     *  these specs can exercise every branch directly, then double-cover
     *  the path through `log()` to make sure the entry's ipAddress
     *  matches. */
    private String invokeClientIp(HttpServletRequest req) {
        def m = AuditService.getDeclaredMethod('clientIp', HttpServletRequest)
        m.accessible = true
        // Now an INSTANCE method that delegates to the wired ClientIpResolver.
        m.invoke(service, req) as String
    }

    def "clientIp prefers CF-Connecting-IP over X-Forwarded-For and remoteAddr"() {
        given:
        // Production reverse-proxy chain: Cloudflare front, nginx behind,
        // app behind that. CF-Connecting-IP is the only header Cloudflare
        // signs end-to-end, so it wins the IP-resolution chain.
        def req = new MockHttpServletRequest()
        req.addHeader('CF-Connecting-IP', '203.0.113.5')
        req.addHeader('X-Forwarded-For', '198.51.100.7, 10.0.0.1')
        req.setRemoteAddr('127.0.0.1')   // immediate peer = trusted loopback proxy

        expect:
        invokeClientIp(req) == '203.0.113.5'
    }

    def "a SPOOFED CF-Connecting-IP from a NON-trusted peer is IGNORED — the real socket IP is logged (integrity-audit fix)"() {
        given:
        // A client connecting DIRECTLY (not via our trusted proxy) sets
        // CF-Connecting-IP / X-Forwarded-For to forge its logged IP or frame a
        // third party. The trusted-proxy gate must reject the headers and record
        // the real socket address so the audit trail can't be poisoned.
        def req = new MockHttpServletRequest()
        req.addHeader('CF-Connecting-IP', '203.0.113.5')
        req.addHeader('X-Forwarded-For', '9.9.9.9')
        req.setRemoteAddr('1.2.3.4')   // not in the trusted-proxy CIDRs

        expect:
        invokeClientIp(req) == '1.2.3.4'
    }

    def "clientIp falls through to X-Forwarded-For when CF-Connecting-IP is blank"() {
        given:
        // No Cloudflare in front — Render / Heroku / nginx-only deploys
        // only get X-Forwarded-For. The FIRST token is the original
        // client (subsequent tokens are intermediate proxies that
        // appended themselves).
        def req = new MockHttpServletRequest()
        req.addHeader('CF-Connecting-IP', '   ')
        req.addHeader('X-Forwarded-For', '198.51.100.7, 10.0.0.1, 10.0.0.2')
        req.setRemoteAddr('127.0.0.1')   // immediate peer = trusted loopback proxy

        expect:
        invokeClientIp(req) == '198.51.100.7'
    }

    def "clientIp falls through to remoteAddr when neither header is present"() {
        given:
        // Local / docker-compose / direct-connection: no proxy headers
        // at all. Servlet remoteAddr is the only source.
        def req = new MockHttpServletRequest()
        req.setRemoteAddr('192.168.1.42')

        expect:
        invokeClientIp(req) == '192.168.1.42'
    }

    def "clientIp survives a crafted XFF of just commas (regression for split('')[0])"() {
        given:
        // Documented regression: the old `xff.split(',')[0]` threw
        // ArrayIndexOutOfBoundsException for an all-comma header
        // ("," / ",,"). A hostile or buggy client must NOT be able to
        // crash audit-row writing. Service must fall through to remoteAddr.
        def req = new MockHttpServletRequest()
        req.addHeader('X-Forwarded-For', xff)
        req.setRemoteAddr('192.168.1.42')

        when:
        def ip = invokeClientIp(req)

        then:
        noExceptionThrown()
        // All-comma yields no non-blank token → falls through to remoteAddr.
        ip == '192.168.1.42'

        where:
        xff << [',', ',,', ',,,', ' , , , ']
    }

    def "clientIp skips a leading blank XFF token and returns the first non-blank one"() {
        given:
        // A malformed proxy chain can prepend an empty token (",x.y.z");
        // the iteration must skip it and return the genuine client IP.
        def req = new MockHttpServletRequest()
        req.addHeader('X-Forwarded-For', ', 198.51.100.7, 10.0.0.1')

        expect:
        invokeClientIp(req) == '198.51.100.7'
    }

    def "clientIp truncates an oversized header value to 64 characters"() {
        given:
        // The ip_address column is VARCHAR(64) — a hostile-crafted CF
        // header longer than 64 chars must be truncated at the boundary
        // so the save doesn't throw a DataException at flush time.
        def fakeIp = 'A' * 200
        def req = new MockHttpServletRequest()
        req.addHeader('CF-Connecting-IP', fakeIp)

        when:
        def ip = invokeClientIp(req)

        then:
        ip.length() == 64
        ip == 'A' * 64
    }

    def "clientIp returns empty string when remoteAddr is also missing"() {
        given:
        // Worst-case: a request with no headers AND no remoteAddr (an
        // async-dispatched or test-shim request). Must not NPE — just
        // produce an empty string so the audit row still writes.
        def req = new MockHttpServletRequest()
        req.setRemoteAddr(null)

        when:
        def ip = invokeClientIp(req)

        then:
        noExceptionThrown()
        ip == ''
    }

    def "log() captures CF-Connecting-IP onto the audit entry's ipAddress"() {
        given:
        // End-to-end via the public log() path — proves the
        // currentRequest() → clientIp() wiring actually lands the
        // resolved IP on the persisted row.
        def req = new MockHttpServletRequest()
        req.addHeader('CF-Connecting-IP', '203.0.113.99')
        req.addHeader('User-Agent', 'spec/1.0')
        bindRequest(req)
        auditLogRepository.save(_) >> { args -> args[0] }

        when:
        def entry = service.log('TEST_EVENT', null, null, null, 'x')

        then:
        entry.ipAddress == '203.0.113.99'
        entry.userAgent == 'spec/1.0'
    }

    def "log() captures the first non-blank XFF token when CF header is absent"() {
        given:
        def req = new MockHttpServletRequest()
        req.addHeader('X-Forwarded-For', '198.51.100.7, 10.0.0.1')
        bindRequest(req)
        auditLogRepository.save(_) >> { args -> args[0] }

        when:
        def entry = service.log('TEST_EVENT', null, null, null, 'x')

        then:
        entry.ipAddress == '198.51.100.7'
    }

    def "log() leaves ipAddress null when there is no servlet request bound"() {
        given:
        // Scheduled jobs (BidService.settle, sweepers, etc.) have no
        // RequestContextHolder context. clientIp must not run — the
        // audit row's ipAddress simply stays null.
        RequestContextHolder.resetRequestAttributes()
        auditLogRepository.save(_) >> { args -> args[0] }

        when:
        def entry = service.log('SCHEDULED_EVENT', null, null, null, 'sweeper ran')

        then:
        entry.ipAddress == null
        entry.userAgent == null
    }

    def "log() does NOT propagate an enrichment-lookup throw — actor read failure degrades to null name"() {
        // Regression pin for the latent rollback-only-leak in the
        // enrichment path. AuditService.log is non-@Transactional but
        // Spring Data's findById is itself @Transactional and joins the
        // caller's tx. A transient DB blip / pool-exhaustion / converter
        // NPE during the actor lookup would otherwise propagate to the
        // caller (StripeService, AdminService, TradeProtectionService…)
        // and — since the ~40 call sites invoke `audit.log(...)` at the
        // end of a try with no inner catch — mark the SHARED transaction
        // rollback-only, undoing the parent's already-successful work
        // when commit throws UnexpectedRollbackException.
        //
        // After the fix, the lookup throw is caught inline, the audit
        // row is written with a null displayName, and the caller never
        // sees the failure. Same posture as the deferOrRun fix wave 23
        // applied to the SAVE path.
        given:
        steamUserRepository.findById(7L) >> { throw new RuntimeException('DB pool exhausted') }
        auditLogRepository.save(_) >> { args -> def a = args[0]; a.id = 1L; a }

        when:
        def entry = service.log('USER_BANNED', 7L, null, null, 'racing pool')

        then:
        noExceptionThrown()
        entry != null
        entry.actorUserId == 7L
        entry.actorName == null
        entry.eventType == 'USER_BANNED'
    }

    def "log() does NOT propagate an enrichment-lookup throw on the SUBJECT side either"() {
        given:
        steamUserRepository.findById(9L) >> { throw new RuntimeException('Timeout') }
        auditLogRepository.save(_) >> { args -> args[0] }

        when:
        def entry = service.log('USER_BANNED', null, 9L, null, 'subject throw')

        then:
        noExceptionThrown()
        entry.subjectUserId == 9L
        entry.subjectName == null
    }

    def "log() truncates an oversized User-Agent header to 120 characters"() {
        given:
        // The user_agent column is VARCHAR(120) — match the take()
        // ceiling so a hostile UA doesn't throw at flush time.
        def fakeUa = 'B' * 500
        def req = new MockHttpServletRequest()
        req.setRemoteAddr('127.0.0.1')
        req.addHeader('User-Agent', fakeUa)
        bindRequest(req)
        auditLogRepository.save(_) >> { args -> args[0] }

        when:
        def entry = service.log('TEST_EVENT', null, null, null, 'x')

        then:
        entry.userAgent.length() == 120
        entry.userAgent == 'B' * 120
    }
}
