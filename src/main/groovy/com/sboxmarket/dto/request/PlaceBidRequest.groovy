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

    /** Optional auto-bid ceiling — bot will keep bidding up to this on user's behalf.
     *  Nullable (a plain manual bid omits it); when present it must be a positive
     *  amount. BidService.placeBid additionally rejects a cap below `amount`, but
     *  the DTO still needs its own lower bound so a negative ceiling never reaches
     *  the service — every other money field here is bounded on both ends. */
    @DecimalMin(value = "0.01", message = "maxAmount must be at least \$0.01")
    @DecimalMax(value = "100000.00", message = "maxAmount must not exceed \$100,000")
    BigDecimal maxAmount
}
