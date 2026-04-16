package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.OfferNotPendingException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Single-responsibility: handles the OFFER lifecycle.
 *
 * Flow:
 *   Buyer makes offer below asking price → PENDING
 *   Seller can ACCEPT → triggers a real PurchaseService.buy at the offered amount
 *                          (cancels any other pending offers on the same listing)
 *                       REJECT → marks REJECTED
 *   Buyer can withdraw → CANCELLED (only while PENDING)
 *
 * Depends on PurchaseService for the actual money movement —
 * Dependency Inversion: OfferService doesn't know how purchases happen,
 * it just calls the service.
 */
@Service
@Slf4j
class OfferService {

    @Autowired OfferRepository offerRepository
    @Autowired ListingRepository listingRepository
    @Autowired WalletRepository walletRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired PurchaseService purchaseService
    @Autowired BanGuard banGuard
    @Autowired TextSanitizer textSanitizer

    @Transactional
    Offer makeOffer(Long buyerUserId, String buyerName, Long listingId, BigDecimal amount) {
        banGuard.assertNotBanned(buyerUserId)
        buyerName = textSanitizer.cleanShort(buyerName)
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_OFFER", "Offer must be greater than 0")
        }
        // Defense-in-depth cap mirroring the DTO layer. Keeps services
        // safe when invoked directly from another service or test.
        if (amount > new BigDecimal("100000")) {
            throw new BadRequestException("OFFER_TOO_HIGH", "Offer must not exceed \$100,000")
        }
        def listing = listingRepository.findById(listingId)
                .orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.status != 'ACTIVE') {
            throw new ListingNotAvailableException(listingId)
        }
        // Offers only make sense for BUY_NOW listings — AUCTION listings
        // have their own bidding surface. Without this guard the offer
        // row lands in the DB in PENDING state forever (acceptOffer
        // would later fail on the PurchaseService auction guard) and
        // clogs both the buyer's outgoing offers and the seller's
        // incoming queue.
        if (listing.listingType == 'AUCTION') {
            throw new BadRequestException("NOT_BUY_NOW",
                "This is an auction listing — place a bid instead of making an offer")
        }
        if (listing.sellerUserId != null && listing.sellerUserId == buyerUserId) {
            throw new ForbiddenException("You can't offer on your own listing")
        }
        if (amount >= listing.price) {
            throw new BadRequestException("OFFER_TOO_HIGH",
                "Offer must be below the asking price (\$${listing.price}) — use Buy Now instead")
        }

        def offer = new Offer(
            listingId   : listingId,
            buyerUserId : buyerUserId,
            sellerUserId: listing.sellerUserId,
            amount      : amount,
            askingPrice : listing.price,
            buyerName   : buyerName,
            itemName    : listing.item?.name,
            itemImageUrl: listing.item?.imageUrl,
            status      : 'PENDING',
            author      : 'USER',
            parentOfferId: null
        )
        def saved = offerRepository.save(offer)
        log.info("Offer ${saved.id} created: $buyerName offered \$${amount} on listing $listingId")
        saved
    }

    /**
     * Seller counter-offer — creates a new Offer linked to the original via
     * `parentOfferId`, flips the old one to COUNTERED, and waits for the
     * buyer to accept or counter again. Mirrors CSFloat's bargaining thread.
     */
    @Transactional
    Offer counterOffer(Long sellerUserId, Long originalOfferId, BigDecimal amount) {
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_COUNTER", "Counter must be greater than 0")
        }
        def original = offerRepository.findById(originalOfferId)
                .orElseThrow { new NotFoundException("Offer", originalOfferId) }
        // Explicitly reject counter-offers on system listings (no
        // sellerUserId on the original). The old guard was
        // `original.sellerUserId != null && original.sellerUserId != caller`,
        // which silently let any user counter a system-seeded listing
        // and insert themselves as the seller on the new counter record.
        if (original.sellerUserId == null) {
            throw new ForbiddenException("Counter-offers on system listings are not supported — accept or reject only")
        }
        if (original.sellerUserId != sellerUserId) {
            throw new ForbiddenException("You can only counter offers on your own listings")
        }
        if (original.status != 'PENDING') {
            throw new OfferNotPendingException(originalOfferId, original.status)
        }
        def listing = listingRepository.findById(original.listingId)
                .orElseThrow { new NotFoundException("Listing", original.listingId) }
        if (amount > listing.price) {
            throw new BadRequestException("COUNTER_TOO_HIGH",
                "Counter must not exceed the asking price (\$${listing.price})")
        }
        if (amount <= original.amount) {
            throw new BadRequestException("COUNTER_NOT_HIGHER",
                "Counter must be higher than the buyer's offer (\$${original.amount})")
        }

        original.status = 'COUNTERED'
        original.updatedAt = System.currentTimeMillis()
        offerRepository.save(original)

        def counter = new Offer(
            listingId    : original.listingId,
            buyerUserId  : original.buyerUserId,
            sellerUserId : sellerUserId,
            amount       : amount,
            askingPrice  : listing.price,
            buyerName    : original.buyerName,
            itemName     : original.itemName,
            itemImageUrl : original.itemImageUrl,
            status       : 'PENDING',
            author       : 'SELLER',
            parentOfferId: original.id
        )
        offerRepository.save(counter)
    }

    /**
     * Full offer thread for a given listing. When `viewerUserId` is one of
     * the participants (listing seller or any offer's buyer) they see every
     * field. Other callers get the thread back with buyer identities
     * redacted to "Buyer #N" so a third party cannot enumerate who is
     * bargaining on a listing by hitting /api/offers/thread/{listingId}.
     *
     * Uses the indexed `findByListingId` query instead of the old
     * `findAll().findAll { it.listingId == ... }` full-table scan.
     */
    List<Offer> thread(Long listingId, Long viewerUserId = null) {
        def all = offerRepository.findByListingId(listingId)
        if (all.isEmpty()) return all

        def listing = listingRepository.findById(listingId).orElse(null)
        def isSeller = listing != null && listing.sellerUserId != null && listing.sellerUserId == viewerUserId
        def isBuyer  = viewerUserId != null && all.any { it.buyerUserId == viewerUserId }
        if (isSeller || isBuyer) return all

        // Redact — return fresh detached Offer instances so we never mutate
        // Hibernate-managed entities. Each unique buyerUserId becomes
        // "Buyer #1", "Buyer #2", etc. so the seller-side UI can still
        // visually group a single counter thread.
        def handles = [:]
        int next = 0
        all.collect { o ->
            def handle = handles[o.buyerUserId]
            if (handle == null) {
                handle = "Buyer #${++next}".toString()
                handles[o.buyerUserId] = handle
            }
            new Offer(
                id:            o.id,
                listingId:     o.listingId,
                buyerUserId:   null,
                sellerUserId:  o.sellerUserId,
                amount:        o.amount,
                askingPrice:   o.askingPrice,
                buyerName:     handle,
                itemName:      o.itemName,
                itemImageUrl:  o.itemImageUrl,
                status:        o.status,
                author:        o.author,
                parentOfferId: o.parentOfferId,
                createdAt:     o.createdAt,
                updatedAt:     o.updatedAt
            )
        }
    }

    /** Seller accepts an offer — runs the purchase at the offered price. */
    @Transactional
    Map acceptOffer(Long sellerUserId, Long offerId) {
        def offer = offerRepository.findById(offerId)
                .orElseThrow { new NotFoundException("Offer", offerId) }
        if (offer.sellerUserId != null && offer.sellerUserId != sellerUserId) {
            throw new ForbiddenException("You can only accept offers on your own listings")
        }
        if (offer.status != 'PENDING') {
            throw new OfferNotPendingException(offerId, offer.status)
        }

        def listing = listingRepository.findById(offer.listingId)
                .orElseThrow { new NotFoundException("Listing", offer.listingId) }
        if (listing.status != 'ACTIVE') {
            offer.status = 'EXPIRED'
            offer.updatedAt = System.currentTimeMillis()
            offerRepository.save(offer)
            throw new ListingNotAvailableException(offer.listingId)
        }

        // Resolve buyer wallet via SteamUser → username = "steam_<steamId64>"
        def buyer = steamUserRepository.findById(offer.buyerUserId).orElse(null)
        if (buyer == null) {
            throw new NotFoundException("Buyer", offer.buyerUserId)
        }
        def actualWallet = walletRepository.findByUsername("steam_" + buyer.steamId64)
        if (actualWallet == null) {
            throw new NotFoundException("Wallet for buyer ${offer.buyerUserId}")
        }
        if (actualWallet.balance < offer.amount) {
            offer.status = 'EXPIRED'
            offerRepository.save(offer)
            throw new InsufficientBalanceException(offer.amount, actualWallet.balance)
        }

        // Temporarily lower the listing price to the offer price so the existing
        // PurchaseService can run unchanged. This is internal — listing transitions
        // to SOLD immediately after.
        def originalPrice = listing.price
        listing.price = offer.amount
        listingRepository.save(listing)

        try {
            purchaseService.buy(actualWallet.id, offer.buyerUserId, offer.listingId)
        } catch (Exception e) {
            // Restore price if purchase fails
            listing.price = originalPrice
            listingRepository.save(listing)
            throw e
        }

        offer.status = 'ACCEPTED'
        offer.updatedAt = System.currentTimeMillis()
        offerRepository.save(offer)

        // Reject all other pending offers on the same listing
        offerRepository.findPendingForListing(offer.listingId).each { other ->
            if (other.id != offer.id) {
                other.status = 'EXPIRED'
                other.updatedAt = System.currentTimeMillis()
                offerRepository.save(other)
            }
        }

        log.info("Offer ${offer.id} accepted, listing ${offer.listingId} sold for \$${offer.amount}")
        [accepted: true, listingId: offer.listingId, finalPrice: offer.amount]
    }

    @Transactional
    Offer rejectOffer(Long sellerUserId, Long offerId) {
        def offer = offerRepository.findById(offerId)
                .orElseThrow { new NotFoundException("Offer", offerId) }
        // Same shape as counterOffer — the old check let random users
        // reject offers on system listings (null sellerUserId) and grief
        // real buyers out of their pending offers.
        if (offer.sellerUserId == null) {
            throw new ForbiddenException("Offers on system listings cannot be rejected — they auto-accept when processed")
        }
        if (offer.sellerUserId != sellerUserId) {
            throw new ForbiddenException("You can only reject offers on your own listings")
        }
        if (offer.status != 'PENDING') {
            throw new OfferNotPendingException(offerId, offer.status)
        }
        offer.status = 'REJECTED'
        offer.updatedAt = System.currentTimeMillis()
        offerRepository.save(offer)
    }

    @Transactional
    Offer cancelOffer(Long buyerUserId, Long offerId) {
        def offer = offerRepository.findById(offerId)
                .orElseThrow { new NotFoundException("Offer", offerId) }
        if (offer.buyerUserId != buyerUserId) {
            throw new ForbiddenException("You can only cancel your own offers")
        }
        if (offer.status != 'PENDING') {
            throw new OfferNotPendingException(offerId, offer.status)
        }
        offer.status = 'CANCELLED'
        offer.updatedAt = System.currentTimeMillis()
        offerRepository.save(offer)
    }

    List<Offer> incoming(Long sellerUserId) { offerRepository.findBySeller(sellerUserId) }
    List<Offer> outgoing(Long buyerUserId)  { offerRepository.findByBuyer(buyerUserId) }
}
