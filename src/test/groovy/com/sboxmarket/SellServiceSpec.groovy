package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.SellService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the relist (sell-from-inventory) + cancelListing
 * flows. BuyOrderService is mocked — the matching engine is covered in
 * BuyOrderServiceSpec and we only want to prove SellService invokes it
 * without failing the parent transaction.
 */
class SellServiceSpec extends Specification {

    ListingRepository listingRepository = Mock()
    ItemRepository    itemRepository    = Mock()
    BuyOrderService   buyOrderService   = Mock()
    BanGuard          banGuard          = Mock()
    TextSanitizer     textSanitizer     = Mock() {
        cleanShort(_) >> { String s -> s }
    }

    @Subject
    SellService service = new SellService(
        listingRepository: listingRepository,
        itemRepository:    itemRepository,
        buyOrderService:   buyOrderService,
        banGuard:          banGuard,
        textSanitizer:     textSanitizer
    )

    private Listing owned(Map args = [:]) {
        new Listing(
            id:           args.id ?: 50L,
            item:         new Item(id: 1L, name: 'Wizard Hat'),
            price:        new BigDecimal("40"),
            buyerUserId:  args.owner ?: 10L,
            sellerUserId: args.originalSeller ?: 99L,
            status:       args.status ?: 'SOLD',
            rarityScore:  new BigDecimal("0.5")
        )
    }

    // ── relist ────────────────────────────────────────────────────

    def "relist creates a fresh ACTIVE listing under the seller"() {
        given:
        def oldListing = owned()
        listingRepository.findById(50L) >> Optional.of(oldListing)
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal("80"))

        then:
        1 * banGuard.assertNotBanned(10L)
        fresh.status == 'ACTIVE'
        fresh.price == new BigDecimal("80")
        fresh.sellerUserId == 10L
        fresh.sellerName == 'Alice'
        oldListing.status == 'RELISTED'
        1 * buyOrderService.tryMatch(_)
    }

    def "relist swallows buy-order-match exceptions"() {
        given:
        listingRepository.findById(_) >> Optional.of(owned())
        listingRepository.save(_) >> { args -> args[0] }
        buyOrderService.tryMatch(_) >> { throw new RuntimeException("boom") }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal("80"))

        then:
        // Still returns the fresh listing even though the match engine blew up
        fresh != null
        fresh.status == 'ACTIVE'
    }

    def "relist refuses zero/negative/null prices"() {
        when:
        service.relist(10L, 'Alice', 50L, price)

        then:
        thrown(BadRequestException)

        where:
        price << [null, BigDecimal.ZERO, new BigDecimal("-1")]
    }

    def 'relist refuses prices above the $100k ceiling'() {
        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal("100001"))

        then:
        thrown(BadRequestException)
    }

    def "relist 404s for unknown listing id"() {
        given:
        listingRepository.findById(_) >> Optional.empty()

        when:
        service.relist(10L, 'Alice', 999L, new BigDecimal("80"))

        then:
        thrown(NotFoundException)
    }

    def "relist forbids non-owner"() {
        given:
        listingRepository.findById(_) >> Optional.of(owned(owner: 99L))

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal("80"))

        then:
        thrown(ForbiddenException)
    }

    def "relist refuses items still ACTIVE (not in inventory)"() {
        given:
        listingRepository.findById(_) >> Optional.of(owned(status: 'ACTIVE'))

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal("80"))

        then:
        thrown(BadRequestException)
    }

    // ── cancelListing ─────────────────────────────────────────────

    def "cancelListing returns the item to seller inventory"() {
        given:
        def listing = new Listing(
            id: 100L, status: 'ACTIVE', sellerUserId: 10L,
            item: new Item(id: 1L, name: 'x')
        )
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }

        when:
        service.cancelListing(10L, 100L)

        then:
        listing.status == 'SOLD'
        listing.buyerUserId == 10L
    }

    def "cancelListing forbids non-seller"() {
        given:
        listingRepository.findById(_) >> Optional.of(new Listing(id: 100L, status: 'ACTIVE', sellerUserId: 99L))

        when:
        service.cancelListing(10L, 100L)

        then:
        thrown(ForbiddenException)
    }

    def "cancelListing refuses already-sold listings"() {
        given:
        listingRepository.findById(_) >> Optional.of(new Listing(id: 100L, status: 'SOLD', sellerUserId: 10L))

        when:
        service.cancelListing(10L, 100L)

        then:
        thrown(ListingNotAvailableException)
    }
}
