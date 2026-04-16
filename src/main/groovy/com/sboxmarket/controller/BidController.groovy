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

    @GetMapping("/auto")
    ResponseEntity<List<Bid>> autoBids(HttpServletRequest req) {
        ResponseEntity.ok(bidService.autoBidsForUser(requireUser(req)))
    }
}
