package com.sboxmarket.exception

import org.springframework.http.HttpStatus

class ForbiddenException extends ApiException {
    ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message)
    }
}
