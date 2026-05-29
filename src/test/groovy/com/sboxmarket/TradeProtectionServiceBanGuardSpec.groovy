package com.sboxmarket

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.Trade
import com.sboxmarket.model.TradeProtection
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TradeProtectionRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.TradeProtectionService
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * BanGuard coverage for TradeProtectionService.enable.
 *
 * Every sibling money-path on the buyer surface (TradeService.cancel /
 * dispute / buy) calls banGuard.assertNotBanned at the top — protection-
 * enable was missed when BanGuard was extracted. A banned buyer enabling
 * protection on a live trade lights up an automatic wallet-credit path
 * via autoClaim when the trade is later cancelled (seller-timeout sweeper
 * fires the bulk of these), exactly the kind of post-ban money movement
 * the guard exists to close. Also covers the account-takeover variant:
 * the takeover account flips protection on every live trade of a freshly
 * banned victim to drain MIN_FEE × N from the wallet before staff lock it.
 *
 * The guard fires BEFORE the trade lookup, so a banned buyer trying to
 * protect an unknown trade still gets 403 (not 404) — a banned account
 * mustn't be allowed to probe trade-id space either.
 */
class TradeProtectionServiceBanGuardSpec extends Specification {

    TradeProtectionRepository tradeProtectionRepository = Mock()
    TradeRepository           tradeRepository           = Mock()
    WalletRepository          walletRepository          = Mock()
    TransactionRepository     transactionRepository     = Mock()
    BanGuard                  banGuard                  = Mock()

    @Subject
    TradeProtectionService service = new TradeProtectionService(
        tradeProtectionRepository : tradeProtectionRepository,
        tradeRepository           : tradeRepository,
        walletRepository          : walletRepository,
        transactionRepository     : transactionRepository,
        banGuard                  : banGuard
    )

    private Trade liveTrade() {
        new Trade(
            id:             1L,
            listingId:      100L,
            itemId:         1L,
            itemName:       'Wizard Hat',
            buyerUserId:    10L,
            buyerWalletId:  500L,
            sellerUserId:   20L,
            price:          new BigDecimal('50.00'),
            state:          'PENDING_SELLER_SEND'
        )
    }

    def "enable rejects a banned buyer BEFORE charging the protection fee"() {
        given: "the buyer is banned"
        banGuard.assertNotBanned(10L) >> {
            throw new ForbiddenException('Your account is banned: scam ring')
        }

        when:
        service.enable(10L, 1L)

        then: "ForbiddenException is rethrown — same 403 surface as every other buyer-side money path"
        def e = thrown(ForbiddenException)
        e.message.contains('banned')

        and: "no DB lookup, no wallet debit, no protection row — the ban fence held"
        0 * tradeRepository.findById(_)
        0 * walletRepository.findById(_)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
        0 * tradeProtectionRepository.save(_)
    }

    def "enable consults BanGuard BEFORE the trade lookup so banned users can't probe trade ids"() {
        given: "a banned buyer aiming at an unknown trade id"
        banGuard.assertNotBanned(10L) >> {
            throw new ForbiddenException('Your account is banned')
        }

        when:
        service.enable(10L, 9999L)

        then: "403, not 404 — the ban check fires first so a banned account can't enumerate trades by id"
        thrown(ForbiddenException)
        0 * tradeRepository.findById(_)
    }

    def "enable proceeds for an unbanned buyer (control case — guard is a passthrough)"() {
        given: "BanGuard.assertNotBanned is a no-op for an unbanned buyer"
        banGuard.assertNotBanned(10L) >> { /* no-op */ }

        and: "a happy-path live trade + funded wallet"
        def trade  = liveTrade()
        def wallet = new Wallet(id: 500L, balance: new BigDecimal('10.00'), currency: 'USD')
        tradeRepository.findById(1L) >> Optional.of(trade)
        tradeProtectionRepository.existsByTradeId(1L) >> false
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { args -> args[0] }
        tradeProtectionRepository.save(_) >> { TradeProtection p -> p }

        when:
        def protection = service.enable(10L, 1L)

        then: "an ACTIVE protection record is created — the guard does not block the happy path"
        protection.status == TradeProtection.ACTIVE

        and: "the guard was consulted exactly once for the buyer userId"
        1 * banGuard.assertNotBanned(10L)
    }
}
