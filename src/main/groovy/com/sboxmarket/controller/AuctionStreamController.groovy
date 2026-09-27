package com.sboxmarket.controller

import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.AuctionEventBus
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
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
    @Autowired ListingRepository listingRepository

    @GetMapping(path = "/{listingId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe(@PathVariable Long listingId) {
        if (listingId == null || listingId <= 0L) {
            throw new NotFoundException("Auction", listingId)
        }
        // The SSE stream mirrors a public auction surface — gate it the
        // same way the marketplace gates a listing card. Anyone (incl.
        // anonymous viewers) may subscribe to a VISIBLE auction, but a
        // missing id, a hidden/off-market listing, or a plain BUY_NOW
        // listing must 404 rather than open a stream. Without this an
        // anonymous client could subscribe to any listing id and the
        // event fan-out would leak live bid activity on private rows.
        def listing = listingRepository.findById(listingId).orElse(null)
        if (listing == null
                || Boolean.TRUE == listing.hidden
                || listing.listingType != 'AUCTION') {
            throw new NotFoundException("Auction", listingId)
        }
        def emitter = bus.subscribe(listingId)
        // For an auction that has already concluded (SOLD / EXPIRED /
        // CANCELLED), no further AuctionBidPlacedEvent will ever fire —
        // so a late subscriber would sit on a stale UI until the next
        // event that never comes. Deliver the terminal state right after
        // the bus's hello so the client can render the final result
        // immediately. The SSE event MUST be named `bid` (NOT `state`):
        // the browser-side EventSource client in csfloat-modals.js only
        // registers `addEventListener('bid', …)`, and a custom-named SSE
        // event with no matching listener is silently dropped by the
        // EventSource API — so an event named `state` would never reach
        // the panel and the "late viewers see the final result"
        // affordance would silently break (no error, no fallback, the
        // UI just sits on its pre-load placeholder until polling fills
        // in). Field shape mirrors the public `bid` payload (and
        // deliberately omits currentBidderId — same redaction rationale
        // the bus's onBid uses) so the existing bid handler ingests the
        // snapshot unchanged. The `kind: 'state'` payload field is the
        // discriminant a future panel revision could use to distinguish
        // a synthetic terminal snapshot from a true post-bid event.
        if (listing.status != null && listing.status != 'ACTIVE') {
            try {
                emitter.send(SseEmitter.event().name('bid').data([
                    listingId        : listing.id,
                    kind             : 'state',
                    currentBid       : listing.currentBid?.toPlainString(),
                    currentBidderName: listing.currentBidderName,
                    bidCount         : listing.bidCount,
                    expiresAt        : listing.expiresAt,
                    status           : listing.status,
                    now              : System.currentTimeMillis()
                ]))
            } catch (Exception ignored) {
                // Send failure means the emitter has already completed
                // (client disconnected / hello write failed). The bus's
                // onError/onCompletion has already evicted it; nothing
                // to do here.
            }
        }
        emitter
    }
}
