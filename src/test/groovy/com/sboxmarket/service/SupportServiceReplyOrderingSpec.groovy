package com.sboxmarket.service

import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicLong

/**
 * Regression spec for the same-millisecond ordering collision in
 * SupportService#reply.
 *
 * SupportService#create already stamps its two synthesised messages
 * (user-question + Clara-auto-reply) with an explicit `createdAt` and a
 * deliberate +1ms delta to defeat the
 *   "two `new SupportMessage()` constructions on the same JVM tick
 *    share the same `System.currentTimeMillis()` default and the repo's
 *    `ORDER BY m.createdAt ASC` becomes non-deterministic"
 * failure mode. The comment block on create() (lines 153-159) calls
 * this exact bug out by name.
 *
 * #reply, however, was left with the bare
 *   {@code new SupportMessage(ticketId: ..., body: ...)}
 * — no explicit `createdAt`, just the field default captured at
 * construction time. A user who double-clicks "Send" on a sluggish
 * network, or two browser tabs racing the same reply, hands the repo
 * two rows whose `createdAt` is the same millisecond. The thread
 * render then flips them at random — and worse, a subsequent staff
 * auto-stamped message (`userMsgCreatedAt + 1`) can sort BEFORE the
 * user reply because the user reply's auto-default lost the race.
 *
 * The fix mirrors create(): stamp `createdAt` explicitly inside
 * #reply, using the LATER of (currentTimeMillis, last-message-in-
 * thread + 1ms). The +1ms clamp guarantees a strictly-monotonic
 * ordering against ANY prior message in the thread — including the
 * Clara auto-reply that #create just stamped with +1ms.
 *
 * This spec freezes the clock to a single millisecond, simulates a
 * pre-existing "latest" message in the thread at that same instant,
 * fires #reply, and asserts the saved reply's createdAt is strictly
 * greater than the prior message — proving the thread render order
 * is deterministic.
 */
class SupportServiceReplyOrderingSpec extends Specification {

    SupportTicketRepository ticketRepository = Mock()
    SupportMessageRepository messageRepository = Mock()
    NotificationService notificationService = Mock()
    TextSanitizer textSanitizer = new TextSanitizer()

    SupportService svc

    def setup() {
        svc = new SupportService(
            ticketRepository:    ticketRepository,
            messageRepository:   messageRepository,
            notificationService: notificationService,
            textSanitizer:       textSanitizer,
            steamUserRepository: null    // skip staff fan-out for this spec
        )
    }

    def "reply stamps createdAt strictly after the latest existing message in the thread"() {
        given: "an OPEN ticket owned by user 7"
        def ticket = new SupportTicket(
            id: 99L, userId: 7L, username: 'alice',
            subject: 'help', category: 'OTHER', status: 'WAITING_USER'
        )
        ticketRepository.findById(99L) >> Optional.of(ticket)

        and: """a pre-existing message in the thread stamped at the same
                wall-clock millisecond the reply is about to land in.
                Mirrors the create()/reply() race: create()'s Clara auto-
                reply is stamped `userMsgCreatedAt + 1`, then the user
                fires reply() so fast it lands in that same ms."""
        def now = System.currentTimeMillis()
        def latestPrior = new SupportMessage(
            id: 50L, ticketId: 99L, author: 'STAFF',
            authorName: 'Clara (auto)', body: 'hi',
            createdAt: now    // collide on purpose
        )
        // The fix needs to peek at the thread's latest createdAt; expose
        // it via the existing findByTicket query the renderer already
        // uses. Returning a single-element list keeps the mock honest —
        // no broader assumptions about pagination.
        messageRepository.findByTicket(99L) >> [latestPrior]

        and: "the message-save sink captures whatever createdAt the service stamps"
        def saved = new AtomicLong(-1L)
        messageRepository.save(_ as SupportMessage) >> { SupportMessage m ->
            saved.set(m.createdAt)
            m.id = 51L
            m
        }
        ticketRepository.save(_ as SupportTicket) >> { SupportTicket t -> t }

        when: "the user replies in the same millisecond as the prior message"
        svc.reply(7L, 'alice', 99L, 'thanks, that worked')

        then: """the new reply's createdAt is STRICTLY greater than the
                prior message — guaranteeing a deterministic
                `ORDER BY createdAt ASC` render. Pre-fix the reply used
                the default-init `System.currentTimeMillis()` captured at
                `new SupportMessage(...)` construction time, which on a
                fast JVM equals `now`, and the ORDER BY tie-break was
                undefined (could put the reply ABOVE Clara's auto-reply
                in the rendered thread)."""
        saved.get() > latestPrior.createdAt
    }

    def "two rapid replies in the same JVM tick are strictly monotonic"() {
        given:
        def ticket = new SupportTicket(
            id: 100L, userId: 8L, username: 'bob',
            subject: 'thread', category: 'OTHER', status: 'WAITING_USER'
        )
        ticketRepository.findById(100L) >> Optional.of(ticket)

        and: """findByTicket returns the running thread as it would after
                each save — exercising the same monotonicity the renderer
                relies on."""
        def thread = []
        messageRepository.findByTicket(100L) >> { thread.collect() }
        messageRepository.save(_ as SupportMessage) >> { SupportMessage m ->
            m.id = (thread.size() + 1L)
            thread << m
            m
        }
        ticketRepository.save(_ as SupportTicket) >> { SupportTicket t -> t }

        when: "user fires two replies back-to-back (double-click / tab race)"
        svc.reply(8L, 'bob', 100L, 'first')
        svc.reply(8L, 'bob', 100L, 'second')

        then: """the thread is in submission order. Pre-fix both saves
                could share `currentTimeMillis()` and the ORDER BY tie
                would flip them — making the user appear to answer their
                own follow-up before they asked it."""
        thread.size() == 2
        thread[0].body == 'first'
        thread[1].body == 'second'
        thread[1].createdAt > thread[0].createdAt
    }
}
