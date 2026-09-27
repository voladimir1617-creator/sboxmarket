package com.sboxmarket.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

class WithdrawRequest {
    @NotNull(message = "amount is required")
    // A CENT, not a dollar — and the change is load-bearing.
    //
    // The real minimum is DERIVED server-side from the payout rates and the
    // per-account charge (PlatformLedgerService.minWithdrawal), and it comes
    // with an exemption: a FULL-BALANCE withdrawal is never blocked by it,
    // because a minimum a balance cannot reach is a minimum that keeps the
    // balance. A hardcoded $1.00 here sat UNDERNEATH that exemption and
    // silently cancelled it for exactly the sellers it was written for — a
    // $0.60 balance on a rail with no per-account charge is payable
    // ($0.25 fee, $0.35 net), the wallet modal offers the sweep, the
    // derived minimum exempts it, and this annotation returned 400 before
    // any of that code ran. Stranded, by a validation constant three layers
    // away from the rates that justify it.
    //
    // The floor that remains is arithmetic, not policy: below a cent there
    // is no payable amount at all. Everything above a cent is decided where
    // the rates live — StripeService refuses WITHDRAWAL_BELOW_FEE when the
    // fees leave nothing and WITHDRAW_BELOW_MINIMUM when the amount is under
    // the derived minimum AND leaves a balance behind.
    @DecimalMin(value = "0.01", message = "minimum withdrawal is \$0.01")
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

    /** 6-digit TOTP code — required only when the user has 2FA enabled.
     *  Nullable for non-2FA users; when present it must be exactly 6 digits.
     *  The plain @Size(max=6) we had before let "1" or "" pass and only
     *  blew up later in the TOTP verifier, which made the failure mode
     *  noisier than it needed to be.
     *
     *  EMPTY is allowed and means "not supplied". The Wallet form always
     *  sends the 2FA input's value, which is "" for the (majority) user
     *  without 2FA -- and `^[0-9]{6}$` rejected that, so EVERY withdrawal
     *  submitted from the UI by a non-2FA user died as a 400
     *  VALIDATION_FAILED "Request body failed validation" before reaching
     *  the controller. A 2FA user who leaves it blank still gets the
     *  specific TOTP_REQUIRED from WalletController.withdraw. */
    @Pattern(regexp = '^([0-9]{6})?$', message = "totpCode must be exactly 6 digits")
    String totpCode
}
