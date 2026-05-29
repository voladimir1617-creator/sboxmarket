package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeProtection
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TradeProtectionRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TradeProtectionService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pins the audit subject on TRADE_PROTECTION_ENABLED to the BUYER, not the
 * seller. The buyer is the wallet whose money just moved (a fresh PURCHASE
 * debit for the protection fee), and the audit-by-subject filter on
 * /security-activity (ProfileController.securityActivity) relies on the
 * subject being the wallet owner so the row surfaces in the OWNER's feed.
 *
 * Regression: the original write tagged trade.sellerUserId as the audit
 * subject. The seller had no money movement, no notification, no
 * involvement at all — only the buyer was charged. Routing the audit row
 * into the seller's by-subject filter:
 *
 *   • Buries a buyer-wallet debit on the WRONG account in the admin audit
 *     by-subject filter — searching the buyer's own audit trail for
 *     "where did that $X disappear to" comes up empty.
 *   • Silently mis-attributes a fee charged to user A as an event "about"
 *     user B in the per-subject trail an admin walks during fraud /
 *     ticket triage.
 *   • Defeats the very design /security-activity uses for the sibling
 *     CLAIMED / REVERSED events (both correctly use
 *     subjectUserId = protection.buyerUserId, per the inline
 *     ProfileController whitelist comment at line ~258): an account
 *     takeover that hot-clicks "Enable protection" on every PENDING_*
 *     trade to bleed MIN_FEE × N out of the buyer's wallet leaves zero
 *     subject-keyed audit footprint on the victim's account.
 *
 * Fix: subjectUserId = buyerUserId, matching the sibling autoClaim /
 * reverseClaim writes (TradeProtectionService:308, 408) and the audit-
 * subject convention pinned by every other money-impact event on the
 * /security-activity whitelist (WITHDRAW_*, DEPOSIT_COMPLETE,
 * REFUND_ISSUED — all subject = the wallet owner).
 */
class TradeProtectionEnableAuditSubjectSpec extends Specification {

    TradeProtectionRepository tradeProtectionRepository = Mock()
    TradeRepository           tradeRepository           = Mock()
    WalletRepository          walletRepository          = Mock()
    TransactionRepository     transactionRepository     = Mock()
    NotificationService       notificationService       = Mock()
    AuditService              auditService              = Mock()

    @Subject
    TradeProtectionService service = new TradeProtectionService(
        tradeProtectionRepository : tradeProtectionRepository,
        tradeRepository           : tradeRepository,
        walletRepository          : walletRepository,
        transactionRepository     : transactionRepository,
        notificationService       : notificationService,
        auditService              : auditService
    )

    def "enable writes the audit row with subjectUserId = buyer (the wallet that was debited), NOT seller"() {
        given: "a protected-fee-eligible trade: buyer 77 enables on a \$50 PENDING_SELLER_SEND trade owned by seller 88"
        def trade = new Trade(
            id:             1L,
            listingId:      100L,
            itemId:         1L,
            itemName:       'Wizard Hat',
            buyerUserId:    77L,
            buyerWalletId:  500L,
            sellerUserId:   88L,
            price:          new BigDecimal('50.00'),
            state:          'PENDING_SELLER_SEND'
        )
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p.id = 7L; p }

        when:
        def protection = service.enable(77L, 1L)

        then: "happy-path side effects all land — fee debit + ACTIVE protection"
        protection.status == TradeProtection.ACTIVE
        wallet.balance == new BigDecimal('9.00')

        and: "the audit row has the BUYER (77) as both actor AND subject — the wallet that just moved is its own subject. NOT the seller (88) — seller had no money movement and is not a participant in this event."
        1 * auditService.log('TRADE_PROTECTION_ENABLED', 77L, 77L, 1L, _)

        and: "the audit row is NOT mis-routed to the seller's by-subject filter — the regression"
        0 * auditService.log('TRADE_PROTECTION_ENABLED', _, 88L, _, _)
    }

    def "enable's audit subject equals its actor — both are the buyer (idempotent invariant on the wallet owner)"() {
        given: "a different buyer/seller pair confirms the audit identity isn't a coincidence of the value 0"
        def trade = new Trade(
            id:             42L,
            listingId:      200L,
            itemId:         2L,
            itemName:       'Crown',
            buyerUserId:    111L,
            buyerWalletId:  900L,
            sellerUserId:   222L,
            price:          new BigDecimal('100.00'),
            state:          'PENDING_BUYER_CONFIRM'
        )
        def wallet = new Wallet(id: 900L, balance: new BigDecimal('20.00'), currency: 'USD')
        tradeRepository.findById(42L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(42L) >> false
        walletRepository.findById(900L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        service.enable(111L, 42L)

        then: "buyer is BOTH the actor (who clicked) and the subject (whose wallet moved) — same as DEPOSIT_COMPLETE / REFUND_ISSUED convention"
        1 * auditService.log('TRADE_PROTECTION_ENABLED',
            111L,   // actor = buyer
            111L,   // subject = buyer (mirrors CLAIMED/REVERSED, NOT the seller 222)
            42L,
            _)
    }
}
