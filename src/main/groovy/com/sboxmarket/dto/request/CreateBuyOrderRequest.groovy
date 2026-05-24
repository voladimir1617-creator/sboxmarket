package com.sboxmarket.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

class CreateBuyOrderRequest {
    Long itemId       // optional — match all items if null

    // Optional filter strings — capped so a client can't POST a multi-MB
    // string that gets shoved into a SQL LIKE / log line. 64 chars is well
    // above the longest real category/rarity name (e.g. "Covert", "Industrial Grade").
    @Size(max = 64, message = "category must be at most 64 characters")
    String category   // optional category filter

    @Size(max = 64, message = "rarity must be at most 64 characters")
    String rarity     // optional rarity filter

    @NotNull
    @DecimalMin(value = "0.01")
    // Mirror the sell-side ceiling so a buy order can never trigger an
    // auto-match on a listing priced above the platform's sanity cap.
    // Without this, a user could POST maxPrice=1e18 and the only thing
    // stopping them from draining their own wallet on the first matching
    // listing was their actual balance — which is too late to recover.
    @DecimalMax(value = "100000.00", message = "maxPrice must not exceed \$100,000")
    BigDecimal maxPrice

    @Positive
    @Max(value = 100L, message = "quantity must be at most 100")
    Integer quantity = 1
}
