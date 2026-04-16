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

    @Autowired ListingRepository listingRepository
    @Autowired BidRepository bidRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired NotificationService notificationService
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
        listingRepository.save(listing)

        if (previousTopId != null && previousTopId != bidderUserId) {
            notificationService.push(
                previousTopId,
                'AUCTION_OUTBID',
                "You were outbid on ${listing.item?.name}",
                "New top bid: \$${amount.toPlainString()}",
                listingId
            )
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
                "Auction lost — insufficient balance", listing.item?.name, listing.id)
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
            "Final bid \$${listing.currentBid.toPlainString()}", listing.id)

        // Mark losing bids
        def bids = bidRepository.findByListing(listing.id)
        def losers = bids.findAll { it.bidderUserId != winnerId && it.status == 'WINNING' }
        losers.each { it.status = 'LOST' }
        if (!losers.isEmpty()) bidRepository.saveAll(losers)
        losers*.bidderUserId.unique().each { uid ->
            notificationService.push(uid, 'AUCTION_LOST',
                "Auction lost · ${listing.item?.name}",
                "Winning bid \$${listing.currentBid.toPlainString()}", listing.id)
        }

        log.info("Auction ${listing.id} settled — winner=${winnerId}, price=\$${listing.currentBid}")
    }
}
