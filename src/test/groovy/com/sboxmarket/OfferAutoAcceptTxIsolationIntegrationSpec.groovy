package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.OfferService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Regression proof for the P1 @Transactional self-invocation bug in
 * OfferService.tryAutoAccept.
 *
 * THE BUG (now fixed):
 *   makeOffer + buyerRaise are both @Transactional. Both call
 *   tryAutoAccept(), which calls `this.acceptOffer(...)`. acceptOffer
 *   is annotated @Transactional(noRollbackFor = [InsufficientBalance,
 *   ListingNotAvailable, ForbiddenException]) so its failure-branch
 *   writes (offer.status='EXPIRED', closeCounteredParent, the buyer's
 *   OFFER_REJECTED ping) survive the throw — except Spring's
 *   @Transactional is proxy-based, and `this.acceptOffer(...)` BYPASSES
 *   the proxy entirely. acceptOffer's writes execute inside the OUTER
 *   makeOffer transaction, and noRollbackFor is silently dropped on
 *   the self-invocation path.
 *
 *   Concretely: a buyer whose wallet drained between offer creation and
 *   the auto-accept attempt sees their fresh offer land EXPIRED instead
 *   of PENDING — acceptOffer's "insufficient balance" branch flipped
 *   the row to EXPIRED, then threw InsufficientBalanceException, which
 *   tryAutoAccept's catch swallowed. The EXPIRED save survives because
 *   it's part of the outer tx the catch never blew up, but it never
 *   should have been visible to the buyer at all — they offered $85
 *   and the system silently said "expired, top up and try again" with
 *   the offer never visible to the seller and never reachable via
 *   buyerRaise. The makeOffer call returns `saved` (a stale snapshot
 *   with status='PENDING' in memory), so the controller's response
 *   tells the buyer they have a PENDING offer — but the DB row is
 *   EXPIRED. Profile -> Offers immediately shows the row as EXPIRED.
 *
 * THE FIX:
 *   tryAutoAccept wraps the acceptOffer self-call in a REQUIRES_NEW
 *   programmatic transaction (TransactionTemplate). The outer makeOffer
 *   tx is suspended; acceptOffer's saves now happen inside a fresh
 *   inner tx. When acceptOffer throws InsufficientBalanceException
 *   (a RuntimeException), the TransactionTemplate's commit hook
 *   rolls back the inner tx — UNDOING the EXPIRED save, the
 *   closeCounteredParent flip, and any OFFER_REJECTED side effects.
 *   tryAutoAccept's catch absorbs the throw as designed. The outer
 *   makeOffer tx resumes, commits the offer in PENDING state, and the
 *   buyer ends up exactly where the auto-accept-failed-fall-back-to-
 *   PENDING contract promised.
 *
 *   Mirrors BidService.runInIsolatedTx — same REQUIRES_NEW pattern
 *   that closed the auction-sweep family of rollback-only leaks.
 *
 * THE REPRO:
 *   Seed a real listing with maxDiscount = 0.20 (threshold $80 on $100
 *   ask), a real buyer with a wallet. Drain the wallet to $10 before
 *   invoking makeOffer($85). The $85 offer crosses the auto-accept
 *   threshold, tryAutoAccept fires acceptOffer, the wallet check
 *   trips, InsufficientBalanceException is raised.
 *
 *   With the bug present (the self-call without REQUIRES_NEW), the
 *   DB row ends up EXPIRED. With the fix, the row is durably PENDING.
 *
 * Sibling: SideEffectTransactionIsolationIntegrationSpec — same
 * proxy-boundary family, fixed for NotificationService / AuditService /
 * PriceHistoryService via afterCommit deferral. We use REQUIRES_NEW
 * here because the auto-accept's result (ACCEPTED, with all the
 * downstream PurchaseService side effects) must be visible to the
 * caller before makeOffer returns — it can't be deferred to post-commit.
 */
@SpringBootTest
@ActiveProfiles("test")
class OfferAutoAcceptTxIsolationIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx

    OfferService          offerService
    OfferRepository       offerRepository
    ListingRepository     listingRepository
    ItemRepository        itemRepository
    SteamUserRepository   steamUserRepository
    WalletRepository      walletRepository

    def setup() {
        offerService        = ctx.getBean(OfferService)
        offerRepository     = ctx.getBean(OfferRepository)
        listingRepository   = ctx.getBean(ListingRepository)
        itemRepository      = ctx.getBean(ItemRepository)
        steamUserRepository = ctx.getBean(SteamUserRepository)
        walletRepository    = ctx.getBean(WalletRepository)
    }

    /**
     * Build a self-contained (buyer, seller, listing) tuple unique to
     * this spec run so the integration-test Spring context can be reused
     * across siblings without bleed. Listing.maxDiscount = 0.20 so any
     * offer >= $80 on the $100 ask trips auto-accept.
     */
    private Map seedWorld() {
        def uniq = String.valueOf(System.nanoTime())
        def buyerSteamId  = "76561199" + (uniq.substring(uniq.length() - 9))
        def sellerSteamId = "76561198" + (uniq.substring(uniq.length() - 9))

        def buyer = steamUserRepository.save(new SteamUser(
            steamId64:   buyerSteamId,
            displayName: "AutoAcceptBuyer-" + uniq,
            // Required so makeOffer's TRADE_URL_MISSING gate passes for
            // a real (non-system) listing — see OfferService line 207.
            tradeUrl:    "https://steamcommunity.com/tradeoffer/new/?partner=1&token=" + uniq
        ))
        def seller = steamUserRepository.save(new SteamUser(
            steamId64:   sellerSteamId,
            displayName: "AutoAcceptSeller-" + uniq
        ))
        // Buyer wallet starts well above the offer amount so makeOffer's
        // creation succeeds; we drain it later, right before driving
        // makeOffer, so the auto-accept attempt sees insufficient funds
        // and exercises the noRollbackFor branch.
        def buyerWallet = walletRepository.save(new Wallet(
            username: "steam_" + buyerSteamId,
            balance:  new BigDecimal("200.00")
        ))
        def item = itemRepository.save(new Item(
            name:        "AutoAccept Wizard Hat " + uniq,
            category:    "Hats",
            rarity:      "Limited",
            iconEmoji:   "🧙",
            accentColor: "#1a0a3a",
            supply:      10,
            totalSold:   0,
            lowestPrice: new BigDecimal("100.00")
        ))
        def listing = listingRepository.save(new Listing(
            item:         item,
            price:        new BigDecimal("100.00"),
            sellerName:   "AutoAcceptSeller",
            sellerAvatar: "AS",
            status:       "ACTIVE",
            rarityScore:  new BigDecimal("0.50"),
            sellerUserId: seller.id,
            // Threshold = 100 * (1 - 0.20) = $80. Any offer >= $80 trips
            // tryAutoAccept; we offer $85 and starve the wallet below
            // that to force acceptOffer to throw InsufficientBalance.
            maxDiscount:  new BigDecimal("0.20")
        ))
        [buyer: buyer, seller: seller, buyerWallet: buyerWallet,
         item: item, listing: listing]
    }

    def "auto-accept failure does NOT poison the outer makeOffer transaction"() {
        given: "a real listing with maxDiscount threshold, a real buyer + wallet"
        def world = seedWorld()
        Long buyerId    = world.buyer.id as Long
        Long listingId  = world.listing.id as Long
        Long walletId   = world.buyerWallet.id as Long

        and: "the buyer's wallet is drained below the auto-accept amount AFTER setup"
        // Drain to $10. The $85 offer crosses the auto-accept threshold
        // ($80), so tryAutoAccept will fire acceptOffer; acceptOffer will
        // then throw InsufficientBalanceException ($10 < $85). With the
        // bug present, that throw — having bypassed the @Transactional
        // proxy on the self-call — marks the outer makeOffer tx
        // rollback-only.
        def drained = walletRepository.findById(walletId).get()
        drained.balance = new BigDecimal("10.00")
        walletRepository.save(drained)

        when: 'the buyer makes a $85 offer — above the $80 auto-accept threshold'
        def offer = offerService.makeOffer(
            buyerId, "AutoAcceptBuyer", listingId, new BigDecimal("85"))

        then: "makeOffer completes without throwing"
        noExceptionThrown()

        and: "the offer was durably persisted in PENDING state — auto-accept failure was undone"
        // Pre-fix: acceptOffer's "insufficient balance" branch flipped
        // the offer row to EXPIRED inside the OUTER makeOffer tx
        // (self-invocation bypassed the proxy, so noRollbackFor was
        // ignored AND the failure-branch writes joined the outer tx).
        // The reload then returned `status == 'EXPIRED'` while the
        // makeOffer return value's in-memory snapshot still read
        // 'PENDING' — the buyer's response would have looked OK while
        // their Profile -> Offers tab showed the same row already
        // EXPIRED. The assertion below fails fast on the EXPIRED row.
        // Post-fix the REQUIRES_NEW inner tx rolls back, so PENDING
        // survives.
        offer != null
        offer.id != null
        offer.status == 'PENDING'
        def reloaded = offerRepository.findById(offer.id).orElse(null)
        reloaded != null
        reloaded.status == 'PENDING'
        reloaded.amount == new BigDecimal("85.00")
        reloaded.buyerUserId == buyerId
        reloaded.listingId == listingId
    }
}
