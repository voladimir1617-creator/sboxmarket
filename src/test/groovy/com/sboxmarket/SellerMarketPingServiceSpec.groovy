package com.sboxmarket

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SellerMarketPingService
import com.sboxmarket.service.UserBlockService
import spock.lang.Specification
import spock.lang.Subject

class SellerMarketPingServiceSpec extends Specification {

    ListingRepository      listingRepository      = Mock()
    NotificationService    notificationService    = Mock()
    NotificationRepository notificationRepository = Mock()
    SteamUserRepository    steamUserRepository    = Mock()
    UserBlockService       userBlockService       = Mock()

    @Subject
    SellerMarketPingService service = new SellerMarketPingService(
        listingRepository:      listingRepository,
        notificationService:    notificationService,
        notificationRepository: notificationRepository,
        steamUserRepository:    steamUserRepository,
        userBlockService:       userBlockService
    )

    Item item = new Item(id: 7L, name: 'Red Cap')

    private Listing listing(long id, long seller, String price, Map extra = [:]) {
        new Listing([id: id, item: item, sellerUserId: seller, price: new BigDecimal(price),
                     status: 'ACTIVE', hidden: false, listingType: 'BUY_NOW'] + extra)
    }

    Set<Long> bannedIds = [] as Set
    Set<Long> pingedToday = [] as Set

    def setup() {
        steamUserRepository.findAllById(_) >> { args -> args[0].collect { new SteamUser(id: it, banned: it in bannedIds) } }
        notificationRepository.countRecentByKindAndRef(*_) >> { args -> args[0] in pingedToday ? 1L : 0L }
    }

    // ── buy order placed ────────────────────────────────────────

    def "a buy order pings each seller priced above the bid, once per seller, with the item path"() {
        given:
        listingRepository.findActiveForItemIds([7L]) >> [
            listing(1, 20, '9.00'), listing(2, 21, '12.00'), listing(3, 21, '13.00'), listing(4, 22, '15.00')
        ]
        def order = new BuyOrder(id: 5L, buyerUserId: 10L, itemId: 7L, itemName: 'Red Cap',
            maxPrice: new BigDecimal('10.00'), quantity: 1, status: 'ACTIVE')

        when:
        int sent = service.notifyBuyOrderPlaced(order)

        then:
        sent == 2
        1 * notificationService.push(21L, 'BUY_ORDER_ON_YOUR_ITEM', 'A buyer wants Red Cap at $10.00', _, 7L, '/item/7')
        1 * notificationService.push(22L, 'BUY_ORDER_ON_YOUR_ITEM', _, _, 7L, '/item/7')
        0 * notificationService.push(20L, *_)
    }

    def "the buyer's own listing, a filled order and a category-only order ping nobody"() {
        given:
        listingRepository.findActiveForItemIds([7L]) >> [listing(1, 10, '20.00')]

        when:
        service.notifyBuyOrderPlaced(new BuyOrder(buyerUserId: 10L, itemId: 7L, maxPrice: 5G, quantity: 1, status: 'ACTIVE'))
        service.notifyBuyOrderPlaced(new BuyOrder(buyerUserId: 11L, itemId: 7L, maxPrice: 5G, quantity: 0, status: 'FILLED'))
        service.notifyBuyOrderPlaced(new BuyOrder(buyerUserId: 11L, itemId: null, category: 'Hats', maxPrice: 5G, quantity: 1, status: 'ACTIVE'))

        then:
        0 * notificationService.push(*_)
    }

    def "a seller who blocked the buyer, a banned seller and a seller pinged today are skipped"() {
        given:
        listingRepository.findActiveForItemIds([7L]) >> [listing(1, 30, '20'), listing(2, 31, '20'), listing(3, 32, '20'), listing(4, 33, '20')]
        userBlockService.isBlocked(30L, 10L) >> true
        userBlockService.isBlocked(_, _) >> false
        bannedIds << 31L
        pingedToday << 32L

        when:
        int sent = service.notifyBuyOrderPlaced(new BuyOrder(buyerUserId: 10L, itemId: 7L, maxPrice: 5G, quantity: 1, status: 'ACTIVE'))

        then:
        sent == 1
        1 * notificationService.push(33L, *_)
        0 * notificationService.push(30L, *_)
        0 * notificationService.push(31L, *_)
        0 * notificationService.push(32L, *_)
    }

    def "a repository failure never reaches the caller"() {
        given:
        listingRepository.findActiveForItemIds(_) >> { throw new RuntimeException('db down') }

        expect:
        service.notifyBuyOrderPlaced(new BuyOrder(buyerUserId: 1L, itemId: 7L, maxPrice: 5G, quantity: 1, status: 'ACTIVE')) == 0
        service.notifyUndercut(listing(9, 1, '3')) == 0
    }

    // ── undercut ────────────────────────────────────────────────

    def "a cheaper listing pings the other sellers priced above it, not itself or its own seller"() {
        given:
        def fresh = listing(9, 40, '8.00')
        listingRepository.findActiveForItemIds([7L]) >> [
            fresh, listing(1, 41, '7.00'), listing(2, 42, '8.00'), listing(3, 43, '9.50'), listing(4, 40, '12.00'),
            listing(5, 44, '20.00', [listingType: 'AUCTION'])
        ]

        when:
        int sent = service.notifyUndercut(fresh)

        then:
        sent == 1
        1 * notificationService.push(43L, 'LISTING_UNDERCUT', 'A cheaper Red Cap just went on sale',
            'Another seller listed it at $8.00, below your price.', 7L, '/item/7')
        0 * notificationService.push(40L, *_)
        0 * notificationService.push(41L, *_)
        0 * notificationService.push(42L, *_)
        0 * notificationService.push(44L, *_)
    }

    def "a listing that is not yet buyable or is an auction pings nobody"() {
        when:
        service.notifyUndercut(listing(9, 40, '1', [status: 'PENDING_ESCROW']))
        service.notifyUndercut(listing(9, 40, '1', [hidden: true]))
        service.notifyUndercut(listing(9, 40, '1', [listingType: 'AUCTION']))

        then:
        0 * listingRepository.findActiveForItemIds(_)
        0 * notificationService.push(*_)
    }

    def "the fanout stops at FANOUT_MAX sellers"() {
        given:
        def fresh = listing(1, 1, '1')
        def many = (2..(SellerMarketPingService.FANOUT_MAX + 50)).collect { listing(it, it, '5') }
        listingRepository.findActiveForItemIds([7L]) >> ([fresh] + many)

        expect:
        service.notifyUndercut(fresh) == SellerMarketPingService.FANOUT_MAX
    }
}
