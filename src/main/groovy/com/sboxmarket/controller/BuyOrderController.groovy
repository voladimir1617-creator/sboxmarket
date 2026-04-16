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

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<List<BuyOrder>> mine(HttpServletRequest req) {
        ResponseEntity.ok(buyOrderService.listForBuyer(requireUser(req)))
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
