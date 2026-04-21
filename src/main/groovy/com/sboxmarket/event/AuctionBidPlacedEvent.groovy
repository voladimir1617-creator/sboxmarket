package com.sboxmarket.event

import groovy.transform.CompileStatic
import groovy.transform.ToString

/**
 * Fired by BidService after a successful bid or buy-now on an auction
 * listing. Delivered by AuctionEventBus to SSE subscribers in the
 * AFTER_COMMIT phase so clients never see a bid the database hasn't
 * durably persisted.
 *
 * Payload mirrors the fields AuctionBidPanel needs to refresh without
 * a round-trip: listing id, current winning bid + bidder, expiresAt
 * (captures soft-close extensions), listing status (catches auction
 * terminal transitions), and a server clock reading the client can
 * use to detect its own drift.
 */
@CompileStatic
@ToString(includePackage = false)
class AuctionBidPlacedEvent {

    final Long listingId
    final String kind              // "bid" | "buy-now"
    final BigDecimal currentBid
    final Long currentBidderId
    final String currentBidderName
    final Integer bidCount
    final Long expiresAt           // epoch ms, may have shifted via soft-close
    final String status            // ACTIVE | SOLD | EXPIRED | CANCELLED
    final Long now                 // server clock (epoch ms) — lets client correct drift

    AuctionBidPlacedEvent(Long listingId, String kind,
                          BigDecimal currentBid,
                          Long currentBidderId, String currentBidderName,
                          Integer bidCount, Long expiresAt, String status) {
        this.listingId = listingId
        this.kind = kind
        this.currentBid = currentBid
        this.currentBidderId = currentBidderId
        this.currentBidderName = currentBidderName
        this.bidCount = bidCount
        this.expiresAt = expiresAt
        this.status = status
        this.now = System.currentTimeMillis()
    }
}
