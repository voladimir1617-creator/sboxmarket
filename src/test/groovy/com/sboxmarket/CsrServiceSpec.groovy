package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.CsrService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification
import spock.lang.Subject

/**
 * CSR panel: ticket replies, goodwill credits (capped), listing flag for
 * admin review. Every route requires a CSR or ADMIN role — asserted on
 * every test.
 */
class CsrServiceSpec extends Specification {

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
        creditCapStr             : '25.00'
    )

    def setup() {
        // Default sanitizer behaviour — echo back (individual tests override)
        textSanitizer.body(_)       >> { String s -> s }
        textSanitizer.cleanShort(_) >> { String s -> s }
        textSanitizer.medium(_)     >> { String s -> s }
        textSanitizer.clean(_, _)   >> { args -> args[0] }
    }

    // ── requireCsr ────────────────────────────────────────────────

    def "requireCsr passes for role=CSR"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))

        when:
        service.requireCsr(5L)

        then:
        noExceptionThrown()
    }

    def "requireCsr passes for role=ADMIN"() {
        given:
        steamUserRepository.findById(1L) >> Optional.of(new SteamUser(id: 1L, role: 'ADMIN'))

        when:
        service.requireCsr(1L)

        then:
        noExceptionThrown()
    }

    def "requireCsr forbids regular USER"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'USER'))

        when:
        service.requireCsr(10L)

        then:
        thrown(ForbiddenException)
    }

    def "requireCsr forbids an unknown user id"() {
        given:
        steamUserRepository.findById(404L) >> Optional.empty()

        when:
        service.requireCsr(404L)

        then:
        thrown(ForbiddenException)
    }

    def "requireCsr(null) throws ForbiddenException — never falls into findById(null) → opaque 500 (sibling: AdminAuthorization.requireAdmin)"() {
        when: "an unauthenticated controller path reaches requireCsr with no session user"
        service.requireCsr(null)

        then: "we get a clean 403, not an IllegalArgumentException from Spring Data's findById(null)"
        thrown(ForbiddenException)
        0 * steamUserRepository.findById(_)
    }

    // ── getTicket ─────────────────────────────────────────────────

    def "getTicket is gated on the CSR role"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'USER'))

        when:
        service.getTicket(10L, 1L)

        then:
        thrown(ForbiddenException)
        0 * supportTicketRepository.findById(_)
    }

    def "getTicket returns the ticket plus its message thread"() {
        given:
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF')
        def messages = [new SupportMessage(id: 1L, ticketId: 1L, author: 'USER')]
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportMessageRepository.findByTicket(1L) >> messages

        when:
        def result = service.getTicket(5L, 1L)

        then:
        result.ticket.is(ticket)
        result.messages.is(messages)
    }

    def "getTicket refuses an unknown ticket"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        supportTicketRepository.findById(404L) >> Optional.empty()

        when:
        service.getTicket(5L, 404L)

        then:
        thrown(NotFoundException)
    }

    // ── reply ─────────────────────────────────────────────────────

    def "reply appends a STAFF message and flips ticket back to WAITING_USER"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF', subject: 'help')
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_) >> { args -> args[0] }
        supportMessageRepository.save(_) >> { args -> def m = args[0]; m.id = 1L; m }

        when:
        def msg = service.reply(5L, 1L, 'here is the answer')

        then:
        msg.author == 'STAFF'
        msg.body == 'here is the answer'
        ticket.status == 'WAITING_USER'
        1 * notificationService.push(10L, 'SUPPORT_REPLY', _, _, _, _)
    }

    def "reply writes a TICKET_REPLIED audit row (actor=CSR, subject=ticket owner)"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF', subject: 'help')
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_) >> { args -> args[0] }
        supportMessageRepository.save(_) >> { args -> def m = args[0]; m.id = 1L; m }

        when:
        service.reply(5L, 1L, 'here is the answer')

        then:
        1 * auditService.log('TICKET_REPLIED', 5L, 10L, 1L, _ as String)
    }

    def "reply still saves even if the audit write throws"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF', subject: 'help')
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_) >> { args -> args[0] }
        supportMessageRepository.save(_) >> { args -> def m = args[0]; m.id = 1L; m }
        auditService.log(*_) >> { throw new RuntimeException("audit db down") }

        when:
        def msg = service.reply(5L, 1L, 'here is the answer')

        then:
        noExceptionThrown()
        msg.author == 'STAFF'
        ticket.status == 'WAITING_USER'
    }

    def "reply is gated on the CSR role"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'USER'))

        when:
        service.reply(10L, 1L, 'body')

        then:
        thrown(ForbiddenException)
        0 * supportMessageRepository.save(_)
        0 * supportTicketRepository.save(_)
    }

    def "reply refuses empty body"() {
        given:
        def csrSanitizer = Mock(TextSanitizer) {
            body(_) >> ''
            cleanShort(_) >> { String s -> s }
        }
        def csrSvc = new CsrService(
            steamUserRepository: steamUserRepository,
            walletRepository: walletRepository,
            transactionRepository: transactionRepository,
            listingRepository: listingRepository,
            offerRepository: offerRepository,
            supportTicketRepository: supportTicketRepository,
            supportMessageRepository: supportMessageRepository,
            notificationService: notificationService,
            textSanitizer: csrSanitizer,
            creditCapStr: '25.00'
        )
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        supportTicketRepository.findById(1L) >> Optional.of(new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF'))

        when:
        csrSvc.reply(5L, 1L, '<script></script>')

        then:
        thrown(BadRequestException)
    }

    // ── close ─────────────────────────────────────────────────────

    def "close flips ticket to RESOLVED and writes a TICKET_CLOSED audit row"() {
        given:
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF')
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.close(5L, 1L)

        then:
        result.status == 'RESOLVED'
        1 * auditService.log('TICKET_CLOSED', 5L, 10L, 1L, _ as String)
    }

    def "close is gated on the CSR role"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'USER'))

        when:
        service.close(10L, 1L)

        then:
        thrown(ForbiddenException)
        0 * supportTicketRepository.save(_)
    }

    def "close still flips the ticket even if the audit write throws"() {
        given:
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF')
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_) >> { args -> args[0] }
        auditService.log(*_) >> { throw new RuntimeException("audit db down") }

        when:
        def result = service.close(5L, 1L)

        then:
        noExceptionThrown()
        result.status == 'RESOLVED'
    }

    def "close pushes a TICKET_CLOSED notification to the ticket owner"() {
        // Was a real gap pre-fix: CSR-side close() flipped the row to
        // RESOLVED with zero signal to the user. reply() already pings
        // on every staff message, and SupportService.sweepStaleWaitingUser
        // fires TICKET_AUTO_RESOLVED on the auto-close path — the manual
        // CSR-close was the lone hole, leaving disputers waiting
        // indefinitely for a verdict that had already shipped.
        given:
        def ticket = new SupportTicket(id: 7L, userId: 99L, subject: 'Trade did not arrive',
                                       status: 'WAITING_STAFF')
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        supportTicketRepository.findById(7L) >> Optional.of(ticket)
        supportTicketRepository.save(_) >> { args -> args[0] }

        when:
        service.close(5L, 7L)

        then:
        1 * notificationService.safePush(99L, 'TICKET_CLOSED',
            { String title -> title.contains('#7') && title.contains('resolved') },
            'Trade did not arrive', 7L, '/support')
    }

    def "close rejects an already-RESOLVED ticket (no silent re-resolve)"() {
        given:
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'RESOLVED', updatedAt: 1000L)
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        supportTicketRepository.findById(1L) >> Optional.of(ticket)

        when:
        service.close(5L, 1L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'ALREADY_RESOLVED'

        and: 'the row is neither re-saved nor re-audited'
        ticket.updatedAt == 1000L
        0 * supportTicketRepository.save(_)
        0 * auditService.log(*_)
    }

    // ── issueGoodwillCredit ───────────────────────────────────────

    def "issueGoodwillCredit bumps wallet balance within the cap"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("0.00"), currency: 'USD')
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.issueGoodwillCredit(5L, 10L, new BigDecimal("20"), 'refund for failed trade')

        then:
        wallet.balance == new BigDecimal("20")
        result.newBalance == new BigDecimal("20")
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'ADJUSTMENT_CREDIT' && tx.amount == new BigDecimal("20")
        })
        1 * notificationService.push(10L, 'CSR_CREDIT', _, _, _, _)
    }

    def "issueGoodwillCredit is gated on the CSR role"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'USER'))

        when:
        service.issueGoodwillCredit(10L, 20L, new BigDecimal("5"), 'note')

        then:
        thrown(ForbiddenException)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
    }

    def "issueGoodwillCredit writes a CSR_CREDIT audit row (actor=CSR, subject=target, resource=wallet)"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("0.00"), currency: 'USD')
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when:
        service.issueGoodwillCredit(5L, 10L, new BigDecimal("20"), 'refund for failed trade')

        then:
        1 * auditService.log('CSR_CREDIT', 5L, 10L, 500L, _ as String)
    }

    def "issueGoodwillCredit still credits the wallet even if the audit write throws"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("0.00"), currency: 'USD')
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }
        auditService.log(*_) >> { throw new RuntimeException("audit db down") }

        when:
        def result = service.issueGoodwillCredit(5L, 10L, new BigDecimal("20"), 'compensation')

        then:
        noExceptionThrown()
        wallet.balance == new BigDecimal("20")
        result.newBalance == new BigDecimal("20")
    }

    def "issueGoodwillCredit allows an amount exactly at the cap"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("0.00"), currency: 'USD')
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when: 'the cap is 25.00 and the request is exactly 25'
        def result = service.issueGoodwillCredit(5L, 10L, new BigDecimal("25"), 'at the cap')

        then: 'the boundary value is permitted — only strictly-over-cap is rejected'
        noExceptionThrown()
        result.newBalance == new BigDecimal("25")
        result.cap == new BigDecimal("25.00")
    }

    def "issueGoodwillCredit refuses amounts over the cap"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))

        when:
        service.issueGoodwillCredit(5L, 10L, new BigDecimal("100"), 'too generous')

        then:
        def e = thrown(BadRequestException)
        e.code == 'OVER_CAP'

        and: 'nothing is written when the cap rejects'
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * auditService.log(*_)
    }

    def "issueGoodwillCredit refuses an unknown target user"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.issueGoodwillCredit(5L, 999L, new BigDecimal("10"), 'note')

        then:
        thrown(NotFoundException)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
    }

    def "issueGoodwillCredit refuses a target user with no wallet"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '111'))
        walletRepository.findByUsername('steam_111') >> null

        when:
        service.issueGoodwillCredit(5L, 10L, new BigDecimal("10"), 'note')

        then:
        thrown(NotFoundException)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
    }

    def "issueGoodwillCredit refuses zero/negative amounts"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))

        when:
        service.issueGoodwillCredit(5L, 10L, amount, 'reason')

        then:
        thrown(BadRequestException)

        where:
        amount << [BigDecimal.ZERO, new BigDecimal("-5"), null]
    }

    def "issueGoodwillCredit refuses missing note (audit trail required)"() {
        given:
        def csrSanitizer = Mock(TextSanitizer) {
            medium(_) >> ''
        }
        def csrSvc = new CsrService(
            steamUserRepository: steamUserRepository,
            walletRepository: walletRepository,
            transactionRepository: transactionRepository,
            listingRepository: listingRepository,
            offerRepository: offerRepository,
            supportTicketRepository: supportTicketRepository,
            supportMessageRepository: supportMessageRepository,
            notificationService: notificationService,
            textSanitizer: csrSanitizer,
            creditCapStr: '25.00'
        )
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))

        when:
        csrSvc.issueGoodwillCredit(5L, 10L, new BigDecimal("10"), '')

        then:
        thrown(BadRequestException)
    }

    // ── flagListing ───────────────────────────────────────────────

    def "flagListing appends a [FLAGGED] note and returns ok"() {
        given:
        def listing = new Listing(id: 100L, description: 'original description')
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }

        when:
        service.flagListing(5L, 100L, 'suspect pricing')

        then:
        // The sanitizer returns its input unchanged per the setup() default.
        // The clean(_, _) stub echoes arg[0] back, so the concatenated string
        // with the [FLAGGED …] marker survives.
        listing.description?.contains('[FLAGGED')
    }

    def "flagListing writes a LISTING_FLAGGED audit row (actor=CSR, subject=seller, resource=listing)"() {
        given:
        def listing = new Listing(id: 100L, sellerUserId: 77L, description: 'original')
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }

        when:
        service.flagListing(5L, 100L, 'suspect pricing')

        then:
        1 * auditService.log('LISTING_FLAGGED', 5L, 77L, 100L, _ as String)
    }

    def "flagListing still saves the listing even if the audit write throws"() {
        given:
        def listing = new Listing(id: 100L, sellerUserId: 77L, description: 'original')
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR', displayName: 'Clara'))
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        auditService.log(*_) >> { throw new RuntimeException("audit db down") }

        when:
        def result = service.flagListing(5L, 100L, 'suspect pricing')

        then:
        noExceptionThrown()
        result.flagged == true
        listing.description?.contains('[FLAGGED')
    }

    def "flagListing refuses an unknown listing"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        listingRepository.findById(404L) >> Optional.empty()

        when:
        service.flagListing(5L, 404L, 'gone')

        then:
        thrown(NotFoundException)
        0 * listingRepository.save(_)
        0 * auditService.log(*_)
    }

    def "flagListing is gated on the CSR role"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'USER'))

        when:
        service.flagListing(10L, 100L, 'reason')

        then:
        thrown(ForbiddenException)
        0 * listingRepository.findById(_)
        0 * listingRepository.save(_)
    }

    // ── isCsr ─────────────────────────────────────────────────────

    def "isCsr returns false for null"() {
        expect:
        !service.isCsr(null)
    }

    def "isCsr returns true for CSR and ADMIN, false for USER"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        steamUserRepository.findById(1L) >> Optional.of(new SteamUser(id: 1L, role: 'ADMIN'))
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'USER'))

        expect:
        service.isCsr(5L) == true
        service.isCsr(1L) == true
        service.isCsr(10L) == false
    }

    // ── lookupUser ────────────────────────────────────────────────

    def "lookupUser returns empty matches for blank query"() {
        expect:
        service.lookupUser(null).matches == []
        service.lookupUser('').matches == []
        service.lookupUser('   ').matches == []
    }

    def "lookupUser delegates to the indexed searchByNameOrSteamId"() {
        given:
        def match = new SteamUser(id: 10L, steamId64: '111', displayName: 'Alice',
                                   role: 'USER', createdAt: 1000L)
        steamUserRepository.searchByNameOrSteamId('alice', _) >> [match]
        walletRepository.findByUsername('steam_111') >> null  // no wallet

        when:
        def result = service.lookupUser('alice')

        then:
        result.matches.size() == 1
        result.matches[0].id == 10L
        result.matches[0].steamId64 == '111'
    }

    def "lookupUser falls back to findById for a numeric query when name search misses"() {
        given:
        steamUserRepository.searchByNameOrSteamId('42', _) >> []
        steamUserRepository.findById(42L) >> Optional.of(
            new SteamUser(id: 42L, steamId64: '777', displayName: 'ById')
        )

        when:
        def result = service.lookupUser('42')

        then:
        result.matches.size() == 1
        result.matches[0].id == 42L
    }

    def "lookupUser does NOT attempt findById for non-numeric queries"() {
        given:
        steamUserRepository.searchByNameOrSteamId('alpha', _) >> []

        when:
        def result = service.lookupUser('alpha')

        then:
        result.matches == []
        0 * steamUserRepository.findById(_)
    }

    // ── dashboardStats ────────────────────────────────────────────

    def "dashboardStats counts tickets by status via indexed counters"() {
        given:
        supportTicketRepository.countOpen() >> 2L
        supportTicketRepository.countByStatus('WAITING_STAFF') >> 1L
        supportTicketRepository.countByStatus('WAITING_USER') >> 1L
        supportTicketRepository.oldestWaitingStaffUpdatedAt() >> 1000L

        when:
        def stats = service.dashboardStats()

        then:
        stats.openTickets == 2L
        stats.waitingStaff == 1L
        stats.waitingUser == 1L
        stats.oldestWaitingAgeMs > 0
        stats.creditCap == new BigDecimal("25.00")
    }

    // ── listTickets search (batch 581) ──────────────────────────────

    def "listTickets without a search term hits findForAdmin"() {
        given:
        supportTicketRepository.findForAdmin('WAITING_STAFF') >> []

        when:
        service.listTickets('WAITING_STAFF', null)

        then:
        0 * supportTicketRepository.searchForAdmin(*_)
    }

    def "listTickets with a search term hits searchForAdmin"() {
        given:
        supportTicketRepository.searchForAdmin('', 'deposit') >> []

        when:
        service.listTickets(null, 'deposit')

        then:
        0 * supportTicketRepository.findForAdmin(_)
    }

    def "listTickets truncates the search string and strips null bytes"() {
        given:
        def longQuery = 'x' * 200
        String captured = null
        supportTicketRepository.searchForAdmin(_, _) >> { args ->
            captured = args[1] as String
            []
        }

        when:
        service.listTickets('', longQuery + '\u0000bad')

        then:
        captured.length() == 100
        !captured.contains('\u0000')
    }
}
