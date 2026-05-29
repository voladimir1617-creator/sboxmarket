package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SupportService
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression spec for the per-user open-ticket DoS in SupportService.
 *
 * Bug: SupportService.create had no per-user cap on open tickets. Every
 * new ticket fans a SUPPORT_REPLY bell push to every ADMIN + CSR (see
 * SupportService.create staff fan-out at lines 202-221 of the original
 * file). Without a cap a single authenticated user could open
 * arbitrarily many tickets and flood the staff inbox — a free DoS on
 * the shift queue. Other per-user-bounded resources in this codebase
 * (saved searches, cart items, watchlist items) all carry an explicit
 * MAX_PER_USER cap enforced at the create boundary; support tickets
 * were the lone outlier.
 *
 * Fix: SupportService.create now consults a new
 * SupportTicketRepository.countOpenByUser(uid) (non-RESOLVED rows only,
 * so the cap is on the live queue) and throws BadRequestException
 * "TOO_MANY_OPEN_TICKETS" once the user is already at
 * MAX_OPEN_TICKETS_PER_USER. RESOLVED tickets don't count so a
 * long-tenure account's archive doesn't lock them out forever.
 */
class SupportServicePerUserOpenCapSpec extends Specification {

    SupportTicketRepository  ticketRepository    = Mock()
    SupportMessageRepository messageRepository   = Mock()
    NotificationService      notificationService = Mock()
    TextSanitizer            textSanitizer       = Mock()

    @Subject
    SupportService service = new SupportService(
        ticketRepository:    ticketRepository,
        messageRepository:   messageRepository,
        notificationService: notificationService,
        textSanitizer:       textSanitizer,
        steamUserRepository: null  // skip staff fan-out for this spec
    )

    def setup() {
        // Default sanitizer behaviour: echo the input back. Matches the
        // pattern in the existing SupportServiceSpec.
        textSanitizer.subject(_)    >> { String s -> s }
        textSanitizer.body(_)       >> { String s -> s }
        textSanitizer.cleanShort(_) >> { String s -> s }
    }

    def "create rejects when the user is already at the open-ticket cap"() {
        given: "user 10 already has MAX_OPEN_TICKETS_PER_USER open tickets"
        ticketRepository.countOpenByUser(10L) >> (long) SupportService.MAX_OPEN_TICKETS_PER_USER

        when:
        service.create(10L, 'Alice', 'one more please', 'BUG', 'body')

        then:
        BadRequestException ex = thrown()
        ex.code == 'TOO_MANY_OPEN_TICKETS'
        // No ticket save — the cap must fire BEFORE the row hits the DB
        // (otherwise the cap is racy against the very INSERT it's
        // supposed to gate).
        0 * ticketRepository.save(_)
        // No message save — the auto-reply must not synthesise either.
        0 * messageRepository.save(_)
        // No bell push — the staff fan-out must not happen.
        0 * notificationService.push(*_)
    }

    def "create rejects even when the user is well over the cap (spammer already past it)"() {
        given: "user 10 has somehow ended up with double the cap open"
        ticketRepository.countOpenByUser(10L) >> (long) (SupportService.MAX_OPEN_TICKETS_PER_USER * 2)

        when:
        service.create(10L, 'Alice', 'spam', 'OTHER', 'body')

        then:
        BadRequestException ex = thrown()
        ex.code == 'TOO_MANY_OPEN_TICKETS'
        0 * ticketRepository.save(_)
    }

    def "create succeeds when the user is exactly one below the cap"() {
        given:
        // Headroom of 1 — the (count >= cap) gate must use strict
        // greater-or-equal, not >, so the cap is the actual upper bound.
        ticketRepository.countOpenByUser(10L) >> (long) (SupportService.MAX_OPEN_TICKETS_PER_USER - 1)
        ticketRepository.save(_) >> { args -> def t = args[0]; t.id = t.id ?: 1L; t }
        messageRepository.save(_) >> { args -> args[0] }

        when:
        def ticket = service.create(10L, 'Alice', 'real issue', 'BUG', 'body')

        then:
        noExceptionThrown()
        ticket != null
        ticket.userId == 10L
        // First save (open ticket) + second save (status flip to
        // WAITING_USER after auto-reply) — matches the create flow in
        // SupportService.create.
        (2.._) * ticketRepository.save(_ as SupportTicket)
    }

    def "create succeeds when the user has no open tickets at all"() {
        given:
        ticketRepository.countOpenByUser(10L) >> 0L
        ticketRepository.save(_) >> { args -> def t = args[0]; t.id = 1L; t }
        messageRepository.save(_) >> { args -> args[0] }

        when:
        def ticket = service.create(10L, 'Alice', 'first ticket', 'PAYMENT', 'body')

        then:
        noExceptionThrown()
        ticket.status == 'WAITING_USER'
    }

    def "the cap is consulted BEFORE sanitization so abusive input doesn't burn CPU"() {
        given:
        ticketRepository.countOpenByUser(10L) >> (long) SupportService.MAX_OPEN_TICKETS_PER_USER

        when:
        service.create(10L, 'Alice', 'spam', 'OTHER', 'body')

        then:
        thrown(BadRequestException)
        // The sanitizer must NOT run when the cap has already been hit —
        // a spammer would otherwise burn the per-line sanitizeMultiline
        // pass on a 2000-char body for every rejected request.
        0 * textSanitizer.subject(_)
        0 * textSanitizer.body(_)
    }
}
