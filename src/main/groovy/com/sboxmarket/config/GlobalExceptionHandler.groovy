package com.sboxmarket.config

import com.sboxmarket.dto.ErrorResponse
import com.sboxmarket.exception.ApiException
import com.sboxmarket.exception.NotFoundException
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Single source of truth for mapping exceptions to HTTP responses.
 * Every 4xx/5xx returns the same {@link ErrorResponse} shape.
 *
 * Safety: the `path` field is NEVER returned to the client in production
 * because it helps attackers enumerate resources (the previous version
 * echoed `/api/listings/99999999` — free enumeration hint). The server-
 * side log line still carries the full path and correlation id so ops
 * can trace any request through MDC without leaking anything to clients.
 *
 * NotFound errors explicitly get a generic message in production too, so
 * "Listing not found: 99999999" becomes just "Not found" — probing the
 * API for valid ids returns an identical response whether the id exists.
 */
@ControllerAdvice
@Slf4j
class GlobalExceptionHandler {

    @Value('${security.verbose-errors:false}') boolean verboseErrors

    @ExceptionHandler(ApiException)
    ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest req) {
        log.debug("Domain exception at ${req.method} ${req.requestURI}: ${ex.code} ${ex.message}")
        def message = verboseErrors ? ex.message : genericMessage(ex)
        def body = new ErrorResponse(
            code         : ex.code,
            message      : message,
            path         : verboseErrors ? req.requestURI : null,
            correlationId: MDC.get("cid")
        )
        ResponseEntity.status(ex.status).body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        // Field errors are safe to return — they point at request fields the
        // client already knows about, not at database internals.
        def fieldErrors = [:]
        ex.bindingResult.fieldErrors.each { fe -> fieldErrors[fe.field] = fe.defaultMessage }
        def body = new ErrorResponse(
            code         : "VALIDATION_FAILED",
            message      : "Request body failed validation",
            path         : verboseErrors ? req.requestURI : null,
            correlationId: MDC.get("cid"),
            details      : [fields: fieldErrors]
        )
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(HttpMessageNotReadableException)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        def body = new ErrorResponse(
            code         : "MALFORMED_JSON",
            message      : "Request body is not valid JSON",
            path         : verboseErrors ? req.requestURI : null,
            correlationId: MDC.get("cid")
        )
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * Query-string type coercion failures. Previously the controller took
     * BigDecimal minPrice/maxPrice directly, and a value like `1;SELECT 1`
     * or `1'` crashed Spring's type converter with a NumberFormatException,
     * which bubbled up as a 500 and was trivially weaponised as a DoS.
     * Now: the converter exception is trapped here and the client sees a
     * 400 with the field name and no server internals.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        log.debug("Type mismatch at ${req.method} ${req.requestURI}: param=${ex.name} value=${ex.value}")
        def body = new ErrorResponse(
            code         : "INVALID_PARAMETER",
            message      : "Invalid value for parameter '${ex.name}'",
            path         : verboseErrors ? req.requestURI : null,
            correlationId: MDC.get("cid"),
            details      : [field: ex.name]
        )
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(MissingServletRequestParameterException)
    ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        def body = new ErrorResponse(
            code         : "MISSING_PARAMETER",
            message      : "Missing required parameter '${ex.parameterName}'",
            path         : verboseErrors ? req.requestURI : null,
            correlationId: MDC.get("cid")
        )
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(NoResourceFoundException)
    ResponseEntity<Void> handleStaticMiss(NoResourceFoundException ignored) {
        ResponseEntity.status(HttpStatus.NOT_FOUND).build()
    }

    /**
     * Hibernate optimistic-lock failure. Two concurrent transactions raced
     * the same row (typically a Listing being bought by two users at once);
     * the second one's @Version check fails here. We map it to 409 CONFLICT
     * with a LISTING_NOT_AVAILABLE code so the loser's frontend treats it
     * the same as "listing already sold" — which is exactly what happened.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException)
    ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                       HttpServletRequest req) {
        log.info("Optimistic lock conflict at ${req.method} ${req.requestURI}: ${ex.message}")
        def body = new ErrorResponse(
            code         : 'LISTING_NOT_AVAILABLE',
            message      : 'This listing is no longer available — someone else bought it first',
            path         : verboseErrors ? req.requestURI : null,
            correlationId: MDC.get("cid")
        )
        ResponseEntity.status(HttpStatus.CONFLICT).body(body)
    }

    /** Services throw these for "client asked for something impossible". Map to 400. */
    @ExceptionHandler([IllegalArgumentException, IllegalStateException])
    ResponseEntity<ErrorResponse> handleClientState(RuntimeException ex, HttpServletRequest req) {
        log.debug("Client state error at ${req.method} ${req.requestURI}: ${ex.message}")
        def body = new ErrorResponse(
            code         : 'BAD_REQUEST',
            message      : verboseErrors ? ex.message : 'Request could not be completed',
            path         : verboseErrors ? req.requestURI : null,
            correlationId: MDC.get("cid")
        )
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(Exception)
    ResponseEntity<ErrorResponse> handleAny(Exception ex, HttpServletRequest req) {
        log.error("Unhandled ${ex.class.simpleName} at ${req.method} ${req.requestURI}", ex)
        def body = new ErrorResponse(
            code         : "INTERNAL_ERROR",
            message      : "An unexpected error occurred. Reference: ${MDC.get('cid') ?: 'n/a'}",
            path         : verboseErrors ? req.requestURI : null,
            correlationId: MDC.get("cid")
        )
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
    }

    /**
     * Maps specific exceptions onto generic messages so attackers can't tell
     * a "missing id" from a "wrong id format" from a "valid id but forbidden".
     * The original message still ends up in the server logs via the MDC.
     */
    private String genericMessage(ApiException ex) {
        switch (ex.code) {
            case 'NOT_FOUND':            return 'Not found'
            case 'FORBIDDEN':            return 'Forbidden'
            case 'UNAUTHORIZED':         return 'Sign in required'
            case 'CONFLICT':             return 'Conflict'
            case 'INSUFFICIENT_BALANCE': return ex.message  // safe — no internal info
            case 'RATE_LIMITED':         return 'Too many requests'
            default:
                // For explicit validation-style errors the message is usually
                // user-actionable and safe ("Amount must be positive"), so we
                // keep it. Anything longer than 140 chars or mentioning an
                // entity name gets swallowed.
                def m = ex.message ?: ''
                if (m.length() > 140 || m =~ /(?i)(entity|table|column|listing|wallet|transaction|steam ?user|offer|bid|loadout|ticket) not found/) {
                    return 'Request could not be completed'
                }
                return m
        }
    }
}
