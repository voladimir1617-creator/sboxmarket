package com.sboxmarket.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

class CreateBuyOrderRequest {
    Long itemId       // optional — match all items if null
    String category   // optional category filter
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
