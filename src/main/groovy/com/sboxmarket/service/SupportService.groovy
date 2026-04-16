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

    /** Tiny FAQ-style auto-responder. Real staff can still reply later. */
    private static String autoReply(String category, String subject) {
        switch ((category ?: 'OTHER').toUpperCase()) {
            case 'PAYMENT':
                return "Thanks for reaching out about payments. Most deposits clear within 2 minutes — " +
                       "if yours hasn't, please include the Stripe session id from your Trades tab so we can investigate."
            case 'TRADE':
                return "Trade questions usually resolve themselves within the 8-day Steam hold. If the listing is " +
                       "already marked sold, the buyer has confirmed receipt and funds should release automatically."
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
            "A support agent has replied to your ticket", ticket.id)
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
        msg
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
}
