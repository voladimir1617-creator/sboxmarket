package com.sboxmarket

import com.sboxmarket.controller.BuyOrderController
import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import com.sboxmarket.repository.WatchlistItemRepository
import com.sboxmarket.service.ApiKeyService
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.SellService
import com.sboxmarket.service.SteamAuthService
import com.sboxmarket.service.SupportService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.WatchlistAlertService
import com.sboxmarket.service.WatchlistService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 8: trade and buy-order history exports, buy-order edits,
 * the watchlist merge, price alerts (auction floors, relist timing,
 * restock wording), staff ticket links, and profile settings (email,
 * 2FA enrolment, stall bio, API keys for banned users, invisible names).
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassEightSpec extends Specification {

    @Autowired ProfileController profileController
    @Autowired BuyOrderController buyOrderController
    @Autowired BuyOrderService buyOrderService
    @Autowired WatchlistService watchlistService
    @Autowired WatchlistAlertService watchlistAlertService
    @Autowired SellService sellService
    @Autowired SupportService supportService
    @Autowired ApiKeyService apiKeyService
    @Autowired SteamUserRepository userRepo
    @Autowired TradeRepository tradeRepo
    @Autowired BuyOrderRepository buyOrderRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired WatchlistAlertRepository alertRepo
    @Autowired WatchlistItemRepository watchlistItemRepo
    @Autowired NotificationRepository notificationRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String role = 'USER', Map extra = [:]) {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser([steamId64: '7656118' + tail.padLeft(10, '0'),
            displayName: "Pass8-${role}-${seq}-${uniq}", role: role] + extra))
    }

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req.session = session
        req
    }

    private Item item(String price) {
        itemRepo.save(new Item(name: "Pass8 ${uniq} ${++seq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal(price), isListed: true, iconEmoji: '🎩'))
    }

    private Listing listing(Item it, String price, Map extra = [:]) {
        listingRepo.save(new Listing([item: it, price: new BigDecimal(price), listingType: 'BUY_NOW',
            status: 'ACTIVE', sellerName: 'p8', rarityScore: BigDecimal.ZERO] + extra))
    }

    private List notificationsFor(Long uid) {
        notificationRepo.findForUser(uid, PageRequest.of(0, 50))
    }

    // ── Trade and buy-order history ───────────────────────────────

    def "the trades CSV for the open chip lists pending and disputed trades"() {
        given:
        def buyer = user()
        def it = item('5.00')
        def l = listing(it, '5.00', [status: 'SOLD'])
        ['PENDING_SELLER_SEND', 'DISPUTED', 'VERIFIED', 'CANCELLED'].each { st ->
            tradeRepo.save(new Trade(buyerUserId: buyer.id, sellerUserId: 999_999L, itemName: "p8-${st}",
                itemId: it.id, listingId: l.id, price: new BigDecimal('5.00'), state: st))
        }

        when:
        def csv = profileController.exportTradesCsv(null, null, 'OPEN', null, signedIn(buyer.id)).body

        then:
        csv.contains('p8-PENDING_SELLER_SEND')
        csv.contains('p8-DISPUTED')
        !csv.contains('p8-VERIFIED')
        !csv.contains('p8-CANCELLED')
    }

    def "lowering an unfilled buy order's quantity does not read as fills"() {
        given:
        def buyer = user()
        def o = buyOrderRepo.save(new BuyOrder(buyerUserId: buyer.id, maxPrice: new BigDecimal('5.00'),
            quantity: 5, originalQuantity: 5, status: 'ACTIVE', itemName: 'p8 order'))

        when:
        def saved = buyOrderService.update(buyer.id, o.id, null, 2)

        then:
        saved.quantity == 2
        saved.originalQuantity == 2
    }

    def "a partly filled order keeps its fills when its quantity is lowered"() {
        given: "5 placed, 3 filled, 2 still open"
        def buyer = user()
        def o = buyOrderRepo.save(new BuyOrder(buyerUserId: buyer.id, maxPrice: new BigDecimal('5.00'),
            quantity: 2, originalQuantity: 5, status: 'ACTIVE', itemName: 'p8 order'))

        when:
        def saved = buyOrderService.update(buyer.id, o.id, null, 1)

        then: "3 filled, 1 open"
        saved.quantity == 1
        saved.originalQuantity - saved.quantity == 3
    }

    def "the buy-order CSV export is not cut at the list's 300-row cap"() {
        given:
        def buyer = user()
        (1..BuyOrderService.BUY_ORDER_LIST_CAP + 5).each { i ->
            buyOrderRepo.save(new BuyOrder(buyerUserId: buyer.id, maxPrice: new BigDecimal('1.00'),
                quantity: 1, originalQuantity: 1, status: 'CANCELLED', itemName: "p8 row ${i}",
                createdAt: 1_000_000L + i))
        }

        when:
        def csv = buyOrderController.exportCsv(null, signedIn(buyer.id)).body
        def rows = csv.readLines().findAll { it.contains('p8 row ') }

        then:
        rows.size() == BuyOrderService.BUY_ORDER_LIST_CAP + 5
    }

    // ── Watchlist and alerts ──────────────────────────────────────

    def "a guest watchlist holding a deleted item id still merges the rest"() {
        given:
        def u = user()
        def real = item('3.00')

        when:
        def ids = watchlistService.bulkMerge(u.id, [real.id, 987_654_321L])

        then: "no row is written for the dead id (production's items FK would fail the whole merge)"
        ids.contains(real.id)
        !ids.contains(987_654_321L)
        watchlistItemRepo.findExistingItemIds(u.id, [987_654_321L]).isEmpty()
    }

    def "an auction with bids counts at its current bid in the item floor"() {
        given:
        def it = item('50.00')
        listing(it, '1.00', [listingType: 'AUCTION', currentBid: new BigDecimal('50.00')])
        listing(it, '60.00')

        expect:
        listingRepo.minPriceForItem(it.id) == new BigDecimal('50.00')
    }

    def "relisting below an alert target fires the alert right away"() {
        given:
        def watcher = user()
        def seller = user()
        def it = item('15.00')
        listing(it, '15.00')
        def owned = listing(it, '12.00', [status: 'SOLD', buyerUserId: seller.id])
        def alert = alertRepo.save(new WatchlistAlert(userId: watcher.id, itemId: it.id,
            targetPrice: new BigDecimal('10.00')))

        when:
        sellService.relist(seller.id, seller.displayName, owned.id, new BigDecimal('8.00'))

        then:
        alertRepo.findById(alert.id).get().status == 'FIRED'
    }

    def "a restock alert never quotes its internal target"() {
        given:
        def watcher = user()
        def it = item('3.20')
        listing(it, '3.20')
        alertRepo.save(new WatchlistAlert(userId: watcher.id, itemId: it.id,
            targetPrice: new BigDecimal('100000.00')))

        when:
        watchlistAlertService.sweepForItem(it.id)
        def n = notificationsFor(watcher.id).find { it.kind == 'WATCHLIST_PRICE_DROP' }

        then:
        n != null
        n.title.startsWith('Restock')
        !n.body.contains('100000')
    }

    // ── Staff ticket links ────────────────────────────────────────

    def "a new ticket ping sends a CSR to the CSR console, not /admin"() {
        given:
        def csr = user('CSR')
        def admin = user('ADMIN')
        def customer = user()

        when:
        def t = supportService.create(customer.id, customer.displayName, "p8 ticket ${uniq}", 'OTHER', 'help please')
        def csrPing = notificationsFor(csr.id).find { it.refId == t.id }
        def adminPing = notificationsFor(admin.id).find { it.refId == t.id }

        then:
        csrPing.path == '/csr?tab=tickets'
        adminPing.path == '/admin?tab=tickets'
    }

    // ── Profile settings ──────────────────────────────────────────

    def "a long valid email is stored whole"() {
        given:
        def u = user()
        def email = ('a' * 40) + '@' + ('b' * 36) + '.com'

        when:
        profileController.setEmail([email: email], signedIn(u.id))

        then:
        userRepo.findById(u.id).get().email == email
    }

    def "an expired email link does not block turning on 2FA"() {
        given:
        def u = user('USER', [email: 'p8@example.com', emailVerificationToken: 'stale-token',
            emailVerificationTokenExpiresAt: System.currentTimeMillis() - 1000L])

        when:
        def res = profileController.enroll2fa(signedIn(u.id)).body

        then:
        res.secret
        userRepo.findById(u.id).get().emailVerificationToken.startsWith('totp_pending:')
    }

    def "the stall bio keeps its line breaks"() {
        given:
        def u = user()

        when:
        profileController.setStallBio([bio: "Fast trades.\nOnline 6-11pm"], signedIn(u.id))

        then:
        userRepo.findById(u.id).get().stallBio == "Fast trades.\nOnline 6-11pm"
    }

    def "a banned user cannot mint a new API key"() {
        given:
        def u = user('USER', [banned: true])

        when:
        apiKeyService.create(u.id, 'bot')

        then:
        thrown(ForbiddenException)
    }

    def "an invisible Steam name falls back to the player placeholder"() {
        given:
        def svc = new SteamAuthService(textSanitizer: new TextSanitizer())

        expect:
        svc.sanitizeName('ㅤㅤ', '76561198000123456') == 'Player_123456'
        svc.sanitizeName('​ ​', '76561198000123456') == 'Player_123456'
        svc.sanitizeName('Real Name', '76561198000123456') == 'Real Name'
    }
}
