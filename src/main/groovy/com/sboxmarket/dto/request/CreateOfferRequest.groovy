package com.sboxmarket.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

class CreateOfferRequest {
    @NotNull(message = "listingId is required")
    @Positive(message = "listingId must be positive")
    Long listingId

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "offer must be at least \$0.01")
    @DecimalMax(value = "100000.00", message = "offer must not exceed \$100,000")
    BigDecimal amount

    /** Optional buyer-supplied note ("brand new acct, fast pay"). 280-char
     *  cap matches the V43 column width. Sanitised + length-checked again
     *  in OfferService.makeOffer so direct service calls share the rule. */
    @Size(max = 280, message = "message must be 280 characters or fewer")
    String message
}
