package com.sboxmarket.exception

import org.springframework.http.HttpStatus

/**
 * Root of every domain exception surfaced to clients.
 *
 * Subclasses carry:
 *   - an HTTP status (mapped 1:1 by GlobalExceptionHandler)
 *   - a stable machine-readable {@code code} (clients branch on this, never on message)
 *   - a human-readable message (shown to users as-is)
 *
 * Rule: business logic throws ONLY ApiException subclasses.
 *       Raw IllegalStateException / RuntimeException are bugs and become 500s.
 */
abstract class ApiException extends RuntimeException {
    final HttpStatus status
    final String code

    ApiException(HttpStatus status, String code, String message) {
        super(message)
        this.status = status
        this.code = code
    }

    ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause)
        this.status = status
        this.code = code
    }
}
