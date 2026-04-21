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
    ResponseEntity<List<Map>> mine(HttpServletRequest req) {
        // Enriched list — each row carries counterpartyTradeUrl +
        // counterpartyName so the Profile → Trades tab can show a
        // "Copy seller trade URL" button without the client having to
        // fan out /api/auth lookups per row. Capped at TRADE_LIST_CAP
        // (200); X-Total-Count header carries the true row count so
        // the Trades tab can render "Showing most recent 200 of N"
        // for power-users.
        def uid = requireUser(req)
        def rows = tradeService.listForUserWithCounterparty(uid)
        long total = tradeService.countForUser(uid)
        ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .body(rows)
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
    ResponseEntity<Trade> markSent(@PathVariable Long id,
                                   @RequestBody(required = false) Map body,
                                   HttpServletRequest req) {
        // Batch 773 — optional `tradeOfferUrl` body param lets the seller
        // attach the Steam trade-offer link at Mark-Sent time. Legacy
        // clients posting no body still work (url falls through to null
        // and the service just skips the update).
        def url = body?.tradeOfferUrl as String
        ResponseEntity.ok(tradeService.sellerMarkSent(requireUser(req), id, url))
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

    // ── Trade chat ───────────────────────────────────────────────────

    @GetMapping('/{id}/messages')
    ResponseEntity<List<com.sboxmarket.model.TradeMessage>> messages(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(tradeService.listMessages(id, requireUser(req)))
    }

    @PostMapping('/{id}/messages')
    ResponseEntity<com.sboxmarket.model.TradeMessage> postMessage(@PathVariable Long id,
                                                                   @RequestBody Map body,
                                                                   HttpServletRequest req) {
        def text = body?.body as String
        if (text == null || text.trim().isEmpty()) {
            throw new com.sboxmarket.exception.BadRequestException('EMPTY_MESSAGE', 'Message body is required')
        }
        if (text.length() > 2000) {
            throw new com.sboxmarket.exception.BadRequestException('TOO_LONG', 'Messages must be under 2000 characters')
        }
        ResponseEntity.ok(tradeService.postMessage(id, requireUser(req), text))
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
