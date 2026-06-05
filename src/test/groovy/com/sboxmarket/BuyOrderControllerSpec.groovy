package com.sboxmarket

import com.sboxmarket.controller.BuyOrderController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.dto.request.CreateBuyOrderRequest
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.BuyOrderService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the standing-buy-order endpoints. The ones that get
 * extra attention:
 *
 *   - `mine()` row enrichment: floor price, floor-gap, and the
 *     "queuePosition" projection that only fires for ACTIVE + item-
 *     pinned orders. Basket orders (category/rarity only, no itemId)
 *     get `queuePosition: null` so the SPA chip hides cleanly.
 *
 *   - `update()` field validation codes (INVALID_PRICE /
 *     INVALID_QUANTITY). These are documented client contracts.
 *
 *   - `countBulk()` parser — same family as WatchlistController's
 *     bulkCounts / SellerStatsController's verifiedBulk — 200-cap,
 *     malformed-token skip, empty-map short-circuit.
 *
 *   - Public-aggregate endpoints all carry `public, max-age=60` so
 *     the homepage + item modal reads are CDN-cacheable.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class BuyOrderControllerSpec extends Specification {

    BuyOrderService     buyOrderService     = Mock()
    SteamUserRepository steamUserRepository = Mock()
    ItemRepository      itemRepository      = Mock()

    @Subject
    BuyOrderController controller = new BuyOrderController(
        buyOrderService    : buyOrderService,
        steamUserRepository: steamUserRepository,
        itemRepository     : itemRepository
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void anonSession() {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null
    }
    private void authedSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    // ── mine() row enrichment ──────────────────────────────────

    def "mine() requires sign-in"() {
        given: anonSession()
        when:  controller.mine(req)
        then:  thrown(UnauthorizedException)
        0 * buyOrderService.listForBuyer(_)
    }

    def "mine() enriches an ACTIVE item-pinned order with floor, gap, and queuePosition"() {
        given:
        def o = new BuyOrder(id: 1L, itemId: 42L, itemName: 'Hat',
                             category: 'Hats', rarity: 'Standard',
                             maxPrice: new BigDecimal('45.00'), quantity: 2,
                             originalQuantity: 3, status: 'ACTIVE',
                             createdAt: 1700L, updatedAt: 1700L)
        def item = new Item(id: 42L, lowestPrice: new BigDecimal('50.00'))
        authedSession(100L)
        1 * buyOrderService.listForBuyer(100L) >> [o]
        1 * itemRepository.findAllById([42L]) >> [item]
        // Batched queue rank: 2 orders ahead of mine (45.00 @ 1700) — one
        // priced higher (50.00) + one same-price-but-earlier (45.00 @ 1600).
        1 * buyOrderService.activeQueueRowsForItems([42L]) >> [
            [42L, new BigDecimal('50.00'), 1L] as Object[],
            [42L, new BigDecimal('45.00'), 1600L] as Object[]
        ]
        1 * buyOrderService.countForBuyer(100L) >> 1L

        when:
        def resp = controller.mine(req)

        then:
        resp.headers.getFirst('X-Total-Count') == '1'
        def row = resp.body[0]
        row.id == 1L
        row.currentFloor == new BigDecimal('50.00')
        row.floorGap == new BigDecimal('5.00')      // 50 − 45
        row.queuePosition == 3L                      // 2 ahead + 1
        row.originalQuantity == 3
    }

    def "mine() returns queuePosition:null for a basket (category-only) order"() {
        given:
        def o = new BuyOrder(id: 2L, itemId: null, category: 'Hats',
                             rarity: 'Limited', maxPrice: new BigDecimal('20.00'),
                             quantity: 1, status: 'ACTIVE',
                             createdAt: 1700L, updatedAt: 1700L)
        authedSession(100L)
        1 * buyOrderService.listForBuyer(100L) >> [o]
        // no itemRepository.findAllById call — itemIds is empty
        0 * itemRepository.findAllById(_)
        0 * buyOrderService.activeQueueRowsForItems(_)   // empty itemIds → not called
        1 * buyOrderService.countForBuyer(100L) >> 1L

        when:
        def resp = controller.mine(req)

        then:
        resp.body[0].currentFloor == null
        resp.body[0].floorGap == null
        resp.body[0].queuePosition == null
    }

    def "mine() returns queuePosition:null for a CANCELLED / FILLED order even with itemId"() {
        given:
        def o = new BuyOrder(id: 3L, itemId: 42L, maxPrice: new BigDecimal('10'),
                             status: 'CANCELLED', createdAt: 1L, updatedAt: 1L)
        def item = new Item(id: 42L, lowestPrice: new BigDecimal('9'))
        authedSession(100L)
        1 * buyOrderService.listForBuyer(100L) >> [o]
        1 * itemRepository.findAllById([42L]) >> [item]
        1 * buyOrderService.activeQueueRowsForItems([42L]) >> []  // fetched once; per-row status gate skips ranking
        1 * buyOrderService.countForBuyer(100L) >> 1L

        when:
        def resp = controller.mine(req)

        then: 'floor is shown for context, but no queue position on a dead order'
        resp.body[0].currentFloor == new BigDecimal('9')
        resp.body[0].queuePosition == null
    }

    def "mine() handles a negative gap (order already above floor)"() {
        given:
        def o = new BuyOrder(id: 4L, itemId: 42L, maxPrice: new BigDecimal('60'),
                             status: 'ACTIVE', createdAt: 1L, updatedAt: 1L)
        def item = new Item(id: 42L, lowestPrice: new BigDecimal('50'))
        authedSession(100L)
        1 * buyOrderService.listForBuyer(100L) >> [o]
        1 * itemRepository.findAllById([42L]) >> [item]
        1 * buyOrderService.activeQueueRowsForItems([42L]) >> []  // 0 ahead → queuePosition 1
        1 * buyOrderService.countForBuyer(100L) >> 1L

        when:
        def resp = controller.mine(req)

        then: 'negative gap surfaces so the UI can signal "should match"'
        resp.body[0].floorGap == new BigDecimal('-10')
    }

    // ── public aggregate endpoints ────────────────────────────

    def "topActive() clamps limit to 1..20 and carries 60s public cache"() {
        given:
        int seenLim = -1
        1 * buyOrderService.listTopActive(_) >> { args ->
            seenLim = args[0]
            []
        }

        when:
        def resp = controller.topActive(500)

        then:
        seenLim == 20
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('public')
        cc?.contains('max-age=60')
    }

    def "topActive() defaults to 8 rows when limit is null"() {
        given:
        int seenLim = -1
        1 * buyOrderService.listTopActive(_) >> { args ->
            seenLim = args[0]
            []
        }

        when:
        controller.topActive(null)

        then:
        seenLim == 8
    }

    def "topActive() with limit=0 honours the explicit-0 request (clamped to 1, NOT the 8 default)"() {
        // Regression — `?limit=0` previously hit Groovy's Elvis-on-zero
        // (`limit ?: 8` treats Integer 0 as falsy and substitutes the
        // default), so the caller asking for "as few as possible" was
        // silently handed 8 rows. Now: explicit null-check, so 0 flows
        // through and gets the controller's documented `Math.max(_, 1)`
        // floor (= 1 row), matching every sibling endpoint touched by
        // the 75678e1 / 0d15de2 / ccfe0b5 / 4e1a0d4 / 8224a9b family.
        given:
        int seenLim = -1
        1 * buyOrderService.listTopActive(_) >> { args ->
            seenLim = args[0]
            []
        }

        when:
        controller.topActive(0)

        then:
        seenLim == 1
    }

    def "countForItem() returns {itemId, count, bestBid} — best null when zero"() {
        given:
        1 * buyOrderService.countActiveForItem(42L) >> 0L
        1 * buyOrderService.bestBidForItem(42L) >> BigDecimal.ZERO

        when:
        def resp = controller.countForItem(42L)

        then:
        resp.body == [itemId: 42L, count: 0L, bestBid: null]
    }

    def "countForItem() surfaces bestBid when positive"() {
        given:
        1 * buyOrderService.countActiveForItem(42L) >> 5L
        1 * buyOrderService.bestBidForItem(42L) >> new BigDecimal('99.99')

        when:
        def resp = controller.countForItem(42L)

        then:
        resp.body == [itemId: 42L, count: 5L, bestBid: new BigDecimal('99.99')]
    }

    def "forItem() clamps limit to 1..20 and carries 60s public cache"() {
        given:
        int seenLim = -1
        1 * buyOrderService.listActiveForItem(42L, _) >> { args ->
            seenLim = args[1]
            []
        }

        when:
        def resp = controller.forItem(42L, 500)

        then:
        seenLim == 20
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('public')
        cc?.contains('max-age=60')
    }

    def "forItem() defaults to 10 rows when limit is null"() {
        given:
        int seenLim = -1
        1 * buyOrderService.listActiveForItem(42L, _) >> { args ->
            seenLim = args[1]
            []
        }

        when:
        controller.forItem(42L, null)

        then:
        seenLim == 10
    }

    // ── countBulk() parser ────────────────────────────────────

    def "countBulk() returns {} on null ids"() {
        when:
        def resp = controller.countBulk(null)

        then:
        0 * buyOrderService.bulkDemandByItemIds(_)
        resp.body == [:]
    }

    def "countBulk() returns {} on empty string ids"() {
        when:
        def resp = controller.countBulk('')

        then:
        0 * buyOrderService.bulkDemandByItemIds(_)
        resp.body == [:]
    }

    def "countBulk() parses valid tokens + skips malformed + negatives"() {
        given:
        List<Long> captured = null
        1 * buyOrderService.bulkDemandByItemIds(_) >> { args ->
            captured = args[0]
            [1L: [count: 3L, bestBid: new BigDecimal('5')]]
        }

        when:
        controller.countBulk('1,abc,-5,')

        then:
        captured == [1L]
    }

    def "countBulk() caps input at 200"() {
        given: 'request with 250 valid ids'
        def lots = (1..250).collect { String.valueOf(it) }.join(',')
        List<Long> captured = null
        1 * buyOrderService.bulkDemandByItemIds(_) >> { args ->
            captured = args[0]
            [:]
        }

        when:
        controller.countBulk(lots)

        then:
        captured.size() == 200
    }

    def "countBulk() returns {} when every token is malformed (no service call)"() {
        when:
        def resp = controller.countBulk('abc,xyz,')

        then:
        0 * buyOrderService.bulkDemandByItemIds(_)
        resp.body == [:]
    }

    // ── projectedPosition() ───────────────────────────────────

    def "projectedPosition() returns null position on invalid inputs"() {
        expect:
        controller.projectedPosition(null, new BigDecimal('5')).body.position == null
        controller.projectedPosition(42L, null).body.position == null
        controller.projectedPosition(42L, BigDecimal.ZERO).body.position == null
        controller.projectedPosition(42L, new BigDecimal('-1')).body.position == null
    }

    def "projectedPosition() returns position = countAhead + 1"() {
        given:
        1 * buyOrderService.countAheadInQueue(42L, new BigDecimal('10'), { it > 0L }) >> 4L

        when:
        def resp = controller.projectedPosition(42L, new BigDecimal('10'))

        then:
        resp.body.position == 5L
        resp.body.itemId == 42L
        resp.body.maxPrice == new BigDecimal('10')
    }

    // ── create / cancel / cancelAll ───────────────────────────

    def "create() requires sign-in"() {
        given: anonSession()
        def body = new CreateBuyOrderRequest(itemId: 1L, maxPrice: new BigDecimal('5'), quantity: 1)

        when:
        controller.create(body, req)

        then:
        thrown(UnauthorizedException)
        0 * buyOrderService.create(_, _, _, _, _, _, _)
    }

    def "create() falls back to 'Player' when user displayName is null"() {
        given:
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(new SteamUser(id: 100L, displayName: null))
        1 * buyOrderService.create(100L, 'Player', 1L, null, null, new BigDecimal('5'), 1) >> new BuyOrder()
        def body = new CreateBuyOrderRequest(itemId: 1L, maxPrice: new BigDecimal('5'), quantity: 1)

        when:
        controller.create(body, req)

        then:
        true
    }

    def "create() defaults quantity to 1 when omitted"() {
        given:
        authedSession(100L)
        1 * steamUserRepository.findById(100L) >> Optional.of(new SteamUser(id: 100L, displayName: 'a'))
        1 * buyOrderService.create(100L, 'a', 1L, null, null, new BigDecimal('5'), 1) >> new BuyOrder()
        def body = new CreateBuyOrderRequest(itemId: 1L, maxPrice: new BigDecimal('5'), quantity: null)

        when:
        controller.create(body, req)

        then:
        true
    }

    def "cancel() returns {id, status} envelope"() {
        given:
        def o = new BuyOrder(id: 9L, status: 'CANCELLED')
        authedSession(100L)
        1 * buyOrderService.cancel(100L, 9L) >> o

        when:
        def resp = controller.cancel(9L, req)

        then:
        resp.body == [id: 9L, status: 'CANCELLED']
    }

    def "cancelAll() idempotent — zero-row caller gets {cancelled:0}"() {
        given:
        authedSession(100L)
        1 * buyOrderService.cancelAllForUser(100L) >> 0

        when:
        def resp = controller.cancelAll(req)

        then:
        resp.body == [cancelled: 0]
    }

    def "cancelAll() reports the real flipped count when non-zero"() {
        given:
        authedSession(100L)
        1 * buyOrderService.cancelAllForUser(100L) >> 5

        when:
        def resp = controller.cancelAll(req)

        then:
        resp.body == [cancelled: 5]
    }

    def "cancel() requires sign-in and never reaches the service"() {
        given: anonSession()

        when:
        controller.cancel(9L, req)

        then: 'anon callers cannot flip another buyer\'s order to CANCELLED'
        thrown(UnauthorizedException)
        0 * buyOrderService.cancel(_, _)
    }

    def "cancelAll() requires sign-in and never reaches the service"() {
        given: anonSession()

        when:
        controller.cancelAll(req)

        then: 'anon callers cannot bulk-cancel any queue'
        thrown(UnauthorizedException)
        0 * buyOrderService.cancelAllForUser(_)
    }

    def "cancel() forwards the SESSION uid — not a caller-supplied value — to the service"() {
        given: 'a signed-in buyer cancelling order 9'
        def o = new BuyOrder(id: 9L, status: 'CANCELLED')
        authedSession(777L)
        // Ownership is enforced in the service keyed on this uid; the
        // controller must source it from the session, never the path.
        1 * buyOrderService.cancel(777L, 9L) >> o

        when:
        def resp = controller.cancel(9L, req)

        then:
        resp.body == [id: 9L, status: 'CANCELLED']
    }

    // ── update() validation ──────────────────────────────────

    def "update() rejects non-numeric maxPrice with INVALID_PRICE"() {
        given: authedSession(100L)

        when:
        controller.update(9L, [maxPrice: 'abc'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_PRICE'
        0 * buyOrderService.update(_, _, _, _)
    }

    def "update() rejects non-integer quantity with INVALID_QUANTITY"() {
        given: authedSession(100L)

        when:
        controller.update(9L, [quantity: 'two'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_QUANTITY'
        0 * buyOrderService.update(_, _, _, _)
    }

    def "update() passes parsed values through to the service"() {
        given:
        def saved = new BuyOrder(id: 9L, maxPrice: new BigDecimal('12'), quantity: 3)
        authedSession(100L)
        1 * buyOrderService.update(100L, 9L, new BigDecimal('12'), 3) >> saved

        when:
        def resp = controller.update(9L, [maxPrice: '12', quantity: '3'], req)

        then:
        resp.body.is(saved)
    }

    def "update() with only maxPrice passes null quantity through"() {
        given:
        authedSession(100L)
        1 * buyOrderService.update(100L, 9L, new BigDecimal('20'), null) >> new BuyOrder()

        when:
        controller.update(9L, [maxPrice: '20'], req)

        then:
        true
    }

    def "update() requires sign-in"() {
        given: anonSession()
        when:  controller.update(9L, [:], req)
        then:  thrown(UnauthorizedException)
        0 * buyOrderService.update(_, _, _, _)
    }

    def "update() with an empty body forwards SESSION uid + both nulls to the service"() {
        given: 'a no-fields edit from a signed-in buyer'
        authedSession(555L)
        // uid must come from the session; both optional fields absent.
        1 * buyOrderService.update(555L, 9L, null, null) >> new BuyOrder(id: 9L)

        when:
        def resp = controller.update(9L, [:], req)

        then:
        resp.body.id == 9L
    }

    // ── exportCsv ────────────────────────────────────────────

    def "exportCsv() returns header + one row per buy order"() {
        given:
        def row = new BuyOrder(id: 1L, itemId: 42L, itemName: 'Hat',
                               category: 'Hats', rarity: 'Standard',
                               maxPrice: new BigDecimal('5.50'), quantity: 2,
                               originalQuantity: 3, status: 'ACTIVE',
                               createdAt: 1700L, updatedAt: 1800L)
        authedSession(100L)
        1 * buyOrderService.listForBuyer(100L) >> [row]

        when:
        def resp = controller.exportCsv(null, req)

        then:
        resp.headers.getFirst('Content-Disposition')?.contains('buy-orders.csv')
        def lines = resp.body.split('\n')
        lines[0] == 'order_id,item_id,item_name,category,rarity,max_price,quantity,original_quantity,status,created_at,updated_at'
        lines[1] == '1,42,Hat,Hats,Standard,5.50,2,3,ACTIVE,1700,1800'
    }

    def "exportCsv() with no orders emits just the header row"() {
        given:
        authedSession(100L)
        1 * buyOrderService.listForBuyer(100L) >> []

        when:
        def resp = controller.exportCsv(null, req)

        then:
        resp.body.trim().split('\n').size() == 1
    }

    def "exportCsv() requires sign-in"() {
        given: anonSession()
        when:  controller.exportCsv(null, req)
        then:  thrown(UnauthorizedException)
        0 * buyOrderService.listForBuyer(_)
    }

    def "exportCsv(status='ACTIVE') filters out non-ACTIVE rows"() {
        given:
        def active    = new BuyOrder(id: 1L, itemId: 42L, itemName: 'Hat',
                                     category: 'Hats', rarity: 'Standard',
                                     maxPrice: new BigDecimal('5.50'), quantity: 2,
                                     originalQuantity: 3, status: 'ACTIVE',
                                     createdAt: 1700L, updatedAt: 1800L)
        def filled    = new BuyOrder(id: 2L, itemId: 43L, itemName: 'Boots',
                                     category: 'Shoes', rarity: 'Standard',
                                     maxPrice: new BigDecimal('3.00'), quantity: 0,
                                     originalQuantity: 1, status: 'FILLED',
                                     createdAt: 1700L, updatedAt: 1800L)
        def cancelled = new BuyOrder(id: 3L, itemId: 44L, itemName: 'Pants',
                                     category: 'Pants', rarity: 'Standard',
                                     maxPrice: new BigDecimal('2.00'), quantity: 1,
                                     originalQuantity: 1, status: 'CANCELLED',
                                     createdAt: 1700L, updatedAt: 1800L)
        authedSession(100L)
        1 * buyOrderService.listForBuyer(100L) >> [active, filled, cancelled]

        when:
        def resp = controller.exportCsv('active', req)

        then:
        // Lower-case `active` is normalised, so the filter still kicks in.
        def lines = resp.body.split('\n')
        lines.size() == 2          // header + 1 ACTIVE row
        lines[1].startsWith('1,42,Hat,')
        !resp.body.contains('Boots')
        !resp.body.contains('Pants')
    }

    def "exportCsv(status='garbage') falls back to no-filter so a stale bookmark still returns rows"() {
        given:
        def row = new BuyOrder(id: 1L, itemId: 42L, itemName: 'Hat',
                               category: 'Hats', rarity: 'Standard',
                               maxPrice: new BigDecimal('5.50'), quantity: 2,
                               originalQuantity: 3, status: 'ACTIVE',
                               createdAt: 1700L, updatedAt: 1800L)
        authedSession(100L)
        1 * buyOrderService.listForBuyer(100L) >> [row]

        when:
        def resp = controller.exportCsv('NOT_A_STATUS', req)

        then:
        // Unknown status string is ignored — full export, not 0-row CSV.
        resp.body.contains('Hat')
    }
}
