package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.TradeProtection
import com.sboxmarket.service.TradeProtectionService
import com.sboxmarket.service.TradeService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Trade Protection endpoints — the buyer-side safety add-on.
 *
 *   GET  /api/trade-protection/quote?price=  → fee for a price (public)
 *   POST /api/trades/{id}/protection         → buyer enables protection
 *   GET  /api/trades/{id}/protection         → a trade's protection status
 *
 * The enable + status routes are gated to the signed-in user; the
 * service enforces "only the buyer on this trade can enable" and
 * participant-visibility on the status read.
 */
@RestController
@Slf4j
class TradeProtectionController {

    @Autowired TradeProtectionService tradeProtectionService
    @Autowired TradeService tradeService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    /**
     * Protection-fee quote for a given item price. Public + viewer-
     * agnostic so the checkout UI can render "Protect this trade —
     * $X" before the buyer commits. 5-minute cache: the fee is a pure
     * function of price and the rate is a constant.
     */
    @GetMapping('/api/trade-protection/quote')
    ResponseEntity<Map> quote(@RequestParam String price) {
        BigDecimal parsed
        try {
            // Length cap before parsing: BigDecimal accepts exponents like
            // 1e3000000, and the fee arithmetic on one burns seconds of CPU
            // on an unauthenticated endpoint.
            if (price == null || price.length() > 20) throw new NumberFormatException()
            parsed = new BigDecimal(price.trim())
        } catch (NumberFormatException e) {
            throw new BadRequestException('INVALID_PRICE', 'price must be a number')
        }
        // Same bounds a listing price has; a quote for -5 or 1e9 covers
        // nothing anyone can buy.
        if (parsed <= BigDecimal.ZERO || parsed > new BigDecimal('100000')) {
            throw new BadRequestException('INVALID_PRICE', 'price must be between $0.01 and $100,000')
        }
        parsed = parsed.setScale(2, java.math.RoundingMode.HALF_UP)
        if (parsed < new BigDecimal('0.01')) {
            throw new BadRequestException('INVALID_PRICE', 'price must be between $0.01 and $100,000')
        }
        def fee = tradeProtectionService.quote(parsed)
        ResponseEntity.ok()
            .header('Cache-Control', 'public, max-age=300')
            .body([
                price:           parsed,
                fee:             fee,
                ratePercent:     2,
                minFee:          TradeProtectionService.MIN_FEE,
                coverageAmount:  parsed
            ])
    }

    /**
     * Enable protection on a trade. Buyer-only — the service rejects
     * non-buyers and trades that aren't in escrow, and charges the
     * protection fee from the buyer's wallet.
     */
    @PostMapping('/api/trades/{id}/protection')
    ResponseEntity<TradeProtection> enable(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireUser(req)
        ResponseEntity.ok(tradeProtectionService.enable(uid, id))
    }

    /**
     * Protection status for a trade. Only the trade's participants can
     * read it. Returns `{ protected:false }` when the trade has no
     * protection record.
     */
    @GetMapping('/api/trades/{id}/protection')
    ResponseEntity<Map> status(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireUser(req)
        // Reuse TradeService's participant gate — get() 404s an unknown
        // trade; the controller enforces participant visibility so a
        // plain GET can't enumerate protection state by trade id.
        def trade = tradeService.get(id)
        if (trade.buyerUserId != uid && trade.sellerUserId != uid) {
            throw new com.sboxmarket.exception.ForbiddenException("Not your trade")
        }
        def summary = tradeProtectionService.summary(id)
        ResponseEntity.ok([
            tradeId:    id,
            protected:  summary != null,
            protection: summary
        ])
    }
}
