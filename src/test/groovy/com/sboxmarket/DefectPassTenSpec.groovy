package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.OfferNotPendingException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.PriceHistoryService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.UserBlockService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 10: offers and counter-offers (sweeps, counters that lapse,
 * accepting a counter short of funds, raises past a block, item links),
 * the public stall during away mode, and cancelled sales in price history.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassTenSpec extends Specification {

    @Autowired OfferService offerService
    @Autowired TradeService tradeService
    @Autowired ListingService listingService
    @Autowired ListingController listingController
    @Autowired UserBlockService userBlockService
    @Autowired PriceHistoryService priceHistoryService
    @Autowired SteamUserRepository userRepo
    @Autowired WalletRepository walletRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired OfferRepository offerRepo
    @Autowired TradeRepository tradeRepo
    @Autowired PriceHistoryRepository historyRepo
    @Autowired NotificationRepository notificationRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())
    static final long DAY = 24L * 60L * 60L * 1000L

    private SteamUser user(Map extra = [:]) {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser([steamId64: '7656120' + tail.padLeft(10, '0'),
            displayName: "Pass10-${seq}-${uniq}", tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'] + extra))
    }

    private Item item(String price = '10.00') {
        itemRepo.save(new Item(name: "Pass10 ${uniq} ${++seq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal(price), isListed: true, iconEmoji: '🎩'))
    }

    private Listing listing(Item it, String price, Map extra = [:]) {
        listingRepo.save(new Listing([item: it, price: new BigDecimal(price), listingType: 'BUY_NOW',
            status: 'ACTIVE', sellerName: 'p10', rarityScore: BigDecimal.ZERO] + extra))
    }

    private Offer offer(Listing l, SteamUser buyer, SteamUser seller, String amount, Map extra = [:]) {
        offerRepo.save(new Offer([listingId: l.id, itemId: l.item.id, buyerUserId: buyer.id, sellerUserId: seller.id,
            amount: new BigDecimal(amount), askingPrice: l.price, status: 'PENDING', buyerName: buyer.displayName,
            itemName: l.item.name] + extra))
    }

    private List notes(Long uid) {
        notificationRepo.findForUser(uid, PageRequest.of(0, 50))
    }

    private MockHttpServletRequest anon() {
        new MockHttpServletRequest()
    }

    // ── Offer sweeps ──────────────────────────────────────────────

    def "the stale-offer sweep actually expires an offer past its window"() {
        given:
        def seller = user(); def buyer = user()
        def l = listing(item(), '10.00', [sellerUserId: seller.id])
        def o = offer(l, buyer, seller, '6.00', [updatedAt: System.currentTimeMillis() - 30 * DAY])

        when:
        offerService.sweepStaleOffers()

        then:
        offerRepo.findById(o.id).get().status == 'EXPIRED'
    }

    def "a lapsed seller counter tells the buyer the counter expired, not their offer"() {
        given:
        def seller = user(); def buyer = user()
        def l = listing(item(), '10.00', [sellerUserId: seller.id])
        offer(l, buyer, seller, '8.00', [author: 'SELLER', updatedAt: System.currentTimeMillis() - 30 * DAY])

        when:
        offerService.sweepStaleOffers()

        then:
        notes(buyer.id)*.title.any { it.startsWith('Counter-offer expired') }
        !notes(buyer.id)*.title.any { it.startsWith('Offer auto-declined') }
    }

    def "the half-life reminder on a seller counter goes to the buyer"() {
        given:
        def seller = user(); def buyer = user()
        def l = listing(item(), '10.00', [sellerUserId: seller.id])
        offer(l, buyer, seller, '8.00', [author: 'SELLER', updatedAt: System.currentTimeMillis() - 4 * DAY])

        when:
        offerService.sweepOffersDueForNudge()

        then:
        notes(buyer.id)*.title.any { it.startsWith('Counter-offer expiring') }
        notes(seller.id).isEmpty()
    }

    // ── Offer actions ─────────────────────────────────────────────

    def "a buyer short of funds keeps the seller's counter open"() {
        given:
        def seller = user(); def buyer = user()
        walletRepo.save(new Wallet(username: "steam_${buyer.steamId64}", balance: new BigDecimal('1.00')))
        def l = listing(item(), '10.00', [sellerUserId: seller.id])
        def counter = offer(l, buyer, seller, '8.00', [author: 'SELLER'])

        when:
        offerService.acceptOffer(buyer.id, counter.id)

        then:
        thrown(InsufficientBalanceException)
        offerRepo.findById(counter.id).get().status == 'PENDING'
    }

    def "a buyer the seller blocked cannot keep raising"() {
        given:
        def seller = user(); def buyer = user()
        def l = listing(item(), '10.00', [sellerUserId: seller.id])
        def o = offer(l, buyer, seller, '5.00')
        userBlockService.block(seller.id, buyer.id)

        when:
        offerService.buyerRaise(buyer.id, o.id, new BigDecimal('6.00'))

        then:
        thrown(ListingNotAvailableException)
        offerRepo.findById(o.id).get().status == 'PENDING'
    }

    def "a seller cannot counter an offer that has already lapsed"() {
        given:
        def seller = user(); def buyer = user()
        def l = listing(item(), '10.00', [sellerUserId: seller.id])
        def o = offer(l, buyer, seller, '5.00', [updatedAt: System.currentTimeMillis() - 30 * DAY])

        when:
        offerService.counterOffer(seller.id, o.id, new BigDecimal('8.00'))

        then:
        thrown(OfferNotPendingException)
    }

    def "offer rows carry the item id the Offers page links to"() {
        given:
        def seller = user(); def buyer = user()
        def it = item()
        offer(listing(it, '10.00', [sellerUserId: seller.id]), buyer, seller, '5.00')

        expect:
        offerService.outgoingWithExpiry(buyer.id)*.itemId == [it.id]
    }

    // ── Public stall ──────────────────────────────────────────────

    def "the away banner counts only what away mode hid"() {
        given: "one listing hidden by hand, one hidden by away mode"
        def seller = user()
        def it = item()
        listing(it, '5.00', [sellerUserId: seller.id, hidden: true])
        listing(it, '6.00', [sellerUserId: seller.id])
        listingService.setAwayMode(seller.id, true, null)

        when:
        def body = listingController.publicStall(seller.id, anon()).body

        then:
        body.away == true
        body.awayCount == 1
    }

    def "an away seller with a bid-on auction still left up reads as away"() {
        given:
        def seller = user()
        def it = item()
        listing(it, '5.00', [sellerUserId: seller.id])
        listing(it, '6.00', [sellerUserId: seller.id, listingType: 'AUCTION', bidCount: 1,
            currentBid: new BigDecimal('6.00'), expiresAt: System.currentTimeMillis() + DAY])
        listingService.setAwayMode(seller.id, true, null)

        when:
        def body = listingController.publicStall(seller.id, anon()).body

        then:
        body.away == true
        body.awayCount == 1
        body.count == 1
    }

    def "Last listed ignores listings the stall doesn't show"() {
        given:
        def seller = user()
        def it = item()
        listing(it, '5.00', [sellerUserId: seller.id, listedAt: System.currentTimeMillis() - 5 * DAY])
        listing(it, '6.00', [sellerUserId: seller.id, hidden: true, listedAt: System.currentTimeMillis()])

        expect:
        listingRepo.findLastListedAtBySeller(seller.id) < System.currentTimeMillis() - 4 * DAY
    }

    // ── Price history ─────────────────────────────────────────────

    def "a cancelled sale comes back off the item's price chart"() {
        given: "a sale charted at an outlier price"
        def seller = user(); def buyer = user()
        def it = item()
        def l = listing(it, '99.00', [sellerUserId: seller.id, status: 'SOLD', buyerUserId: buyer.id,
            soldAt: System.currentTimeMillis()])
        priceHistoryService.record(it, new BigDecimal('99.00'), 1)
        def t = tradeRepo.save(new Trade(buyerUserId: buyer.id, sellerUserId: seller.id, itemName: it.name,
            itemId: it.id, listingId: l.id, price: new BigDecimal('99.00'), state: 'PENDING_SELLER_SEND'))

        when:
        tradeService.cancel(seller.id, t.id, 'out of stock')

        then:
        historyRepo.findByItemIdOrdered(it.id).isEmpty()
    }
}
