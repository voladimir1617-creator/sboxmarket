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
 * Rule: business logic SHOULD throw ApiException subclasses so the
 *       status/code/message are explicit and intentional.
 *
 * Note on raw exceptions (see GlobalExceptionHandler):
 *   - IllegalArgumentException / IllegalStateException are treated as
 *     "client asked for something impossible" and map to 400 BAD_REQUEST.
 *     Services do throw these deliberately; they are not necessarily bugs.
 *   - Any other unhandled RuntimeException is an unanticipated fault and
 *     maps to 500 INTERNAL_ERROR with a generic, non-leaking body.
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
