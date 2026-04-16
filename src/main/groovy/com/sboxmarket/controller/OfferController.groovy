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
    ResponseEntity<List<Offer>> incoming(HttpServletRequest req) {
        ResponseEntity.ok(offerService.incoming(requireUser(req)))
    }

    @GetMapping("/outgoing")
    ResponseEntity<List<Offer>> outgoing(HttpServletRequest req) {
        ResponseEntity.ok(offerService.outgoing(requireUser(req)))
    }

    @PostMapping
    ResponseEntity<Map> create(@Valid @RequestBody CreateOfferRequest body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid)
                .orElseThrow { new UnauthorizedException("Unknown user") }
        def offer = offerService.makeOffer(uid, user.displayName ?: "Player", body.listingId, body.amount)
        ResponseEntity.ok([id: offer.id, status: offer.status, amount: offer.amount])
    }

    @PostMapping("/{id}/accept")
    ResponseEntity<Map> accept(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(offerService.acceptOffer(requireUser(req), id))
    }

    @PostMapping("/{id}/reject")
    ResponseEntity<Map> reject(@PathVariable Long id, HttpServletRequest req) {
        def offer = offerService.rejectOffer(requireUser(req), id)
        ResponseEntity.ok([id: offer.id, status: offer.status])
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Map> cancel(@PathVariable Long id, HttpServletRequest req) {
        def offer = offerService.cancelOffer(requireUser(req), id)
        ResponseEntity.ok([id: offer.id, status: offer.status])
    }

    /** Seller counter-offer — creates a new Offer linked to the original. */
    @PostMapping("/{id}/counter")
    ResponseEntity<Map> counter(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def amount = parseAmount(body?.amount)
        def counter = offerService.counterOffer(uid, id, amount)
        ResponseEntity.ok([id: counter.id, parentOfferId: counter.parentOfferId, amount: counter.amount, status: counter.status])
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
