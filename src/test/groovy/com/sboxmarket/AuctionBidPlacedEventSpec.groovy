package com.sboxmarket

import com.sboxmarket.event.AuctionBidPlacedEvent
import spock.lang.Specification

/**
 * Payload-shape coverage for {@link AuctionBidPlacedEvent}.
 *
 * This event ships over SSE to every connected auction-bid subscriber
 * AFTER_COMMIT of the bid transaction, and the AuctionBidPanel on the
 * client reads each named field to refresh without an extra REST
 * round-trip. A regression in field naming / null handling / now-stamp
 * would silently break the live bid wire format for every connected
 * client.
 *
 * The `now` clock-stamp is set inside the constructor, so we pin it as
 * "very close to current wall clock" rather than an exact value — a
 * future refactor that swaps a fixed clock in would be flagged.
 */
class AuctionBidPlacedEventSpec extends Specification {

    def "constructor wires every payload field through unchanged"() {
        given:
        def t0 = System.currentTimeMillis()

        when:
        def event = new AuctionBidPlacedEvent(
            42L,                          // listingId
            'bid',                        // kind
            new BigDecimal('15.50'),      // currentBid
            777L,                         // currentBidderId
            'Alice',                      // currentBidderName
            5,                            // bidCount
            t0 + 60_000L,                 // expiresAt
            'ACTIVE'                      // status
        )

        then:
        event.listingId == 42L
        event.kind == 'bid'
        event.currentBid == new BigDecimal('15.50')
        event.currentBidderId == 777L
        event.currentBidderName == 'Alice'
        event.bidCount == 5
        event.expiresAt == t0 + 60_000L
        event.status == 'ACTIVE'
    }

    def "kind=buy-now is accepted as a first-class payload variant"() {
        // BidService fires the same event for the buy-now flow on auction
        // listings; the kind string is the discriminator the client
        // branches on. Both variants must round-trip without coercion.
        when:
        def event = new AuctionBidPlacedEvent(
            1L, 'buy-now', new BigDecimal('100'), 99L, 'Bob',
            12, 0L, 'SOLD'
        )

        then:
        event.kind == 'buy-now'
        event.status == 'SOLD'
    }

    def "constructor stamps the server clock close to current wall time"() {
        // The `now` field exists so clients can correct their own
        // local-clock drift. It MUST be set inside the constructor (not
        // injected) so an event the server creates at second N carries
        // an N-stamp regardless of when it is later observed.
        given:
        long before = System.currentTimeMillis()

        when:
        def event = new AuctionBidPlacedEvent(
            1L, 'bid', new BigDecimal('1'), 1L, 'X', 1, before + 1000L, 'ACTIVE'
        )
        long after = System.currentTimeMillis()

        then:
        event.now >= before
        event.now <= after
    }

    def "nullable fields (currentBidderId, currentBidderName, currentBid) round-trip null"() {
        // A bid on a fresh auction with no prior bidder needs to send
        // the bidder fields as nulls — non-null assertions in the
        // constructor would crash the very first bid on every auction.
        when:
        def event = new AuctionBidPlacedEvent(
            1L, 'bid', null, null, null, 0, 0L, 'ACTIVE'
        )

        then:
        event.currentBid == null
        event.currentBidderId == null
        event.currentBidderName == null
        event.bidCount == 0
    }

    def "every status string the BidService emits round-trips through"() {
        // The status field captures terminal transitions; the client
        // closes its panel on SOLD / EXPIRED / CANCELLED and stays open
        // on ACTIVE. Mistyping the status string would silently leave
        // closed auctions on screen.
        when:
        def event = new AuctionBidPlacedEvent(1L, 'bid', new BigDecimal('1'), 2L, 'X', 1, 0L, status)

        then:
        event.status == status

        where:
        status << ['ACTIVE', 'SOLD', 'EXPIRED', 'CANCELLED']
    }

    def "expiresAt captures soft-close window extensions as a fresh epoch-ms"() {
        // Soft-close extends the auction window when a bid lands inside
        // the final N seconds. The event MUST carry the NEW expiresAt
        // (not the original) so the client's countdown re-anchors.
        given:
        long originalExpiry = 10_000L
        long extendedExpiry = 70_000L

        when:
        def event = new AuctionBidPlacedEvent(
            1L, 'bid', new BigDecimal('20'), 2L, 'Late Bidder', 7, extendedExpiry, 'ACTIVE'
        )

        then:
        event.expiresAt == extendedExpiry
        event.expiresAt != originalExpiry
    }

    def "toString includes the payload fields (debug surfaces use it directly)"() {
        // @ToString(includePackage=false) — the log lines around the
        // event publish path stringify it for debugging. Pin the
        // shape so a future @ToString config flip doesn't silently
        // turn every audit log entry into `AuctionBidPlacedEvent@1a2b`.
        when:
        def event = new AuctionBidPlacedEvent(
            42L, 'bid', new BigDecimal('15.50'), 777L, 'Alice', 5, 60_000L, 'ACTIVE'
        )
        def s = event.toString()

        then:
        s.contains('AuctionBidPlacedEvent')
        s.contains('42')
        s.contains('bid')
        s.contains('Alice')
        s.contains('ACTIVE')
        // includePackage=false — full FQN should NOT appear.
        !s.contains('com.sboxmarket.event.AuctionBidPlacedEvent')
    }
}
