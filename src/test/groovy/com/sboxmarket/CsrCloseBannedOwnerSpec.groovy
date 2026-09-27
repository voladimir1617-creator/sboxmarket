package com.sboxmarket

import com.sboxmarket.model.SteamUser
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
 * CsrService.close notification-leak guard — when the ticket owner has
 * been banned since the ticket was created, the CSR close itself must
 * still flip the row + write the audit trail, but the user-facing
 * TICKET_CLOSED bell push must NOT fire. Mirrors the same policy
 * `reply()` (lines 293-329) and `issueGoodwillCredit` (line 411) already
 * enforce, and matches `NotificationService.filterActiveRecipients`
 * which drops banned ids from batch sends. The single-recipient
 * CSR-close path was the lone gap: it pushed `TICKET_CLOSED` to a
 * banned account about staff activity on a frozen ticket.
 */
class CsrCloseBannedOwnerSpec extends Specification {

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

    def "close on a banned owner's ticket suppresses the TICKET_CLOSED bell push — status flip and audit still happen"() {
        given: 'a banned ticket owner — auth layer keeps them out of the app, no point pinging the bell'
        def csr   = new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara')
        def owner = new SteamUser(id: 10L, role: 'USER', displayName: 'Booted Bob',
                                  banned: true, banReason: 'fraud')
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF', subject: 'help')

        steamUserRepository.findById(5L)  >> Optional.of(csr)
        steamUserRepository.findById(10L) >> Optional.of(owner)
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_)   >> { args -> args[0] }

        when:
        def out = service.close(5L, 1L)

        then: 'the row still flips — staff need the resolved state and forensic trail'
        out.status == 'RESOLVED'

        and: 'no bell push to a banned account'
        0 * notificationService.safePush(10L, _, _, _, _, _)
        0 * notificationService.push(10L, _, _, _, _, _)

        and: 'audit row still fires — the CSR action is recorded regardless of delivery'
        1 * auditService.log(AuditService.TICKET_CLOSED, 5L, 10L, 1L, _ as String)
    }

    def "close on an ACTIVE owner's ticket still pushes the TICKET_CLOSED bell (control case)"() {
        given:
        def csr   = new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara')
        def owner = new SteamUser(id: 10L, role: 'USER', displayName: 'Active Alice', banned: false)
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF', subject: 'help')

        steamUserRepository.findById(5L)  >> Optional.of(csr)
        steamUserRepository.findById(10L) >> Optional.of(owner)
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_)   >> { args -> args[0] }

        when:
        service.close(5L, 1L)

        then: 'bell push fires for a non-banned owner'
        1 * notificationService.safePush(10L, 'TICKET_CLOSED', _, _, 1L, '/support')
    }
}
