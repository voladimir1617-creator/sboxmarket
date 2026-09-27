package com.sboxmarket

import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SupportService
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification

/**
 * Wave 127 multi-pod race regression pin for
 * {@link SupportService#sweepStaleWaitingUser}.
 *
 * Two race classes pinned together:
 *
 *   1. MULTI-POD: `findStaleWaitingUser` was read concurrently by every
 *      pod's daily sweeper. Both pods saw the SAME WAITING_USER row, both
 *      flipped status=RESOLVED, both fired TICKET_AUTO_RESOLVED bell push
 *      — user received the auto-close ping TWICE for ONE ticket.
 *
 *   2. USER-REPLY OUT-OF-BAND: a user replying between sweeper read and
 *      save flips status WAITING_USER → WAITING_STAFF. The old
 *      unconditional save() would have STOMPED that with RESOLVED,
 *      silently discarding the user's reply and closing a ticket they
 *      expected staff to read.
 *
 * Fix: per-row `claimAutoResolve` conditional UPDATE flips
 * WAITING_USER→RESOLVED only WHERE status is still WAITING_USER and
 * returns 1 on win, 0 on losing-claim or status-already-changed.
 *
 * Same shape as waves 112, 120, 124, 125, 126.
 */
class SupportServiceAutoCloseClaimSpec extends Specification {

    SupportTicketRepository  ticketRepository    = Mock()
    SupportMessageRepository messageRepository   = Mock()
    NotificationService      notificationService = Mock()
    TextSanitizer            textSanitizer       = Mock()

    SupportService service = new SupportService(
        ticketRepository:    ticketRepository,
        messageRepository:   messageRepository,
        notificationService: notificationService,
        textSanitizer:       textSanitizer,
        autoResolveWaitingUserDays: 7L
    )

    private SupportTicket makeStale(long id) {
        new SupportTicket(
            id:        id,
            userId:    42L,
            subject:   'My ticket',
            status:    'WAITING_USER',
            updatedAt: System.currentTimeMillis() - (10L * 24L * 60L * 60L * 1000L)
        )
    }

    // ── (1) Winning claim → fan-out fires ─────────────────────────────────

    def "sweepStaleWaitingUser fires TICKET_AUTO_RESOLVED when claim returns 1 (this pod won)"() {
        given:
        def t = makeStale(1001L)
        ticketRepository.findStaleWaitingUser(_) >> [t]
        ticketRepository.claimAutoResolve(1001L, _) >> 1

        when:
        service.sweepStaleWaitingUser()

        then:
        1 * notificationService.push(42L, 'TICKET_AUTO_RESOLVED', _, _, 1001L, _)
    }

    // ── (2) Losing claim → fan-out skipped ────────────────────────────────

    def "sweepStaleWaitingUser bails when claim returns 0 (sibling pod or user reply)"() {
        given: "the user replied between sweeper read and claim — status flipped WAITING_USER → WAITING_STAFF"
        def t = makeStale(2002L)
        ticketRepository.findStaleWaitingUser(_) >> [t]
        ticketRepository.claimAutoResolve(2002L, _) >> 0

        when:
        service.sweepStaleWaitingUser()

        then: "no notification — losing the claim means the user's reply (or sibling pod's work) stands"
        0 * notificationService.push(_, _, _, _, _, _)
    }

    // ── (3) sweep does NOT call save() — conditional UPDATE handles it ────

    def "sweepStaleWaitingUser does NOT call save() — the claim UPDATE is the only persistence path"() {
        given:
        def t = makeStale(3003L)
        ticketRepository.findStaleWaitingUser(_) >> [t]
        ticketRepository.claimAutoResolve(3003L, _) >> 1

        when:
        service.sweepStaleWaitingUser()

        then: "no .save(t) — would race with a concurrent CSR reply or user-side message that changed status"
        0 * ticketRepository.save(_)
    }

    // ── (4) Mixed batch — only the won-claim rows notify ──────────────────

    def "sweepStaleWaitingUser in a 5-row batch with 2 won claims fires exactly 2 notifications"() {
        given:
        def tickets = (4001L..4005L).collect { makeStale(it) }
        ticketRepository.findStaleWaitingUser(_) >> tickets
        ticketRepository.claimAutoResolve(4001L, _) >> 1
        ticketRepository.claimAutoResolve(4002L, _) >> 0
        ticketRepository.claimAutoResolve(4003L, _) >> 1
        ticketRepository.claimAutoResolve(4004L, _) >> 0
        ticketRepository.claimAutoResolve(4005L, _) >> 0

        when:
        service.sweepStaleWaitingUser()

        then:
        2 * notificationService.push(_, 'TICKET_AUTO_RESOLVED', _, _, _, _)
    }
}
