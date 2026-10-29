package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * A listing that sells another way (Buy Now, a buy-order fill, an auction
 * settle) goes SOLD without touching its offers: none of those paths call
 * into OfferService. Only acceptOffer expires the competing offers. Before
 * this fix, a buyer's PENDING offer on a listing that sold through Buy Now
 * stayed PENDING until the 7-day stale sweep, and the buyer was never told
 * that the item had sold.
 *
 * Runs against the real H2 schema so the repository query and the
 * conditional PENDING→EXPIRED claim are exercised for real. Only the
 * notification sink is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
class OfferSoldListingSweepIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx

    OfferRepository offerRepository
    ListingRepository listingRepository
    ItemRepository itemRepository
    NotificationService notificationService = Mock()
    OfferService service

    def setup() {
        offerRepository   = ctx.getBean(OfferRepository)
        listingRepository = ctx.getBean(ListingRepository)
        itemRepository    = ctx.getBean(ItemRepository)
        service = new OfferService(
            offerRepository:     offerRepository,
            listingRepository:   listingRepository,
            notificationService: notificationService,
            banGuard:            Mock(BanGuard),
            textSanitizer:       Mock(TextSanitizer),
            autoDeclineDays:     7L
        )
    }

    private static long uniqueId() {
        // Buyer ids no other spec in the shared context uses.
        9_000_000_000L + (System.nanoTime() % 1_000_000_000L)
    }

    private Listing seedListing(String status, String itemName) {
        def item = itemRepository.save(new Item(
            name: itemName, category: 'Hats', rarity: 'Limited',
            iconEmoji: 'H', accentColor: '#1a0a3a', supply: 10, totalSold: 0,
            lowestPrice: new BigDecimal('100.00')))
        listingRepository.save(new Listing(
            item: item, price: new BigDecimal('100.00'),
            sellerName: 'Seller', sellerAvatar: 'S', status: status,
            rarityScore: new BigDecimal('0.50'), sellerUserId: 7L))
    }

    private Offer seedOffer(Listing listing, long buyerId, String status) {
        // Fresh offer (updatedAt = now), so the 7-day stale sweep would
        // not touch it for a week.
        offerRepository.save(new Offer(
            listingId: listing.id, buyerUserId: buyerId, sellerUserId: 7L,
            amount: new BigDecimal('80.00'), askingPrice: new BigDecimal('100.00'),
            status: status))
    }

    def "a PENDING offer on a SOLD listing is expired and its buyer is told the item sold"() {
        given:
        long buyerId = uniqueId()
        def listing = seedListing('SOLD', 'Sold Wizard Hat ' + buyerId)
        def offer = seedOffer(listing, buyerId, 'PENDING')

        when:
        service.sweepOffersOnSoldListings()

        then: "one OFFER_REJECTED push to the buyer, naming the item"
        1 * notificationService.push(buyerId, 'OFFER_REJECTED',
            { it.toString().contains('Sold Wizard Hat') },
            { it.toString().contains('Sold Wizard Hat') && it.toString().contains('sold to another buyer, so your offer closed') },
            listing.id,
            "/item/${listing.item.id}".toString())
        0 * notificationService.push(buyerId, *_)

        and:
        offerRepository.findById(offer.id).get().status == 'EXPIRED'
    }

    def "a second sweep sends no duplicate notification"() {
        given:
        long buyerId = uniqueId()
        def listing = seedListing('SOLD', 'Twice Swept Hat ' + buyerId)
        def offer = seedOffer(listing, buyerId, 'PENDING')

        when:
        service.sweepOffersOnSoldListings()
        service.sweepOffersOnSoldListings()

        then:
        1 * notificationService.push(buyerId, 'OFFER_REJECTED', _, _, _, _)
        0 * notificationService.push(buyerId, *_)
        offerRepository.findById(offer.id).get().status == 'EXPIRED'
    }

    def "a PENDING offer on an ACTIVE listing is left alone"() {
        given:
        long buyerId = uniqueId()
        def listing = seedListing('ACTIVE', 'Active Hat ' + buyerId)
        def offer = seedOffer(listing, buyerId, 'PENDING')

        when:
        service.sweepOffersOnSoldListings()

        then:
        0 * notificationService.push(buyerId, *_)
        offerRepository.findById(offer.id).get().status == 'PENDING'
    }

    def "an already #status offer on a SOLD listing is left alone"() {
        given:
        long buyerId = uniqueId()
        def listing = seedListing('SOLD', 'Closed Offer Hat ' + buyerId)
        def offer = seedOffer(listing, buyerId, status)

        when:
        service.sweepOffersOnSoldListings()

        then:
        0 * notificationService.push(buyerId, *_)
        offerRepository.findById(offer.id).get().status == status

        where:
        status << ['ACCEPTED', 'CANCELLED']
    }

    def "a lost claim (offer changed between read and claim) sends nothing"() {
        given: "the conditional claim reports the row was no longer PENDING"
        OfferRepository racing = Mock()
        service.offerRepository = racing
        def offer = new Offer(id: 77L, listingId: 5L, buyerUserId: 42L, status: 'PENDING',
            amount: new BigDecimal('80.00'), askingPrice: new BigDecimal('100.00'))
        racing.findPendingOnSoldListings() >> [offer]
        racing.claimAutoExpire(77L, _) >> 0

        when:
        service.sweepOffersOnSoldListings()

        then:
        0 * notificationService.push(*_)
        0 * racing.save(_)
    }
}
