package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
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

    /**
     * Newline-preserving body sanitizer. {@link TextSanitizer#body} collapses
     * EVERY whitespace run — including \n — into a single space, which flattens
     * a multi-paragraph ticket body (and the carefully templated report-user
     * body: Reporter / Target / Reason / Context) into one unreadable line for
     * the CSR reading it. We still need the per-character XSS stripping that
     * TextSanitizer does, so we run it line-by-line and rejoin with \n.
     *
     * Blank-line runs are clamped to a single blank line so a hostile body
     * can't be 2000 lines of nothing, and the whole thing is capped at the
     * same LIMIT_LONG (2000) the column allows.
     */
    private String sanitizeMultiline(String body) {
        if (body == null) return null
        // Normalise CRLF / lone CR so the split is consistent across clients.
        def lines = body.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1)
        def cleaned = new StringBuilder()
        int blankRun = 0
        for (String line : lines) {
            // Each line goes through the real sanitizer (HTML/JS/entity strip,
            // intra-line whitespace collapse). An all-whitespace line yields ''.
            def c = textSanitizer.body(line) ?: ''
            if (c.isEmpty()) {
                blankRun++
                if (blankRun > 1) continue          // clamp blank-line runs
            } else {
                blankRun = 0
            }
            if (cleaned.length() > 0) cleaned.append('\n')
            cleaned.append(c)
        }
        def result = cleaned.toString().trim()
        if (result.length() > TextSanitizer.LIMIT_LONG) {
            result = result.substring(0, TextSanitizer.LIMIT_LONG)
        }
        result
    }

    /**
     * Canonical category for a ticket: upper-cased, stripped to A-Z/_ only,
     * and clamped to OTHER when the input is null/blank or — after stripping
     * symbols and digits — leaves nothing behind (e.g. "123" or ":)"). One
     * place so the stored {@code ticket.category} and the {@link #autoReply}
     * template family can never disagree.
     */
    private static String normalizeCategory(String category) {
        def c = (category ?: 'OTHER').toUpperCase().replaceAll(/[^A-Z_]/, '')
        c.isEmpty() ? 'OTHER' : c
    }

    /**
     * Tiny FAQ-style auto-responder. Real staff can still reply later.
     * Expects an already-normalized category (see {@link #normalizeCategory});
     * still null-guards defensively so a stray caller can't NPE.
     */
    private static String autoReply(String category) {
        switch (category ?: 'OTHER') {
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
        // Non-owner gets the SAME 404 as a wholly missing id — never a
        // 403 — so an authenticated attacker iterating GET /api/support/
        // tickets/{id} can't distinguish "exists but not yours" from
        // "doesn't exist" and thereby enumerate other users' ticket ids
        // (IDOR enumeration leak). Mirrors the ApiKeyService.revoke
        // pattern in this codebase. Applies to every per-ticket method
        // below so the differential-response leak is closed across the
        // whole surface, not just the read path.
        if (t.userId != userId) throw new NotFoundException("SupportTicket", ticketId)
        [ticket: t, messages: messageRepository.findByTicket(ticketId)]
    }

    @Transactional
    SupportTicket create(Long userId, String username, String subject, String category, String body) {
        // Sanitize EVERYTHING at the ingestion boundary — HTML tags, JS
        // protocols, on* attributes, and HTML entities are stripped. The
        // stored values are guaranteed safe to render as plain text.
        def cleanSubject = textSanitizer.subject(subject)
        // Newline-preserving — keeps paragraph breaks in long ticket bodies
        // and the report-user template readable for the CSR (see method doc).
        def cleanBody    = sanitizeMultiline(body)
        def cleanName    = textSanitizer.cleanShort(username)
        if (!cleanSubject || cleanSubject.isEmpty()) {
            throw new BadRequestException("INVALID_SUBJECT", "Subject is required")
        }
        if (!cleanBody || cleanBody.isEmpty()) {
            throw new BadRequestException("INVALID_BODY", "Message body is required")
        }
        // Normalize ONCE — the stored category and the auto-reply template
        // are both derived from this value so they can never diverge. A raw
        // "trade  :)" used to store "TRADE" but feed autoReply the un-stripped
        // "TRADE  :)", which fell through to the generic template; an all-
        // symbol category like ":)" used to store an empty string.
        def cleanCategory = normalizeCategory(category)
        def ticket = new SupportTicket(
            userId:   userId,
            username: cleanName,
            subject:  cleanSubject,
            category: cleanCategory,
            status:   'WAITING_STAFF'
        )
        ticketRepository.save(ticket)

        // SupportMessage.createdAt defaults to System.currentTimeMillis() at
        // construction time. The two messages below are built back-to-back —
        // on a fast JVM they routinely share the same millisecond, which
        // makes the repository's `ORDER BY m.createdAt ASC` non-deterministic
        // and the rendered thread can flip the auto-reply ABOVE the user's
        // own question. Stamp the messages with an explicit +1ms delta so
        // the ordering is stable for the lifetime of the thread.
        long userMsgCreatedAt = System.currentTimeMillis()
        messageRepository.save(new SupportMessage(
            ticketId:   ticket.id,
            author:     'USER',
            authorName: cleanName,
            body:       cleanBody,
            createdAt:  userMsgCreatedAt
        ))
        // Synthesised first staff response so the thread isn't empty.
        // Driven by the SAME normalized category as the stored ticket so the
        // template family always matches what the CSR sees on the row. The
        // +1ms ensures the staff auto-reply always renders AFTER the user's
        // question, never above it (see comment above).
        messageRepository.save(new SupportMessage(
            ticketId:   ticket.id,
            author:     'STAFF',
            authorName: 'Clara (auto)',
            body:       autoReply(cleanCategory),
            createdAt:  userMsgCreatedAt + 1L
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
        // Same-as-missing 404 for non-owners — see getTicket() for the
        // enumeration rationale. Reply must NOT confirm a foreign id.
        if (ticket.userId != userId) throw new NotFoundException("SupportTicket", ticketId)
        if (ticket.status == 'RESOLVED') {
            throw new BadRequestException("RESOLVED", "Ticket is already resolved")
        }
        // Newline-preserving so a multi-paragraph follow-up keeps its shape.
        def cleanBody = sanitizeMultiline(body)
        def cleanName = textSanitizer.cleanShort(username)
        if (!cleanBody || cleanBody.isEmpty()) {
            throw new BadRequestException("INVALID_BODY", "Message body is required")
        }
        // Strictly-monotonic createdAt — same bug class as the +1ms
        // stamp in create() (see lines 153-159). Without an explicit
        // stamp, two rapid replies (double-click on slow network, two
        // browser tabs racing the same submit, or a reply landing in
        // the same ms as create()'s Clara auto-reply at userMsgCreatedAt
        // + 1) share `System.currentTimeMillis()` and the repo's
        // `ORDER BY m.createdAt ASC` becomes a non-deterministic
        // tie-break — the thread render can flip the reply ABOVE the
        // message it's answering. Stamping max(now, last+1) makes the
        // ordering deterministic for the lifetime of the thread.
        long now = System.currentTimeMillis()
        long lastInThread = (messageRepository.findByTicket(ticketId) ?: [])
            .collect { it.createdAt ?: 0L }
            .max() ?: 0L
        long stamp = Math.max(now, lastInThread + 1L)
        def msg = messageRepository.save(new SupportMessage(
            ticketId:   ticketId,
            author:     'USER',
            authorName: cleanName,
            body:       cleanBody,
            createdAt:  stamp
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
        // Same-as-missing 404 for non-owners — see getTicket() for the
        // enumeration rationale.
        if (ticket.userId != userId) throw new NotFoundException("SupportTicket", ticketId)
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
        // Same-as-missing 404 for non-owners — see getTicket() for the
        // enumeration rationale.
        if (ticket.userId != userId) throw new NotFoundException("SupportTicket", ticketId)
        // State-machine guard — mirror reply()/reopen(). Re-resolving an
        // already-RESOLVED ticket was a silent no-op that still bumped
        // updatedAt, re-sorting the user's ticket list for no reason. Now a
        // double-resolve (stale tab, double-click) returns a clean 400
        // instead of churning the row.
        if (ticket.status == 'RESOLVED') {
            throw new BadRequestException("ALREADY_RESOLVED", "Ticket is already resolved")
        }
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

    /** Per-tick scope cap on the daily sweep. The repo query keeps
     *  its no-arg shape (the existing test suite stubs it that way)
     *  so this is an in-memory clamp on the per-tick close + push
     *  fan-out. Stale tickets that don't fit in one pass stay
     *  WAITING_USER and are picked up on the next daily tick — the
     *  status='WAITING_USER' filter in findStaleWaitingUser makes the
     *  work naturally idempotent so a crash mid-loop never re-pushes
     *  an already-RESOLVED ticket. 5000 is well below the heap budget
     *  even on the smallest deploy, and a daily cadence drains a
     *  hundred-thousand-row backlog inside a month. */
    static final int SWEEP_BATCH_LIMIT = 5000

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
    // Deliberately NOT @Transactional at the sweep level — same bug
    // class as the OfferService.sweepStaleOffers / BuyOrderService.
    // sweepStaleBuyOrders / WatchlistAlertService.sweep fixes (batch
    // 311). With an outer transaction wrapping the per-row work, a
    // single row's ticketRepository.save() failure (optimistic-lock
    // collision, constraint violation, etc.) marks the SHARED
    // transaction rollback-only — the per-iteration try/catch
    // swallows the exception, the loop continues "successfully", and
    // then EVERY ticket close so far gets rolled back at commit time
    // with UnexpectedRollbackException. Without the outer wrapper,
    // SimpleJpaRepository.save() runs in its own auto-tx, so the
    // try/catch genuinely isolates per-row failures.
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
        int eligible = candidates.size()
        // Clamp per-tick work scope (see SWEEP_BATCH_LIMIT). Remaining
        // tickets roll over to the next daily tick — the status filter
        // in findStaleWaitingUser means already-resolved rows fall out
        // of the candidate set naturally so the rollover never double-
        // closes the same ticket. fixedDelay (Spring serialises ticks
        // per scheduled method) prevents the sweeper from overlapping
        // itself, so the clamp is a pure throughput knob.
        if (eligible > SWEEP_BATCH_LIMIT) {
            candidates = candidates.take(SWEEP_BATCH_LIMIT)
        }
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
            def backlog = eligible > SWEEP_BATCH_LIMIT
                ? " (eligible=${eligible}, capped at ${SWEEP_BATCH_LIMIT} — backlog will drain across subsequent ticks)"
                : ""
            log.info("Support sweeper: auto-closed ${closed} stale WAITING_USER ticket(s) idle >${autoResolveWaitingUserDays}d${backlog}")
        }
    }
}
