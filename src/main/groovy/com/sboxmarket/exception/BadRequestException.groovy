package com.sboxmarket.exception

import org.springframework.http.HttpStatus

class BadRequestException extends ApiException {
    BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message)
    }
    BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message)
    }
}
