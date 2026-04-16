package com.sboxmarket.dto

import java.time.Instant

/**
 * Consistent error payload for every 4xx/5xx response.
 * Clients branch on {@code code} — never on {@code message}.
 */
class ErrorResponse {
    String code
    String message
    String path
    String correlationId
    Instant timestamp = Instant.now()
    Map<String, Object> details   // optional structured context (e.g. field errors)

    static ErrorResponse of(String code, String message, String path) {
        new ErrorResponse(code: code, message: message, path: path)
    }
}
