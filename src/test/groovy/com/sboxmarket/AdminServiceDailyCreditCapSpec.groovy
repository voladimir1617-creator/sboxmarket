package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pins the rolling 24h per-admin wallet-CREDIT cap on AdminService.creditWallet.
 *
 * The per-call cap ($10,000) bounds ONE adjustment and says nothing about how
 * many. /api/admin is rate-limited at 20 requests / 10 seconds, so the
 * arithmetic ceiling was 20 x $10,000 every 10 seconds, and CANT_CREDIT_SELF
 * does not close it - the attacker credits a confederate instead of himself.
 * CsrService.issueGoodwillCredit closes the identical loop one level down and
 * reasons about it in its own docstring; the same reasoning had never been
 * applied here, where the per-call figure is 400x larger.
 *
 * The cap could not previously be built from the transaction table at all: an
 * admin row was stamped `stripeReference: 'admin'` - a CONSTANT - so every
 * admin's adjustments were indistinguishable from every other admin's, while
 * CSR stamps `csr_<id>` precisely so a rolling sum can be keyed per actor.
 * The actor existed only in the audit log, whose only amount-bearing field is a
 * free-text summary built from operator-supplied note text. So this spec pins
 * BOTH halves, because the bound is worthless without the identity:
 *
 *   1. an over-budget credit is refused (ADMIN_DAILY_CAP), nothing persisted
 *   2. the rolling sum is keyed on THIS admin, not on a shared constant
 *   3. the persisted row carries admin_<id> so tomorrow's sum can find it
 *   4. the window really is 24 hours
 *   5. INVERSE - a credit inside the remaining budget still goes through
 *   6. INVERSE - a DEBIT is not bounded by the credit cap, and does not even
 *      consult it: netting debits against the running total would be a reset
 *      button (credit a confederate, debit some other funded wallet, repeat)
 */
class AdminServiceDailyCreditCapSpec extends Specification {

    static final Long ADMIN  = 7L
    static final Long TARGET = 20L

    SteamUserRepository     steamUserRepository     = Mock()
    WalletRepository        walletRepository        = Mock()
    TransactionRepository   transactionRepository   = Mock()
    ListingRepository       listingRepository       = Mock()
    SupportTicketRepository supportTicketRepository = Mock()
    ItemRepository          itemRepository          = Mock()
    NotificationService     notificationService     = Mock()
    TextSanitizer           textSanitizer           = Mock() {
        medium(_) >> { String s -> s }
    }
    AdminAuthorization      adminAuthorization      = Mock()
    BanGuard                banGuard                = Mock()
    AuditService            auditService            = Mock()

    @Subject
    AdminService service = new AdminService(
        // The production default, not a figure invented here. A spec that picks
        // its own bound cannot tell you the shipped one is enforced.
        dailyCreditCapStr        : '25000.00',
        steamUserRepository      : steamUserRepository,
        walletRepository         : walletRepository,
        transactionRepository    : transactionRepository,
        listingRepository        : listingRepository,
        supportTicketRepository  : supportTicketRepository,
        itemRepository           : itemRepository,
        notificationService      : notificationService,
        textSanitizer            : textSanitizer,
        adminAuthorization       : adminAuthorization,
        banGuard                 : banGuard,
        auditService             : auditService
    )

    private Wallet wireTarget(BigDecimal balance = new BigDecimal('100.00')) {
        def user   = new SteamUser(id: TARGET, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222', balance: balance, currency: 'USD')
        steamUserRepository.findById(TARGET) >> Optional.of(user)
        walletRepository.findByUsername('steam_222') >> wallet
        wallet
    }

    def "a credit that would breach the rolling 24h total is refused and nothing is persisted"() {
        given: 'this admin has already moved $20,000 today'
        def wallet = wireTarget()

        when: 'a further $6,000 would take the day to $26,000, past the $25,000 bound'
        service.creditWallet(ADMIN, TARGET, new BigDecimal('6000'), 'goodwill')

        then: 'the day total is read for THIS admin'
        1 * transactionRepository.sumByTypeReferenceSince(
            'ADJUSTMENT_CREDIT', 'admin_7', _) >> new BigDecimal('20000.00')

        and: 'ADMIN_DAILY_CAP, naming what is already spent and where the rest must go'
        def ex = thrown(BadRequestException)
        ex.code == 'ADMIN_DAILY_CAP'
        ex.message.contains('20000.00')
        ex.message.contains('manual payout')

        and: 'no balance moved, no ledger row, no user notification, no audit row'
        wallet.balance == new BigDecimal('100.00')
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * notificationService.push(*_)
        0 * auditService.log(*_)
    }

    def "the rolling sum is keyed on the acting admin, not on a shared constant"() {
        given:
        wireTarget()

        when: 'admin 7 credits'
        service.creditWallet(ADMIN, TARGET, new BigDecimal('10'), 'goodwill')

        then: 'summed under admin 7 alone - a constant here would pool every admin into one budget'
        1 * transactionRepository.sumByTypeReferenceSince(
            'ADJUSTMENT_CREDIT', 'admin_7', _) >> BigDecimal.ZERO
        0 * transactionRepository.sumByTypeReferenceSince('ADJUSTMENT_CREDIT', 'admin', _)
    }

    def "the persisted row carries the acting admin id so tomorrow's sum can find it"() {
        given:
        wireTarget()
        Transaction saved = null

        when:
        service.creditWallet(ADMIN, TARGET, new BigDecimal('10'), 'goodwill')

        then:
        1 * transactionRepository.sumByTypeReferenceSince(_, _, _) >> BigDecimal.ZERO
        1 * transactionRepository.save(_) >> { Transaction t -> saved = t; t }

        and: 'admin_<id>, not the bare constant that made the cap uncomputable'
        saved.stripeReference == 'admin_7'
        saved.type == 'ADJUSTMENT_CREDIT'
    }

    def "the window really is 24 hours"() {
        given:
        wireTarget()
        Long since = null

        when:
        service.creditWallet(ADMIN, TARGET, new BigDecimal('10'), 'goodwill')

        then: 'the lower bound sits ~24h back - not 0, not a week'
        1 * transactionRepository.sumByTypeReferenceSince(_, _, _) >> { String t, String r, Long s ->
            since = s
            BigDecimal.ZERO
        }

        and:
        def ageMs = System.currentTimeMillis() - since
        ageMs >= (23L * 60L * 60L * 1000L)
        ageMs <= (25L * 60L * 60L * 1000L)
    }

    def "INVERSE CONTROL - a credit inside the remaining budget still goes through"() {
        given: '$20,000 already spent, so $5,000 of headroom remains'
        def wallet = wireTarget()

        when: 'a $4,999 credit, comfortably inside it'
        def result = service.creditWallet(ADMIN, TARGET, new BigDecimal('4999'), 'goodwill')

        then:
        1 * transactionRepository.sumByTypeReferenceSince(_, _, _) >> new BigDecimal('20000.00')
        noExceptionThrown()

        and: 'the balance moves and the ledger row is written'
        1 * walletRepository.save(wallet)
        1 * transactionRepository.save(_)
        wallet.balance == new BigDecimal('5099.00')
        result.newBalance == new BigDecimal('5099.00')
    }

    def "INVERSE CONTROL - a debit is not bounded by the credit cap and never consults it"() {
        given: 'the day credit budget would be irrelevant to a debit'
        def wallet = wireTarget(new BigDecimal('500.00'))

        when: 'a clawback of $300 - money destroyed, not minted'
        service.creditWallet(ADMIN, TARGET, new BigDecimal('-300'), 'clawback')

        then: 'the credit budget is not even read - netting debits would be a reset button'
        0 * transactionRepository.sumByTypeReferenceSince(*_)
        noExceptionThrown()

        and:
        wallet.balance == new BigDecimal('200.00')
        1 * walletRepository.save(wallet)
    }
}
