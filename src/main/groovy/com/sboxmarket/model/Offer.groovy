package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@Entity
@Table(name = "offers")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class Offer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /** The active listing this offer is targeting. */
    @Column(nullable = false)
    Long listingId

    /** SteamUser.id of the buyer making the offer. */
    @Column(nullable = false)
    Long buyerUserId

    /** SteamUser.id of the seller (snapshot at offer time, may be null for system listings). */
    @Column
    Long sellerUserId

    /** Amount the buyer is offering. */
    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal amount

    /** Original asking price at the time the offer was made (for diff display). */
    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal askingPrice

    /** PENDING, ACCEPTED, REJECTED, CANCELLED, EXPIRED */
    @Column(nullable = false)
    String status = "PENDING"

    /** Display name of the buyer at offer time, for the seller to see in their inbox. */
    @Column
    String buyerName

    /** Snapshot of the item name so the offer remains readable after the listing is deleted. */
    @Column
    String itemName

    /** Snapshot of the item image url. */
    @Column(length = 500)
    String itemImageUrl

    /** Snapshot of the catalogue item id at offer time. Lets the OffersModal
     *  render the item name as an `<a href="/item/:id">` without a per-row
     *  Listing lookup. Nullable for legacy offers predating V44 — the UI
     *  falls back to plain text when this is null. */
    @Column(name = 'item_id')
    Long itemId

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(name = "updated_at", nullable = false)
    Long updatedAt = System.currentTimeMillis()

    /** Parent offer id — set when this offer is a counter to a previous one.
     *  Lets us render a full back-and-forth thread in the Offers UI. */
    @Column
    Long parentOfferId

    /** USER or SELLER — lets us render the thread with the right bubble side.
     *  The first offer in a chain is always USER; a SELLER counter lives under
     *  the same root via parentOfferId. */
    @Column(length = 16)
    String author = "USER"

    /** Optional buyer-supplied note ("brand new acct, fast pay"). Sanitised
     *  via TextSanitizer.cleanShort + capped at 280 chars in OfferService.
     *  Null when the buyer didn't add a message. Surfaces inline on the
     *  seller's offer row so they have context before accepting/declining. */
    @Column(length = 280)
    String message

    /** Optional seller reply attached at reject-time ("already committed to
     *  another buyer, sorry"). Same sanitiser + 280-char cap. Surfaces
     *  inline on the buyer's rejected offer row so the rejection is never
     *  silent — the buyer sees the seller's reasoning without having to
     *  DM them. Added in V45 / batch 387. Null for legacy rejections. */
    @Column(name = 'seller_reply', length = 280)
    String sellerReply

    /** Wall-clock millis when the half-life nudge sweeper last pinged
     *  the seller about this still-pending offer. Set once and only
     *  once per offer — the partial index `idx_offers_pending_unnudged`
     *  filters on `IS NULL` so re-nudging is impossible without an
     *  explicit reset. Null for offers that haven't crossed the
     *  half-life threshold yet. Added in V47 / batch 499. */
    @Column(name = 'seller_nudged_at')
    Long sellerNudgedAt
}
