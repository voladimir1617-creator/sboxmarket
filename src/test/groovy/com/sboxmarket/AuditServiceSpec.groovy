package com.sboxmarket

import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import spock.lang.Specification
import spock.lang.Subject

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

    @Subject
    AuditService service = new AuditService(
        auditLogRepository : auditLogRepository,
        steamUserRepository: steamUserRepository
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
}
