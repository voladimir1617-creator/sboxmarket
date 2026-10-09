package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Bid
import com.sboxmarket.model.CartItem
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.model.WatchlistItem
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.CartItemRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import com.sboxmarket.repository.WatchlistItemRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.BidService
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.PriceHistoryService
import com.sboxmarket.service.SteamMarketPriceService
import com.sboxmarket.service.SupportService
import com.sboxmarket.service.WatchlistService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 9: support tickets (the stale-ticket sweep, reopen cap,
 * admin close and reply pings), the watchlist "Clear all", away mode
 * against hand-hidden listings, hidden price cuts, auctions that end
 * without a sale, the My Bids lists, and the Steam price sync.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassNineSpec extends Specification {

    @Autowired SupportService supportService
    @Autowired AdminService adminService
    @Autowired WatchlistService watchlistService
    @Autowired ListingService listingService
    @Autowired ListingController listingController
    @Autowired BidService bidService
    @Autowired SteamUserRepository userRepo
    @Autowired SupportTicketRepository ticketRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired BidRepository bidRepo
    @Autowired CartItemRepository cartRepo
    @Autowired WatchlistAlertRepository alertRepo
    @Autowired WatchlistItemRepository watchlistItemRepo
    @Autowired NotificationRepository notificationRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String role = 'USER', Map extra = [:]) {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser([steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "Pass9-${role}-${seq}-${uniq}", role: role] + extra))
    }

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req.session = session
        req
    }

    private Item item(String price) {
        itemRepo.save(new Item(name: "Pass9 ${uniq} ${++seq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal(price), isListed: true, iconEmoji: '🎩'))
    }

    private Listing listing(Item it, String price, Map extra = [:]) {
        listingRepo.save(new Listing([item: it, price: new BigDecimal(price), listingType: 'BUY_NOW',
            status: 'ACTIVE', sellerName: 'p9', rarityScore: BigDecimal.ZERO] + extra))
    }

    private List kindsFor(Long uid) {
        notificationRepo.findForUser(uid, PageRequest.of(0, 50))*.kind
    }

    // ── Support tickets ───────────────────────────────────────────

    def "the daily sweep closes a ticket left waiting on the user"() {
        given:
        def u = user()
        def old = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        def t = ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName, subject: 'p9 stale',
            status: 'WAITING_USER', createdAt: old, updatedAt: old))

        when:
        supportService.sweepStaleWaitingUser()

        then:
        ticketRepo.findById(t.id).get().status == 'RESOLVED'
    }

    def "reopening is refused once the user is at the open-ticket cap"() {
        given:
        def u = user()
        SupportService.MAX_OPEN_TICKETS_PER_USER.times {
            ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName, subject: "p9 open ${it}", status: 'OPEN'))
        }
        def resolved = ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName, subject: 'p9 done', status: 'RESOLVED'))

        when:
        supportService.reopen(u.id, resolved.id)

        then:
        def e = thrown(BadRequestException)
        e.code == 'TOO_MANY_OPEN_TICKETS'
        ticketRepo.findById(resolved.id).get().status == 'RESOLVED'
    }

    def "an admin closing a ticket tells its owner"() {
        given:
        def admin = user('ADMIN')
        def u = user()
        def t = ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName, subject: 'p9 close', status: 'WAITING_STAFF'))

        when:
        adminService.closeTicket(admin.id, t.id)

        then:
        kindsFor(u.id).contains('TICKET_CLOSED')
    }

    def "an admin reply to a banned owner sends no bell"() {
        given:
        def admin = user('ADMIN')
        def u = user('USER', [banned: true])
        def t = ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName, subject: 'p9 banned', status: 'WAITING_STAFF'))

        when:
        adminService.staffReply(admin.id, t.id, 'hello')

        then:
        !kindsFor(u.id).contains('SUPPORT_REPLY')
    }

    // ── Watchlist ─────────────────────────────────────────────────

    def "clearing the watchlist cancels the price alerts on those items"() {
        given:
        def u = user()
        def it = item('9.00')
        watchlistItemRepo.save(new WatchlistItem(userId: u.id, itemId: it.id))
        def alert = alertRepo.save(new WatchlistAlert(userId: u.id, itemId: it.id, targetPrice: new BigDecimal('1.00')))

        when:
        watchlistService.clear(u.id)

        then:
        alertRepo.findById(alert.id).get().status == 'CANCELLED'
    }

    // ── Away mode ─────────────────────────────────────────────────

    def "turning away mode off leaves a listing the seller hid by hand hidden"() {
        given:
        def seller = user()
        def it = item('5.00')
        def handHidden = listing(it, '5.00', [sellerUserId: seller.id, hidden: true])
        def shown = listing(it, '6.00', [sellerUserId: seller.id])

        when:
        listingService.setAwayMode(seller.id, true, null)
        listingService.setAwayMode(seller.id, false, null)

        then:
        listingRepo.findById(handHidden.id).get().hidden == true
        listingRepo.findById(shown.id).get().hidden == false
    }

    def "one hand-hidden listing does not read as away mode"() {
        given:
        def seller = user()
        def it = item('5.00')
        listing(it, '5.00', [sellerUserId: seller.id, hidden: true])
        listing(it, '6.00', [sellerUserId: seller.id])

        expect:
        listingController.awayState(signedIn(seller.id)).body.hidden == false
    }

    def "a price cut on a hidden listing does not ping cart holders"() {
        given:
        def seller = user()
        def shopper = user()
        def it = item('8.00')
        def l = listing(it, '8.00', [sellerUserId: seller.id, hidden: true])
        cartRepo.save(new CartItem(userId: shopper.id, listingId: l.id))

        when:
        listingController.updateStall(l.id, [price: '5.00'], signedIn(seller.id))

        then:
        !kindsFor(shopper.id).contains('PRICE_DROPPED')
    }

    // ── Auctions ──────────────────────────────────────────────────

    def "a house auction that ends with no bids is not recorded as a sale"() {
        given:
        def it = item('5.00')
        def l = listing(it, '5.00', [listingType: 'AUCTION', expiresAt: System.currentTimeMillis() - 1000])

        when:
        bidService.settle(l)

        then:
        listingRepo.findById(l.id).get().status == 'EXPIRED'
    }

    def "My Bids shows one row per auction"() {
        given:
        def bidder = user()
        def it = item('5.00')
        def l = listing(it, '5.00', [listingType: 'AUCTION', expiresAt: System.currentTimeMillis() + 3_600_000])
        def now = System.currentTimeMillis()
        bidRepo.save(new Bid(listingId: l.id, bidderUserId: bidder.id, bidderName: 'b', amount: new BigDecimal('5.00'), status: 'OUTBID', createdAt: now - 2000))
        bidRepo.save(new Bid(listingId: l.id, bidderUserId: bidder.id, bidderName: 'b', amount: new BigDecimal('6.00'), status: 'WINNING', createdAt: now - 1000))
        def ended = listing(it, '5.00', [listingType: 'AUCTION', status: 'SOLD'])
        bidRepo.save(new Bid(listingId: ended.id, bidderUserId: bidder.id, bidderName: 'b', amount: new BigDecimal('5.00'), status: 'LOST', createdAt: now - 2000))
        bidRepo.save(new Bid(listingId: ended.id, bidderUserId: bidder.id, bidderName: 'b', amount: new BigDecimal('7.00'), status: 'LOST', createdAt: now - 1000))

        when:
        def live = bidService.liveBidsForUser(bidder.id)
        def past = bidService.pastBidsForUser(bidder.id)

        then:
        live.size() == 1
        live[0].status == 'WINNING'
        past.size() == 1
        past[0].amount == new BigDecimal('7.00')
    }

    // ── Steam price sync ──────────────────────────────────────────

    def "the Steam sync saves over the current row, not the copy it loaded"() {
        given: "the loop loaded the item before a sale bumped totalSold"
        def stale = new Item(id: 77L, name: 'p9 synced', category: 'Hats', rarity: 'Standard', isListed: false,
            lowestPrice: new BigDecimal('5.00'), totalSold: 0, trendPercent: 0)
        def current = new Item(id: 77L, name: 'p9 synced', category: 'Hats', rarity: 'Standard', isListed: false,
            lowestPrice: new BigDecimal('5.00'), totalSold: 7, trendPercent: 0)
        ItemRepository repo = Mock()
        repo.findAll() >> [stale]
        repo.findById(77L) >> Optional.of(current)
        def svc = Spy(SteamMarketPriceService) {
            fetchSteamPrice(_, _) >> [lowest: new BigDecimal('4.00'), median: new BigDecimal('4.10')]
        }
        svc.itemRepository = repo
        svc.priceHistoryService = Mock(PriceHistoryService)

        when:
        svc.syncPricesFromSteamBody()

        then:
        1 * repo.save({ it.totalSold == 7 }) >> { args -> args[0] }
    }
}
