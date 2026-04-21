package com.sboxmarket.controller

import com.sboxmarket.service.AuctionEventBus
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Long-lived Server-Sent Events stream for a single auction listing.
 * Clients open one connection per visible auction; AuctionEventBus fans
 * AuctionBidPlacedEvent out to every subscriber of that listing in the
 * AFTER_COMMIT phase.
 *
 * Replaces the 8-second polling loop in AuctionBidPanel for realtime
 * outbid feedback. Clients must still keep a polling fallback for
 * environments where SSE is blocked (some proxies, some corporate
 * networks); the stream is best-effort.
 */
@RestController
@RequestMapping("/api/bids/stream")
@Slf4j
class AuctionStreamController {

    @Autowired AuctionEventBus bus

    @GetMapping(path = "/{listingId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe(@PathVariable Long listingId) {
        if (listingId == null || listingId <= 0L) {
            // Return an immediately-completed emitter rather than 404 —
            // keeps the contract simple (always a stream).
            def em = new SseEmitter(0L)
            try { em.complete() } catch (Exception ignored) {}
            return em
        }
        bus.subscribe(listingId)
    }
}
