package com.sboxmarket.service

import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Regression spec for the cross-user ticket-id enumeration leak in
 * SupportService.
 *
 * Bug: every per-ticket method (getTicket / reply / reopen / resolve)
 * threw NotFoundException when the id was missing but ForbiddenException
 * when the id existed and belonged to a different user. The two map to
 * 404 and 403 respectively, so an authenticated attacker iterating the
 * support endpoints could distinguish "doesn't exist" (404) from
 * "exists, not yours" (403) and enumerate other users' ticket ids one
 * status code at a time.
 *
 * Fix: non-owner access now throws NotFoundException — the SAME response
 * an attacker sees for a wholly missing id. Mirrors the
 * ApiKeyService.revoke pattern in this codebase (see
 * ApiKeyServiceSpec#"revoke 404s for a non-owner (NOT 403) so key ids
 * can't be enumerated").
 *
 * This spec drives every per-ticket method through the same non-owner
 * scenario so the fix can't regress on just one of them.
 */
class SupportServiceCrossUserAccessSpec extends Specification {

    SupportTicketRepository ticketRepository = Mock()
    SupportMessageRepository messageRepository = Mock()
    NotificationService notificationService = Mock()
    TextSanitizer textSanitizer = new TextSanitizer()

    SupportService svc

    /** Ticket 42 belongs to user 10. Every test below has user 99 trying
     *  to touch it. */
    static final long TICKET_ID  = 42L
    static final long OWNER_ID   = 10L
    static final long ATTACKER   = 99L

    def setup() {
        svc = new SupportService(
            ticketRepository:    ticketRepository,
            messageRepository:   messageRepository,
            notificationService: notificationService,
            textSanitizer:       textSanitizer,
            steamUserRepository: null    // skip staff fan-out for this spec
        )
    }

    private SupportTicket otherUsersTicket(String status = 'WAITING_STAFF') {
        new SupportTicket(
            id:       TICKET_ID,
            userId:   OWNER_ID,
            username: 'owner',
            subject:  'help',
            category: 'OTHER',
            status:   status
        )
    }

    def "getTicket 404s for a non-owner (NOT 403) so ticket ids can't be enumerated"() {
        given:
        ticketRepository.findById(TICKET_ID) >> Optional.of(otherUsersTicket())

        when:
        svc.getTicket(ATTACKER, TICKET_ID)

        then:
        // Must be a 404 — IDENTICAL to the missing-id case — so an
        // attacker iterating GET /api/support/tickets/{id} can't tell
        // "exists but not yours" apart from "doesn't exist".
        thrown(NotFoundException)
        // Bonus: must NOT leak the other user's thread.
        0 * messageRepository.findByTicket(_)
    }

    def "getTicket 404s for an unknown id (sanity — the response that non-owner case must match)"() {
        given:
        ticketRepository.findById(_) >> Optional.empty()

        when:
        svc.getTicket(ATTACKER, 999L)

        then:
        thrown(NotFoundException)
    }

    def "reply 404s for a non-owner (NOT 403) and writes nothing"() {
        given:
        ticketRepository.findById(TICKET_ID) >> Optional.of(otherUsersTicket())

        when:
        svc.reply(ATTACKER, 'attacker', TICKET_ID, 'sneaky message')

        then:
        thrown(NotFoundException)
        // No write may happen for a non-owner — neither a message
        // append nor a status flip on the foreign ticket.
        0 * messageRepository.save(_)
        0 * ticketRepository.save(_)
    }

    def "reopen 404s for a non-owner (NOT 403) and writes nothing"() {
        given: "a foreign RESOLVED ticket (status that would normally allow reopen)"
        ticketRepository.findById(TICKET_ID) >> Optional.of(otherUsersTicket('RESOLVED'))

        when:
        svc.reopen(ATTACKER, TICKET_ID)

        then:
        thrown(NotFoundException)
        0 * ticketRepository.save(_)
    }

    def "resolve 404s for a non-owner (NOT 403) and writes nothing"() {
        given: "a foreign OPEN ticket (status that would normally allow resolve)"
        ticketRepository.findById(TICKET_ID) >> Optional.of(otherUsersTicket('WAITING_STAFF'))

        when:
        svc.resolve(ATTACKER, TICKET_ID)

        then:
        thrown(NotFoundException)
        0 * ticketRepository.save(_)
    }

    @Unroll
    def "every per-ticket method gives the SAME exception class for missing-id vs non-owner: #method"() {
        given: """the same SupportService instance is asked twice: once with a
                  wholly missing id (Optional.empty) and once with a foreign
                  ticket (Optional.of(other-user's-ticket)). Pre-fix the two
                  branches returned NotFoundException vs ForbiddenException
                  — a status-code differential an attacker can iterate against
                  to enumerate ticket ids. Post-fix both branches must throw
                  NotFoundException so the responses are indistinguishable."""

        when: "missing id"
        ticketRepository.findById(TICKET_ID) >> Optional.empty()
        invoke.call(svc, ATTACKER, TICKET_ID)

        then:
        def missingEx = thrown(Exception)

        when: "foreign id"
        ticketRepository.findById(TICKET_ID) >> Optional.of(otherUsersTicket('RESOLVED'))
        invoke.call(svc, ATTACKER, TICKET_ID)

        then:
        def foreignEx = thrown(Exception)

        and: "both code paths surface the same exception class — the wire-level response is now indistinguishable"
        missingEx.class == foreignEx.class
        missingEx.class == NotFoundException

        where:
        method       | invoke
        'getTicket'  | { SupportService s, Long u, Long t -> s.getTicket(u, t) }
        'reply'      | { SupportService s, Long u, Long t -> s.reply(u, 'attacker', t, 'hi') }
        'reopen'     | { SupportService s, Long u, Long t -> s.reopen(u, t) }
        'resolve'    | { SupportService s, Long u, Long t -> s.resolve(u, t) }
    }
}
