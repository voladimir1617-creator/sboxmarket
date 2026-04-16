package com.sboxmarket.exception

import org.springframework.http.HttpStatus

class UnauthorizedException extends ApiException {
    UnauthorizedException() {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Sign in required")
    }
    UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message)
    }
}
