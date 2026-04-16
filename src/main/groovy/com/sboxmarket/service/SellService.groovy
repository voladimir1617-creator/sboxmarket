package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
    @Autowired @Lazy BuyOrderService buyOrderService
    @Autowired BanGuard banGuard
    @Autowired TextSanitizer textSanitizer

    @Transactional
    Listing relist(Long sellerUserId, String sellerName, Long ownedListingId, BigDecimal newPrice) {
        banGuard.assertNotBanned(sellerUserId)
        sellerName = textSanitizer.cleanShort(sellerName)
        // Defensive: validation belongs in the DTO but we keep it here too for
        // callers that bypass the HTTP boundary (e.g. the offer-accept flow).
        if (newPrice == null || newPrice <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_PRICE", "Price must be greater than 0")
        }
        if (newPrice > new BigDecimal('100000')) {
            throw new BadRequestException("PRICE_TOO_HIGH", "Price must not exceed \$100,000")
        }

        def owned = listingRepository.findById(ownedListingId)
                .orElseThrow { new NotFoundException("Listing", ownedListingId) }

        if (owned.buyerUserId == null || owned.buyerUserId != sellerUserId) {
            throw new ForbiddenException("You do not own this item")
        }
        if (owned.status != 'SOLD') {
            throw new BadRequestException("NOT_IN_INVENTORY", "Item is not in your inventory")
        }

        // Mark the old listing as RELISTED (removes it from inventory queries)
        owned.status = 'RELISTED'
        listingRepository.save(owned)

        // Create a new ACTIVE listing under this user
        def fresh = new Listing(
            item        : owned.item,
            price       : newPrice,
            sellerName  : sellerName,
            sellerAvatar: (sellerName ?: 'US').take(2).toUpperCase(),
            condition   : '',
            rarityScore : owned.rarityScore,
            status      : 'ACTIVE',
            sellerUserId: sellerUserId
        )
        def saved = listingRepository.save(fresh)
        log.info("User $sellerUserId relisted item ${owned.item.name} as listing ${saved.id} for \$${newPrice}")

        // Try to auto-fulfil any standing buy order that matches this fresh listing.
        // Failures here must never fail the parent transaction.
        try {
            buyOrderService.tryMatch(saved)
        } catch (Exception e) {
            log.warn("Buy-order match failed for listing ${saved.id}: ${e.message}")
        }
        saved
    }

    @Transactional
    void cancelListing(Long sellerUserId, Long listingId) {
        def listing = listingRepository.findById(listingId)
                .orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.sellerUserId != sellerUserId) {
            throw new ForbiddenException("You can only cancel your own listings")
        }
        if (listing.status != 'ACTIVE') {
            throw new ListingNotAvailableException(listingId)
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
    }
}
