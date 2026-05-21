package com.sboxmarket

import com.sboxmarket.config.GlobalExceptionHandler
import com.sboxmarket.dto.ErrorResponse
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import spock.lang.Specification
import spock.lang.Subject

/**
 * Safety net over the global exception handler. The important properties:
 *
 *   1) In non-verbose mode NotFound / Forbidden / Unauthorized / Conflict
 *      all collapse to generic messages so attackers can't enumerate
 *      resources or discover the internal error shape.
 *   2) The `path` field is null in non-verbose mode.
 *   3) Messages containing entity/table names get swallowed.
 *   4) InsufficientBalance is exempt — the message is user-safe and tells
 *      the buyer what they need to know.
 *   5) Validation failures surface the field names (they're safe).
 *   6) In verbose mode (dev / opt-in prod debug) the real message comes
 *      through unchanged.
 */
class GlobalExceptionHandlerSpec extends Specification {

    @Subject
    GlobalExceptionHandler handler = new GlobalExceptionHandler(verboseErrors: false)

    private MockHttpServletRequest req(String path = '/api/listings/42') {
        def r = new MockHttpServletRequest('GET', path)
        r
    }

    // ── Generic message rewrites ──────────────────────────────────

    def "NotFound becomes a generic 'Not found' without leaking the resource"() {
        when:
        def resp = handler.handleApi(new NotFoundException("Listing", 42L), req())

        then:
        resp.statusCode == HttpStatus.NOT_FOUND
        ErrorResponse body = resp.body
        body.message == 'Not found'
        body.code == 'NOT_FOUND'
        body.path == null
    }

    def "Forbidden collapses to generic message"() {
        when:
        def resp = handler.handleApi(new ForbiddenException("Not your wallet"), req())

        then:
        resp.statusCode == HttpStatus.FORBIDDEN
        resp.body.message == 'Forbidden'
    }

    def "Unauthorized collapses to 'Sign in required'"() {
        when:
        def resp = handler.handleApi(new UnauthorizedException(), req())

        then:
        resp.statusCode == HttpStatus.UNAUTHORIZED
        resp.body.message == 'Sign in required'
    }

    def "BadRequest with a short safe message is passed through"() {
        when:
        def resp = handler.handleApi(new BadRequestException("INVALID_PRICE", "Price must be positive"), req())

        then:
        resp.body.code == 'INVALID_PRICE'
        resp.body.message == 'Price must be positive'
    }

    def "BadRequest with an entity-name leak is swallowed"() {
        when:
        def resp = handler.handleApi(
            new BadRequestException("OOPS", "listing not found for user 42 in wallet 500"),
            req()
        )

        then:
        resp.body.message == 'Request could not be completed'
    }

    def "BadRequest with a very long message is swallowed"() {
        given:
        def longMsg = 'x' * 200

        when:
        def resp = handler.handleApi(new BadRequestException("OOPS", longMsg), req())

        then:
        resp.body.message == 'Request could not be completed'
    }

    def "InsufficientBalance passes its message through as it's user-safe"() {
        when:
        def resp = handler.handleApi(
            new InsufficientBalanceException(new BigDecimal("50"), new BigDecimal("10")),
            req()
        )

        then:
        resp.body.code == 'INSUFFICIENT_BALANCE'
        // Contains the numbers — that's expected and user-actionable
        resp.body.message != 'Request could not be completed'
    }

    def "InsufficientBalance exposes structured details (batch 963)"() {
        // Frontend composes a 'Top up $40 to continue' CTA from these
        // fields instead of string-parsing the message. Pin the shape.
        when:
        def resp = handler.handleApi(
            new InsufficientBalanceException(new BigDecimal("50.00"), new BigDecimal("10.00")),
            req()
        )

        then:
        resp.body.code == 'INSUFFICIENT_BALANCE'
        resp.body.details != null
        resp.body.details.required  == new BigDecimal('50.00')
        resp.body.details.available == new BigDecimal('10.00')
        resp.body.details.shortfall == new BigDecimal('40.00')
    }

    def "other ApiException subclasses do NOT get insufficient-balance details"() {
        when:
        def resp = handler.handleApi(new NotFoundException('Listing', 42L), req())

        then:
        // NotFound doesn't carry required/available fields; the handler
        // should leave details null rather than invent an empty map.
        resp.body.details == null
    }

    // ── Verbose mode ──────────────────────────────────────────────

    def "verbose mode returns the real error message and request path"() {
        given:
        def verbose = new GlobalExceptionHandler(verboseErrors: true)
        def request = req('/api/wallet/withdraw')

        when:
        def resp = verbose.handleApi(new NotFoundException("Wallet", 999L), request)

        then:
        resp.statusCode == HttpStatus.NOT_FOUND
        resp.body.message.contains('Wallet')  // real message passes through
        resp.body.path == '/api/wallet/withdraw'
    }

    // ── Client-state errors ───────────────────────────────────────

    def "IllegalArgumentException maps to 400 with generic message"() {
        when:
        def resp = handler.handleClientState(new IllegalArgumentException("Amount must be positive"), req())

        then:
        resp.statusCode == HttpStatus.BAD_REQUEST
        resp.body.code == 'BAD_REQUEST'
        resp.body.message == 'Request could not be completed'
    }

    def "IllegalStateException maps to 400 with generic message"() {
        when:
        def resp = handler.handleClientState(new IllegalStateException("Already processed"), req())

        then:
        resp.statusCode == HttpStatus.BAD_REQUEST
        resp.body.message == 'Request could not be completed'
    }

    // ── Query-param coercion errors ───────────────────────────────

    def "MethodArgumentTypeMismatchException returns 400 with the field name only"() {
        given:
        def ex = Mock(MethodArgumentTypeMismatchException)
        ex.name  >> 'minPrice'
        ex.value >> "1';drop table users"

        when:
        def resp = handler.handleTypeMismatch(ex, req())

        then:
        resp.statusCode == HttpStatus.BAD_REQUEST
        resp.body.code == 'INVALID_PARAMETER'
        resp.body.message == "Invalid value for parameter 'minPrice'"
        // The raw malicious value NEVER appears in the response
        !resp.body.message.contains('drop')
    }

    def "MissingServletRequestParameterException returns 400 pointing at the field"() {
        given:
        def ex = new MissingServletRequestParameterException('amount', 'BigDecimal')

        when:
        def resp = handler.handleMissingParam(ex, req())

        then:
        resp.statusCode == HttpStatus.BAD_REQUEST
        resp.body.code == 'MISSING_PARAMETER'
        resp.body.message.contains('amount')
    }

    // ── Protocol-level mappings (must NOT fall through to 500) ─────

    def "HttpRequestMethodNotSupportedException maps to 405, not a generic 500"() {
        given:
        def ex = new HttpRequestMethodNotSupportedException('POST', ['GET', 'HEAD'])

        when:
        def resp = handler.handleMethodNotSupported(ex, req('/api/listings/42'))

        then:
        resp.statusCode == HttpStatus.METHOD_NOT_ALLOWED
        resp.body.code == 'METHOD_NOT_ALLOWED'
        resp.body.path == null
        // The 405 carries an Allow header so clients can self-correct
        resp.headers.getFirst(HttpHeaders.ALLOW) != null
        resp.headers.getAllow().contains(HttpMethod.GET)
    }

    def "405 response never leaks the raw exception message"() {
        given:
        def ex = new HttpRequestMethodNotSupportedException('DELETE')

        when:
        def resp = handler.handleMethodNotSupported(ex, req())

        then:
        resp.statusCode == HttpStatus.METHOD_NOT_ALLOWED
        // Generic copy only — no servlet/framework internals
        resp.body.message == 'HTTP method not allowed for this endpoint'
        !resp.body.message.contains('DELETE')
    }

    def "HttpMediaTypeNotSupportedException maps to 415, not a generic 500"() {
        given:
        def ex = new HttpMediaTypeNotSupportedException('text/plain not supported')

        when:
        def resp = handler.handleMediaTypeNotSupported(ex, req('/api/offers'))

        then:
        resp.statusCode == HttpStatus.UNSUPPORTED_MEDIA_TYPE
        resp.body.code == 'UNSUPPORTED_MEDIA_TYPE'
        resp.body.message == 'Request media type is not supported'
        resp.body.path == null
    }

    def "HttpMediaTypeNotAcceptableException maps to 406, not a generic 500"() {
        given:
        def ex = new HttpMediaTypeNotAcceptableException('cannot produce text/csv')

        when:
        def resp = handler.handleMediaTypeNotAcceptable(ex, req('/api/listings'))

        then:
        resp.statusCode == HttpStatus.NOT_ACCEPTABLE
        resp.body.code == 'NOT_ACCEPTABLE'
        resp.body.path == null
    }

    def "MaxUploadSizeExceededException maps to 413, not a generic 500"() {
        given:
        def ex = new MaxUploadSizeExceededException(2L * 1024L * 1024L)

        when:
        def resp = handler.handleMaxUpload(ex, req('/api/profile/avatar'))

        then:
        resp.statusCode == HttpStatus.PAYLOAD_TOO_LARGE
        resp.body.code == 'PAYLOAD_TOO_LARGE'
        resp.body.message == 'Request body is too large'
        resp.body.path == null
    }

    def "protocol-level handlers echo the path only in verbose mode"() {
        given:
        def verbose = new GlobalExceptionHandler(verboseErrors: true)

        when:
        def resp = verbose.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException('PUT'), req('/api/listings/7'))

        then:
        resp.statusCode == HttpStatus.METHOD_NOT_ALLOWED
        resp.body.path == '/api/listings/7'
    }

    // ── Catch-all ─────────────────────────────────────────────────

    def "ObjectOptimisticLockingFailureException maps to 409 LISTING_NOT_AVAILABLE"() {
        given:
        def ex = new ObjectOptimisticLockingFailureException(
            "Row was updated or deleted by another transaction",
            com.sboxmarket.model.Listing
        )

        when:
        def resp = handler.handleOptimisticLock(ex, req('/api/listings/42/buy'))

        then:
        resp.statusCode == HttpStatus.CONFLICT
        resp.body.code == 'LISTING_NOT_AVAILABLE'
        resp.body.message.contains('no longer available')
    }

    def "unhandled Exception maps to INTERNAL_ERROR with a generic message"() {
        when:
        def resp = handler.handleAny(new RuntimeException("NPE at BuyOrderService line 42"), req())

        then:
        resp.statusCode == HttpStatus.INTERNAL_SERVER_ERROR
        resp.body.code == 'INTERNAL_ERROR'
        // Real message is NOT in the response
        !resp.body.message.contains('NPE')
        !resp.body.message.contains('BuyOrderService')
    }
}
