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
 * Pins the wallet-freeze gate on AdminService.creditWallet. freezeWallet's
 * own javadoc (AdminService line 1734-1740) states the contract: "all
 * money-in / money-out paths refuse" — used for regulatory holds, fraud
 * investigations, or user-requested security lockouts.
 *
 * Every OTHER money-path on the platform enforces this:
 *   - WalletController.deposit            (line 365, code WALLET_FROZEN)
 *   - WalletController.withdraw           (line 385, code WALLET_FROZEN)
 *   - AdminService.approveWithdrawal      (line 551, code WALLET_FROZEN)
 *   - CsrService.issueGoodwillCredit      (line 406, code WALLET_FROZEN)
 *
 * AdminService.creditWallet was the holdout: a second admin clicking
 * Credit on a stale Users tab — or a compromised admin account — could
 * push money INTO or pull money OUT of a wallet that staff had
 * deliberately locked, silently bypassing the freeze that fraud /
 * regulatory / user-lockout workflows depend on.
 *
 * This spec pins the gate from both directions:
 *   1. positive amount on a frozen wallet → WALLET_FROZEN, no save, no audit
 *   2. negative amount on a frozen wallet → WALLET_FROZEN, no save, no audit
 *   3. positive amount on a non-frozen wallet → cleanly proceeds (the gate
 *      is freeze-specific, not a blanket block)
 */
class AdminServiceCreditWalletFrozenSpec extends Specification {

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

    def "creditWallet refuses a positive adjustment on a frozen wallet — freeze contract is money-IN aware"() {
        given: 'a frozen wallet with a posted freeze reason'
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222',
            balance: new BigDecimal('100.00'),
            frozen: true,
            frozenReason: 'fraud investigation')
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet

        when: 'admin attempts a $50 goodwill credit'
        service.creditWallet(1L, 20L, new BigDecimal('50'), 'goodwill')

        then: 'WALLET_FROZEN code surfaces with the staff reason so the operator sees why'
        def ex = thrown(BadRequestException)
        ex.code == 'WALLET_FROZEN'
        ex.message.contains('fraud investigation')

        and: 'the wallet balance is NOT mutated and NO ledger row is written'
        wallet.balance == new BigDecimal('100.00')
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * notificationService.push(*_)
        0 * auditService.log(*_, *_, *_, *_, *_)
    }

    def "creditWallet refuses a negative adjustment on a frozen wallet — freeze contract is money-OUT aware"() {
        given: 'a frozen wallet — staff lockout is in effect'
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222',
            balance: new BigDecimal('100.00'),
            frozen: true,
            frozenReason: 'regulatory hold')
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet

        when: 'admin attempts a -$30 clawback'
        service.creditWallet(1L, 20L, new BigDecimal('-30'), 'clawback')

        then: 'WALLET_FROZEN — debit path is gated symmetrically with the credit path'
        def ex = thrown(BadRequestException)
        ex.code == 'WALLET_FROZEN'
        ex.message.contains('regulatory hold')

        and: 'the wallet balance is unchanged and no ledger / notify / audit fired'
        wallet.balance == new BigDecimal('100.00')
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * notificationService.push(*_)
    }

    def "creditWallet still proceeds on a non-frozen wallet (gate is freeze-specific, not a blanket block)"() {
        given: 'identical fixture to the frozen test but frozen=false'
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

        then: 'happy-path proceeds — the freeze gate did not over-block'
        noExceptionThrown()
        wallet.balance == new BigDecimal('150.00')
        result.newBalance == new BigDecimal('150.00')
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'ADJUSTMENT_CREDIT' && tx.amount == new BigDecimal('50')
        })
    }
}
