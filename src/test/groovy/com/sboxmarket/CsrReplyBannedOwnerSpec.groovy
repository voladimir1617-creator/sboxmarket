package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.CsrService
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification
import spock.lang.Subject

/**
 * CsrService.reply notification-leak guard — when the ticket owner has
 * been banned since the ticket was created, the CSR reply itself must
 * still save (forensic + thread continuity) but the user-facing bell
 * push + security email must NOT fire. Mirrors the policy that
 * `issueGoodwillCredit` (line 393) refuses banned targets outright and
 * `NotificationService.filterActiveRecipients` drops banned ids from
 * batch sends. The single-recipient CSR-reply path was the gap: it
 * pushed `SUPPORT_REPLY` + emailed a banned account about staff
 * activity on a frozen ticket.
 */
class CsrReplyBannedOwnerSpec extends Specification {

    SteamUserRepository       steamUserRepository       = Mock()
    WalletRepository          walletRepository          = Mock()
    TransactionRepository     transactionRepository     = Mock()
    ListingRepository         listingRepository         = Mock()
    OfferRepository           offerRepository           = Mock()
    SupportTicketRepository   supportTicketRepository   = Mock()
    SupportMessageRepository  supportMessageRepository  = Mock()
    NotificationService       notificationService       = Mock()
    TextSanitizer             textSanitizer             = Mock()
    AuditService              auditService              = Mock()
    EmailService              emailService              = Mock()

    @Subject
    CsrService service = new CsrService(
        steamUserRepository      : steamUserRepository,
        walletRepository         : walletRepository,
        transactionRepository    : transactionRepository,
        listingRepository        : listingRepository,
        offerRepository          : offerRepository,
        supportTicketRepository  : supportTicketRepository,
        supportMessageRepository : supportMessageRepository,
        notificationService      : notificationService,
        textSanitizer            : textSanitizer,
        auditService             : auditService,
        emailService             : emailService,
        creditCapStr             : '25.00'
    )

    def setup() {
        textSanitizer.body(_)       >> { String s -> s }
        textSanitizer.cleanShort(_) >> { String s -> s }
    }

    def "reply on a banned owner's ticket suppresses the SUPPORT_REPLY bell push AND the email — reply, status flip, and audit still happen"() {
        given: 'a banned ticket owner with a verified email — security-gate would otherwise let the email through'
        def csr   = new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara')
        def owner = new SteamUser(id: 10L, role: 'USER', displayName: 'Booted Bob',
                                  banned: true, banReason: 'fraud',
                                  email: 'bob@example.com', emailVerified: true)
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF', subject: 'help')

        steamUserRepository.findById(5L)  >> Optional.of(csr)
        steamUserRepository.findById(10L) >> Optional.of(owner)
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_)   >> { args -> args[0] }
        supportMessageRepository.save(_)  >> { args -> def m = args[0]; m.id = 1L; m }
        // Email gate would say YES for this user (verified email) — proves
        // the banned-owner branch fires BEFORE the security-email gate.
        emailService.canSendSecurityTo(owner) >> true

        when:
        def msg = service.reply(5L, 1L, 'here is the answer')

        then: 'the reply itself is saved and the ticket flips state — staff need their thread + forensic trail'
        msg.author == 'STAFF'
        msg.body == 'here is the answer'
        ticket.status == 'WAITING_USER'

        and: 'no bell push to a banned account'
        0 * notificationService.push(10L, _, _, _, _, _)
        0 * notificationService.safePush(10L, 'SUPPORT_REPLY', _, _, _, _)

        and: 'no security email to a banned account, even though canSendSecurityTo says yes'
        0 * emailService.sendSupportReply(*_)

        and: 'audit row still fires — the CSR action is recorded regardless of delivery'
        1 * auditService.log('TICKET_REPLIED', 5L, 10L, 1L, _ as String)
    }

    def "reply on an ACTIVE owner's ticket still pushes the bell + sends the email (control case)"() {
        given:
        def csr   = new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara')
        def owner = new SteamUser(id: 10L, role: 'USER', displayName: 'Active Alice',
                                  banned: false,
                                  email: 'alice@example.com', emailVerified: true)
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF', subject: 'help')

        steamUserRepository.findById(5L)  >> Optional.of(csr)
        steamUserRepository.findById(10L) >> Optional.of(owner)
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_)   >> { args -> args[0] }
        supportMessageRepository.save(_)  >> { args -> def m = args[0]; m.id = 1L; m }
        emailService.canSendSecurityTo(owner) >> true

        when:
        service.reply(5L, 1L, 'here is the answer')

        then: 'both delivery channels fire for a non-banned owner'
        1 * notificationService.safePush(10L, 'SUPPORT_REPLY', _, _, _, _)
        1 * emailService.sendSupportReply('alice@example.com', 'Active Alice', 1L, 'help', 'here is the answer')
    }
}
