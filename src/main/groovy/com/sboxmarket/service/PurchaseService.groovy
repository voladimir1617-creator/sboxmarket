package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Orchestrates a purchase: verify funds, debit wallet, mark listing sold,
 * record transactions for both sides, transfer ownership to buyer.
 *
 * Single-Responsibility: this class only knows about the BUY flow.
 * It depends on repositories (interfaces), not concrete DB implementations.
 */
@Service
@Slf4j
class PurchaseService {

    @Autowired ListingRepository listingRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired BanGuard banGuard
    @Autowired(required = false) AuditService auditService
    // Needed to resolve the seller's wallet via their Steam64 — every other
    // service keys wallets on `"steam_${steamId64}"`, not the numeric user id.
    @Autowired(required = false) SteamUserRepository steamUserRepository
    @Autowired(required = false) TradeService tradeService
    @Autowired(required = false) PriceHistoryService priceHistoryService
    @Autowired(required = false) ItemRepository itemRepository
    @Autowired(required = false) com.sboxmarket.repository.CartItemRepository cartItemRepository
    @Autowired(required = false) EmailService emailService

    @Transactional
    Map buy(Long buyerWalletId, Long buyerUserId, Long listingId) {
        banGuard.assertNotBanned(buyerUserId)
        def buyerWallet = walletRepository.findById(buyerWalletId)
                .orElseThrow { new NotFoundException("Wallet", buyerWalletId) }
        // Wallet freeze gate (batch 509). Frozen wallets can't purchase.
        // Mirrors WalletController's deposit/withdraw freeze checks.
        if (Boolean.TRUE.equals(buyerWallet.frozen)) {
            throw new BadRequestException("WALLET_FROZEN",
                "Your wallet is frozen by staff" +
                    (buyerWallet.frozenReason ? ": ${buyerWallet.frozenReason}" : '') +
                    ". Open a support ticket to resolve.")
        }
        // Active-chargeback gate on purchases (batch 511). Mirrors the
        // same guard the withdraw endpoint has had since batch 465.
        // Without this, a user with a disputed deposit could withdraw-
        // via-purchase: they buy a listing, the seller ships the item
        // in-game, we later lose the dispute and eat the loss while the
        // buyer keeps the item AND recovers the money from their bank.
        // Since we can't segregate which dollars in the balance are
        // disputed vs clean, block all outflows until the dispute closes.
        long disputed = transactionRepository.countActiveDisputedDeposits(buyerWallet.id)
        if (disputed > 0L) {
            throw new BadRequestException("PURCHASE_DISPUTE_HOLD",
                "Purchases are paused while you have ${disputed} unresolved deposit " +
                "dispute${disputed == 1 ? '' : 's'} on file. Once your bank closes the " +
                "chargeback (or staff clears the hold), purchases will resume.")
        }
        def listing = listingRepository.findById(listingId)
                .orElseThrow { new NotFoundException("Listing", listingId) }

        if (listing.status != 'ACTIVE') {
            throw new ListingNotAvailableException(listingId)
        }
        // Hidden listings are invisible to the public grid but the
        // listing id is stable. Reject buy attempts on hidden rows so
        // a cached client or a scraped-id payload can't purchase a
        // listing the seller has pulled off-market. Mirrors the same
        // guard added to `OfferService.makeOffer` (batch 308).
        if (Boolean.TRUE.equals(listing.hidden)) {
            throw new ListingNotAvailableException(listingId)
        }
        // AUCTION listings settle via BidService.settle() when expiresAt
        // passes — not via this BUY_NOW path. Without this guard, anyone
        // could sidestep the bidding mechanism by POSTing /api/listings/
        // {id}/buy on an auction, orphaning the current high bidder and
        // collapsing the whole bid history. Force auction purchases back
        // into the bid surface so the auction winner is whoever actually
        // bid highest.
        if (listing.listingType == 'AUCTION') {
            throw new BadRequestException("NOT_BUY_NOW",
                "This is an auction listing — place a bid instead of buying directly")
        }
        if (listing.buyerUserId != null && listing.buyerUserId == buyerUserId) {
            throw new BadRequestException("ALREADY_OWNED", "You already own this listing")
        }
        if (listing.sellerUserId != null && listing.sellerUserId == buyerUserId) {
            throw new BadRequestException("OWN_LISTING", "You can't buy your own listing")
        }
        if (buyerWallet.balance < listing.price) {
            throw new InsufficientBalanceException(listing.price, buyerWallet.balance)
        }
        // P2P sales require the buyer's Steam trade URL — the seller needs
        // it to send the item. Without this guard the purchase completes,
        // debits the buyer, opens the trade, and strands the seller with
        // no way to ship. Fail early so the buyer sees a friendly prompt
        // to set their trade URL instead of a buy-then-stuck flow. System
        // listings (sellerUserId == null) skip this — they resolve
        // in-platform, no Steam trade needed.
        if (listing.sellerUserId != null) {
            def buyer = steamUserRepository?.findById(buyerUserId)?.orElse(null)
            if (buyer != null && !buyer.tradeUrl?.trim()) {
                throw new BadRequestException("TRADE_URL_MISSING",
                    "Set your Steam trade URL in Profile before buying — the seller needs it to send you the item.")
            }
        }

        // Debit buyer
        buyerWallet.balance = buyerWallet.balance - listing.price
        walletRepository.save(buyerWallet)

        // Mark listing sold + transfer ownership
        listing.status = 'SOLD'
        listing.soldAt = System.currentTimeMillis()
        listing.buyerUserId = buyerUserId
        listingRepository.save(listing)

        // Record the sale price in the item's price-history table so the
        // item-detail sparkline reflects real buyer-paid prices, not just
        // whatever the external SCMM / Steam market sync happens to push.
        // Wrapped in try/catch + null guard so a history write never breaks
        // the purchase transaction — the sale itself is the authoritative
        // side-effect; the chart is cosmetic.
        try {
            priceHistoryService?.record(listing.item, listing.price, 1)
        } catch (Exception e) {
            log.warn("price-history record failed for listing ${listingId}: ${e.message}")
        }

        // Bump Item.totalSold atomically so the Database page's "Most
        // Traded" sort reflects real platform activity (not just the
        // external SCMM subscription count). Wrapped + null-guarded so
        // a repo hiccup never breaks the purchase.
        try {
            if (listing.item?.id != null) {
                itemRepository?.incrementTotalSold(listing.item.id)
            }
        } catch (Exception e) {
            log.warn("totalSold bump failed for item ${listing.item?.id}: ${e.message}")
        }

        // Record transaction on buyer side
        def buyerTx = new Transaction(
            walletId       : buyerWalletId,
            type           : 'PURCHASE',
            status         : 'COMPLETED',
            amount         : listing.price,
            currency       : buyerWallet.currency,
            stripeReference: 'wallet',
            description    : "Bought ${listing.item.name} from ${listing.sellerName}",
            listingId      : listing.id
        )
        transactionRepository.save(buyerTx)

        // P2P escrow: when a real seller exists, DON'T credit them
        // immediately. Create a Trade record that holds the buyer's
        // funds in escrow until the Steam trade offer is completed.
        // The seller gets paid only after the buyer clicks "Confirm
        // Receipt" (or the 8-day auto-release window passes).
        //
        // For system listings (sellerUserId == null), there's no
        // counterparty to trade with, so no escrow is needed.
        if (listing.sellerUserId != null) {
            // P2P escrow is CRITICAL — without the Trade row, the buyer
            // has no way to confirm receipt, dispute, or get a refund.
            // If this fails, the entire transaction must roll back so the
            // buyer isn't debited for a trade that doesn't exist.
            def sellerUser = steamUserRepository?.findById(listing.sellerUserId)?.orElse(null)
            def sellerWallet = sellerUser ? walletRepository.findByUsername("steam_${sellerUser.steamId64}") : null
            tradeService?.open(
                listing.id,
                listing.item?.id,
                listing.item?.name,
                buyerUserId,
                buyerWalletId,
                listing.sellerUserId,
                sellerWallet?.id,
                listing.price
            )
        }

        // Append-only audit trail — fires after the transaction is persisted
        // so failed attempts never pollute the log.
        try {
            auditService?.log(AuditService.LISTING_PURCHASED, buyerUserId, listing.sellerUserId, listing.id,
                "Bought ${listing.item?.name} for \$${listing.price}")
        } catch (Exception ignore) {}

        // Persisted notifications (buyer + seller). NotificationService is optional
        // in unit tests that use mocked collaborators — guard the null case.
        if (notificationService != null) {
            // Batch 631: safePush so a bell-push failure can't roll back
            // the purchase — the money movement already succeeded above.
            notificationService.safePush(buyerUserId, 'ITEM_PURCHASED',
                "Purchased ${listing.item.name}",
                "Paid \$${listing.price.toPlainString()} from balance",
                listing.id,
                '/profile?tab=trades')
            // For P2P trades, the seller notification is sent by
            // TradeService.open() as TRADE_REQUESTED — don't duplicate
            // it here with a premature TRADE_VERIFIED.
            // Buyer-side purchase receipt email (batch 571). The seller
            // gets sendTradeOpened from TradeService.open; this is the
            // mirror for the buyer. Gated the same as other
            // transactional trade emails (verified email + global
            // notification opt-in + TRADES bucket unmuted). Silent-fail
            // on any error — the bell push already went out.
            try {
                if (emailService != null && steamUserRepository != null) {
                    def buyer = steamUserRepository.findById(buyerUserId).orElse(null)
                    if (emailService.canSendTo(buyer, 'TRADES')) {
                        def sellerName = null
                        if (listing.sellerUserId != null) {
                            try {
                                sellerName = steamUserRepository.findById(listing.sellerUserId)
                                    .orElse(null)?.displayName
                            } catch (Exception ignore) { /* fall through */ }
                        }
                        emailService.sendPurchaseReceipt(buyer.email, buyer.displayName,
                            listing.item?.name, sellerName, listing.price, listing.id)
                    }
                }
            } catch (Exception e) {
                log.warn("Purchase-receipt email failed for buyer ${buyerUserId}: ${e.message}")
            }
            // Cart-item-sold fan-out (batch 503). When a popular drop
            // sells, every OTHER user who had the listing queued in
            // their cart sees a stale-grey row next time they open the
            // cart — and never knew until then. This pings them so
            // they can re-shop the item before the price moves.
            // Capped at CART_FANOUT_CAP so a hot listing doesn't fan
            // out to every cart on the platform; the cap is high
            // enough (50) to cover every realistic case.
            if (cartItemRepository != null) {
                try {
                    def others = cartItemRepository.findOtherUsersWithListing(listingId, buyerUserId) ?: []
                    if (!others.isEmpty()) {
                        def itemName = listing.item?.name ?: 'an item'
                        def itemId = listing.item?.id
                        others.take(50).each { uid ->
                            try {
                                notificationService.push(uid, 'CART_ITEM_SOLD',
                                    "Cart item sold · ${itemName}",
                                    "${itemName} was bought by another user. Other listings may still be available — find a similar one in the marketplace.",
                                    listingId,
                                    itemId != null ? "/item/${itemId}" : '/cart')
                            } catch (Exception e) {
                                log.warn("CART_ITEM_SOLD push failed for uid=${uid}: ${e.message}")
                            }
                        }
                        // Scrub the now-sold listing from every cart so
                        // the next /api/cart fetch doesn't show a ghost
                        // row. Best-effort — a delete miss just leaves
                        // the row for the client-side stale detector.
                        try {
                            cartItemRepository.deleteAllByListing(listingId)
                        } catch (Exception e) {
                            log.warn("CART_ITEM_SOLD scrub failed for listing=${listingId}: ${e.message}")
                        }
                    }
                } catch (Exception e) {
                    log.warn("CART_ITEM_SOLD fan-out failed for listing=${listingId}: ${e.message}")
                }
            }
        }

        log.info("Buyer wallet $buyerWalletId bought listing $listingId for \$${listing.price}")
        [
            transactionId: buyerTx.id,
            newBalance   : buyerWallet.balance,
            listing      : listing
        ]
    }
}
