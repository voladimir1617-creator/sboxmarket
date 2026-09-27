package com.sboxmarket.exception

import org.springframework.http.HttpStatus

class BadRequestException extends ApiException {
    BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message)
    }
    BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message)
    }
    /**
     * Cause-preserving ctor for the common "wrap a JDK parse failure" pattern
     * (e.g. catch NumberFormatException → throw BadRequestException). Previously
     * the parent ApiException had a cause-carrying ctor but no subclass exposed
     * it, so call sites that wrote `new BadRequestException(code, e.message)`
     * silently dropped the cause AND leaked the JDK exception text — which on
     * NumberFormatException echoes user input verbatim ("For input string: ...").
     * Use this ctor with a fixed safe message and pass the original exception
     * so the log line carries the full chain via Slf4j's `, ex` mechanism.
     */
    BadRequestException(String code, String message, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, code, message, cause)
    }
}
