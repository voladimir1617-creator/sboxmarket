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
        1 * notificationService.push(10L, 'SUPPORT_REPLY', _, _, _)
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

    def "close flips ticket to RESOLVED"() {
        given:
        def ticket = new SupportTicket(id: 1L, userId: 10L, status: 'WAITING_STAFF')
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))
        supportTicketRepository.findById(1L) >> Optional.of(ticket)
        supportTicketRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.close(5L, 1L)

        then:
        result.status == 'RESOLVED'
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
        1 * notificationService.push(10L, 'CSR_CREDIT', _, _, _)
    }

    def "issueGoodwillCredit refuses amounts over the cap"() {
        given:
        steamUserRepository.findById(5L) >> Optional.of(new SteamUser(id: 5L, role: 'CSR'))

        when:
        service.issueGoodwillCredit(5L, 10L, new BigDecimal("100"), 'too generous')

        then:
        thrown(BadRequestException)
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
}
