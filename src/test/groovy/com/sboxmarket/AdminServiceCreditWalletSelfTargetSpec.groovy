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
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pins the self-target gate on AdminService.creditWallet.
 *
 * Every other privileged AdminService mutation rejects an `adminUserId
 * == targetUserId` call up-front:
 *   - banUser           (line 767, code CANT_BAN_SELF)
 *   - forceLogout       (line 974, code CANT_FORCE_LOGOUT_SELF)
 *   - grantAdmin        (line 1036, code CANT_GRANT_SELF)
 *   - revokeAdmin       (line 1078, code CANT_REVOKE_SELF)
 *   - grantCsr          (line 1112, code CANT_GRANT_SELF)
 *
 * creditWallet was the holdout — and was the most exploitable hole of
 * all because it's the only one with a direct financial payoff. A
 * corrupt or compromised admin could push up to the $10k single-call
 * cap into their own wallet in one click, with an operator-written
 * note ("server costs reimbursement") that doubles as the audit row.
 * The audit row's actor AND subject collapse to the same id, so the
 * usual "show me admin actions taken against user X" filter would
 * not surface the action under any other user's id — the only paper
 * trail was a row whose subjectUserId equals the actor, which is
 * exactly the shape admins use to filter their OWN benign actions
 * out of dashboards.
 *
 * This spec pins:
 *   1. positive self-credit → CANT_CREDIT_SELF, no wallet save, no tx,
 *      no notification, no audit
 *   2. negative self-debit → CANT_CREDIT_SELF (symmetric — the gate
 *      isn't credit-only; an admin shouldn't be able to silently wipe
 *      their own balance before another admin notices either)
 *   3. crediting a DIFFERENT user still proceeds (gate is self-only,
 *      not a blanket block)
 */
class AdminServiceCreditWalletSelfTargetSpec extends Specification {

    SteamUserRepository     steamUserRepository     = Mock()
    WalletRepository        walletRepository        = Mock()
    TransactionRepository   transactionRepository   = Mock()
    ListingRepository       listingRepository       = Mock()
    com.sboxmarket.repository.ListingReportRepository listingReportRepository = Mock()
    SupportTicketRepository supportTicketRepository = Mock()
    ItemRepository          itemRepository          = Mock()
    NotificationService     notificationService     = Mock()
    TextSanitizer           textSanitizer           = Mock() {
        medium(_) >> { String s -> s }
    }
    AdminAuthorization      adminAuthorization      = Mock()
    BanGuard                banGuard                = Mock()
    com.sboxmarket.service.EmailService emailService = Mock()
    com.sboxmarket.repository.TradeRepository tradeRepository = Mock()
    com.sboxmarket.service.TradeService tradeService = Mock()
    com.sboxmarket.repository.WatchlistAlertRepository watchlistAlertRepository = Mock()
    com.sboxmarket.service.BuyOrderService buyOrderService = Mock()
    com.sboxmarket.repository.OfferRepository offerRepository = Mock()
    com.sboxmarket.repository.SupportMessageRepository supportMessageRepository = Mock()
    com.sboxmarket.service.AuditService auditService = Mock()

    @Subject
    AdminService service = new AdminService(
        // Declared for the same reason CsrServiceSpec declares dailyCapStr:
        // creditWallet reads this @Value field, and a hand-constructed service
        // gets no Spring injection. Left at the production default so these
        // specs exercise the shipped bound rather than one invented here.
        dailyCreditCapStr        : '25000.00',
        steamUserRepository      : steamUserRepository,
        walletRepository         : walletRepository,
        transactionRepository    : transactionRepository,
        listingRepository        : listingRepository,
        listingReportRepository  : listingReportRepository,
        supportTicketRepository  : supportTicketRepository,
        supportMessageRepository : supportMessageRepository,
        itemRepository           : itemRepository,
        notificationService      : notificationService,
        textSanitizer            : textSanitizer,
        adminAuthorization       : adminAuthorization,
        banGuard                 : banGuard,
        emailService             : emailService,
        auditService             : auditService,
        tradeRepository          : tradeRepository,
        tradeService             : tradeService,
        watchlistAlertRepository : watchlistAlertRepository,
        buyOrderService          : buyOrderService,
        offerRepository          : offerRepository
    )

    def "creditWallet refuses positive self-credit — corrupt admin cannot pay themselves"() {
        when: 'admin id=42 attempts to credit THEIR OWN wallet $9,999'
        service.creditWallet(42L, 42L, new BigDecimal('9999'), 'server costs')

        then: 'CANT_CREDIT_SELF surfaces — gate runs BEFORE any state lookup'
        def ex = thrown(BadRequestException)
        ex.code == 'CANT_CREDIT_SELF'

        and: 'no wallet was fetched, no ledger row written, no notification, no audit'
        0 * walletRepository.findByUsername(_)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * notificationService.push(*_)
        0 * auditService.log(*_, *_, *_, *_, *_)
    }

    def "creditWallet refuses negative self-debit too — gate is symmetric, not credit-only"() {
        when: 'admin id=42 attempts to debit THEIR OWN wallet -$500 (e.g. hide a mistakenly-large prior credit)'
        service.creditWallet(42L, 42L, new BigDecimal('-500'), 'reversal')

        then: 'same CANT_CREDIT_SELF code — debit path is gated like credit'
        def ex = thrown(BadRequestException)
        ex.code == 'CANT_CREDIT_SELF'

        and: 'no side effects fire'
        0 * walletRepository.findByUsername(_)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * notificationService.push(*_)
    }

    def "creditWallet still proceeds when targeting ANOTHER user — gate is self-only, not a blanket block"() {
        given: 'admin id=1 credits a DIFFERENT user id=20'
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222',
            balance: new BigDecimal('100.00'),
            frozen: false)
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.creditWallet(1L, 20L, new BigDecimal('50'), 'goodwill')

        then: 'happy path proceeds — gate did not over-block legitimate other-user credits'
        noExceptionThrown()
        wallet.balance == new BigDecimal('150.00')
        result.newBalance == new BigDecimal('150.00')
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'ADJUSTMENT_CREDIT' && tx.amount == new BigDecimal('50')
        })
    }
}
