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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

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
    /** Optional so unit tests that build the service with `new
     *  PurchaseService(...)` (no Spring context) still work — in that case
     *  there is never an active transaction and {@link #deferOrRun} runs
     *  the work immediately so the mocked-repo assertions still fire. */
    @Autowired(required = false) PlatformTransactionManager transactionManager

    /**
     * Run {@code work} after the caller's transaction commits — or
     * immediately when there is no active transaction (matches the
     * pattern used by {@link PriceHistoryService}/{@link AuditService}).
     *
     * Why this matters for the BUY path: a same-transaction repository
     * call that throws (DB blip, constraint, optimistic lock) calls
     * {@code setRollbackOnly()} on the SHARED transaction via Spring's
     * inner transactional proxy. The wrapping try/catch in {@link #buy}
     * swallows the exception and the buyer sees "success", but the
     * outer commit then blows up with UnexpectedRollbackException — the
     * money movement, the SOLD flip, and the Trade escrow row are ALL
     * rolled back while the buyer's UI thinks the purchase landed. By
     * deferring the cosmetic side-effects (totalSold bump, cart scrub,
     * CART_ITEM_SOLD fan-out) to {@code afterCommit}, they run in fresh
     * REQUIRES_NEW transactions AFTER the money write is durably
     * committed, so a failure in any of them can no longer poison the
     * sale itself. (PriceHistoryService and AuditService already do
     * this for the same reason; this closes the remaining gap.)
     */
    private void deferOrRun(Closure work) {
        if (transactionManager != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override void afterCommit() {
                    try {
                        def tt = new TransactionTemplate(transactionManager)
                        tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                        tt.executeWithoutResult { work() }
                    } catch (Exception e) {
                        log.warn("Deferred best-effort write failed: ${e.message}")
                    }
                }
            })
        } else {
            // No active transaction (unit tests / non-transactional caller).
            // Still swallow exceptions so a best-effort side-effect can't
            // fail the caller — matches the safety semantic the original
            // inline try/catch wrappers provided. The "throw must not
            // break the parent" contract is what every call-site relies on.
            try {
                work()
            } catch (Exception e) {
                log.warn("Best-effort write failed (no active tx): ${e.message}")
            }
        }
    }

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
        //
        // P2P seller-wallet resolution is hoisted up here (same gate, pre-debit)
        // so we can FAIL the purchase if the seller has no wallet, rather than
        // debit the buyer, flip listing→SOLD, open a Trade with
        // sellerWalletId=null, then quietly skip the seller credit at
        // VERIFIED release. That post-hoc path leaves the platform holding
        // the buyer's money with no automated way to pay the seller — only
        // a log line ("Manual payout required") that ops has to spot. Fail
        // fast here so the buyer sees a clean error and the money never moves.
        // System listings (sellerUserId == null) skip both checks — they
        // resolve in-platform, no Steam trade and no seller wallet needed.
        com.sboxmarket.model.SteamUser _resolvedSellerUser = null
        Wallet _resolvedSellerWallet = null
        if (listing.sellerUserId != null) {
            def buyer = steamUserRepository?.findById(buyerUserId)?.orElse(null)
            if (buyer != null && !buyer.tradeUrl?.trim()) {
                throw new BadRequestException("TRADE_URL_MISSING",
                    "Set your Steam trade URL in Profile before buying — the seller needs it to send you the item.")
            }
            _resolvedSellerUser = steamUserRepository?.findById(listing.sellerUserId)?.orElse(null)
            _resolvedSellerWallet = _resolvedSellerUser ?
                walletRepository.findByUsername("steam_${_resolvedSellerUser.steamId64}") : null
            if (_resolvedSellerWallet == null) {
                throw new BadRequestException("SELLER_WALLET_MISSING",
                    "This listing can't be purchased right now — the seller's payout account isn't set up. " +
                    "We've notified them; try again later or pick a different listing.")
            }
        }

        // Debit buyer
        buyerWallet.balance = buyerWallet.balance - listing.price
        walletRepository.save(buyerWallet)

        // Mark listing sold + transfer ownership.
        //
        // saveAndFlush (NOT plain save) is load-bearing for concurrency
        // correctness. The wallet debit above and this listing update are
        // both versioned (@Version on Wallet + Listing). With a plain
        // save() the UPDATEs stay buffered in the persistence context and
        // are only pushed to the DB at the first auto-flush point — which,
        // walking the code below, is the SELECT inside
        // priceHistoryService.record(). That SELECT-triggered flush is
        // wrapped in a try/catch, so when a concurrent buyer wins the race
        // the loser's StaleObjectStateException surfaces *inside* that
        // catch block and gets SWALLOWED — masking the optimistic-lock
        // conflict and letting the loser cascade into an opaque
        // UnexpectedRollbackException instead of the clean
        // ObjectOptimisticLockingFailureException the HTTP layer maps to
        // 409. Flushing here forces both versioned UPDATEs out to the DB
        // immediately, OUTSIDE any try/catch, so the lock conflict
        // propagates cleanly and the cosmetic side-effects below run only
        // once the sale is guaranteed to be the winner.
        listing.status = 'SOLD'
        listing.soldAt = System.currentTimeMillis()
        listing.buyerUserId = buyerUserId
        listingRepository.saveAndFlush(listing)

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
        // external SCMM subscription count). Deferred to afterCommit
        // (not just try/catch) so a failing UPDATE can't mark the
        // shared transaction rollback-only and silently kill the sale.
        // The try/catch alone wasn't enough: Spring's transactional
        // proxy on JpaRepository calls setRollbackOnly() BEFORE the
        // exception escapes back here, so the swallow only hid the
        // failure — the outer commit still threw UnexpectedRollbackException.
        // Null-guard preserved so a missing item id is a quiet no-op.
        final Long _itemIdForBump = listing.item?.id
        if (_itemIdForBump != null && itemRepository != null) {
            deferOrRun { itemRepository.incrementTotalSold(_itemIdForBump) }
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
            // Wallet resolution was already done in the pre-debit gate above
            // (SELLER_WALLET_MISSING) so we know _resolvedSellerWallet is
            // non-null here — reuse it instead of re-querying.
            tradeService?.open(
                listing.id,
                listing.item?.id,
                listing.item?.name,
                buyerUserId,
                buyerWalletId,
                listing.sellerUserId,
                _resolvedSellerWallet?.id,
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
        }

        // Cart-item-sold fan-out (batch 503). When a popular drop
        // sells, every OTHER user who had the listing queued in
        // their cart sees a stale-grey row next time they open the
        // cart — and never knew until then. This pings them so
        // they can re-shop the item before the price moves.
        // Capped at CART_FANOUT_CAP so a hot listing doesn't fan
        // out to every cart on the platform; the cap is high
        // enough (50) to cover every realistic case.
        //
        // Hoisted OUT of the `notificationService != null` block above:
        // the cart-row SCRUB has to happen on every successful sale,
        // not just the ones where the notification bean happens to be
        // wired in. Without this, a misconfigured / disabled notifier
        // left ghost cart rows on every sold listing — silent data
        // leak between buyers — until the next client-side stale check
        // happened to fire. Notifications are still gated on the bean
        // (the inner null-check below), but the scrub is unconditional.
        //
        // Deferred to afterCommit (not just try/catch). The cart query
        // AND the bulk DELETE are repository calls — if either throws
        // (DB blip, lock-wait timeout, dialect quirk), Spring's inner
        // transactional proxy calls setRollbackOnly() on the SHARED
        // transaction before the exception bubbles. The outer
        // try/catch hid that, but the parent commit still threw
        // UnexpectedRollbackException and the money write + SOLD flip
        // + Trade escrow were ALL rolled back while the buyer thought
        // the purchase succeeded. Running the scrub post-commit in a
        // fresh REQUIRES_NEW transaction means a cart-side failure can
        // now only leave a few ghost rows (the client-side stale
        // detector picks them up) instead of silently nuking the sale.
        if (cartItemRepository != null) {
            final Long _listingIdForScrub = listingId
            final Long _buyerIdForScrub = buyerUserId
            final String _itemNameForScrub = listing.item?.name ?: 'an item'
            final Long _itemIdForScrub = listing.item?.id
            final NotificationService _notifierForScrub = notificationService
            final com.sboxmarket.repository.CartItemRepository _cartRepoForScrub = cartItemRepository
            final SteamUserRepository _userRepoForScrub = steamUserRepository
            deferOrRun {
                try {
                    def others = _cartRepoForScrub.findOtherUsersWithListing(_listingIdForScrub, _buyerIdForScrub) ?: []
                    if (!others.isEmpty()) {
                        if (_notifierForScrub != null) {
                            // Drop banned recipients before the push fan-out —
                            // same bug class batch 314/315 closed for the
                            // saved-search and seller-follow fan-outs. A user
                            // banned after queuing the listing in their cart
                            // can't act on a CART_ITEM_SOLD ping (banGuard
                            // rejects re-shop attempts anyway), so the bell
                            // entry is dead-end noise. Bulk lookup keeps it
                            // to one query for the whole fan-out.
                            def recipients = others.take(50) as List<Long>
                            if (_userRepoForScrub != null && !recipients.isEmpty()) {
                                try {
                                    def users = _userRepoForScrub.findAllById(recipients)
                                    if (users != null) {
                                        def bannedIds = users
                                            .findAll { Boolean.TRUE.equals(it.banned) }
                                            .collect { it.id } as Set
                                        if (!bannedIds.isEmpty()) {
                                            recipients = recipients.findAll { !bannedIds.contains(it) }
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("CART_ITEM_SOLD banned-filter lookup failed: ${e.message}")
                                }
                            }
                            recipients.each { uid ->
                                try {
                                    _notifierForScrub.push(uid, 'CART_ITEM_SOLD',
                                        "Cart item sold · ${_itemNameForScrub}",
                                        "${_itemNameForScrub} was bought by another user. Other listings may still be available — find a similar one in the marketplace.",
                                        _listingIdForScrub,
                                        _itemIdForScrub != null ? "/item/${_itemIdForScrub}" : '/cart')
                                } catch (Exception e) {
                                    log.warn("CART_ITEM_SOLD push failed for uid=${uid}: ${e.message}")
                                }
                            }
                        }
                        // Scrub the now-sold listing from every cart so
                        // the next /api/cart fetch doesn't show a ghost
                        // row. Best-effort — a delete miss just leaves
                        // the row for the client-side stale detector.
                        try {
                            _cartRepoForScrub.deleteAllByListing(_listingIdForScrub)
                        } catch (Exception e) {
                            log.warn("CART_ITEM_SOLD scrub failed for listing=${_listingIdForScrub}: ${e.message}")
                        }
                    }
                } catch (Exception e) {
                    log.warn("CART_ITEM_SOLD fan-out failed for listing=${_listingIdForScrub}: ${e.message}")
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
