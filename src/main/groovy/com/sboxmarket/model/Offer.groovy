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
}
