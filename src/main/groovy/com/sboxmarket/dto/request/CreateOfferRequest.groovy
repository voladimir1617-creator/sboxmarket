package com.sboxmarket.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

class CreateOfferRequest {
    @NotNull(message = "listingId is required")
    @Positive(message = "listingId must be positive")
    Long listingId

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "offer must be at least \$0.01")
    @DecimalMax(value = "100000.00", message = "offer must not exceed \$100,000")
    BigDecimal amount
}
