package com.sboxmarket.controller

import com.sboxmarket.dto.request.CreateBuyOrderRequest
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.BuyOrderService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** Standing buy orders — buyer-side reverse listings. */
@RestController
@RequestMapping("/api/buy-orders")
@Slf4j
class BuyOrderController {

    @Autowired BuyOrderService buyOrderService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired com.sboxmarket.repository.ItemRepository itemRepository

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<List<Map>> mine(HttpServletRequest req) {
        def uid = requireUser(req)
        def rows = buyOrderService.listForBuyer(uid)
        // Enrich each row with the item's current floor price + a
        // per-row "gap" (floor − maxPrice). Negative gap means the
        // order is already above floor and should have matched; zero-
        // to-small-positive gap means the order is one drop away from
        // a match. Null floor when the order spec isn't item-specific
        // (buy orders pinned to category+rarity carry no itemId).
        def itemIds = rows*.itemId.findAll { it != null }.unique()
        def byId = [:]
        if (!itemIds.isEmpty()) {
            itemRepository.findAllById(itemIds).each { byId[it.id] = it }
        }
        def out = rows.collect { o ->
            def floor = o.itemId != null ? byId[o.itemId]?.lowestPrice : null
            def gap = (floor != null && o.maxPrice != null) ? (floor - o.maxPrice) : null
            [
                id: o.id, itemId: o.itemId, itemName: o.itemName,
                category: o.category, rarity: o.rarity,
                maxPrice: o.maxPrice, quantity: o.quantity,
                originalQuantity: o.originalQuantity, status: o.status,
                createdAt: o.createdAt, updatedAt: o.updatedAt,
                currentFloor: floor,
                floorGap:     gap
            ]
        }
        ResponseEntity.ok(out)
    }

    /** Public demand-count for an item — returns the count of ACTIVE buy
     *  orders pinned to this item id. Powers the "N buyers want this"
     *  chip on the item detail modal. Counter-party identities are NOT
     *  surfaced — this is an aggregate-only signal. */
    @GetMapping("/count/item/{id}")
    ResponseEntity<Map> countForItem(@PathVariable Long id) {
        def best = buyOrderService.bestBidForItem(id)
        ResponseEntity.ok([
            itemId:  id,
            count:   buyOrderService.countActiveForItem(id),
            bestBid: (best != null && best > BigDecimal.ZERO) ? best : null
        ])
    }

    @PostMapping
    ResponseEntity<BuyOrder> create(@Valid @RequestBody CreateBuyOrderRequest body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        ResponseEntity.ok(buyOrderService.create(
            uid, user.displayName ?: "Player",
            body.itemId, body.category, body.rarity,
            body.maxPrice, body.quantity ?: 1
        ))
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Map> cancel(@PathVariable Long id, HttpServletRequest req) {
        def order = buyOrderService.cancel(requireUser(req), id)
        ResponseEntity.ok([id: order.id, status: order.status])
    }
}
