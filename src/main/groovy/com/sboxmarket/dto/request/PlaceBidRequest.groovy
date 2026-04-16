package com.sboxmarket.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

class PlaceBidRequest {
    @NotNull
    @Positive
    Long listingId

    @NotNull
    @DecimalMin(value = "0.01")
    @DecimalMax(value = "100000.00", message = "bid amount must not exceed \$100,000")
    BigDecimal amount

    /** Optional auto-bid ceiling — bot will keep bidding up to this on user's behalf. */
    @DecimalMax(value = "100000.00", message = "maxAmount must not exceed \$100,000")
    BigDecimal maxAmount
}
