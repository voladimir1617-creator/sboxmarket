package com.sboxmarket.model

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import jakarta.validation.constraints.*

@Entity
@Table(name = "listings")
class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /**
     * Optimistic-lock token. Hibernate bumps this on every save and checks
     * it on every update. Two concurrent buyers racing PurchaseService.buy()
     * both read version=N, both attempt to write version=N+1, and the
     * second loser's commit fails with ObjectOptimisticLockingFailureException.
     * GlobalExceptionHandler maps that onto 409 CONFLICT so the loser sees
     * "listing not available" instead of getting their wallet drained for
     * nothing. Added here (not on every entity) because listings are the
     * single mutable resource the whole marketplace converges on.
     *
     * Nullable in SQL + default 0 in Groovy so the column is safe to add to
     * existing rows under ddl-auto=update without a data backfill.
     */
    @JsonIgnore
    @Version
    @Column
    Integer version = 0

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "item_id", nullable = false)
    Item item

    @NotNull
    @DecimalMin("0.01")
    @Column(nullable = false, precision = 10, scale = 2)
    BigDecimal price

    @NotBlank
    @Column(nullable = false)
    String sellerName

    @Column
    String sellerAvatar  // initials or url

    @Column(nullable = false)
    String status = "ACTIVE"  // ACTIVE, SOLD, CANCELLED

    @Column(nullable = false)
    String condition = "Factory New"  // Factory New, Well-Worn, Battle-Scarred, etc.

    @Column(nullable = false)
    BigDecimal rarityScore = BigDecimal.ZERO  // 0-1 like float value in CSFloat

    @Column
    String tradeLink

    @Column(nullable = false)
    Long listedAt = System.currentTimeMillis()

    @Column
    Long soldAt

    /** SteamUser.id of the seller who created the listing (null = system/seed listing). */
    @Column
    Long sellerUserId

    /** SteamUser.id of the buyer who purchased the listing (null if still ACTIVE). */
    @Column
    Long buyerUserId

    // The columns below are nullable in the schema (so Hibernate can add them
    // to existing tables under ddl-auto=update without rejecting historical
    // rows) but always populated to a sensible default by the Groovy field
    // initialisers when a new Listing is constructed in code.

    /** "BUY_NOW" (default, flat price) or "AUCTION" (bid-based, expires at expiresAt). */
    @Column
    String listingType = "BUY_NOW"

    /** Only set for AUCTION listings — epoch ms when bidding closes. */
    @Column
    Long expiresAt

    /** Only set for AUCTION listings — current highest bid (may be null if none). */
    @Column(precision = 19, scale = 2)
    BigDecimal currentBid

    /** Only set for AUCTION listings — user id of the current highest bidder. */
    @Column
    Long currentBidderId

    /** Only set for AUCTION listings — display name snapshot of current highest bidder. */
    @Column
    String currentBidderName

    /** Total bid count for display purposes. */
    @Column
    Integer bidCount = 0

    /** Optional per-listing max discount offer (0..1, e.g. 0.20 = accept offers >= 80% asking). */
    @Column(precision = 5, scale = 2)
    BigDecimal maxDiscount

    /** Set to true when the seller uses My Stall → Hide Listing. Hidden listings are excluded
     *  from the public marketplace but still count as ACTIVE for the seller. */
    @Column
    Boolean hidden = false

    /** Optional free-text seller description, 32 chars max (enforced at the API layer). */
    @Column(length = 64)
    String description
}
