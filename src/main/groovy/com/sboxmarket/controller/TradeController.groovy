package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Trade
import com.sboxmarket.service.TradeService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Trade escrow endpoints — every route is gated to the signed-in user,
 * and TradeService enforces "only the buyer / seller on this specific
 * trade can push the state forward" per method.
 */
@RestController
@RequestMapping("/api/trades")
@Slf4j
class TradeController {

    @Autowired TradeService tradeService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<List<Trade>> mine(HttpServletRequest req) {
        ResponseEntity.ok(tradeService.listForUser(requireUser(req)))
    }

    @GetMapping("/{id}")
    ResponseEntity<Trade> get(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireUser(req)
        def t = tradeService.get(id)
        // Enforce participant visibility at the controller too so a plain
        // GET can't enumerate trades by id.
        if (t.buyerUserId != uid && t.sellerUserId != uid) {
            throw new com.sboxmarket.exception.ForbiddenException("Not your trade")
        }
        ResponseEntity.ok(t)
    }

    @PostMapping("/{id}/accept")
    ResponseEntity<Trade> accept(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(tradeService.sellerAccept(requireUser(req), id))
    }

    @PostMapping("/{id}/sent")
    ResponseEntity<Trade> markSent(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(tradeService.sellerMarkSent(requireUser(req), id))
    }

    @PostMapping("/{id}/confirm")
    ResponseEntity<Trade> confirm(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(tradeService.buyerConfirm(requireUser(req), id))
    }

    @PostMapping("/{id}/dispute")
    ResponseEntity<Trade> dispute(@PathVariable Long id, @RequestBody(required = false) Map body, HttpServletRequest req) {
        def reason = capReason(body?.reason as String)
        ResponseEntity.ok(tradeService.dispute(requireUser(req), id, reason))
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<Trade> cancel(@PathVariable Long id, @RequestBody(required = false) Map body, HttpServletRequest req) {
        def reason = capReason(body?.reason as String)
        ResponseEntity.ok(tradeService.cancel(requireUser(req), id, reason))
    }

    private static String capReason(String raw) {
        if (raw != null && raw.length() > 2000) {
            throw new BadRequestException("REASON_TOO_LONG", "Reason must be under 2000 characters")
        }
        raw
    }
}
