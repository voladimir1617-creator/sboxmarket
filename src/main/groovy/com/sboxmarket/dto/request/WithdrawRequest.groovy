package com.sboxmarket.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

class WithdrawRequest {
    @NotNull(message = "amount is required")
    @DecimalMin(value = "1.00", message = "minimum withdrawal is \$1.00")
    // Cap per-call at $10,000 to match the deposit cap. Anything larger
    // needs to go through the admin-approved manual payout path. Without
    // this cap a user with an inflated balance (bug, mis-credit, admin
    // error) could drain the whole wallet in one request before fraud
    // analysis has a chance to fire. The actual wallet balance is also
    // checked in WalletController.withdraw.
    @DecimalMax(value = "10000.00", message = "maximum withdrawal per request is \$10,000")
    BigDecimal amount

    @Size(max = 255, message = "destination must be at most 255 characters")
    String destination

    /** 6-digit TOTP code — required only when the user has 2FA enabled. */
    @Size(max = 6, message = "totpCode must be 6 digits")
    String totpCode
}
