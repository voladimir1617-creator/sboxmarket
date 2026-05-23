package com.sboxmarket.service

import com.sboxmarket.event.AuctionBidPlacedEvent
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Bid
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Transaction
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Auction bidding + auto-bid bot + scheduled expiry sweep. The bot works exactly
 * like CSFloat's: each bidder sets a "maximum they'd pay"; the service only pushes
 * the *current* price up to one minimum-increment above the next-highest maximum.
 */
@Service
@Slf4j
class BidService {

    // Minimum bid increment in dollars — keeps a cheap floor so bid wars don't spin.
    private static final BigDecimal INCREMENT = new BigDecimal("0.05")

    /** Anti-snipe window. If a bid lands within this many ms of the auction
     *  close, push `expiresAt` out so the close is always a fair contest,
     *  not a "who can curl faster" latency race. CSFloat / eBay call this a
     *  "soft close". Default 30s, nudges auctions that would have closed
     *  in <30s to close at `now + SNIPE_EXTEND`. */
    private static final long SNIPE_WINDOW_MS = 30_000L
    private static final long SNIPE_EXTEND_MS = 30_000L

    @Autowired ListingRepository listingRepository
    @Autowired BidRepository bidRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired NotificationService notificationService
    @Autowired(required = false) EmailService emailService
    @Autowired(required = false) TradeService tradeService
    @Autowired(required = false) com.sboxmarket.repository.WatchlistAlertRepository watchlistAlertRepository
    @Autowired(required = false) PriceHistoryService priceHistoryService
    @Autowired(required = false) com.sboxmarket.repository.ItemRepository itemRepository
    @Autowired BanGuard banGuard
    @Autowired TextSanitizer textSanitizer
    /**
     * Spring event publisher — drives AuctionEventBus SSE fan-out after
     * the transaction commits. `required = false` so Spock specs that
     * construct this service via property map don't need to mock it.
     */
    @Autowired(required = false) ApplicationEventPublisher applicationEventPublisher

    /**
     * Fire an AuctionBidPlacedEvent for the current listing state. Call
     * this after every listing-state mutation in placeBid / buyNowAuction
     * that clients should see in real time. The AuctionEventBus listener
     * is wired for phase=AFTER_COMMIT, so this is safe to call mid-
     * transaction — the event only fans out once the DB write durably
     * lands.
     */
    private void publishBidEvent(Listing listing, String kind) {
        if (applicationEventPublisher == null || listing == null || listing.id == null) return
        try {
            applicationEventPublisher.publishEvent(new AuctionBidPlacedEvent(
                listing.id,
                kind,
                listing.currentBid,
                listing.currentBidderId,
                listing.currentBidderName,
                listing.bidCount,
                listing.expiresAt,
                listing.status
            ))
        } catch (Exception e) {
            log.warn("Auction bid event publish failed for listing ${listing.id}: ${e.message}")
        }
    }

    @Transactional
    Bid placeBid(Long bidderUserId, String bidderName, Long listingId,
                 BigDecimal amount, BigDecimal maxAmount = null) {
        banGuard.assertNotBanned(bidderUserId)
        bidderName = textSanitizer.cleanShort(bidderName)
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_BID", "Bid amount must be positive")
        }
        // Auto-bid ceiling sanity. `PlaceBidRequest` bounds maxAmount above
        // ($100k) but not below — a negative or sub-`amount` cap is
        // contradictory (an auto-raise ceiling beneath your own bid can
        // never fire) and would otherwise persist as confusing dead data
        // on the Bid row. A cap that simply equals `amount` is fine — it's
        // just a plain manual bid, handled by the `> amount` AUTO test
        // below — so only reject a cap STRICTLY below the bid.
        if (maxAmount != null && maxAmount < amount) {
            throw new BadRequestException("INVALID_MAX_BID",
                "Auto-bid cap must be at least your bid amount")
        }
        def listing = listingRepository.findById(listingId)
            .orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.status != 'ACTIVE') {
            throw new BadRequestException("NOT_ACTIVE", "Listing is not active")
        }
        // Hidden auctions are invisible to the grid but the listing id
        // is stable. Reject bids on hidden rows so a cached client or a
        // crafted /api/bids POST can't inflate an auction the seller
        // has pulled off-market. Mirrors the same guard added to
        // PurchaseService.buy + OfferService.makeOffer in batch 308.
        if (Boolean.TRUE.equals(listing.hidden)) {
            throw new BadRequestException("NOT_ACTIVE", "Listing is not active")
        }
        if (listing.listingType != 'AUCTION') {
            throw new BadRequestException("NOT_AUCTION", "Listing is not an auction")
        }
        if (listing.expiresAt != null && System.currentTimeMillis() > listing.expiresAt) {
            throw new BadRequestException("EXPIRED", "Auction has ended")
        }
        if (listing.sellerUserId != null && listing.sellerUserId == bidderUserId) {
            throw new ForbiddenException("Cannot bid on your own auction")
        }
        // Same buyer-trade-URL gate as PurchaseService + OfferService — when
        // an auction settles the winner's bid is auto-debited via the usual
        // P2P escrow flow; the seller needs the winner's Steam trade URL to
        // send the item. Enforce at bid time so the bidder fixes profile
        // now rather than winning an auction they can't receive. System
        // auctions (no sellerUserId) skip this — they resolve in-platform.
        if (listing.sellerUserId != null) {
            def traderOpt = steamUserRepository?.findById(bidderUserId)
            def trader = traderOpt != null ? traderOpt.orElse(null) : null
            if (trader != null && !trader.tradeUrl?.trim()) {
                throw new BadRequestException("TRADE_URL_MISSING",
                    "Set your Steam trade URL in Profile before bidding — the seller needs it to send the item if you win.")
            }
        }
        // Solvency check at bid time — close a grief loophole where a user
        // with $0 balance could spam high bids to scare real buyers away,
        // then lose the auction silently at settle-time. We don't lock the
        // funds (that would block cross-auction bidding), just verify the
        // bidder CURRENTLY has enough to honor the bid (or their auto-bid
        // ceiling if one is set). Settle-time still does its own balance
        // check as a belt-and-braces second pass.
        def bidderOpt = steamUserRepository.findById(bidderUserId)
        def bidderUser = (bidderOpt != null) ? bidderOpt.orElse(null) : null
        if (bidderUser != null && bidderUser.steamId64 != null) {
            def bidderWallet = walletRepository.findByUsername("steam_${bidderUser.steamId64}")
            if (bidderWallet != null) {
                // Wallet freeze gate (batch 510). Rejects at bid time so
                // a frozen bidder doesn't win an auction they can't pay
                // for — settle would fail on the WALLET_FROZEN check in
                // PurchaseService.buy, orphaning the auction with no winner
                // and wasting the seller's time.
                if (Boolean.TRUE.equals(bidderWallet.frozen)) {
                    throw new BadRequestException("WALLET_FROZEN",
                        "Your wallet is frozen by staff" +
                            (bidderWallet.frozenReason ? ": ${bidderWallet.frozenReason}" : '') +
                            ". Open a support ticket to resolve.")
                }
                // Dispute-hold fail-early (batch 511). Same rationale as
                // the wallet freeze check — an auction win that fails at
                // settle-time orphans the auction. Refuse the bid
                // upfront so the bidder knows to resolve the dispute
                // first.
                if (transactionRepository != null) {
                    long disputed = transactionRepository.countActiveDisputedDeposits(bidderWallet.id)
                    if (disputed > 0L) {
                        throw new BadRequestException("PURCHASE_DISPUTE_HOLD",
                            "Bidding is paused while you have ${disputed} unresolved deposit " +
                            "dispute${disputed == 1 ? '' : 's'} on file.")
                    }
                }
                def requiredBalance = (maxAmount != null && maxAmount > amount) ? maxAmount : amount
                if (bidderWallet.balance == null || bidderWallet.balance < requiredBalance) {
                    throw new BadRequestException("INSUFFICIENT_BALANCE",
                        "You need at least \$${requiredBalance.toPlainString()} in your wallet to place this bid — " +
                        "deposit first. Your current balance: \$${(bidderWallet.balance ?: BigDecimal.ZERO).toPlainString()}")
                }
            }
        }
        // First bid floor = listing.price (matches starting price exactly).
        // Every subsequent bid must clear the current top bid by at least
        // one INCREMENT ($0.05) — without that, a new bid equal to the
        // current top silently displaces the real high bidder, since
        // `placeBid` overwrites `currentBidderId` on every save.
        BigDecimal minRequired
        if (listing.currentBid == null) {
            minRequired = listing.price
            if (amount < minRequired) {
                throw new BadRequestException("BID_TOO_LOW",
                    "Bid must be at least \$${minRequired.toPlainString()}")
            }
        } else {
            minRequired = listing.currentBid + INCREMENT
            if (amount < minRequired) {
                throw new BadRequestException("BID_TOO_LOW",
                    "Bid must be at least \$${minRequired.toPlainString()} " +
                    "(current bid + \$${INCREMENT.toPlainString()} increment)")
            }
        }

        def kind = (maxAmount != null && maxAmount > amount) ? 'AUTO' : 'MANUAL'

        // Outbid the previous top bidder (if any). Look up their
        // most-recent bid on this listing so we can decide whether an
        // auto-bid bot re-raise should fire before we write out the
        // outbid notification.
        def previousTopId = listing.currentBidderId
        def previousAmount = listing.currentBid
        Bid previousTopBid = null
        if (previousTopId != null && previousTopId != bidderUserId) {
            previousTopBid = bidRepository.findByListing(listingId)
                .find { it.bidderUserId == previousTopId }
        }
        // Tracks the row that should read WINNING when control reaches
        // the final fall-through return. Starts as the bid we are about
        // to save; Branch B (the new bidder's own auto-raise) reassigns
        // it to the higher bot-placed row so only the newest WINNING row
        // survives the OUTBID demotion below.
        Bid winningRow = null

        def bid = new Bid(
            listingId:     listingId,
            bidderUserId:  bidderUserId,
            bidderName:    bidderName,
            amount:        amount,
            maxAmount:     maxAmount,
            kind:          kind,
            status:        'WINNING'
        )
        bidRepository.save(bid)
        winningRow = bid

        listing.currentBid        = amount
        listing.currentBidderId   = bidderUserId
        listing.currentBidderName = bidderName
        listing.bidCount          = (listing.bidCount ?: 0) + 1
        // Soft close — anti-sniping. A bid that lands inside the final
        // SNIPE_WINDOW_MS window extends the auction by SNIPE_EXTEND_MS so
        // every competing bidder gets a fair chance to react. Without this
        // the auction collapsed into a latency race in the final seconds,
        // a known failure mode on eBay-style marketplaces. We never shorten
        // the auction, only extend it.
        //
        // `timeLeft >= 0` (not `> 0`) — covers the boundary race where a
        // bid lands AT `expiresAt` exactly. The expiry guard above uses
        // `now > expiresAt`, so a bid at `now == expiresAt` is accepted;
        // without `>= 0` here the auction would not extend and the next
        // sweeper tick would close it immediately, defeating the
        // anti-sniping intent for the exact-boundary case.
        if (listing.expiresAt != null) {
            def now = System.currentTimeMillis()
            def timeLeft = listing.expiresAt - now
            if (timeLeft >= 0 && timeLeft <= SNIPE_WINDOW_MS) {
                def newExpiresAt = now + SNIPE_EXTEND_MS
                if (newExpiresAt > listing.expiresAt) {
                    listing.expiresAt = newExpiresAt
                    log.info("Auction ${listingId} soft-closed — extended to +${SNIPE_EXTEND_MS}ms by bid from ${bidderUserId}")
                }
            }
        }
        listingRepository.save(listing)

        if (previousTopId != null && previousTopId != bidderUserId) {
            // Auto-bid bot resolution. eBay-style: each bidder has an
            // "effective max" (maxAmount if AUTO, otherwise their amount).
            // The winner is whoever has the higher max; the winning price
            // is one increment above the loser's max. Handles all four
            // cases in one pass:
            //   A AUTO, B AUTO  — higher cap wins at loser_cap + INC
            //   A AUTO, B MANUAL — if A_cap > B_amount, A bot-raises to
            //                      B_amount + INC and B is outbid
            //   A MANUAL, B AUTO — if B_cap > A_amount (which it must be
            //                      since amount >= A_amount + INC), B
            //                      auto-raises to A_amount + INC (no-op,
            //                      already landed above the floor)
            //   A MANUAL, B MANUAL — no bot fires, B wins at amount
            def aMax = (previousTopBid?.kind == 'AUTO' && previousTopBid?.maxAmount != null)
                ? previousTopBid.maxAmount
                : (previousAmount ?: BigDecimal.ZERO)
            def bMax = (maxAmount != null && maxAmount > amount) ? maxAmount : amount

            // `>=` not `>`: on an exact max-cap tie the EARLIER bidder (A,
            // the standing top bid) keeps the lead. eBay / CSFloat
            // convention — whoever set the max first wins a dead heat,
            // so a latecomer can't snipe a tie by bidding the same cap a
            // moment later. With `>`, neither branch fired on a tie and
            // B silently kept the listing at their submitted `amount`.
            if (aMax >= bMax) {
                // Previous top wins via bot re-raise. Settle at one increment
                // above B's cap, capped at A's own max. On a tie this resolves
                // to `aMax` itself (raised would exceed aMax and gets clamped).
                def raised = (bMax + INCREMENT)
                if (raised > aMax) raised = aMax
                // Solvency re-check for the bot re-raise (batch 330). The
                // bid-time solvency check covered A's original bid, but A's
                // wallet can drop between their first AUTO bid and B's
                // outbid (e.g. A bought something else). If A can't cover
                // the raised amount right now, skip the bot re-raise — B
                // wins at their submitted amount. Without this guard the
                // auction re-settles at A's raised price and collapses at
                // settle-time (the winner can't pay → item returns to the
                // seller and B, who COULD pay, loses too).
                if (previousTopId != null) {
                    def prevOpt = steamUserRepository.findById(previousTopId)
                    def prevUser = (prevOpt != null) ? prevOpt.orElse(null) : null
                    def prevWallet = (prevUser?.steamId64 != null)
                        ? walletRepository.findByUsername("steam_${prevUser.steamId64}")
                        : null
                    if (prevWallet != null && (prevWallet.balance == null || prevWallet.balance < raised)) {
                        log.info("Auto-bid skipped for ${previousTopId} on listing ${listingId}: balance ${prevWallet?.balance} < raise \$${raised}")
                        try {
                            notificationService.push(previousTopId, 'AUCTION_OUTBID',
                                "Auto-bid skipped — balance too low",
                                "You were outbid on ${listing.item?.name ?: 'an auction'} and your auto-bid cap (\$${(previousTopBid?.maxAmount ?: BigDecimal.ZERO).toPlainString()}) couldn't fire because your wallet balance dropped below the next required bid (\$${raised.toPlainString()}). Top up to keep bidding.",
                                listingId,
                                listing.item?.id != null ? "/item/${listing.item.id}" : null)
                        } catch (Exception e) {
                            log.warn("Auto-bid skip notification failed for ${previousTopId}: ${e.message}")
                        }
                        // Bot re-raise skipped — B's manual bid stands as
                        // the winner. Demote the prior leader (and any
                        // other still-live rows) to OUTBID so only B's
                        // new bid reads WINNING.
                        markOthersOutbid(listingId, bid)
                        publishBidEvent(listing, 'bid')
                        return bid
                    }
                }
                def botBid = new Bid(
                    listingId:     listingId,
                    bidderUserId:  previousTopId,
                    bidderName:    previousTopBid.bidderName,
                    amount:        raised,
                    maxAmount:     previousTopBid.maxAmount,
                    kind:          'AUTO',
                    status:        'WINNING'
                )
                bidRepository.save(botBid)
                listing.currentBid        = raised
                listing.currentBidderId   = previousTopId
                listing.currentBidderName = previousTopBid.bidderName
                listing.bidCount          = (listing.bidCount ?: 0) + 1
                listingRepository.save(listing)
                try {
                    notificationService.push(
                        bidderUserId,
                        'AUCTION_OUTBID',
                        "Outbid on ${listing.item?.name} by auto-bid",
                        "The previous top bidder's auto-cap out-raised you to \$${raised.toPlainString()}.",
                        listingId,
                        listing.item?.id != null ? "/item/${listing.item.id}" : null
                    )
                } catch (Exception e) {
                    log.warn("Auto-bid outbid push failed for user ${bidderUserId}: ${e.message}")
                }
                log.info("Auto-bid: ${previousTopId} raised to \$${raised} (cap \$${previousTopBid.maxAmount}) on listing ${listingId}")
                // The bot's re-raise row is the new top bid. Demote every
                // other still-live row — B's just-placed bid AND the
                // previous top's own older WINNING row — to OUTBID so the
                // re-raise is the sole WINNING bid.
                markOthersOutbid(listingId, botBid)
                publishBidEvent(listing, 'bid')
                return botBid
            } else if (bMax > aMax && bMax > amount) {
                // New bidder's auto-cap beats the previous top's max. Bot
                // raises B on their own behalf to (aMax + INC) capped at bMax.
                // Only fires when B actually needs a raise beyond their
                // submitted amount — if B placed >= aMax already, their
                // original bid stands and we fall through to the plain
                // outbid notification below.
                def raised = (aMax + INCREMENT)
                if (raised > bMax) raised = bMax
                if (raised > amount) {
                    // Save a second bid from B at the higher level so
                    // the history reflects the resolved win price.
                    def botBid = new Bid(
                        listingId:     listingId,
                        bidderUserId:  bidderUserId,
                        bidderName:    bidderName,
                        amount:        raised,
                        maxAmount:     maxAmount,
                        kind:          'AUTO',
                        status:        'WINNING'
                    )
                    bidRepository.save(botBid)
                    // B's own auto-raise row is now the live top bid —
                    // B's original lower row must be demoted alongside
                    // every rival row when we reach the fall-through.
                    winningRow = botBid
                    listing.currentBid        = raised
                    listing.bidCount          = (listing.bidCount ?: 0) + 1
                    listingRepository.save(listing)
                    log.info("Auto-bid: new bidder ${bidderUserId} raised to \$${raised} (cap \$${bMax}) on listing ${listingId}")
                }
                // A still gets the normal outbid ping — they lost cleanly.
            }
            notificationService.push(
                previousTopId,
                'AUCTION_OUTBID',
                "You were outbid on ${listing.item?.name}",
                "New top bid: \$${amount.toPlainString()}",
                listingId,
                listing.item?.id != null ? "/item/${listing.item.id}" : null
            )
            // Email the displaced bidder — the notification bell may go
            // unchecked for hours; by the time they see it the auction
            // might be over. Gated on emailVerified, silent-fail.
            try {
                def prev = steamUserRepository.findById(previousTopId).orElse(null)
                if (emailService != null && emailService.canSendTo(prev, 'AUCTIONS')) {
                    def itemUrl = listing.item?.id != null
                        ? "/item/${listing.item.id}".toString()
                        : null
                    emailService.sendAuctionOutbid(prev.email, prev.displayName,
                        listing.item?.name, amount, itemUrl)
                }
            } catch (Exception e) {
                log.warn("Outbid email failed for user ${previousTopId}: ${e.message}")
            }
        }

        // Demote every other still-live bid on the listing to OUTBID so
        // only `winningRow` reads WINNING. Covers all paths that reach
        // here: a plain manual outbid (the prior leader's row flips), the
        // new bidder's own auto-raise (their older row flips), and a
        // self-raise where the bidder already led the auction (their
        // previous WINNING rows flip). Previously nothing demoted prior
        // rows, so OUTBID was never written and history showed every
        // bidder as WINNING forever.
        markOthersOutbid(listingId, winningRow)
        publishBidEvent(listing, 'bid')
        // Return `winningRow`, not the bare `bid`. When Branch B fired —
        // the new bidder's own auto-cap out-raised the prior top, so the
        // bot saved a SECOND higher row on their behalf — `winningRow` is
        // that bot row and `bid` (their original submitted row) was just
        // demoted to OUTBID by `markOthersOutbid` above. Returning `bid`
        // there handed the caller a row reading `status: OUTBID` even
        // though the bidder is in fact winning, so the API told a winning
        // auto-bidder "you were outbid". `winningRow` is always the row
        // that actually reads WINNING (it equals `bid` on every other
        // path), mirroring Branch A which already returns its bot row.
        winningRow
    }

    /**
     * Public bid history for a listing. Same anti-enumeration pattern as
     * OfferService.thread — if the viewer is the listing seller or the
     * bidder themselves they see every field, anyone else gets a redacted
     * copy with bidderUserId nulled and bidderName rewritten to
     * "Bidder #1", "Bidder #2", … so a third party cannot walk bid
     * history to build up a trading profile of other users. The original
     * Hibernate-managed entities are never mutated — we return fresh
     * detached copies.
     */
    List<Bid> historyFor(Long listingId, Long viewerUserId = null) {
        def all = bidRepository.findByListing(listingId)
        if (all.isEmpty()) return all

        def listing = listingRepository.findById(listingId).orElse(null)
        def isSeller = listing != null && listing.sellerUserId != null && listing.sellerUserId == viewerUserId
        def isBidder = viewerUserId != null && all.any { it.bidderUserId == viewerUserId }
        if (isSeller || isBidder) return all

        def handles = [:]
        int next = 0
        all.collect { b ->
            def handle = handles[b.bidderUserId]
            if (handle == null) {
                handle = "Bidder #${++next}".toString()
                handles[b.bidderUserId] = handle
            }
            new Bid(
                id:           b.id,
                listingId:    b.listingId,
                bidderUserId: null,
                bidderName:   handle,
                amount:       b.amount,
                maxAmount:    null,
                kind:         b.kind,
                status:       b.status,
                createdAt:    b.createdAt
            )
        }
    }

    List<Bid> autoBidsForUser(Long userId) {
        bidRepository.findActiveAutoBidsForUser(userId)
    }

    /** All live (WINNING + OUTBID) bids for a user — drives the "Active Bids"
     *  profile tab so users can see every auction they're still in without
     *  having to remember each listing id. Enriches each row with itemId +
     *  itemName so the UI can render "Wizard Hat" with a deep link instead
     *  of a bare "Listing #N". Bulk-fetches the listings in one query to
     *  avoid N+1 when the user has many active bids. */
    List<Bid> liveBidsForUser(Long userId) {
        def bids = bidRepository.findLiveBidsForUser(userId)
        decorateWithListing(bids)
    }

    /** Past bids — WON, LOST, CANCELLED. Drives Profile → Bids → Past
     *  sub-tab (batch 361). Capped at 100 most-recent so a power bidder
     *  doesn't ship thousands of rows on every tab open. Enriched with
     *  the same item fields as the live list so the UI is symmetric. */
    List<Bid> pastBidsForUser(Long userId) {
        def bids = bidRepository.findPastBidsForUser(userId,
            org.springframework.data.domain.PageRequest.of(0, 100))
        decorateWithListing(bids)
    }

    /** Shared listing enrichment — bulk-fetches listing rows so the UI
     *  can render `itemName` + current state without an N+1 per bid. */
    private List<Bid> decorateWithListing(List<Bid> bids) {
        if (bids == null || bids.isEmpty()) return bids
        def listingIds = bids*.listingId.unique()
        def listingsById = listingRepository.findAllById(listingIds)
            .collectEntries { [(it.id): it] }
        bids.each { b ->
            def l = listingsById[b.listingId]
            if (l != null) {
                b.itemId            = l.item?.id
                b.itemName          = l.item?.name
                b.listingExpiresAt  = l.expiresAt
                b.listingCurrentBid = l.currentBid
            }
        }
        bids
    }

    /**
     * Auction Buy-Now (batch 371). Closes the auction immediately at
     * `listing.buyNowPrice` and awards it to the caller. Reuses the
     * existing `settle()` path so wallet charge + SOLD transition +
     * trade-escrow open + losing-bidder notifications all go through
     * the same machinery as a timer-driven auction close.
     *
     * Existing bidders get flipped to LOST with a dedicated
     * AUCTION_LOST push explaining the buyer hit Buy Now (not an
     * outbid) so the UX makes sense.
     */
    @Transactional
    Listing buyNowAuction(Long buyerUserId, String buyerName, Long listingId) {
        banGuard.assertNotBanned(buyerUserId)
        buyerName = textSanitizer.cleanShort(buyerName)
        def listing = listingRepository.findById(listingId)
            .orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.status != 'ACTIVE') {
            throw new BadRequestException("NOT_ACTIVE", "Listing is not active")
        }
        if (Boolean.TRUE.equals(listing.hidden)) {
            throw new BadRequestException("NOT_ACTIVE", "Listing is not active")
        }
        if (listing.listingType != 'AUCTION') {
            throw new BadRequestException("NOT_AUCTION", "Listing is not an auction")
        }
        if (listing.buyNowPrice == null || listing.buyNowPrice <= BigDecimal.ZERO) {
            throw new BadRequestException("NO_BUY_NOW",
                "This auction doesn't have a Buy Now price set")
        }
        // Buy-Now ceiling can be overtaken by live bidding. SellService
        // enforces `buyNowPrice > startingPrice` at creation, but a bid
        // war can push `currentBid` to or past `buyNowPrice` over the
        // auction's life. Honouring a Buy-Now at that point would settle
        // BELOW the standing top bid — robbing the seller and the honest
        // high bidder. CSFloat hides the Buy-Now button once bids reach
        // it; we reject server-side as defence-in-depth. The buyer should
        // just outbid normally.
        if (listing.currentBid != null && listing.currentBid >= listing.buyNowPrice) {
            throw new BadRequestException("BUY_NOW_UNAVAILABLE",
                "Bidding has reached the Buy Now price — place a higher bid instead.")
        }
        if (listing.expiresAt != null && System.currentTimeMillis() > listing.expiresAt) {
            throw new BadRequestException("EXPIRED", "Auction has already ended")
        }
        if (listing.sellerUserId != null && listing.sellerUserId == buyerUserId) {
            throw new ForbiddenException("You can't Buy Now your own auction")
        }
        // Wallet solvency — settle() runs its own check too, but a clean
        // 409 here beats the generic "auction returned to seller" settle
        // fallback when the buyer simply doesn't have the money.
        def buyerUser = steamUserRepository.findById(buyerUserId).orElse(null)
        if (buyerUser != null && buyerUser.steamId64 != null) {
            def buyerWallet = walletRepository.findByUsername("steam_${buyerUser.steamId64}")
            if (buyerWallet == null || buyerWallet.balance == null ||
                    buyerWallet.balance < listing.buyNowPrice) {
                throw new BadRequestException("INSUFFICIENT_BALANCE",
                    "You need \$${listing.buyNowPrice.toPlainString()} in your wallet for Buy Now. " +
                    "Current balance: \$${(buyerWallet?.balance ?: BigDecimal.ZERO).toPlainString()}")
            }
        }

        // Snapshot existing bidders so we can notify them post-settle.
        def existingBids = bidRepository.findByListing(listingId)
        def losingBidders = new LinkedHashSet<Long>()
        existingBids.each { b ->
            if (b.bidderUserId != null && b.bidderUserId != buyerUserId &&
                    b.status in ['WINNING', 'OUTBID']) {
                losingBidders.add(b.bidderUserId as Long)
            }
        }
        // Flip every *losing* bidder's live bid to LOST — buy-now closes
        // the auction so nobody else wins. The buyer's OWN prior bids
        // (unlikely path, but possible if they bid earlier AND then used
        // Buy Now) are deliberately left untouched: settle()'s happy path
        // resolves the buyer's highest non-terminal row to WON, the rest
        // to OUTBID. Flipping them to LOST here would brand the buyer's
        // history as a loss on an auction they actually won.
        def buyNowRowsToSave = existingBids.findAll {
            it.bidderUserId != buyerUserId && it.status in ['WINNING', 'OUTBID']
        }
        buyNowRowsToSave.each { it.status = 'LOST' }
        if (!buyNowRowsToSave.isEmpty()) bidRepository.saveAll(buyNowRowsToSave)

        // Override the listing so settle() awards it to the buyer at
        // buyNowPrice with immediate expiry.
        listing.currentBid        = listing.buyNowPrice
        listing.currentBidderId   = buyerUserId
        listing.currentBidderName = buyerName ?: ("Buyer_" + buyerUserId)
        listing.expiresAt         = System.currentTimeMillis()

        settle(listing)

        // Notify losing bidders — distinct AUCTION_LOST body so they
        // know it was Buy Now, not being outbid by a higher manual bid.
        losingBidders.each { uid ->
            try {
                notificationService?.push(uid, 'AUCTION_LOST',
                    "Auction ended · ${listing.item?.name ?: 'item'}",
                    "${listing.currentBidderName} used Buy Now at \$${listing.buyNowPrice.toPlainString()}.",
                    listingId,
                    listing.item?.id != null ? "/item/${listing.item.id}" : null)
            } catch (Exception e) {
                log.warn("AUCTION_LOST (buy-now) push failed for user ${uid}: ${e.message}")
            }
        }
        publishBidEvent(listing, 'buy-now')
        listing
    }

    /**
     * Cancel the auto-raise on a single bid. We DON'T retract the bid
     * itself — the user's current winning bid stands, we just null out
     * the ceiling so the auto-bid bot stops pushing the price up on
     * their behalf. Caller must own the bid or we throw ForbiddenException.
     */
    @Transactional
    int cancelAutoBid(Long userId, Long bidId) {
        def bid = bidRepository.findById(bidId).orElseThrow { new NotFoundException("Bid", bidId) }
        if (bid.bidderUserId != userId) {
            throw new ForbiddenException("Not your bid")
        }
        // Only live bids (WINNING / OUTBID) carry an actionable auto-raise.
        // A WON / LOST / CANCELLED bid belongs to a closed auction — there's
        // no bot left to stop, so silently no-op rather than rewriting
        // terminal history. Matches the `findActiveAutoBidsForUser` filter
        // the bulk-cancel path uses.
        if (!(bid.status in ['WINNING', 'OUTBID'])) return 0
        if (bid.maxAmount == null && bid.kind != 'AUTO') return 0
        bid.maxAmount = null
        bid.kind = 'MANUAL'
        bidRepository.save(bid)
        1
    }

    /** Bulk cancel of every active auto-raise the user owns. Returns the
     *  number of rows touched. Used by the Profile → Auto-Bids bulk
     *  cancel button. */
    @Transactional
    int cancelAllAutoBidsForUser(Long userId) {
        def rows = bidRepository.findActiveAutoBidsForUser(userId)
        rows.each { b -> b.maxAmount = null; b.kind = 'MANUAL' }
        if (!rows.isEmpty()) bidRepository.saveAll(rows)
        rows.size()
    }

    /**
     * Runs every 30s and closes any auctions whose expiresAt is in the past.
     * Winning bidder's wallet is charged (if they have the funds) and the listing
     * is marked SOLD. Anyone else who had a live bid gets a LOST notification.
     */
    @Scheduled(fixedDelay = 30_000L)
    @Transactional
    void sweepExpired() {
        def now = System.currentTimeMillis()
        // Indexed query — pulls only the auction rows whose expiresAt has
        // passed. Old path did `findByStatus('ACTIVE').findAll { ... }`
        // which loaded every active marketplace listing into memory on
        // every 30-second tick.
        def expired = listingRepository.findExpiredAuctions(now)
        for (Listing listing : expired) {
            try { settle(listing) }
            catch (Exception e) { log.error("Failed to settle auction ${listing.id}: ${e.message}") }
        }
    }

    /** Window used by the sweeper below — fire the "ending soon" nudge
     *  when an auction is at most this close to its `expiresAt`. */
    private static final long ENDING_SOON_WINDOW_MS = 10L * 60L * 1000L

    /**
     * Runs every 2 minutes and pushes one AUCTION_ENDING notification to
     * every unique bidder AND every user watching the item whenever an
     * active auction is within the 10-minute close window. A per-listing
     * `ending_soon_notified` flag on `listings` dedups across ticks so
     * bidders and watchers see the nudge at most once. Anti-snipe soft-
     * close can still extend the auction after the nudge fires — that's
     * fine: the flag stays true and later bids re-trigger no further
     * notification, which matches the "one reminder, not a barrage"
     * intent.
     */
    @Scheduled(fixedDelay = 120_000L, initialDelay = 30_000L)
    @Transactional
    void sweepEndingSoon() {
        def now = System.currentTimeMillis()
        def cutoff = now + ENDING_SOON_WINDOW_MS
        def due = listingRepository.findEndingSoonUnnotified(now, cutoff)
        if (due.isEmpty()) return
        for (Listing listing : due) {
            try { notifyEndingSoon(listing) }
            catch (Exception e) {
                log.warn("Ending-soon notify failed for listing ${listing.id}: ${e.message}")
            }
        }
        log.info("Ending-soon fanout: notified on ${due.size()} auction${due.size() == 1 ? '' : 's'}")
    }

    @Transactional
    protected void notifyEndingSoon(Listing listing) {
        // Guard a null expiresAt — the ending-soon math below subtracts
        // from it and would NPE. findEndingSoonUnnotified should only
        // return rows with a deadline, but a legacy/mis-tagged row with
        // no expiresAt would otherwise be rescanned every sweep forever.
        if (listing == null || listing.expiresAt == null) return
        // Bidders first (they have explicit skin-in-the-game), watchers
        // second. Dedup via a Set so a user who both bid and watched is
        // only pinged once per auction.
        def recipients = new LinkedHashSet<Long>()
        bidRepository.findByListing(listing.id)
            .collect { it.bidderUserId }
            .findAll { it != null }
            .each { recipients.add(it as Long) }
        if (watchlistAlertRepository != null && listing.item?.id != null) {
            watchlistAlertRepository.findActiveUserIdsForItem(listing.item.id)
                .findAll { it != null }
                .each { recipients.add(it as Long) }
        }
        // Filter banned accounts — same bug class as batches 314/315.
        // A banned user who had bid pre-ban or had a watchlist alert
        // set should stop receiving AUCTION_ENDING pings since they
        // can't act on them (banGuard rejects new bids). Bulk lookup
        // is cheap: one query for all recipients, lose the banned.
        if (!recipients.isEmpty()) {
            try {
                def users = steamUserRepository.findAllById(recipients)
                def banned = users.findAll { Boolean.TRUE.equals(it.banned) }
                    .collect { it.id }
                if (!banned.isEmpty()) recipients.removeAll(banned)
            } catch (Exception e) {
                log.warn("AUCTION_ENDING banned-filter lookup failed: ${e.message}")
            }
        }
        if (!recipients.isEmpty()) {
            def itemName = listing.item?.name ?: 'an auction'
            def priceStr = listing.currentBid != null
                ? "Current bid \$${listing.currentBid.toPlainString()}"
                : "Starting at \$${listing.price?.toPlainString() ?: '0'}"
            def mins = Math.max(1L, (long) Math.round(
                (listing.expiresAt - System.currentTimeMillis()) / 60000.0d))
            recipients.each { uid ->
                try {
                    notificationService.push(uid, 'AUCTION_ENDING',
                        "Ending in ~${mins}m · ${itemName}",
                        priceStr, listing.id,
                        "/item/${listing.item?.id ?: ''}".toString())
                } catch (Exception e) {
                    log.warn("AUCTION_ENDING push failed for uid=${uid}: ${e.message}")
                }
            }
            // Email fan-out (batch 572). Bell pushes only reach users
            // actively on the site during the 10-minute window; an
            // email ping catches bidders / watchers who aren't. Gated
            // on verified email + global notification pref + AUCTIONS
            // bucket unmuted (so a heavy auction watcher can opt out
            // without losing transactional trade emails). Uses the
            // same steamUserRepository lookup the banned-filter just
            // did, but re-fetched here to pick up email/verified fields.
            if (emailService != null && steamUserRepository != null) {
                try {
                    def eligible = steamUserRepository.findAllById(recipients)
                    def itemUrl = listing.item?.id != null ? "/item/${listing.item.id}".toString() : null
                    def topBid  = listing.currentBid ?: listing.price ?: BigDecimal.ZERO
                    eligible.each { u ->
                        try {
                            if (emailService.canSendTo(u, 'AUCTIONS')) {
                                emailService.sendAuctionEnding(u.email, u.displayName,
                                    itemName, topBid, (long) mins, itemUrl)
                            }
                        } catch (Exception inner) {
                            log.warn("AUCTION_ENDING email failed for uid=${u.id}: ${inner.message}")
                        }
                    }
                } catch (Exception outer) {
                    log.warn("AUCTION_ENDING email fan-out failed: ${outer.message}")
                }
            }
        }
        listing.endingSoonNotified = true
        listingRepository.save(listing)
    }

    @Transactional
    protected void settle(Listing listing) {
        if (listing.currentBidderId == null) {
            // No bids — return the item to the seller's inventory (mirrors
            // SellService.cancelListing by flipping to SOLD + buyerUserId =
            // sellerUserId so findOwnedBy picks it up) and ping the seller
            // so they know the auction ran out silently. Without this the
            // item was stuck: not in seller's inventory (no SOLD row), not
            // on the marketplace (EXPIRED status), not listed under
            // MyStall Active. The seller had no way to relist it.
            listing.status = 'SOLD'
            listing.buyerUserId = listing.sellerUserId
            listing.soldAt = System.currentTimeMillis()
            listingRepository.save(listing)
            if (listing.sellerUserId != null) {
                try {
                    notificationService.push(listing.sellerUserId,
                        'AUCTION_EXPIRED_NO_BIDS',
                        "Auction ended with no bids · ${listing.item?.name ?: 'your auction'}",
                        "The item is back in your inventory — relist it at a different price or as Buy Now.",
                        listing.id,
                        '/sell')
                } catch (Exception e) {
                    log.warn("AUCTION_EXPIRED_NO_BIDS push failed for seller ${listing.sellerUserId}: ${e.message}")
                }
                fireAuctionExpiredEmail(listing,
                    'No bids were placed before the timer ran out.')
            }
            return
        }
        def winnerId = listing.currentBidderId
        def winnerUser = steamUserRepository.findById(winnerId).orElse(null)
        // Banned winners get the same treatment as a vanished account —
        // return the item to the seller rather than completing a trade
        // the buyer can't honor (the banGuard on PurchaseService.buy
        // would reject, leaving the auction stuck in a half-settled
        // state otherwise). Batch 316 bug fix.
        boolean winnerInvalid = (winnerUser == null) || Boolean.TRUE.equals(winnerUser.banned)
        if (winnerInvalid) {
            listing.status = 'SOLD'
            listing.buyerUserId = listing.sellerUserId
            listing.soldAt = System.currentTimeMillis()
            listingRepository.save(listing)
            // Close out every live bid on the now-SOLD listing. Without
            // this, a banned/deleted winner leaves not just their own
            // bid but every other bidder's bid stuck in WINNING — so
            // Profile → Active Bids shows the closed auction as still
            // live forever for all of them, and it never appears under
            // Past Bids. Same stale-row class batch 324 fixed for the
            // happy path; the no-sale branches were missed.
            closeOutLiveBids(listing.id)
            if (listing.sellerUserId != null) {
                try {
                    notificationService.push(listing.sellerUserId, 'AUCTION_EXPIRED_NO_BIDS',
                        "Auction closed without a valid winner · ${listing.item?.name ?: 'your auction'}",
                        "The top bidder's account is no longer available. The item is back in your inventory.",
                        listing.id, '/sell')
                } catch (Exception e) {
                    log.warn("AUCTION_EXPIRED_NO_BIDS push failed: ${e.message}")
                }
                fireAuctionExpiredEmail(listing,
                    "The top bidder's account was banned or deleted before settlement — no trade was opened.")
            }
            return
        }
        def wallet = walletRepository.findByUsername("steam_${winnerUser.steamId64}")
        if (wallet == null || wallet.balance < listing.currentBid) {
            // Winner can't afford — return the item to the seller, ping
            // both parties. Bid-time solvency check (BidService.placeBid)
            // should make this near-impossible, but balance can drop
            // between bid and settle if the bidder spent the money on
            // another listing in the meantime.
            listing.status = 'SOLD'
            listing.buyerUserId = listing.sellerUserId
            listing.soldAt = System.currentTimeMillis()
            listingRepository.save(listing)
            // Close out every live bid — the auction is over even
            // though no trade opened. Mirrors the winner-invalid branch
            // above; otherwise losing bidders' bids stay WINNING and
            // haunt their Active Bids tab indefinitely.
            closeOutLiveBids(listing.id)
            try {
                notificationService.push(winnerId, 'AUCTION_LOST',
                    "Auction lost — insufficient balance", listing.item?.name, listing.id,
                    listing.item?.id != null ? "/item/${listing.item.id}" : null)
            } catch (Exception e) {
                log.warn("AUCTION_LOST push to winner failed: ${e.message}")
            }
            if (listing.sellerUserId != null) {
                try {
                    notificationService.push(listing.sellerUserId, 'AUCTION_EXPIRED_NO_BIDS',
                        "Auction winner couldn't pay · ${listing.item?.name ?: 'your auction'}",
                        "The top bidder's balance dropped below their bid. The item is back in your inventory.",
                        listing.id, '/sell')
                } catch (Exception e) {
                    log.warn("AUCTION_EXPIRED_NO_BIDS push to seller failed: ${e.message}")
                }
                fireAuctionExpiredEmail(listing,
                    "The winning bidder's wallet balance dropped below their bid between bid-time and settle-time — no trade was opened.")
            }
            return
        }

        wallet.balance = wallet.balance - listing.currentBid
        walletRepository.save(wallet)
        listing.status = 'SOLD'
        listing.soldAt = System.currentTimeMillis()
        listing.buyerUserId = winnerId
        listingRepository.save(listing)

        // Record the winning bid as the day's closing price so the item's
        // sparkline picks up real auction outcomes — otherwise only BUY_NOW
        // purchases and external SCMM sync fed the chart.
        try {
            priceHistoryService?.record(listing.item, listing.currentBid, 1)
        } catch (Exception e) {
            log.warn("price-history record failed for auction ${listing.id}: ${e.message}")
        }

        // Bump Item.totalSold on the auction-won branch (same reason as
        // PurchaseService.buy — keep "Most Traded" honest).
        try {
            if (listing.item?.id != null) {
                itemRepository?.incrementTotalSold(listing.item.id)
            }
        } catch (Exception e) {
            log.warn("totalSold bump failed for item ${listing.item?.id}: ${e.message}")
        }

        transactionRepository.save(new Transaction(
            walletId:        wallet.id,
            type:            'PURCHASE',
            status:          'COMPLETED',
            amount:          listing.currentBid,
            currency:        wallet.currency,
            stripeReference: 'auction',
            description:     "Won auction · ${listing.item?.name}",
            listingId:       listing.id
        ))

        // P2P escrow: auction wins go through the same trade flow as
        // BUY_NOW purchases so the seller must deliver before getting paid.
        // Without this, auction sellers were credited immediately with no
        // confirm/dispute/cancel flow for the buyer.
        if (listing.sellerUserId != null && tradeService != null) {
            def sellerUser = steamUserRepository.findById(listing.sellerUserId).orElse(null)
            def sellerWallet = sellerUser ? walletRepository.findByUsername("steam_${sellerUser.steamId64}") : null
            tradeService.open(
                listing.id,
                listing.item?.id,
                listing.item?.name,
                winnerId,
                wallet.id,
                listing.sellerUserId,
                sellerWallet?.id,
                listing.currentBid
            )
        }

        notificationService.push(winnerId, 'AUCTION_WON',
            "You won · ${listing.item?.name}",
            "Final bid \$${listing.currentBid.toPlainString()}", listing.id,
            '/profile?tab=trades')
        // Ping the seller too (batch 397). Previously the auction closing
        // successfully was a silent event on the seller side — they'd
        // discover it only by noticing a new row on their Trades tab. The
        // ending-soon push (AUCTION_ENDING) doesn't cover this since it
        // fires BEFORE the close. Deep-links to the trade so the seller
        // can immediately send the Steam offer.
        if (listing.sellerUserId != null) {
            try {
                notificationService.push(listing.sellerUserId, 'AUCTION_SOLD',
                    "Auction sold · ${listing.item?.name ?: 'your auction'}",
                    "Won for \$${listing.currentBid.toPlainString()} — send the Steam trade offer to the winner.",
                    listing.id,
                    '/profile?tab=trades')
            } catch (Exception e) {
                log.warn("AUCTION_SOLD push to seller failed: ${e.message}")
            }
        }
        // Email the winner too — auctions settle on the 30s-poll
        // timer, not on a page they're watching, so a bell-only
        // notification is easy to miss for hours.
        try {
            def winner = steamUserRepository.findById(winnerId).orElse(null)
            if (emailService != null && emailService.canSendTo(winner, 'AUCTIONS')) {
                def itemUrl = listing.item?.id != null
                    ? "/item/${listing.item.id}".toString()
                    : null
                emailService.sendAuctionWon(winner.email, winner.displayName,
                    listing.item?.name, listing.currentBid, itemUrl)
            }
        } catch (Exception e) {
            log.warn("Auction-won email failed for user ${winnerId}: ${e.message}")
        }

        // Close out every non-terminal bid on the listing (batch 324 +
        // the auto-bid orphan fix). Before this, settle flipped only ONE
        // of the winner's WINNING rows to WON via `bids.find { ... }`.
        // The auto-bid bot, however, saves a SECOND WINNING row for the
        // same user on a re-raise — so the winner's older row stayed
        // WINNING on a now-SOLD listing and Profile → Active Bids
        // (findLiveBidsForUser filters WINNING/OUTBID) showed the closed
        // auction as live for the winner forever.
        //
        // Now: ALL of the winner's non-terminal rows are resolved — the
        // single highest-amount one becomes WON, every other one becomes
        // OUTBID (a trailing row the bidder no longer holds the top with,
        // matching Trade History). Every losing bidder's non-terminal
        // rows — WINNING or OUTBID — all become LOST, so no stale row of
        // any kind survives on the SOLD listing.
        def bids = bidRepository.findByListing(listing.id)
        def winnersLive = bids.findAll {
            it.bidderUserId == winnerId && it.status in ['WINNING', 'OUTBID']
        }
        // Highest-amount row wins; sort descending so element 0 is the
        // WON row even if `findByListing`'s ordering ever changes. Ties
        // (equal amount) fall to the EARLIEST `createdAt` so the same-
        // amount tiebreaker is "who bid first", not "who happened to be
        // inserted first" — id and createdAt usually correlate, but
        // clock-skew on a clustered insert can make id ordering disagree
        // with bid time, and the fairness rule is "earlier bidder wins".
        // `id` is the final fallback so the pick stays deterministic
        // when two rows share a millisecond.
        def winnersTop = winnersLive.isEmpty() ? null : winnersLive.sort { a, b ->
            (b.amount <=> a.amount) ?:
                ((a.createdAt ?: 0L) <=> (b.createdAt ?: 0L)) ?:
                ((a.id ?: 0L) <=> (b.id ?: 0L))
        }.first()
        def winnerRowsToSave = []
        winnersLive.each { row ->
            row.status = row.is(winnersTop) ? 'WON' : 'OUTBID'
            winnerRowsToSave << row
        }
        if (!winnerRowsToSave.isEmpty()) bidRepository.saveAll(winnerRowsToSave)
        def losers = bids.findAll {
            it.bidderUserId != winnerId && it.status in ['WINNING', 'OUTBID']
        }
        losers.each { it.status = 'LOST' }
        if (!losers.isEmpty()) bidRepository.saveAll(losers)
        losers*.bidderUserId.unique().each { uid ->
            notificationService.push(uid, 'AUCTION_LOST',
                "Auction lost · ${listing.item?.name}",
                "Winning bid \$${listing.currentBid.toPlainString()}", listing.id,
                listing.item?.id != null ? "/item/${listing.item.id}" : null)
        }

        log.info("Auction ${listing.id} settled — winner=${winnerId}, price=\$${listing.currentBid}")
    }

    /** Flip every still-live bid (WINNING / OUTBID) on a closed listing
     *  to LOST. Called by the two no-sale settle branches (banned /
     *  deleted winner, winner-can't-pay) so a settle that opens no
     *  trade still terminates every bidder's bid — otherwise those
     *  bids stay WINNING on a SOLD listing and the bidder's
     *  Profile → Active Bids tab shows the dead auction as live forever
     *  (findLiveBidsForUser filters on WINNING/OUTBID). The happy path
     *  already does its own winner→WON / loser→LOST flip inline.
     *  Best-effort and isolated: a save failure is logged, not fatal —
     *  the listing itself is already the authoritative SOLD record. */
    private void closeOutLiveBids(Long listingId) {
        if (listingId == null) return
        try {
            def live = bidRepository.findByListing(listingId)
                .findAll { it.status in ['WINNING', 'OUTBID'] }
            if (live.isEmpty()) return
            live.each { it.status = 'LOST' }
            bidRepository.saveAll(live)
        } catch (Exception e) {
            log.warn("closeOutLiveBids failed for listing ${listingId}: ${e.message}")
        }
    }

    /**
     * After a new top bid lands, demote every *other* still-live
     * (WINNING / OUTBID) bid row on the listing to OUTBID, leaving only
     * `keepWinningBid` — the row that should now read WINNING — untouched.
     *
     * Without this the `OUTBID` status was dead code: `placeBid` saved
     * every new bid as WINNING and never demoted the prior leader, so bid
     * history showed every bidder as "WINNING" forever and
     * `findLiveBidsForUser` could not tell a top bid from a trailing one.
     *
     * Identity-based exclusion: the kept row is matched by `id` (or by
     * object identity when the row has not been flushed yet) so it is
     * never accidentally demoted. Self-raises are handled naturally — a
     * bidder's own older WINNING rows are *other* rows and get demoted,
     * leaving only their newest bid WINNING.
     *
     * Best-effort and isolated, like `closeOutLiveBids` — a save failure
     * is logged, not fatal; the listing's denormalised top bid is the
     * authoritative record of who currently leads.
     */
    private void markOthersOutbid(Long listingId, Bid keepWinningBid) {
        if (listingId == null) return
        try {
            def all = bidRepository.findByListing(listingId)
            if (all == null || all.isEmpty()) return
            def demoted = all.findAll { b ->
                b.status in ['WINNING', 'OUTBID'] &&
                    !(keepWinningBid != null &&
                        (b.is(keepWinningBid) ||
                         (b.id != null && keepWinningBid.id != null && b.id == keepWinningBid.id)))
            }
            def changed = demoted.findAll { it.status != 'OUTBID' }
            if (changed.isEmpty()) return
            changed.each { it.status = 'OUTBID' }
            bidRepository.saveAll(changed)
        } catch (Exception e) {
            log.warn("markOthersOutbid failed for listing ${listingId}: ${e.message}")
        }
    }

    /** Fire AUCTION_EXPIRED email to the seller (batch 607). Shared by
     *  the three no-sale settle paths (zero bids, banned winner,
     *  insufficient balance). TRADES-unrelated — gated on the AUCTIONS
     *  bucket because it's auction-activity. Silent-fail — the bell
     *  push already went out. */
    private void fireAuctionExpiredEmail(Listing listing, String reason) {
        if (listing?.sellerUserId == null) return
        try {
            if (emailService == null || steamUserRepository == null) return
            def seller = steamUserRepository.findById(listing.sellerUserId).orElse(null)
            if (!emailService.canSendTo(seller, 'AUCTIONS')) return
            emailService.sendAuctionExpired(seller.email, seller.displayName,
                listing.item?.name, reason, listing.id)
        } catch (Exception e) {
            log.warn("AUCTION_EXPIRED email failed for seller ${listing?.sellerUserId}: ${e.message}")
        }
    }
}
