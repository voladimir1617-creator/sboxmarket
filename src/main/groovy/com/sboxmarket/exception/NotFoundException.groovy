package com.sboxmarket.exception

import org.springframework.http.HttpStatus

class NotFoundException extends ApiException {
    NotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", "${resource} not found: ${id}")
    }
    NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message)
    }
}
