package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Announcement
import com.sboxmarket.repository.AnnouncementRepository
import com.sboxmarket.service.AnnouncementService
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the sitewide announcement banner CRUD. The service has
 * two real guard rails worth pinning:
 *   - Message sanitization + length floor (3+ chars after trimming).
 *   - Severity whitelist (INFO / WARN / CRITICAL) — anything else 400s.
 * Plus the basic happy paths: current() / listAll() forwards, create
 * assigns fields, deactivate flips `active` to false.
 */
class AnnouncementServiceSpec extends Specification {

    AnnouncementRepository announcementRepository = Mock()
    TextSanitizer textSanitizer = Mock() {
        medium(_) >> { String s -> s?.trim() }
    }
    AuditService auditService = Mock()

    @Subject
    AnnouncementService service = new AnnouncementService(
        announcementRepository: announcementRepository,
        textSanitizer: textSanitizer,
        auditService: auditService
    )

    // ── current ──────────────────────────────────────────────────

    def "current returns null when no live banner exists"() {
        given:
        announcementRepository.findCurrent(_) >> []

        expect:
        service.current() == null
    }

    def "current returns the newest row from the repo"() {
        given:
        def newest = new Announcement(id: 3L, message: 'Heads up', active: true)
        announcementRepository.findCurrent(_) >> [newest, new Announcement(id: 2L)]

        expect:
        service.current() == newest
    }

    // ── listAll (admin) ──────────────────────────────────────────

    def "listAll forwards to findAllForAdmin"() {
        given:
        def rows = [new Announcement(id: 1L), new Announcement(id: 2L)]
        announcementRepository.findAllForAdmin() >> rows

        expect:
        service.listAll() == rows
    }

    // ── create (happy path) ──────────────────────────────────────

    def "create sanitizes + persists a banner with defaults"() {
        given:
        announcementRepository.save(_) >> { Announcement a -> a.id = 7L; a }

        when:
        def row = service.create(10L, 'Scheduled maintenance at 2am UTC', null, null)

        then:
        row.id == 7L
        row.message == 'Scheduled maintenance at 2am UTC'
        row.severity == 'INFO'        // null → default INFO
        row.active == true
        row.createdByUserId == 10L
        1 * auditService.log('ANNOUNCEMENT_CREATED', 10L, null, 7L, _)
    }

    def "create uppercases a lowercase severity and accepts the whitelist"() {
        given:
        announcementRepository.save(_) >> { Announcement a -> a }

        expect:
        service.create(10L, 'Message body', input, null).severity == expected

        where:
        input      | expected
        'info'     | 'INFO'
        'warn'     | 'WARN'
        'critical' | 'CRITICAL'
        'WARN'     | 'WARN'
    }

    def "create rejects a non-whitelisted severity"() {
        when:
        service.create(10L, 'Message body', 'BOGUS', null)

        then:
        thrown(BadRequestException)
    }

    def "create rejects messages shorter than 3 chars after trimming"() {
        when:
        service.create(10L, text, 'INFO', null)

        then:
        thrown(BadRequestException)

        where:
        text << [null, '', ' ', 'ab', '   a  ']
    }

    def "create truncates messages over 500 characters"() {
        given:
        announcementRepository.save(_) >> { Announcement a -> a }
        def long600 = 'x' * 600

        when:
        def row = service.create(10L, long600, 'INFO', null)

        then:
        row.message.length() == 500
    }

    def "create propagates expiresAt onto the row"() {
        given:
        announcementRepository.save(_) >> { Announcement a -> a }
        def deadline = System.currentTimeMillis() + 3600_000L

        when:
        def row = service.create(10L, 'Message body', 'INFO', deadline)

        then:
        row.expiresAt == deadline
    }

    // ── deactivate ───────────────────────────────────────────────

    def "deactivate flips active to false and saves"() {
        given:
        def live = new Announcement(id: 7L, active: true)
        announcementRepository.findById(7L) >> Optional.of(live)
        announcementRepository.save(_) >> { Announcement a -> a }

        when:
        def row = service.deactivate(10L, 7L)

        then:
        row.active == false
        1 * auditService.log('ANNOUNCEMENT_DEACTIVATED', 10L, null, 7L, null)
    }

    def "deactivate 404s for an unknown id"() {
        given:
        announcementRepository.findById(_) >> Optional.empty()

        when:
        service.deactivate(10L, 999L)

        then:
        thrown(NotFoundException)
    }

    // ── sweepExpired (batch 582) ────────────────────────────────────

    def "sweepExpired flips every expired-but-active row to inactive"() {
        given:
        def row1 = new Announcement(id: 1L, message: 'old 1', active: true,
            expiresAt: 100L, severity: 'INFO')
        def row2 = new Announcement(id: 2L, message: 'old 2', active: true,
            expiresAt: 200L, severity: 'WARN')
        announcementRepository.findExpiredButActive(_) >> [row1, row2]
        announcementRepository.save(_) >> { args -> args[0] }

        when:
        service.sweepExpired()

        then:
        row1.active == false
        row2.active == false
        1 * announcementRepository.save(row1)
        1 * announcementRepository.save(row2)
    }

    def "sweepExpired is a no-op when nothing is due"() {
        given:
        announcementRepository.findExpiredButActive(_) >> []

        when:
        service.sweepExpired()

        then:
        0 * announcementRepository.save(_)
    }

    def "sweepExpired swallows per-row save exceptions and keeps iterating"() {
        given:
        def row1 = new Announcement(id: 1L, message: 'boom', active: true,
            expiresAt: 100L, severity: 'INFO')
        def row2 = new Announcement(id: 2L, message: 'ok', active: true,
            expiresAt: 100L, severity: 'INFO')
        announcementRepository.findExpiredButActive(_) >> [row1, row2]
        announcementRepository.save(row1) >> { throw new RuntimeException('db flake') }
        announcementRepository.save(row2) >> { args -> args[0] }

        when:
        service.sweepExpired()

        then:
        // Second row still flipped — per-row isolation holds.
        row2.active == false
    }
}
