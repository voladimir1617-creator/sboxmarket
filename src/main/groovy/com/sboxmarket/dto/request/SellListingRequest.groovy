package com.sboxmarket.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

class SellListingRequest {
    @NotNull(message = "listingId is required")
    @Positive(message = "listingId must be positive")
    Long listingId

    @NotNull(message = "price is required")
    @DecimalMin(value = "0.01", message = "price must be at least \$0.01")
    @DecimalMax(value = "100000.00", message = "price must not exceed \$100,000")
    BigDecimal price

    /** Optional. "BUY_NOW" (default, flat price) or "AUCTION" (starting bid at `price`,
     *  ends after `durationHours`). Anything else is rejected by the service. */
    @Pattern(regexp = "BUY_NOW|AUCTION", message = "listingType must be BUY_NOW or AUCTION")
    String listingType

    /** Required for AUCTION listings. 1h minimum (prevents "auction ends in 30s" meme
     *  spam), 168h (7 days) maximum to match typical e-commerce auction windows. */
    @Min(value = 1L,   message = "durationHours must be at least 1")
    @Max(value = 168L, message = "durationHours must not exceed 168 (7 days)")
    Long durationHours

    /** Optional seller note — "quick sale", "mint, never worn", etc. Sanitized
     *  server-side; 500-char cap matches the column size and the edit form. */
    @Size(max = 500, message = "description must not exceed 500 characters")
    String description

    /** Optional Buy-Now ceiling on an AUCTION listing (batch 371).
     *  Must exceed the starting `price` — service layer validates.
     *  Ignored for BUY_NOW listings (the `price` field is already the
     *  Buy-Now amount there). Nullable for plain auctions. */
    @DecimalMin(value = "0.01", message = "buyNowPrice must be at least \$0.01")
    @DecimalMax(value = "100000.00", message = "buyNowPrice must not exceed \$100,000")
    BigDecimal buyNowPrice

    /** Optional auto-accept threshold (batch 646) — fraction 0..1 where
     *  0.20 means "auto-accept any offer >= 80% of ask". Null / 0 =
     *  no auto-accept (manual seller confirmation required). Mirrors
     *  the per-listing edit form on MyStall so sellers can set it at
     *  list-creation time instead of having to list → open MyStall →
     *  edit just to enable auto-accept. Service layer clamps
     *  defensively. */
    @DecimalMin(value = "0.00", message = "maxDiscount must be at least 0")
    @DecimalMax(value = "0.99", message = "maxDiscount must be less than 1 (100% off)")
    BigDecimal maxDiscount
}
