package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ConflictException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Orchestrates a sale-listing: a user takes an item they own (a previously
 * purchased Listing) and lists it back on the market at their own price.
 *
 * Single-Responsibility: only handles the "list from inventory" flow.
 */
@Service
@Slf4j
class SellService {

    @Autowired ListingRepository listingRepository
    @Autowired ItemRepository itemRepository
    @Autowired TradeRepository tradeRepository
    @Autowired @Lazy BuyOrderService buyOrderService
    @Autowired @Lazy TradeService tradeService
    @Autowired BanGuard banGuard
    @Autowired TextSanitizer textSanitizer
    @Autowired(required = false) SellerFollowService sellerFollowService
    @Autowired(required = false) SavedSearchService savedSearchService
    @Autowired(required = false) BidRepository bidRepository
    @Autowired(required = false) OfferRepository offerRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) WatchlistAlertService watchlistAlertService
    @Autowired(required = false) com.sboxmarket.repository.CartItemRepository cartItemRepository
    @Autowired(required = false) ListingFloorRefreshService listingFloorRefreshService
    // Bot-escrow custody — on cancel, if the item is held by the bot, send it
    // back to the seller. Optional/gated: no-op when the bot is unconfigured.
    @Autowired(required = false) SteamEscrowService steamEscrowService

    /**
     * Per-listing transaction wrapper for cancelAllActive (boss-QA cycle 33).
     * Required = false so Spock specs that build the service via property
     * map without a Spring context still work: runInIsolatedTx falls back
     * to executing the closure inline when there is no transaction manager.
     *
     * Why this exists: cancelAllActive was `@Transactional` and wrapped the
     * whole batch in ONE outer tx. Each per-listing `cancelListing` call was
     * also `@Transactional` BUT Spring's default CGLIB-proxy mode does NOT
     * intercept self-invocation, so the inner annotation was dead code and
     * the per-listing work ran in the SHARED outer tx. A thrown ApiException
     * from one row (e.g. ListingNotAvailableException because someone bought
     * between findActiveBySeller and cancelListing) marked the shared tx
     * rollback-only. The try/catch above swallowed the exception, but the
     * @Transactional commit would then throw UnexpectedRollbackException at
     * the very end, surfacing a 500 even though the user-facing
     * `{cancelled:N, failed:K}` summary was already populated.
     *
     * Mirrors the TradeService.sweepPendingConfirm + BidService.sweepExpired
     * pattern: the outer batch is unannotated, and each per-listing
     * cancelListing runs in a REQUIRES_NEW transaction via TransactionTemplate
     * so one rollback can't poison sibling cancels in the batch.
     */
    @Autowired(required = false) PlatformTransactionManager transactionManager
    private void runInIsolatedTx(Closure work) {
        if (transactionManager == null) {
            // No Spring context (Spock plain-prop test) — run inline.
            work()
            return
        }
        def tt = new TransactionTemplate(transactionManager)
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        tt.execute({ status -> work() ; null } as org.springframework.transaction.support.TransactionCallback)
    }

    /**
     * Run {@code work} after the caller's transaction commits — or
     * immediately when there is no active transaction. Mirrors the
     * pattern in PurchaseService / AuditService / NotificationService /
     * PriceHistoryService (wave 23) — same docstring lineage as those.
     *
     * Why this matters for the RELIST path: `buyOrderService.tryMatch`
     * is itself `@Transactional` with default REQUIRED propagation, so
     * when called from inside relist's own @Transactional method it
     * JOINS the relist transaction. tryMatch then calls
     * `purchaseService.buy`, which writes to wallet / listing / trade /
     * transaction rows. If ANY of those repository.save() calls throws
     * (DB blip, optimistic-lock race, dialect quirk), Spring Data's
     * @Transactional save proxy marks the SHARED relist transaction
     * rollback-only. The wrapping try/catch in relist absorbs the
     * exception and the method appears to succeed — but on commit
     * Spring throws UnexpectedRollbackException and the relist's
     * RELISTED flip on the old row, the fresh ACTIVE listing INSERT,
     * AND every fan-out side-effect all silently roll back while the
     * caller's HTTP response says 200. Money-path catastrophe — the
     * seller sees their inventory unchanged, the new listing never
     * appears on the marketplace, and any followers / saved-search /
     * watchlist pushes about a listing that doesn't exist have already
     * fired. Exact same bug class as wave 60 (ebc1b45) on
     * TradeProtectionService.
     *
     * A simple `@Transactional(propagation = REQUIRES_NEW)` flip on
     * tryMatch won't work — purchaseService.buy reads the fresh listing
     * by id, and inside a REQUIRES_NEW tx the parent's uncommitted
     * INSERT is invisible (READ_COMMITTED isolation), so tryMatch
     * would see no candidate listing and silently no-op every time.
     *
     * Deferring tryMatch to afterCommit fixes both problems at once:
     * the relist transaction durably commits FIRST (the new listing
     * row is visible to anything that queries it), THEN tryMatch runs
     * in its own fresh REQUIRES_NEW tx and reads the now-committed
     * listing. A failure inside the deferred body can only roll back
     * the buy + buy-order-save (which is the correct scope — the
     * relist already landed), and it can never poison the relist tx
     * because the relist tx no longer exists.
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
            // fail the caller — matches the inline try/catch semantic the
            // original wrapper provided.
            try {
                work()
            } catch (Exception e) {
                log.warn("Best-effort write failed (no active tx): ${e.message}")
            }
        }
    }

    /**
     * Pre-flight: refuse a platform-inventory relist while bot-escrow is live.
     *
     * ── The gap this closes ───────────────────────────────────────────────
     * {@link #relist} hardcoded {@code status: 'ACTIVE'} and never asked for a
     * deposit. The first-list path
     * ({@code SteamInventoryController.listFromSteam}) does the opposite: it
     * creates the row PENDING_ESCROW and calls
     * {@code SteamEscrowService.requestDepositForListing} so the bot physically
     * holds the item before anyone can buy it. So with the bot live a RELISTED
     * item was buyable while the bot held nothing: the buyer pays, a Trade
     * opens, {@code SteamDeliveryService.resolveAssetId} finds no custody row,
     * records NO_ASSET_ID, and the sale stalls until the 3-day auto-cancel
     * refunds the buyer. The buyer's money is safe; the marketplace is
     * advertising an item it cannot deliver, which is the part that isn't.
     *
     * ── Why this refuses instead of routing through the escrow path ───────
     * The escrow path's one entry point needs a CURRENT, REAL, seller-owned
     * Steam asset id — {@code requestDepositForListing(listing, assetId, name)}
     * hands that id straight to the bot as "request exactly this asset from
     * this trade URL". A relist has no such id:
     *
     *   - Platform/house-bought items never had a Steam asset at all
     *     ({@code owned.assetId} is null) — there is nothing on Steam to
     *     collect, so no deposit can ever succeed.
     *   - For an item that DID come from Steam, {@code owned.assetId} is the
     *     ORIGINAL seller's id. Steam reassigns the asset id on every trade —
     *     this codebase already relies on that fact
     *     ({@code SteamEscrowService.fetchBotInventoryIndex} indexes by name
     *     precisely "so custody-confirm can match an asset even when Steam
     *     reassigns the asset id on receipt"). The stored id therefore names a
     *     copy this seller does not own.
     *
     * Passing either into the escrow path is strictly WORSE than the gap it
     * would be closing. relist flips the source row to RELISTED (gone from the
     * seller's inventory) before creating the fresh row; the fresh row would
     * sit PENDING_ESCROW against a deposit offer Steam can never fill, and 24h
     * later {@code sweepStalePendingDeposits} flips it PENDING_ESCROW →
     * CANCELLED — a terminal state that does NOT hand the item back the way
     * {@link #cancelListing} does. The seller's item would vanish from their
     * platform inventory permanently.
     *
     * Re-deriving a live asset id here (fetch the seller's Steam inventory,
     * match by market_hash_name, pick a copy) is the THIRD code path this
     * change is explicitly not allowed to invent — and it is guesswork besides,
     * because "which physical copy is the one you bought" is not a question the
     * platform can answer. The path that CAN answer it already exists and the
     * seller can already reach it: the Sell modal's Steam tab, where they pick
     * the concrete asset and {@code listFromSteam} escrows it properly.
     *
     * So: fail here, loud and early, with a sentence naming the action that
     * works. Same posture and same shape as
     * {@code SteamInventoryController.requireSellerTradeUrl} — refuse before
     * any row is written rather than return 200 for something that silently did
     * not happen.
     *
     * ── Escrow DISABLED is untouched ──────────────────────────────────────
     * Gated on {@code escrowEnabled}, which is false whenever the bot sidecar
     * is unconfigured (STEAM_BOT_BASE_URL unset — dev / test / CI, and
     * production as it stands today). On that path nothing here fires and
     * relist behaves exactly as before: straight to ACTIVE, seller hand-
     * delivers from their own Steam client. Listing from inventory is the
     * project's only live seller capability and this must not take it away.
     * The null-safe navigation also keeps the guard inert when the bean is
     * absent entirely (Spock property-map construction), matching every other
     * optional collaborator on this service.
     *
     * ── Why the message is SHORT ──────────────────────────────────────────
     * {@code GlobalExceptionHandler.genericMessage} replaces any domain
     * message longer than 140 characters with "Request could not be
     * completed" whenever {@code security.verbose-errors} is false — which is
     * the default, and production. The first draft of this message was 214
     * characters, so the seller would have been told nothing at all: visible,
     * but not actionable, which is the same defect in a different costume.
     * Keep it under 140 and keep the words "Steam tab" in it — both are pinned
     * by RelistEscrowGateSpec, the length via a round-trip through the real
     * handler rather than a bare character count.
     */
    private void assertRelistNotBypassingEscrow() {
        if (!(steamEscrowService?.escrowEnabled)) return
        throw new BadRequestException("RELIST_NEEDS_STEAM_DEPOSIT",
            "Our bot has to hold an item before it can sell, and a relist gives us no Steam " +
            "item to collect. List it from the Steam tab instead.")
    }

    @Transactional
    Listing relist(Long sellerUserId, String sellerName, Long ownedListingId, BigDecimal newPrice,
                   String listingType = 'BUY_NOW', Long durationHours = null, String description = null,
                   BigDecimal buyNowPrice = null, BigDecimal maxDiscount = null) {
        banGuard.assertNotBanned(sellerUserId)
        // Pre-flight, before ANY read or write: with the escrow bot live this
        // path cannot produce a deliverable listing. See the guard's docstring.
        assertRelistNotBypassingEscrow()
        sellerName = textSanitizer.cleanShort(sellerName)
        // HALF_UP-normalize the money inputs to whole cents up front so the
        // range checks + the buyNowPrice-vs-startingBid comparison below — and
        // the persisted listing price — all agree on the same 2dp value.
        // Mirrors the offer/deposit/withdraw/bid boundary-normalization pattern
        // (the DTO intentionally caps magnitude but not scale). maxDiscount is a
        // percent, not a money amount, so it is left untouched.
        if (newPrice != null)    newPrice    = newPrice.setScale(2, java.math.RoundingMode.HALF_UP)
        if (buyNowPrice != null) buyNowPrice = buyNowPrice.setScale(2, java.math.RoundingMode.HALF_UP)
        // Defensive: validation belongs in the DTO but we keep it here too for
        // callers that bypass the HTTP boundary (e.g. the offer-accept flow).
        if (newPrice == null || newPrice <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_PRICE", "Price must be greater than 0")
        }
        if (newPrice > new BigDecimal('100000')) {
            throw new BadRequestException("PRICE_TOO_HIGH", "Price must not exceed \$100,000")
        }
        // Whitelist listingType — null / unknown values fall back to BUY_NOW
        // so an older client that doesn't send the field still works.
        def resolvedType = (listingType in ['BUY_NOW', 'AUCTION']) ? listingType : 'BUY_NOW'
        if (resolvedType == 'AUCTION') {
            if (durationHours == null) {
                throw new BadRequestException("DURATION_REQUIRED",
                    "durationHours is required for AUCTION listings")
            }
            if (durationHours < 1L || durationHours > 168L) {
                throw new BadRequestException("INVALID_DURATION",
                    "durationHours must be between 1 and 168")
            }
        }
        // Auction Buy-Now ceiling (batch 371). Optional. Must exceed
        // the starting bid to be meaningful. Rejected on BUY_NOW
        // listings because the `price` field is already the Buy-Now
        // amount for those.
        if (buyNowPrice != null) {
            if (resolvedType != 'AUCTION') {
                throw new BadRequestException("BUY_NOW_ON_BUY_NOW",
                    "buyNowPrice only applies to AUCTION listings")
            }
            if (buyNowPrice <= newPrice) {
                throw new BadRequestException("INVALID_BUY_NOW",
                    "buyNowPrice must be greater than the starting bid (\$${newPrice})")
            }
            if (buyNowPrice > new BigDecimal('100000')) {
                throw new BadRequestException("BUY_NOW_TOO_HIGH",
                    "buyNowPrice must not exceed \$100,000")
            }
        }
        // Optional auto-accept threshold (batch 646). BigDecimal 0..1 —
        // 0.20 means "auto-accept any offer >= 80% of the ask". Invalid
        // values are rejected here so callers that bypass the controller
        // (e.g. integration tests) still get validated. BUY_NOW-only
        // semantically; we accept it on auctions too but the auction
        // flow never calls makeOffer so the threshold has no effect —
        // same policy the UI follows.
        //
        // Validated up-front alongside every other input check: this
        // range guard MUST run before the `owned.status = 'RELISTED'`
        // mutation below. Previously it sat after that flip, so a bad
        // maxDiscount only got caught once the source listing had
        // already been mutated — relying on @Transactional rollback to
        // undo it instead of rejecting the request before any write.
        BigDecimal resolvedMaxDiscount = null
        if (maxDiscount != null) {
            if (maxDiscount < BigDecimal.ZERO || maxDiscount >= BigDecimal.ONE) {
                throw new BadRequestException("INVALID_DISCOUNT",
                    "maxDiscount must be between 0 (no auto-accept) and 1 (100% off) exclusive")
            }
            resolvedMaxDiscount = maxDiscount.signum() == 0 ? null : maxDiscount
        }

        def owned = listingRepository.findById(ownedListingId)
                .orElseThrow { new NotFoundException("Listing", ownedListingId) }

        if (owned.buyerUserId == null || owned.buyerUserId != sellerUserId) {
            throw new ForbiddenException("You do not own this item")
        }
        if (owned.status != 'SOLD') {
            throw new BadRequestException("NOT_IN_INVENTORY", "Item is not in your inventory")
        }
        // Bought but not yet received. The purchase opened an escrow trade, and
        // until the buyer confirms it the item is still the seller's: the trade
        // can be cancelled, which refunds the buyer AND hands this very row
        // back to the seller. Relisting before that let one item be sold
        // twice -- the reseller kept the refund and the second buyer paid for
        // an item the reseller never had.
        def openPurchase = (tradeRepository?.findAllByListingId(ownedListingId) ?: []).find {
            it.buyerUserId == sellerUserId && !(it.state in ['VERIFIED', 'CANCELLED'])
        }
        if (openPurchase != null) {
            throw new BadRequestException("ITEM_IN_ESCROW",
                "You can sell this once you have received it. Confirm receipt of trade #${openPurchase.id} in Profile → Trades first.")
        }

        // Mark the old listing as RELISTED (removes it from inventory queries)
        owned.status = 'RELISTED'
        listingRepository.save(owned)

        // Optional seller note. Sanitised + 500-char capped — matches
        // the column size and the MyStall edit-form cap (batch 304).
        def cleanDesc = null
        if (description != null && description.trim()) {
            cleanDesc = textSanitizer.clean(description, 500)
        }
        // Create a new ACTIVE listing under this user
        def fresh = new Listing(
            item        : owned.item,
            price       : newPrice,
            sellerName  : sellerName,
            sellerAvatar: (sellerName ?: 'US').take(2).toUpperCase(),
            condition   : '',
            rarityScore : owned.rarityScore,
            // ACTIVE unconditionally, and that is now SAFE rather than an
            // oversight: assertRelistNotBypassingEscrow at the top of this
            // method means we only ever get here with the escrow bot OFF, where
            // ACTIVE-on-create is the correct legacy behaviour (the seller
            // hand-delivers). It must NOT become a conditional PENDING_ESCROW —
            // holding a listing without a deposit request is a hold nothing can
            // ever release, and the 24h timeout sweeper would then cancel it
            // WITHOUT returning the item to the seller's inventory.
            status      : 'ACTIVE',
            sellerUserId: sellerUserId,
            listingType : resolvedType,
            description : cleanDesc,
            buyNowPrice : resolvedType == 'AUCTION' ? buyNowPrice : null,
            maxDiscount : resolvedMaxDiscount
        )
        if (resolvedType == 'AUCTION') {
            fresh.expiresAt = System.currentTimeMillis() + (durationHours * 60L * 60L * 1000L)
        }
        def saved = listingRepository.save(fresh)
        log.info("User $sellerUserId relisted item ${owned.item.name} as ${resolvedType} listing ${saved.id} for \$${newPrice}")
        // A cheaper copy moves the item's floor now, not at the next sweep.
        // Registered before the buy-order match below, so if that match
        // buys this copy, its own refresh runs after and has the last word.
        final Long _floorItemId = owned.item?.id
        if (_floorItemId != null && listingFloorRefreshService != null) {
            deferOrRun { listingFloorRefreshService.refreshItem(_floorItemId) }
        }

        // Try to auto-fulfil any standing buy order that matches this fresh
        // listing — DEFERRED to afterCommit (wave 60 follow-up). Failures
        // here must never fail the parent transaction. A plain try/catch
        // around tryMatch is NOT enough: tryMatch is `@Transactional` and
        // joins the relist tx, so when its inner purchaseService.buy ->
        // wallet/listing/trade save throws and the catch swallows it,
        // Spring has already marked the SHARED tx rollback-only and the
        // relist commit blows up with UnexpectedRollbackException. The
        // committed RELISTED flip + ACTIVE INSERT silently roll back
        // while the caller's HTTP response says 200. Deferring the entire
        // tryMatch call to afterCommit means the relist tx durably
        // commits FIRST (the fresh listing is visible), then tryMatch
        // runs in its own REQUIRES_NEW tx and reads the now-committed
        // row — a buy() failure can only roll back its own inner tx, the
        // relist is unaffected. See `deferOrRun` docstring for the full
        // failure-mode walk-through.
        final Listing _savedForMatch = saved
        deferOrRun {
            try {
                buyOrderService.tryMatch(_savedForMatch)
            } catch (Exception e) {
                log.warn("Buy-order match failed for listing ${_savedForMatch.id}: ${e.message}")
            }
        }
        // Fan out NEW_LISTING_FROM_SELLER to every user following this
        // seller. Wrapped in try/catch so one bad subscription doesn't
        // roll back the relist. Best-effort notification delivery.
        try {
            sellerFollowService?.notifyFollowersOfNewListing(saved)
        } catch (Exception e) {
            log.warn("Follower fanout failed for listing ${saved.id}: ${e.message}")
        }
        // Saved-search match fanout — same isolation contract as above.
        try {
            savedSearchService?.notifyMatchingForListing(saved)
        } catch (Exception e) {
            log.warn("Saved-search fanout failed for listing ${saved.id}: ${e.message}")
        }
        // Synchronous watchlist-alert sweep (batch 389). The scheduled
        // sweeper runs every 5 min, which is fine for ambient listings but
        // feels sluggish for the user who has a $10 alert and the seller
        // just listed at $8. Scoped to this one item. It reads the item's
        // committed floor, so it is deferred like the floor refresh above
        // (registered after it, so it runs after it): inside the relist tx
        // it would only ever see the old floor and never fire.
        if (_floorItemId != null && watchlistAlertService != null) {
            deferOrRun { watchlistAlertService.sweepForItem(_floorItemId) }
        }
        saved
    }

    /**
     * Bulk-cancel every active listing the seller owns (batch 375).
     * Powers the MyStall "Cancel all active" shortcut — heavy sellers
     * quitting the platform / taking a long break shouldn't have to
     * click ✕ on each row. Per-row try/catch so one failure doesn't
     * abort the rest. Auctions with live bids are SKIPPED by default
     * (fanout noise to every bidder; the seller almost never wants
     * to blow up a running auction as collateral damage); caller
     * passes `includeAuctions=true` to override.
     *
     * Returns `{cancelled: N, skippedAuctions: M, failed: K}` so the
     * UI can toast a meaningful summary.
     */
    /**
     * Boss-QA cycle 33: outer @Transactional REMOVED. The previous
     * decoration was actively harmful — it wrapped every per-listing
     * cancelListing call in the SAME tx, so a single rollback-only
     * marker from a row that lost the buy-race (ListingNotAvailableException)
     * poisoned the WHOLE batch. The try/catch swallowed the per-row
     * exception but the @Transactional commit then threw
     * UnexpectedRollbackException, surfacing a 500 even though the
     * `{cancelled, skippedAuctions, failed}` summary was already populated.
     *
     * Each per-listing cancelListing now runs in its own REQUIRES_NEW
     * tx via runInIsolatedTx — one rollback can't poison siblings. The
     * outer banGuard.assertNotBanned is the only mutation-adjacent
     * operation outside the loop and it's a read-only guard (throws or
     * returns; doesn't write), so it doesn't need its own tx.
     */
    Map cancelAllActive(Long sellerUserId, boolean includeAuctions = false) {
        banGuard.assertNotBanned(sellerUserId)
        def active = listingRepository.findActiveBySeller(sellerUserId)
        int cancelled = 0, skippedAuctions = 0, failed = 0
        active.each { l ->
            if (!includeAuctions && l.listingType == 'AUCTION' && (l.bidCount ?: 0) > 0) {
                skippedAuctions++
                return
            }
            try {
                // Thread includeAuctions through as the per-listing `force`
                // flag. With includeAuctions=false the pre-filter above already
                // skipped bid-on auctions, so force=false changes nothing; with
                // includeAuctions=true the seller has explicitly opted to dump
                // their entire stall including live auctions, so force=true lets
                // cancelListing's new bid-lock through for exactly that case.
                runInIsolatedTx { cancelListing(sellerUserId, l.id, includeAuctions) }
                cancelled++
            } catch (Exception e) {
                failed++
                log.warn("Bulk-cancel: listing ${l.id} skipped (${e.message})")
            }
        }
        log.info("Bulk-cancel: user ${sellerUserId} · cancelled=${cancelled} skippedAuctions=${skippedAuctions} failed=${failed}")
        [cancelled: cancelled, skippedAuctions: skippedAuctions, failed: failed]
    }

    @Transactional
    void cancelListing(Long sellerUserId, Long listingId, boolean force = false) {
        def listing = listingRepository.findById(listingId)
                .orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.sellerUserId != sellerUserId) {
            throw new ForbiddenException("You can only cancel your own listings")
        }
        // csfloat parity: an auction LOCKS once it has its first bid. Letting a
        // seller pull a bid-on auction is a price-manipulation channel — bait
        // the price up with shills, then withdraw before settling — and it
        // strands every honest bidder. Only the admin force path
        // (AdminService.forceCancelListing, which doesn't route through here)
        // or an explicit bulk `includeAuctions` override (force=true, a
        // deliberate "nuke my whole stall" action) may cancel a bid-on
        // auction. A no-bid auction stays freely cancellable — nobody's
        // committed yet — exactly like csfloat. bidCount is the same
        // denormalised counter cancelAllActive's pre-filter trusts, kept in
        // lockstep by BidService on every placeBid/auto-raise.
        if (!force && listing.listingType == 'AUCTION' && (listing.bidCount ?: 0) > 0) {
            throw new ConflictException("AUCTION_HAS_BIDS",
                "This auction already has bids and can no longer be cancelled. It will settle when it ends.")
        }
        // PENDING_ESCROW listings (item being deposited into / held by the bot
        // but not yet activated) are cancellable too — the seller may pull the
        // listing before/while the item is in custody. Everything else
        // (SOLD / RELISTED / CANCELLED) is terminal for cancel.
        if (listing.status != 'ACTIVE' && listing.status != SteamEscrowService.STATUS_PENDING_ESCROW) {
            throw new ListingNotAvailableException(listingId)
        }
        // If there's an active trade in escrow for this listing, cancel it
        // and refund the buyer before we return the item to inventory.
        // Without this, a buyer's funds could be trapped in escrow forever
        // once the listing is removed from the marketplace.
        def openTrade = tradeRepository.findByListingId(listingId)
        if (openTrade != null && openTrade.state != 'VERIFIED' && openTrade.state != 'CANCELLED') {
            // REQUIRES_NEW sub-tx via runInIsolatedTx. tradeService.cancel is
            // @Transactional(REQUIRED) so without the wrapper it JOINS this
            // method's outer cancelListing tx. A failing save inside cancel
            // (versioned-Listing optimistic lock from a concurrent dispute,
            // wallet refund hiccup, transitionTo race) marks the SHARED tx
            // rollback-only BEFORE the throw reaches the catch below. The
            // catch swallows the throw and cancelListing continues to the
            // listing.status = 'SOLD' flip at the bottom — but at commit
            // time Spring throws UnexpectedRollbackException and EVERY
            // write in cancelListing (offer cancellations, bid cancellations,
            // listing flip, cart fanouts) silently rolls back while the
            // caller's HTTP response says 200. Buyer-money path catastrophe:
            // the trade is stuck in mid-cancel state, the listing stays
            // ACTIVE, and the cart-holders / bidders we just "notified"
            // still see live state on a listing the seller thinks they
            // cancelled. Same bug class as wave 60 (ebc1b45) on
            // TradeProtectionService and the relist tryMatch deferral.
            //
            // runInIsolatedTx spins a REQUIRES_NEW sub-tx so the inner
            // cancel commits or rolls back on its own — the outer cancel-
            // Listing tx is never poisoned. The fallback path (no tx
            // manager wired) runs inline so existing Spock unit specs
            // that build SellService via the property-map constructor
            // continue to exercise the trade-cancel branch.
            try {
                runInIsolatedTx {
                    tradeService.cancel(sellerUserId, openTrade.id, "Seller cancelled listing")
                }
            } catch (Exception e) {
                log.warn("Failed to auto-cancel trade {} on listing cancel: {}", openTrade.id, e.message)
            }
        }
        // Live offers on the cancelled listing — flip every still-live
        // offer to CANCELLED and notify each buyer. Without this, offer
        // rows orphan: the buyer sees a live offer on a listing that no
        // longer exists, and if the offer somehow got accepted later the
        // server's listing-status check would 400 with a confusing
        // "listing not active" error. Clean cleanup here, with a
        // courtesy notification so the buyer knows to pick a different
        // listing or place a buy order.
        //
        // "Live" is PENDING *or* COUNTERED — both are negotiation states
        // the buyer can still act on, and `findLiveByBuyerAndListing`
        // (which powers the ItemModal "You offered $X" chip) treats them
        // as one. A PENDING-only sweep left a COUNTERED buyer original
        // dangling: its child SELLER counter (PENDING, same listingId)
        // got cancelled but the COUNTERED parent never reached a terminal
        // state, so the buyer kept seeing a live offer on a deleted
        // listing. `findByListingId` returns every offer on the listing
        // in one indexed query, and we filter to the live pair here.
        if (offerRepository != null) {
            try {
                def live = offerRepository.findByListingId(listingId)
                    .findAll { it.status == 'PENDING' || it.status == 'COUNTERED' }
                def itemName = listing.item?.name ?: 'this item'
                def itemId = listing.item?.id
                live.each { o ->
                    o.status = 'CANCELLED'
                    o.updatedAt = System.currentTimeMillis()
                }
                if (!live.isEmpty()) offerRepository.saveAll(live)
                if (notificationService != null) {
                    // Dedup per buyer — a buyer with a COUNTERED original
                    // AND its PENDING child counter has two rows on this
                    // listing but should only get one "offer cancelled"
                    // ping for the single negotiation thread.
                    def notified = new HashSet<Long>()
                    live.each { o ->
                        if (o.buyerUserId == null || !notified.add(o.buyerUserId as Long)) return
                        try {
                            notificationService.push(o.buyerUserId, 'OFFER_REJECTED',
                                "Offer cancelled · ${itemName}",
                                "The seller removed the listing your offer was tied to.",
                                o.id,
                                itemId != null ? "/item/${itemId}" : '/offers')
                        } catch (Exception e) {
                            log.warn("Offer-cancelled push failed for buyer ${o.buyerUserId}: ${e.message}")
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Live-offer cleanup failed for listing ${listingId}: ${e.message}")
            }
        }

        // Auction with live bids — notify every bidder that their bid is
        // void and flip bid rows to CANCELLED so they stop counting in the
        // bidder's ProfileAutoBidsTab and "Active bids" UI. Funds aren't
        // held for bids (charging only happens on auction settle), so no
        // refund is needed — just a clear "auction was cancelled by the
        // seller" signal so bidders aren't left wondering.
        if (listing.listingType == 'AUCTION' && bidRepository != null) {
            try {
                def bids = bidRepository.findByListing(listingId)
                def uniqueBidders = new LinkedHashSet<Long>()
                bids.each { b ->
                    if (b.status == 'WINNING' || b.status == 'OUTBID') {
                        b.status = 'CANCELLED'
                    }
                    if (b.bidderUserId != null) uniqueBidders.add(b.bidderUserId as Long)
                }
                if (!bids.isEmpty()) bidRepository.saveAll(bids)
                if (notificationService != null) {
                    def itemName = listing.item?.name ?: 'an auction'
                    uniqueBidders.each { uid ->
                        try {
                            notificationService.push(uid, 'AUCTION_CANCELLED',
                                "Auction cancelled · ${itemName}",
                                'The seller withdrew this auction before it settled. No charge was made.',
                                listing.id,
                                listing.item?.id != null ? "/item/${listing.item.id}" : null)
                        } catch (Exception e) {
                            log.warn("AUCTION_CANCELLED push failed for uid=${uid}: ${e.message}")
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Auction cancel fan-out failed for listing {}: {}", listingId, e.message)
            }
        }
        // Cart-holder fan-out (batch 504). Mirrors the cart-item-sold
        // path in PurchaseService.buy (batch 503): when this listing
        // disappears from the marketplace, every OTHER user with it
        // queued in their cart sees a stale-grey row next time they
        // open the cart unless we ping them now. Same CART_ITEM_SOLD
        // kind so existing UI wiring covers it; body says "removed by
        // seller" instead of "bought by another user". Capped at 50
        // for fan-out cost. Bulk-delete the row from every cart at
        // the end so the next /api/cart fetch is clean.
        if (cartItemRepository != null && notificationService != null) {
            try {
                def others = cartItemRepository.findOtherUsersWithListing(listingId, sellerUserId) ?: []
                if (!others.isEmpty()) {
                    def itemName = listing.item?.name ?: 'an item'
                    def itemId = listing.item?.id
                    others.take(50).each { uid ->
                        try {
                            notificationService.push(uid, 'CART_ITEM_SOLD',
                                "Cart item removed · ${itemName}",
                                "${itemName} was withdrawn by the seller. Other listings may still be available — find a similar one in the marketplace.",
                                listingId,
                                itemId != null ? "/item/${itemId}" : '/cart')
                        } catch (Exception e) {
                            log.warn("CART_ITEM_SOLD (seller-cancel) push failed for uid=${uid}: ${e.message}")
                        }
                    }
                }
                try {
                    cartItemRepository.deleteAllByListing(listingId)
                } catch (Exception e) {
                    log.warn("CART scrub failed for listing=${listingId} on seller-cancel: ${e.message}")
                }
            } catch (Exception e) {
                log.warn("CART fan-out failed for listing=${listingId} on seller-cancel: ${e.message}")
            }
        }

        // Bot-escrow RETURN leg. If the bot is holding this listing's real
        // Steam asset (IN_CUSTODY), send it back to the seller and flip custody
        // → RETURNED. No-op when the bot is unconfigured or the item was never
        // deposited. Isolated REQUIRES_NEW sub-tx + try/catch so a return
        // hiccup can't poison this cancel tx (same rationale as the trade-
        // cancel block above). The seller's in-platform inventory row below is
        // still created — the RETURNED Steam offer is the real-item movement.
        //
        // Gate the WHOLE block on escrowEnabled so the disabled/legacy path
        // (bot unconfigured — dev / test / CI, or the escrowService bean
        // absent) never even opens the REQUIRES_NEW sub-tx. returnToSeller
        // already self-gates to a no-op when escrow is off, but wrapping a
        // no-op in runInIsolatedTx would still spin (and commit) an empty
        // transaction on the cancel path — pointless work, and it perturbs
        // the cancel-tx-isolation contract the existing specs pin.
        if (steamEscrowService?.escrowEnabled) {
            try {
                runInIsolatedTx {
                    steamEscrowService.returnToSeller(listingId, "Seller cancelled listing")
                }
            } catch (Exception e) {
                log.warn("Escrow return-to-seller failed for listing ${listingId}: ${e.message}")
            }
        }

        // Return the item to inventory. The ListingRepository.findOwnedBy
        // query orders by `soldAt DESC`, so stamping the cancellation time
        // here ensures the returned item shows up at the top of the
        // user's inventory view instead of falling to the bottom of the
        // null-soldAt bucket.
        listing.status = 'SOLD'
        listing.buyerUserId = sellerUserId
        listing.soldAt = System.currentTimeMillis()
        listingRepository.save(listing)
        final Long _floorItemId = listing.item?.id
        if (_floorItemId != null && listingFloorRefreshService != null) {
            deferOrRun { listingFloorRefreshService.refreshItem(_floorItemId) }
        }
    }
}
