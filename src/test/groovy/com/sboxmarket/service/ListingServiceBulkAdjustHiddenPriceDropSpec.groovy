package com.sboxmarket.service

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.CartItemRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import spock.lang.Specification

/**
 * Regression spec for the bulk-adjust PRICE_DROPPED fan-out leaking onto
 * HIDDEN listings.
 *
 * Repro shape: seller in away mode (every active listing has hidden=true)
 * runs a bulk -10%. The marketplace grid won't surface those rows AND
 * PurchaseService.buy rejects every hidden listing with
 * ListingNotAvailableException — but pre-fix the fan-out cheerfully fired
 * PRICE_DROPPED bells at every cart-holder of every hidden row, with body
 * text saying "Check out before it sells". Click-through landed on a 404
 * — a misleading bell driving wasted clicks.
 *
 * The bulk path amplifies this far more than the single-edit path: a
 * seller bulk-adjusting from away mode is the ordinary case ("prep my
 * stall for return"), so EVERY active listing is hidden and EVERY
 * fan-out is wasted.
 *
 * Fix: the priceDrops capture in bulkAdjustPrices now drops rows with
 * hidden=true so the downstream cartItemRepository / notificationService
 * fan-out skips them entirely. Visible drops still notify normally.
 */
class ListingServiceBulkAdjustHiddenPriceDropSpec extends Specification {

    ListingRepository listingRepository = Mock()
    ItemRepository itemRepository = Mock()
    CartItemRepository cartItemRepository = Mock()
    NotificationService notificationService = Mock()
    ListingService svc

    private static final Long SELLER = 42L
    private static final Long CART_HOLDER = 99L

    def setup() {
        svc = new ListingService(
            listingRepository: listingRepository,
            itemRepository: itemRepository,
            cartItemRepository: cartItemRepository,
            notificationService: notificationService)
        // Item floor recompute path — minPriceForItem + save. We don't
        // care about the values here; the test is scoped to the fan-out.
        itemRepository.findById(_) >> Optional.empty()
    }

    private Listing makeListing(Long id, BigDecimal price, boolean hidden, Long itemId = 7L) {
        def item = new Item(id: itemId, name: 'AK-47 | Redline')
        new Listing(
            id: id,
            price: price,
            sellerUserId: SELLER,
            sellerName: 'me',
            status: 'ACTIVE',
            listingType: 'BUY_NOW',
            hidden: hidden,
            item: item)
    }

    def "hidden listings do NOT fire PRICE_DROPPED bells on bulk price drop"() {
        given:
        // Two listings, both bulk-dropped from $100 to $90 (−10%). One
        // visible, one hidden (seller is in away mode for example).
        def visible = makeListing(1L, new BigDecimal('100.00'), false)
        def hidden  = makeListing(2L, new BigDecimal('100.00'), true)
        listingRepository.findActiveBySeller(SELLER) >> [visible, hidden]
        // filterActiveRecipients returns the input untouched — the test
        // is about WHICH listings fan out, not which recipients survive.
        notificationService.filterActiveRecipients(_) >> { args -> args[0] as List<Long> }

        when:
        def result = svc.bulkAdjustPrices(SELLER, new BigDecimal('-10'))

        then:
        result.touched == 2
        result.skipped == 0
        // Visible listing: cart-holders are probed and pinged.
        1 * cartItemRepository.findOtherUsersWithListing(1L, SELLER) >> [CART_HOLDER]
        1 * notificationService.push(CART_HOLDER, 'PRICE_DROPPED', _, _, 1L, _)

        // Hidden listing: cart-holders are NEVER probed (the row drops
        // out of priceDrops up front), so no probe + no bell.
        0 * cartItemRepository.findOtherUsersWithListing(2L, _)
        0 * notificationService.push(_, _, _, _, 2L, _)
    }

    def "fan-out is fully suppressed when EVERY adjusted listing is hidden (away-mode case)"() {
        given:
        // Three hidden listings — the common "seller is in away mode and
        // preps prices for return" case. Pre-fix this fired three bells
        // per cart-holder; the click-through hit the hidden-listing buy
        // guard and 404'd. Post-fix the cart repo is never touched.
        def a = makeListing(10L, new BigDecimal('50.00'), true)
        def b = makeListing(11L, new BigDecimal('75.00'), true)
        def c = makeListing(12L, new BigDecimal('120.00'), true)
        listingRepository.findActiveBySeller(SELLER) >> [a, b, c]
        notificationService.filterActiveRecipients(_) >> { args -> args[0] as List<Long> }

        when:
        def result = svc.bulkAdjustPrices(SELLER, new BigDecimal('-15'))

        then:
        result.touched == 3
        // Repo + notification fan-out is entirely skipped.
        0 * cartItemRepository.findOtherUsersWithListing(_, _)
        0 * notificationService.push(_, _, _, _, _, _)
    }

    def "visible listings still fire PRICE_DROPPED on bulk drop (no over-suppression)"() {
        given:
        // Negative-control: the fix must not break the happy path —
        // visible-only batches still ping cart-holders.
        def v1 = makeListing(20L, new BigDecimal('40.00'), false)
        def v2 = makeListing(21L, new BigDecimal('60.00'), false)
        listingRepository.findActiveBySeller(SELLER) >> [v1, v2]
        cartItemRepository.findOtherUsersWithListing(20L, SELLER) >> [CART_HOLDER]
        cartItemRepository.findOtherUsersWithListing(21L, SELLER) >> [CART_HOLDER]
        notificationService.filterActiveRecipients(_) >> { args -> args[0] as List<Long> }

        when:
        def result = svc.bulkAdjustPrices(SELLER, new BigDecimal('-20'))

        then:
        result.touched == 2
        1 * notificationService.push(CART_HOLDER, 'PRICE_DROPPED', _, _, 20L, _)
        1 * notificationService.push(CART_HOLDER, 'PRICE_DROPPED', _, _, 21L, _)
    }
}
