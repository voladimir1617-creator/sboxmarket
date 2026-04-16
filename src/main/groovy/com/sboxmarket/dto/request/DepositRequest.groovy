package com.sboxmarket.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull

class DepositRequest {
    @NotNull(message = "amount is required")
    @DecimalMin(value = "1.00", message = "minimum deposit is \$1.00")
    @DecimalMax(value = "10000.00", message = "maximum deposit is \$10,000")
    BigDecimal amount
}
