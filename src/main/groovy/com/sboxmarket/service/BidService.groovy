package com.sboxmarket.service

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
    @Autowired BanGuard banGuard
    @Autowired TextSanitizer textSanitizer

    @Transactional
    Bid placeBid(Long bidderUserId, String bidderName, Long listingId,
                 BigDecimal amount, BigDecimal maxAmount = null) {
        banGuard.assertNotBanned(bidderUserId)
        bidderName = textSanitizer.cleanShort(bidderName)
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_BID", "Bid amount must be positive")
        }
        def listing = listingRepository.findById(listingId)
            .orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.status != 'ACTIVE') {
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

        // Outbid the previous top bidder
        def previousTopId = listing.currentBidderId
        def previousAmount = listing.currentBid

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
        if (listing.expiresAt != null) {
            def now = System.currentTimeMillis()
            def timeLeft = listing.expiresAt - now
            if (timeLeft > 0 && timeLeft <= SNIPE_WINDOW_MS) {
                def newExpiresAt = now + SNIPE_EXTEND_MS
                if (newExpiresAt > listing.expiresAt) {
                    listing.expiresAt = newExpiresAt
                    log.info("Auction ${listingId} soft-closed — extended to +${SNIPE_EXTEND_MS}ms by bid from ${bidderUserId}")
                }
            }
        }
        listingRepository.save(listing)

        if (previousTopId != null && previousTopId != bidderUserId) {
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
                if (emailService != null && prev != null &&
                        Boolean.TRUE.equals(prev.emailVerified) && prev.email &&
                        Boolean.TRUE.equals(prev.emailNotificationsEnabled)) {
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

        bid
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

    @Transactional
    protected void settle(Listing listing) {
        if (listing.currentBidderId == null) {
            // No bids — expire the listing quietly
            listing.status = 'EXPIRED'
            listingRepository.save(listing)
            return
        }
        def winnerId = listing.currentBidderId
        def winnerUser = steamUserRepository.findById(winnerId).orElse(null)
        if (winnerUser == null) {
            listing.status = 'EXPIRED'
            listingRepository.save(listing)
            return
        }
        def wallet = walletRepository.findByUsername("steam_${winnerUser.steamId64}")
        if (wallet == null || wallet.balance < listing.currentBid) {
            // Winner can't afford — mark listing failed, notify them
            listing.status = 'EXPIRED'
            listingRepository.save(listing)
            notificationService.push(winnerId, 'AUCTION_LOST',
                "Auction lost — insufficient balance", listing.item?.name, listing.id,
                listing.item?.id != null ? "/item/${listing.item.id}" : null)
            return
        }

        wallet.balance = wallet.balance - listing.currentBid
        walletRepository.save(wallet)
        listing.status = 'SOLD'
        listing.soldAt = System.currentTimeMillis()
        listing.buyerUserId = winnerId
        listingRepository.save(listing)

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
            '/profile')
        // Email the winner too — auctions settle on the 30s-poll
        // timer, not on a page they're watching, so a bell-only
        // notification is easy to miss for hours.
        try {
            def winner = steamUserRepository.findById(winnerId).orElse(null)
            if (emailService != null && winner != null &&
                    Boolean.TRUE.equals(winner.emailVerified) && winner.email &&
                    Boolean.TRUE.equals(winner.emailNotificationsEnabled)) {
                def itemUrl = listing.item?.id != null
                    ? "/item/${listing.item.id}".toString()
                    : null
                emailService.sendAuctionWon(winner.email, winner.displayName,
                    listing.item?.name, listing.currentBid, itemUrl)
            }
        } catch (Exception e) {
            log.warn("Auction-won email failed for user ${winnerId}: ${e.message}")
        }

        // Mark losing bids
        def bids = bidRepository.findByListing(listing.id)
        def losers = bids.findAll { it.bidderUserId != winnerId && it.status == 'WINNING' }
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
}
