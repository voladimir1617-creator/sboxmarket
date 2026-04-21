package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Support ticket thread orchestration. Users can open tickets and reply; staff
 * replies (for now synthesised by a rule-based auto-responder) fire from here
 * too so the thread always has a two-sided conversation on day one.
 */
@Service
@Slf4j
class SupportService {

    @Autowired SupportTicketRepository ticketRepository
    @Autowired SupportMessageRepository messageRepository
    @Autowired NotificationService notificationService
    @Autowired TextSanitizer textSanitizer
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository

    /** Tiny FAQ-style auto-responder. Real staff can still reply later. */
    private static String autoReply(String category, String subject) {
        switch ((category ?: 'OTHER').toUpperCase()) {
            case 'PAYMENT':
                return "Thanks for reaching out about payments. Most deposits clear within 2 minutes — " +
                       "if yours hasn't, please include the Stripe session id from your Trades tab so we can investigate."
            case 'TRADE':
                return "Trade questions usually resolve themselves within the 8-day Steam hold. If the listing is " +
                       "already marked sold, the buyer has confirmed receipt and funds should release automatically."
            case 'REFUND':
                return "Thanks — a refund specialist will review your trade details and the item delivery status, " +
                       "then follow up here. Typical review turnaround is under 24 hours. If you have a screenshot " +
                       "of the Steam trade offer (or the missing / mismatched item) feel free to attach it in a reply."
            case 'ACCOUNT':
                return "For account issues, please confirm the Steam ID64 shown in your Personal Info tab. We can " +
                       "verify your session from that value and reset anything that looks off."
            case 'BUG':
                return "Thanks for the bug report — tell us which browser you're on and the last action you took " +
                       "before it happened. A screenshot of the DevTools console helps too."
            default:
                return "Thanks for reaching out, a support agent will reply shortly. In the meantime you can " +
                       "browse the FAQ via the Help menu."
        }
    }

    List<SupportTicket> listForUser(Long userId) {
        ticketRepository.findByUser(userId)
    }

    Map getTicket(Long userId, Long ticketId) {
        def t = ticketRepository.findById(ticketId)
            .orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        if (t.userId != userId) throw new ForbiddenException("Not your ticket")
        [ticket: t, messages: messageRepository.findByTicket(ticketId)]
    }

    @Transactional
    SupportTicket create(Long userId, String username, String subject, String category, String body) {
        // Sanitize EVERYTHING at the ingestion boundary — HTML tags, JS
        // protocols, on* attributes, and HTML entities are stripped. The
        // stored values are guaranteed safe to render as plain text.
        def cleanSubject = textSanitizer.subject(subject)
        def cleanBody    = textSanitizer.body(body)
        def cleanName    = textSanitizer.cleanShort(username)
        if (!cleanSubject || cleanSubject.isEmpty()) {
            throw new BadRequestException("INVALID_SUBJECT", "Subject is required")
        }
        if (!cleanBody || cleanBody.isEmpty()) {
            throw new BadRequestException("INVALID_BODY", "Message body is required")
        }
        def ticket = new SupportTicket(
            userId:   userId,
            username: cleanName,
            subject:  cleanSubject,
            category: (category ?: 'OTHER').toUpperCase().replaceAll(/[^A-Z_]/, ''),
            status:   'WAITING_STAFF'
        )
        ticketRepository.save(ticket)

        messageRepository.save(new SupportMessage(
            ticketId:   ticket.id,
            author:     'USER',
            authorName: cleanName,
            body:       cleanBody
        ))
        // Synthesised first staff response so the thread isn't empty
        messageRepository.save(new SupportMessage(
            ticketId:   ticket.id,
            author:     'STAFF',
            authorName: 'Clara (auto)',
            body:       autoReply(category, subject)
        ))
        ticket.status = 'WAITING_USER'
        ticket.updatedAt = System.currentTimeMillis()
        ticketRepository.save(ticket)

        notificationService?.push(userId, 'SUPPORT_REPLY',
            "Support opened · #${ticket.id}",
            "A support agent has replied to your ticket", ticket.id, '/support')
        // Admin + CSR fan-out (batch 505). New tickets used to land
        // silently in /admin?tab=tickets and wait for someone to
        // manually reload — shift gaps meant a REFUND category ticket
        // could sit for hours before anyone saw it. Now every ADMIN
        // and CSR role-user gets a bell ping the moment the ticket
        // opens, deep-linked straight to the tickets tab. Uses the
        // same role-indexed `findByRole` as the chargeback fan-out so
        // the query stays O(staff count), not O(total users).
        if (steamUserRepository != null) {
            try {
                def catLabel = ticket.category ?: 'OTHER'
                def staff = []
                staff.addAll(steamUserRepository.findByRole('ADMIN') ?: [])
                staff.addAll(steamUserRepository.findByRole('CSR') ?: [])
                staff.unique { it.id }.each { s ->
                    try {
                        notificationService.push(s.id, 'SUPPORT_REPLY',
                            "New support ticket · #${ticket.id}",
                            "[${catLabel}] ${cleanSubject.take(100)} — from ${cleanName ?: 'user ' + userId}",
                            ticket.id, '/admin?tab=tickets')
                    } catch (Exception e) {
                        log.warn("New-ticket staff push failed for uid=${s.id}: ${e.message}")
                    }
                }
            } catch (Exception e) {
                log.warn("New-ticket staff fan-out failed for ticket ${ticket.id}: ${e.message}")
            }
        }
        ticket
    }

    @Transactional
    SupportMessage reply(Long userId, String username, Long ticketId, String body) {
        def ticket = ticketRepository.findById(ticketId)
            .orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        if (ticket.userId != userId) throw new ForbiddenException("Not your ticket")
        if (ticket.status == 'RESOLVED') {
            throw new BadRequestException("RESOLVED", "Ticket is already resolved")
        }
        def cleanBody = textSanitizer.body(body)
        def cleanName = textSanitizer.cleanShort(username)
        if (!cleanBody || cleanBody.isEmpty()) {
            throw new BadRequestException("INVALID_BODY", "Message body is required")
        }
        def msg = messageRepository.save(new SupportMessage(
            ticketId:   ticketId,
            author:     'USER',
            authorName: cleanName,
            body:       cleanBody
        ))
        ticket.status = 'WAITING_STAFF'
        ticket.updatedAt = System.currentTimeMillis()
        ticketRepository.save(ticket)
        // Staff fan-out on user reply (batch 505). Same rationale as
        // the new-ticket fan-out above: a user reply flips status to
        // WAITING_STAFF, but without an active push, a busy shift
        // wouldn't see the signal until they manually filtered the
        // tickets tab. Ping both ADMIN and CSR so whoever has capacity
        // picks it up.
        if (steamUserRepository != null) {
            try {
                def staff = []
                staff.addAll(steamUserRepository.findByRole('ADMIN') ?: [])
                staff.addAll(steamUserRepository.findByRole('CSR') ?: [])
                staff.unique { it.id }.each { s ->
                    try {
                        notificationService?.push(s.id, 'SUPPORT_REPLY',
                            "User reply on ticket #${ticketId}",
                            "${cleanName ?: 'User ' + userId}: ${cleanBody.take(120)}",
                            ticketId, '/admin?tab=tickets')
                    } catch (Exception e) {
                        log.warn("User-reply staff push failed for uid=${s.id}: ${e.message}")
                    }
                }
            } catch (Exception e) {
                log.warn("User-reply staff fan-out failed for ticket ${ticketId}: ${e.message}")
            }
        }
        msg
    }

    /**
     * Reopen a RESOLVED ticket (batch 858). Flips status back to
     * WAITING_STAFF so the same thread continues instead of the user
     * having to open a brand-new ticket (losing the previously built
     * context). Staff are re-notified via the same fan-out pattern as
     * user replies so whoever picks up the reopened thread sees it
     * without manually filtering the tickets queue.
     */
    @Transactional
    SupportTicket reopen(Long userId, Long ticketId) {
        def ticket = ticketRepository.findById(ticketId)
            .orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        if (ticket.userId != userId) throw new ForbiddenException("Not your ticket")
        if (ticket.status != 'RESOLVED') {
            throw new BadRequestException("NOT_RESOLVED",
                "Only resolved tickets can be reopened")
        }
        ticket.status = 'WAITING_STAFF'
        ticket.updatedAt = System.currentTimeMillis()
        ticketRepository.save(ticket)
        // Staff fan-out — mirrors the reply-path ping so the reopened
        // thread doesn't sit silent on the queue.
        if (steamUserRepository != null) {
            try {
                def staff = []
                staff.addAll(steamUserRepository.findByRole('ADMIN') ?: [])
                staff.addAll(steamUserRepository.findByRole('CSR') ?: [])
                staff.unique { it.id }.each { s ->
                    try {
                        notificationService?.push(s.id, 'SUPPORT_REPLY',
                            "Ticket #${ticketId} reopened",
                            "User reopened the resolved thread.",
                            ticketId, '/admin?tab=tickets')
                    } catch (Exception e) {
                        log.warn("Reopen staff push failed for uid=${s.id}: ${e.message}")
                    }
                }
            } catch (Exception e) {
                log.warn("Reopen staff fan-out failed for ticket ${ticketId}: ${e.message}")
            }
        }
        ticket
    }

    @Transactional
    SupportTicket resolve(Long userId, Long ticketId) {
        def ticket = ticketRepository.findById(ticketId)
            .orElseThrow { new NotFoundException("SupportTicket", ticketId) }
        if (ticket.userId != userId) throw new ForbiddenException("Not your ticket")
        ticket.status = 'RESOLVED'
        ticket.updatedAt = System.currentTimeMillis()
        ticketRepository.save(ticket)
    }

    /** Days a WAITING_USER ticket sits idle before the sweeper auto-
     *  resolves it (batch 553). 14d = two weeks after staff's last
     *  reply. Configurable for ops who prefer a tighter / looser
     *  window. Set to 0 to disable the sweep entirely. */
    @Value('${support.auto-resolve-waiting-user-days:14}')
    long autoResolveWaitingUserDays

    /**
     * Daily sweep — flips any WAITING_USER ticket idle longer than
     * {@link #autoResolveWaitingUserDays} days to RESOLVED and pushes
     * a TICKET_AUTO_RESOLVED notification so the user still sees
     * closure in the bell (and can re-open with a fresh ticket if
     * needed). Prevents a long-running site's /support page from
     * accumulating dead threads where staff answered and the user
     * never came back.
     *
     * Runs once a day at a 45-minute offset so it doesn't collide with
     * the notification-retention sweeper at 30m or the cart sweeper at
     * 60m. Logs a row count for ops visibility.
     */
    @Scheduled(fixedDelay = 24L * 60L * 60L * 1000L,
               initialDelay = 45L * 60L * 1000L)
    @Transactional
    void sweepStaleWaitingUser() {
        if (autoResolveWaitingUserDays <= 0L) return
        def cutoff = System.currentTimeMillis() - (autoResolveWaitingUserDays * 24L * 60L * 60L * 1000L)
        def candidates
        try {
            candidates = ticketRepository.findStaleWaitingUser(cutoff)
        } catch (Exception e) {
            log.warn("Stale ticket sweep query failed: ${e.message}")
            return
        }
        if (candidates == null || candidates.isEmpty()) return
        int closed = 0
        candidates.each { t ->
            try {
                t.status = 'RESOLVED'
                t.updatedAt = System.currentTimeMillis()
                ticketRepository.save(t)
                notificationService?.push(t.userId, 'TICKET_AUTO_RESOLVED',
                    "Support ticket auto-closed · ${t.subject ?: 'your question'}",
                    "Staff didn't hear back from you within ${autoResolveWaitingUserDays} days, so the thread was auto-closed. Open a new ticket any time if you still need help.",
                    t.id, '/support')
                closed++
            } catch (Exception e) {
                log.warn("Auto-close failed for ticket ${t.id}: ${e.message}")
            }
        }
        if (closed > 0) {
            log.info("Support sweeper: auto-closed ${closed} stale WAITING_USER ticket(s) idle >${autoResolveWaitingUserDays}d")
        }
    }
}
