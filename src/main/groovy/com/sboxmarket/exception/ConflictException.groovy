package com.sboxmarket.exception

import org.springframework.http.HttpStatus

/** Resource state prevents the requested action (e.g. listing already sold). */
class ConflictException extends ApiException {
    ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message)
    }
}

class InsufficientBalanceException extends ApiException {
    final BigDecimal required
    final BigDecimal available

    InsufficientBalanceException(BigDecimal required, BigDecimal available) {
        super(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_BALANCE",
              "Insufficient balance: need \$${required}, have \$${available}")
        this.required = required
        this.available = available
    }
}

class ListingNotAvailableException extends ApiException {
    ListingNotAvailableException(Long listingId) {
        super(HttpStatus.CONFLICT, "LISTING_NOT_AVAILABLE",
              "Listing ${listingId} is no longer available")
    }
}

class OfferNotPendingException extends ApiException {
    OfferNotPendingException(Long offerId, String status) {
        super(HttpStatus.CONFLICT, "OFFER_NOT_PENDING",
              "Offer ${offerId} is no longer pending (status: ${status})")
    }
}
