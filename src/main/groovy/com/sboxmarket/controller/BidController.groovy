package com.sboxmarket.controller

import com.sboxmarket.dto.request.PlaceBidRequest
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Bid
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.BidService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/bids")
@Slf4j
class BidController {

    @Autowired BidService bidService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired(required = false) com.sboxmarket.repository.ListingRepository listingRepository

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @PostMapping
    ResponseEntity<Map> place(@Valid @RequestBody PlaceBidRequest body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def bid = bidService.placeBid(uid, user.displayName ?: "Player",
            body.listingId, body.amount, body.maxAmount)
        ResponseEntity.ok([id: bid.id, amount: bid.amount, kind: bid.kind, status: bid.status])
    }

    @GetMapping("/listing/{id}")
    ResponseEntity<List<Bid>> history(@PathVariable Long id, HttpServletRequest req) {
        def viewer = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        ResponseEntity.ok(bidService.historyFor(id, viewer))
    }

    /** Auction Buy-Now (batch 371). Closes the auction immediately at
     *  the seller's `buyNowPrice` and awards it to the caller. Only
     *  valid when `listing.buyNowPrice` is set and the auction hasn't
     *  already expired. 400 with `NO_BUY_NOW` if the auction doesn't
     *  support it.
     *
     *  Batch 961 — accepts an optional `{expectedPrice: "9.99"}` body.
     *  When present, rejects with 400 PRICE_CHANGED if the server-side
     *  buyNowPrice has drifted since the buyer loaded the modal. Same
     *  defence-in-depth pattern as POST /api/listings/{id}/buy. */
    @PostMapping("/listing/{id}/buy-now")
    ResponseEntity<Map> buyNowAuction(@PathVariable Long id,
                                      @RequestBody(required = false) Map body,
                                      HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        // Optional price-match guard. Runs BEFORE buyNowAuction() so the
        // transactional path isn't entered at a surprise price — mirrors
        // the buy-listing and cart-checkout guards.
        if (body?.expectedPrice != null) {
            BigDecimal expected
            try { expected = new BigDecimal(body.expectedPrice.toString()) }
            catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_PRICE",
                    "expectedPrice must be a valid number")
            }
            def lOpt = listingRepository?.findById(id)?.orElse(null)
            if (lOpt != null && lOpt.buyNowPrice != null &&
                    lOpt.buyNowPrice.compareTo(expected) != 0) {
                throw new com.sboxmarket.exception.BadRequestException("PRICE_CHANGED",
                    "Buy-Now price moved from \$${expected} to \$${lOpt.buyNowPrice} — refresh and retry")
            }
        }
        def listing = bidService.buyNowAuction(uid, user.displayName ?: "Player", id)
        ResponseEntity.ok([
            listingId: listing.id,
            status:    listing.status,
            price:     listing.currentBid,
            buyerUserId: listing.buyerUserId
        ])
    }

    @GetMapping("/auto")
    ResponseEntity<List<Bid>> autoBids(HttpServletRequest req) {
        ResponseEntity.ok(bidService.autoBidsForUser(requireUser(req)))
    }

    /** Every bid the caller currently has live (WINNING + OUTBID). Drives
     *  the Profile → Active Bids tab — lets users see every auction they're
     *  still in without walking each listing individually. */
    @GetMapping("/my-active")
    ResponseEntity<List<Bid>> myActive(HttpServletRequest req) {
        ResponseEntity.ok(bidService.liveBidsForUser(requireUser(req)))
    }

    /** Past bids (WON/LOST/CANCELLED) — drives Profile → Bids → Past
     *  sub-tab (batch 361). Capped at 100 most-recent server-side. */
    @GetMapping("/my-past")
    ResponseEntity<List<Bid>> myPast(HttpServletRequest req) {
        ResponseEntity.ok(bidService.pastBidsForUser(requireUser(req)))
    }

    /** Cancel auto-raise on a single bid. Doesn't retract the bid itself
     *  (CSFloat-style semantics — current winning bid stands), just
     *  clears the ceiling so the auto-bid bot stops raising on the user's
     *  behalf. Only the bid's own owner can call this. */
    @PostMapping("/auto/{bidId}/cancel")
    ResponseEntity<Map> cancelAutoBid(@PathVariable Long bidId, HttpServletRequest req) {
        def n = bidService.cancelAutoBid(requireUser(req), bidId)
        ResponseEntity.ok([id: bidId, cancelled: n])
    }

    /** Bulk cancel all of the viewer's active auto-raises. Returns the
     *  number of rows touched. */
    @PostMapping("/auto/cancel-all")
    ResponseEntity<Map> cancelAllAutoBids(HttpServletRequest req) {
        def n = bidService.cancelAllAutoBidsForUser(requireUser(req))
        ResponseEntity.ok([cancelled: n])
    }
}
