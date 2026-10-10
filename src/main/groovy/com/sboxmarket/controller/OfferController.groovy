package com.sboxmarket.controller

import com.sboxmarket.dto.request.CreateOfferRequest
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Offer
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.OfferService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** Thin HTTP adapter for the offer flow. All business logic lives in OfferService. */
@RestController
@RequestMapping("/api/offers")
@Slf4j
class OfferController {

    @Autowired OfferService offerService
    @Autowired SteamUserRepository steamUserRepository

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping("/incoming")
    ResponseEntity<List<Map>> incoming(HttpServletRequest req) {
        // DTO variant carries the computed `expiresAt` so the Offers tab
        // can render an auto-decline countdown without round-tripping
        // the server config (batch 269). Capped at OFFER_LIST_CAP (300);
        // X-Total-Count header reports the true row count for the
        // overflow banner on very active sellers.
        def uid = requireUser(req)
        def rows = offerService.incomingWithExpiry(uid)
        long total = offerService.countIncoming(uid)
        ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .body(rows)
    }

    @GetMapping("/outgoing")
    ResponseEntity<List<Map>> outgoing(HttpServletRequest req) {
        def uid = requireUser(req)
        def rows = offerService.outgoingWithExpiry(uid)
        long total = offerService.countOutgoing(uid)
        ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .body(rows)
    }

    /** Per-listing summary of PENDING buyer offers on the caller's
     *  listings — { bestAmount, count, newestAt } keyed by listing id.
     *  Drives the MyStall "Best offer $X · N pending" chip so sellers
     *  see where bargainers are waiting without opening the Offers
     *  tab. Owner-scoped via requireUser — returns only offers on
     *  listings the caller owns. */
    @GetMapping("/best-per-listing")
    ResponseEntity<Map> bestPerListing(HttpServletRequest req) {
        def uid = requireUser(req)
        ResponseEntity.ok(offerService.pendingOfferSummaryForSeller(uid))
    }

    /** The caller's live (PENDING/COUNTERED) offer on one listing, or
     *  null if none. Drives the "You offered $X" chip on the ItemModal
     *  (batch 368) so a buyer revisiting a listing sees their own offer
     *  state without opening the Offers tab. Returns `{ offer: null }`
     *  for anon callers + unoffered listings so the UI can branch on a
     *  single null check. */
    @GetMapping("/mine-for-listing/{listingId}")
    ResponseEntity<Map> mineForListing(@PathVariable Long listingId,
                                       HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) return ResponseEntity.ok([offer: null])
        def offer = offerService.liveOfferByBuyerForListing(uid, listingId)
        ResponseEntity.ok([offer: offer])
    }

    /** Two-value badge source for the nav — "how many offers need my
     *  attention". Seller side is PENDING incoming (actionable); buyer
     *  side is PENDING outgoing (awaiting counterparty). Frontend shows
     *  the seller count because that's the actionable one; buyer count is
     *  included so a future design can split them. */
    @GetMapping("/counts")
    ResponseEntity<Map> counts(HttpServletRequest req) {
        def uid = requireUser(req)
        ResponseEntity.ok([
            incomingPending: offerService.countPendingIncoming(uid),
            outgoingPending: offerService.countPendingOutgoing(uid)
        ])
    }

    @PostMapping
    ResponseEntity<Map> create(@Valid @RequestBody CreateOfferRequest body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid)
                .orElseThrow { new UnauthorizedException("Unknown user") }
        def offer = offerService.makeOffer(uid, user.displayName ?: "Player",
            body.listingId, body.amount, body.message)
        // Batch 881 — include itemName so the frontend toast can render
        // "Offered $X on '<item>'" instead of a generic "Offer sent".
        // expiresAt lets the toast name the real response window
        // (offer.auto-decline-days), not a hard-coded 7 days.
        ResponseEntity.ok([id: offer.id, status: offer.status, amount: offer.amount,
            itemName: offer.itemName, expiresAt: offerService.computeExpiresAt(offer)])
    }

    @PostMapping("/{id}/accept")
    ResponseEntity<Map> accept(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(offerService.acceptOffer(requireUser(req), id))
    }

    @PostMapping("/{id}/reject")
    ResponseEntity<Map> reject(@PathVariable Long id,
                               @RequestBody(required = false) Map body,
                               HttpServletRequest req) {
        def reply = body?.reply as String
        def offer = offerService.rejectOffer(requireUser(req), id, reply)
        ResponseEntity.ok([id: offer.id, status: offer.status])
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Map> cancel(@PathVariable Long id, HttpServletRequest req) {
        def offer = offerService.cancelOffer(requireUser(req), id)
        ResponseEntity.ok([id: offer.id, status: offer.status])
    }

    /** Bulk-cancel every PENDING outgoing offer for the caller. Mirrors
     *  POST /api/buy-orders/cancel-all + /api/bids/auto/cancel-all —
     *  one click to exit every open negotiation. Idempotent (zero-row
     *  callers get `{cancelled:0}`). */
    @PostMapping("/outgoing/cancel-all")
    ResponseEntity<Map> cancelAllOutgoing(HttpServletRequest req) {
        int n = offerService.cancelAllForUser(requireUser(req))
        ResponseEntity.ok([cancelled: n])
    }

    /** Seller counter-offer — creates a new Offer linked to the original. */
    @PostMapping("/{id}/counter")
    ResponseEntity<Map> counter(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def amount = parseAmount(body?.amount)
        def message = body?.message as String
        def counter = offerService.counterOffer(uid, id, amount, message)
        ResponseEntity.ok([id: counter.id, parentOfferId: counter.parentOfferId, amount: counter.amount, status: counter.status])
    }

    /** Buyer raise — a buyer escalates their own pending offer without
     *  waiting for the seller. Cancels the original PENDING row and
     *  creates a new PENDING offer threaded via parentOfferId. Amount
     *  must be strictly greater than the old offer and strictly below
     *  the asking price. */
    @PostMapping("/{id}/raise")
    ResponseEntity<Map> raise(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def amount = parseAmount(body?.amount)
        def message = body?.message as String
        def raised = offerService.buyerRaise(uid, id, amount, message)
        ResponseEntity.ok([id: raised.id, parentOfferId: raised.parentOfferId, amount: raised.amount, status: raised.status])
    }

    /** Defensive parse for counter-offer body.amount so a missing or
     *  malformed value returns a structured 400 instead of bubbling up
     *  through GlobalExceptionHandler as an "INTERNAL_ERROR" 500. */
    private static BigDecimal parseAmount(Object raw) {
        if (raw == null) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount is required")
        }
        try {
            def bd = new BigDecimal(raw.toString())
            if (bd.signum() <= 0) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount must be greater than 0")
            }
            if (bd > new BigDecimal("1000000")) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount exceeds the \$1,000,000 limit")
            }
            return bd
        } catch (NumberFormatException ignored) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_AMOUNT", "amount must be a valid number")
        }
    }

    /**
     * Full conversation thread for a listing. Public endpoint, but the
     * service redacts buyer identities for anyone who isn't the listing
     * seller or a participant in the thread — a third party hitting this
     * URL sees "Buyer #1", "Buyer #2" instead of real display names.
     */
    @GetMapping("/thread/{listingId}")
    ResponseEntity<List<com.sboxmarket.model.Offer>> thread(@PathVariable Long listingId, HttpServletRequest req) {
        def viewer = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        ResponseEntity.ok(offerService.thread(listingId, viewer))
    }
}
