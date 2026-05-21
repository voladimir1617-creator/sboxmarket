package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SupportService
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification
import spock.lang.Subject

/**
 * Ticket lifecycle coverage. Three paths: create (opens a ticket +
 * synthetic auto-reply), reply (user posts a message, flips state to
 * WAITING_STAFF), resolve (user closes ticket, flips to RESOLVED).
 *
 * All three require the acting user to own the ticket. All three call
 * through the TextSanitizer for subject/body/author fields.
 */
class SupportServiceSpec extends Specification {

    SupportTicketRepository  ticketRepository  = Mock()
    SupportMessageRepository messageRepository = Mock()
    NotificationService      notificationService = Mock()
    TextSanitizer            textSanitizer = Mock()

    @Subject
    SupportService service = new SupportService(
        ticketRepository     : ticketRepository,
        messageRepository    : messageRepository,
        notificationService  : notificationService,
        textSanitizer        : textSanitizer
    )

    def setup() {
        // Default sanitizer behaviour: echo the input back. Individual tests
        // override by re-stubbing inside their `given:` block (Spock's last-
        // declared stub wins for the same method/args pattern).
        textSanitizer.subject(_)    >> { String s -> s }
        textSanitizer.body(_)       >> { String s -> s }
        textSanitizer.cleanShort(_) >> { String s -> s }
    }

    // ── create ────────────────────────────────────────────────────

    def "create opens a ticket plus a user message and an auto-reply"() {
        given:
        ticketRepository.save(_) >> { args -> def t = args[0]; t.id = t.id ?: 1L; t }
        messageRepository.save(_) >> { args -> args[0] }

        when:
        def ticket = service.create(10L, 'Alice', 'My deposit is stuck', 'PAYMENT', 'Help please')

        then:
        ticket.userId == 10L
        ticket.subject == 'My deposit is stuck'
        ticket.category == 'PAYMENT'
        ticket.status == 'WAITING_USER'
        // USER message + STAFF auto-reply
        2 * messageRepository.save({ SupportMessage m -> m.ticketId == 1L })
        1 * notificationService.push(10L, 'SUPPORT_REPLY', _, _, _, _)
    }

    def "create auto-reply picks a category-specific template"() {
        given:
        def saved = []
        ticketRepository.save(_) >> { args -> def t = args[0]; t.id = 1L; t }
        messageRepository.save(_) >> { args -> saved << args[0]; args[0] }

        when:
        service.create(10L, 'Alice', 'refund please', 'PAYMENT', 'body')

        then:
        def staffMsg = saved.find { it.author == 'STAFF' }
        staffMsg != null
        staffMsg.body.toLowerCase().contains('payment') || staffMsg.body.toLowerCase().contains('deposit')
    }

    def "create preserves newlines in a multi-paragraph body"() {
        given:
        // The report-user controller hands create() a multi-line templated
        // body (Reporter / Target / Reason / Context). TextSanitizer.body()
        // collapses every \n to a space; sanitizeMultiline() must keep the
        // paragraph breaks so the CSR can read the report.
        def saved = []
        ticketRepository.save(_) >> { args -> def t = args[0]; t.id = 1L; t }
        messageRepository.save(_) >> { args -> saved << args[0]; args[0] }
        def multiline = "Reporter: alice\nTarget: bob\n\nContext:\nThey scammed me"

        when:
        service.create(10L, 'Alice', 'Report', 'FRAUD', multiline)

        then:
        def userMsg = saved.find { it.author == 'USER' }
        userMsg.body.contains('\n')
        userMsg.body.contains('Reporter: alice')
        userMsg.body.contains('Context:')
        // The blank line between Target and Context survives as exactly one \n\n.
        userMsg.body.contains('Target: bob\n\nContext:')
    }

    def "create clamps blank-line runs in the body to a single blank line"() {
        given:
        def saved = []
        ticketRepository.save(_) >> { args -> def t = args[0]; t.id = 1L; t }
        messageRepository.save(_) >> { args -> saved << args[0]; args[0] }
        // 6 consecutive newlines — a hostile body padding the row with blanks.
        def padded = "line one\n\n\n\n\n\nline two"

        when:
        service.create(10L, 'Alice', 'subject', 'OTHER', padded)

        then:
        def userMsg = saved.find { it.author == 'USER' }
        // Clamped to one blank line — never 5 of them.
        userMsg.body == 'line one\n\nline two'
    }

    def "create normalises the category (uppercase + strip non-alpha)"() {
        given:
        ticketRepository.save(_) >> { args -> def t = args[0]; t.id = 1L; t }
        messageRepository.save(_) >> { args -> args[0] }

        when:
        def ticket = service.create(10L, 'Alice', 'subject', 'trade  :)', 'body')

        then:
        ticket.category == 'TRADE'
    }

    def "create refuses empty subject"() {
        given:
        // Use a fresh sanitizer that returns empty for subject() — the default
        // echo-back stub in setup() would otherwise hand the input back through.
        def emptySubjectSanitizer = Mock(TextSanitizer) {
            subject(_) >> ''
            body(_)    >> { String s -> s ?: '' }
            cleanShort(_) >> { String s -> s }
        }
        def svc = new SupportService(
            ticketRepository   : ticketRepository,
            messageRepository  : messageRepository,
            notificationService: notificationService,
            textSanitizer      : emptySubjectSanitizer
        )

        when:
        svc.create(10L, 'Alice', '<script></script>', 'OTHER', 'body')

        then:
        thrown(BadRequestException)
    }

    def "create refuses empty body"() {
        given:
        def emptyBodySanitizer = Mock(TextSanitizer) {
            subject(_) >> { String s -> s ?: '' }
            body(_)    >> ''
            cleanShort(_) >> { String s -> s }
        }
        def svc = new SupportService(
            ticketRepository   : ticketRepository,
            messageRepository  : messageRepository,
            notificationService: notificationService,
            textSanitizer      : emptyBodySanitizer
        )

        when:
        svc.create(10L, 'Alice', 'subject', 'OTHER', '<script></script>')

        then:
        thrown(BadRequestException)
    }

    // ── reply ─────────────────────────────────────────────────────

    def "reply appends a user message and flips state to WAITING_STAFF"() {
        given:
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_USER')
        ticketRepository.findById(1L) >> Optional.of(ticket)
        ticketRepository.save(_) >> { args -> args[0] }
        messageRepository.save(_) >> { args -> def m = args[0]; m.id = 1L; m }

        when:
        def msg = service.reply(10L, 'Alice', 1L, 'still stuck')

        then:
        msg != null
        msg.author == 'USER'
        ticket.status == 'WAITING_STAFF'
    }

    def "reply forbids non-owner"() {
        given:
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_USER')
        ticketRepository.findById(_) >> Optional.of(ticket)

        when:
        service.reply(99L, 'Mallory', 1L, 'haha')

        then:
        thrown(ForbiddenException)
    }

    def "reply refuses on a RESOLVED ticket"() {
        given:
        ticketRepository.findById(_) >> Optional.of(new SupportTicket(id: 1L, userId: 10L, status: 'RESOLVED'))

        when:
        service.reply(10L, 'Alice', 1L, 'one more thing')

        then:
        thrown(BadRequestException)
    }

    def "reply refuses empty body"() {
        given:
        ticketRepository.findById(_) >> Optional.of(new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_USER'))
        def emptyBodySanitizer = Mock(TextSanitizer) {
            body(_)    >> ''
            cleanShort(_) >> { String s -> s }
        }
        def svc = new SupportService(
            ticketRepository   : ticketRepository,
            messageRepository  : messageRepository,
            notificationService: notificationService,
            textSanitizer      : emptyBodySanitizer
        )

        when:
        svc.reply(10L, 'Alice', 1L, '<script></script>')

        then:
        thrown(BadRequestException)
    }

    def "reply 404s for unknown ticket id"() {
        given:
        ticketRepository.findById(_) >> Optional.empty()

        when:
        service.reply(10L, 'Alice', 999L, 'x')

        then:
        thrown(NotFoundException)
    }

    // ── resolve ───────────────────────────────────────────────────

    def "resolve flips status to RESOLVED for the owner"() {
        given:
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_USER')
        ticketRepository.findById(1L) >> Optional.of(ticket)
        ticketRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.resolve(10L, 1L)

        then:
        result.status == 'RESOLVED'
    }

    def "resolve forbids non-owner"() {
        given:
        ticketRepository.findById(_) >> Optional.of(new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_USER'))

        when:
        service.resolve(99L, 1L)

        then:
        thrown(ForbiddenException)
    }

    def "resolve refuses an already-RESOLVED ticket (state-machine guard)"() {
        given:
        // Double-resolve from a stale tab / double-click. Mirrors reply()'s
        // RESOLVED guard — should 400, not silently re-save and bump updatedAt.
        ticketRepository.findById(1L) >> Optional.of(
            new SupportTicket(id: 1L, userId: 10L, status: 'RESOLVED'))

        when:
        service.resolve(10L, 1L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'ALREADY_RESOLVED'
        0 * ticketRepository.save(_)
    }

    def "resolve 404s for unknown ticket id"() {
        given:
        ticketRepository.findById(_) >> Optional.empty()

        when:
        service.resolve(10L, 999L)

        then:
        thrown(NotFoundException)
    }

    // ── getTicket ─────────────────────────────────────────────────

    def "getTicket returns ticket + messages for owner"() {
        given:
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_USER')
        ticketRepository.findById(1L) >> Optional.of(ticket)
        messageRepository.findByTicket(1L) >> [new SupportMessage(id: 1L, ticketId: 1L, author: 'USER')]

        when:
        def result = service.getTicket(10L, 1L)

        then:
        result.ticket == ticket
        result.messages.size() == 1
    }

    def "getTicket forbids non-owner"() {
        given:
        ticketRepository.findById(_) >> Optional.of(new SupportTicket(id: 1L, userId: 10L))

        when:
        service.getTicket(99L, 1L)

        then:
        thrown(ForbiddenException)
    }

    // ── sweepStaleWaitingUser (batch 553) ───────────────────────────
    //
    // Daily auto-resolve sweep for tickets that staff replied to and
    // the user never came back to. Prevents a long-running site's
    // support queue from accumulating dead WAITING_USER threads.

    def "sweepStaleWaitingUser flips stale tickets to RESOLVED and pushes TICKET_AUTO_RESOLVED (batch 553)"() {
        given:
        service.autoResolveWaitingUserDays = 14L
        def stale1 = new SupportTicket(id: 1L, userId: 10L, subject: 'where is my deposit', status: 'WAITING_USER', updatedAt: 1L)
        def stale2 = new SupportTicket(id: 2L, userId: 20L, subject: null, status: 'WAITING_USER', updatedAt: 1L)
        ticketRepository.findStaleWaitingUser(_) >> [stale1, stale2]
        ticketRepository.save(_) >> { args -> args[0] }

        when:
        service.sweepStaleWaitingUser()

        then:
        stale1.status == 'RESOLVED'
        stale2.status == 'RESOLVED'
        1 * notificationService.push(10L, 'TICKET_AUTO_RESOLVED', _, _, 1L, '/support')
        1 * notificationService.push(20L, 'TICKET_AUTO_RESOLVED', _, _, 2L, '/support')
    }

    def "sweepStaleWaitingUser is a no-op when disabled (0 days)"() {
        given:
        service.autoResolveWaitingUserDays = 0L

        when:
        service.sweepStaleWaitingUser()

        then:
        0 * ticketRepository.findStaleWaitingUser(_)
        0 * ticketRepository.save(_)
        0 * notificationService.push(*_)
    }

    def "sweepStaleWaitingUser keeps going if one row's save throws (batch 553)"() {
        given:
        service.autoResolveWaitingUserDays = 14L
        def good = new SupportTicket(id: 1L, userId: 10L, subject: 'ok', status: 'WAITING_USER', updatedAt: 1L)
        def bad  = new SupportTicket(id: 2L, userId: 20L, subject: 'boom', status: 'WAITING_USER', updatedAt: 1L)
        ticketRepository.findStaleWaitingUser(_) >> [bad, good]
        ticketRepository.save(bad)  >> { throw new RuntimeException('db flake') }
        ticketRepository.save(good) >> { args -> args[0] }

        when:
        service.sweepStaleWaitingUser()

        then:
        good.status == 'RESOLVED'
        // The good ticket still got its auto-resolved push even though
        // the bad one blew up mid-batch — per-row isolation holds.
        1 * notificationService.push(10L, 'TICKET_AUTO_RESOLVED', _, _, 1L, '/support')
    }
}
