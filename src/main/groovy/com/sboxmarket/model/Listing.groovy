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

    /**
     * @deprecated Dead column kept for schema compatibility — the Steam
     *  trade link surface moved to {@code SteamUser.tradeUrl} (profile-
     *  level, set once per seller) and {@code Trade.tradeOfferUrl}
     *  (per-trade, set at Mark-Sent time, batch 773). No code path
     *  reads or writes this column in 800+ commits of history. Schema
     *  drop is a future migration; the @JsonIgnore here removes the
     *  always-null field from every public listing payload so we
     *  stop wasting wire bytes on a vestigial null.
     */
    @JsonIgnore
    @Deprecated
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

    /** Only set for AUCTION listings — user id of the current highest bidder.
     *  NEVER serialized to clients: the public listing endpoints fan this
     *  entity out to every viewer (anonymous included), and the top bidder's
     *  real SteamUser.id would bypass the redaction BidService.historyFor
     *  applies to third-party bid history AND the deliberate omission
     *  AuctionEventBus.onBid uses on the SSE stream (see the NOTE comment
     *  on that emitter). The display-name snapshot `currentBidderName`
     *  remains exposed for the auction UI; only the user id is redacted. */
    @JsonIgnore
    @Column
    Long currentBidderId

    /** Only set for AUCTION listings — display name snapshot of current highest bidder. */
    @Column
    String currentBidderName

    /** Total bid count for display purposes. */
    @Column
    Integer bidCount = 0

    /** Optional auction Buy-Now ceiling (V42 / batch 371). When set, a
     *  buyer can skip the auction and instantly settle at this price
     *  via `POST /listings/{id}/buy-now-auction`. Service layer enforces
     *  `buyNowPrice > price` (must exceed the starting bid). Null for
     *  plain auctions + every BUY_NOW listing. */
    @Column(name = 'buy_now_price', precision = 10, scale = 2)
    BigDecimal buyNowPrice

    /** Optional per-listing max discount offer (0..1, e.g. 0.20 = accept offers >= 80% asking). */
    @Column(precision = 5, scale = 2)
    BigDecimal maxDiscount

    /** Set to true when the seller uses My Stall → Hide Listing. Hidden listings are excluded
     *  from the public marketplace but still count as ACTIVE for the seller. */
    @Column
    Boolean hidden = false

    /** Optional free-text seller description. Capped at 500 chars by the
     *  SellService.relist / SteamInventoryController.listFromSteam /
     *  ListingController.update sanitiser calls AND by the column size
     *  itself. Migration V39 widened the column from 64 → 500 when the
     *  sell form got a real textarea (batch 304). */
    @Column(length = 500)
    String description

    /** Aggregate count of user-submitted reports. Admins surface high-count listings first. */
    @Column(name = "report_count", nullable = false)
    Integer reportCount = 0

    /** Epoch ms of the most recent report — lets us age out stale signals client-side. */
    @Column(name = "last_reported_at")
    Long lastReportedAt

    /** Flipped by BidService.sweepEndingSoon when an auction's 10-minute
     *  close reminder fires so the sweeper doesn't re-notify bidders +
     *  watchers on every 2-minute tick. Only meaningful for AUCTION rows;
     *  non-auction rows stay at the default FALSE and never read it.
     *
     *  @JsonIgnore — pure internal scheduler state, no client UI reads
     *  it. Pre-fix it leaked on every public listing response
     *  (/just-listed, /top-deals, /most-watched, /search…) which gave a
     *  competitor a free "has this auction's 10-min warning already fired"
     *  signal — a small but real edge for late-snipe tooling that wants
     *  to know whether the rush notification has primed bidders yet. The
     *  field stays on the DB row for the sweeper; only the wire payload
     *  loses it. */
    @JsonIgnore
    @Column(name = "ending_soon_notified", nullable = false)
    Boolean endingSoonNotified = false

    /** Number of times soft-close anti-snipe has already pushed
     *  {@code expiresAt} out for this auction. Capped by
     *  {@code BidService.MAX_SOFT_CLOSE_EXTENSIONS} so a griefer
     *  cannot keep an auction open forever by spamming bids in the
     *  final seconds (each bid is gated by wallet balance, not
     *  debited, so a user with $1000 could otherwise buy ~20,000
     *  extensions = ~7 days of stalling at $0 actual cost). Nullable
     *  in SQL so ddl-auto=update can add the column to existing
     *  AUCTION rows without a backfill — the Groovy initializer is 0
     *  for fresh rows; the BidService cap math coalesces null → 0
     *  for legacy rows. Only meaningful for AUCTION listings;
     *  non-auction rows stay at 0 forever. @JsonIgnore — internal
     *  anti-griefing counter, no client surface reads it. */
    @JsonIgnore
    @Column(name = "soft_close_extensions")
    Integer softCloseExtensions = 0

    /** Seller's review aggregate, attached at serialization time by
     *  ListingController. Lets the buyer see "★ 4.7 (23)" on every
     *  listing row without a per-row API call — csfloat-parity. Null
     *  when sellerUserId is null (system listings) or when the seller
     *  has zero reviews. Not persisted. */
    @Transient
    Double sellerRating

    @Transient
    Integer sellerReviewCount

    /** Seller's epoch-ms `lastSeenAt`, attached at serialization time by
     *  ListingController#decorateWithSellerLastSeen so the marketplace
     *  can render real "Online now" presence dots. Null when sellerUserId
     *  is null (system listings) or the seller has never loaded a page
     *  since V61 deployed. The frontend treats `now - sellerLastSeenAt
     *  < 15 min` as Online, otherwise Offline; null falls back to the
     *  deterministic-seed pattern shipped before V61. Not persisted. */
    @Transient
    Long sellerLastSeenAt
}
