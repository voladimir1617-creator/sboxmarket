package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import spock.lang.Specification
import spock.lang.Subject

/**
 * AuditLog.actorName + subjectName are length=80 columns, but the source
 * SteamUser.displayName is length=255. A staff action against a user with
 * a 81+ char displayName would overflow the audit row on INSERT — the
 * row is silently dropped by the deferOrRun catch and the privileged-
 * action trail then has a hole exactly where staff would most want it.
 *
 * The fix caps actorName / subjectName at 80 chars in {@link AuditService#log}
 * BEFORE construction so the row always fits the persisted columns.
 */
class AuditServiceLongDisplayNameSpec extends Specification {

    AuditLogRepository  auditLogRepository  = Mock()
    SteamUserRepository steamUserRepository = Mock()

    @Subject
    AuditService service = new AuditService(
        auditLogRepository : auditLogRepository,
        steamUserRepository: steamUserRepository
    )

    def "actorName and subjectName are capped at the 80-char column width"() {
        given: 'a user whose displayName exceeds the 80-char AuditLog column'
        def longName = 'L' * 200   // 200 chars — fits SteamUser(length=255), overflows AuditLog(length=80)
        steamUserRepository.findById(1L)  >> Optional.of(new SteamUser(id: 1L,  displayName: longName))
        steamUserRepository.findById(2L)  >> Optional.of(new SteamUser(id: 2L,  displayName: longName))
        auditLogRepository.save(_) >> { args -> def a = args[0]; a.id = 99L; a }

        when:
        def entry = service.log('USER_BANNED', 1L, 2L, null, 'long name ban')

        then: 'both name fields land inside the 80-char persisted column'
        entry.actorName != null
        entry.actorName.length()   == 80
        entry.subjectName != null
        entry.subjectName.length() == 80
        // Sanity — same content as the source name, just truncated.
        entry.actorName   == longName.take(80)
        entry.subjectName == longName.take(80)
    }

    def "displayName at or under the cap is untouched"() {
        given:
        def exactlyEighty = 'A' * 80
        def shortName     = 'Bob'
        steamUserRepository.findById(1L)  >> Optional.of(new SteamUser(id: 1L,  displayName: exactlyEighty))
        steamUserRepository.findById(2L)  >> Optional.of(new SteamUser(id: 2L,  displayName: shortName))
        auditLogRepository.save(_) >> { args -> args[0] }

        when:
        def entry = service.log('USER_BANNED', 1L, 2L, null, 'ok')

        then:
        entry.actorName   == exactlyEighty
        entry.actorName.length()   == 80
        entry.subjectName == shortName
    }
}
