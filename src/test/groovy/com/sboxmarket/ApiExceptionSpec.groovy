package com.sboxmarket

import com.sboxmarket.exception.ApiException
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ConflictException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.OfferNotPendingException
import com.sboxmarket.exception.UnauthorizedException
import org.springframework.http.HttpStatus
import spock.lang.Specification

/**
 * Direct constructor + getter coverage for the {@link ApiException}
 * hierarchy. Every domain exception in the app derives from this base
 * and is matched in {@code GlobalExceptionHandler.handleApi} on its
 * {@code status} + {@code code} fields, so a regression in how the
 * exception's own constructor wires those values would silently mis-map
 * every request through the whole 4xx/5xx surface.
 *
 * The handler itself is exercised in {@code GlobalExceptionHandlerSpec};
 * this spec pins the contract of the exception types in isolation so a
 * subtle refactor to a subclass (default code, message template, extra
 * field) is caught by a tightly-scoped failure.
 */
class ApiExceptionSpec extends Specification {

    // ── ApiException root contract ───────────────────────────────────

    def "ApiException carries status, code, and message via the no-cause constructor"() {
        given:
        def ex = new TestApiException(HttpStatus.I_AM_A_TEAPOT, 'TEAPOT', 'short and stout')

        expect:
        ex.status == HttpStatus.I_AM_A_TEAPOT
        ex.code == 'TEAPOT'
        ex.message == 'short and stout'
        ex.cause == null
    }

    def "ApiException preserves the cause through the cause constructor"() {
        // The cause-carrying ctor is used by services that re-wrap a
        // checked DB / IO exception as a domain ApiException — losing
        // the cause would break the cause-chain walk in
        // CatastrophicErrorFilter + OutageObservabilityFilter.
        given:
        def root = new IllegalStateException('underlying')
        def ex = new TestApiException(HttpStatus.BAD_GATEWAY, 'UPSTREAM', 'gateway down', root)

        expect:
        ex.status == HttpStatus.BAD_GATEWAY
        ex.code == 'UPSTREAM'
        ex.message == 'gateway down'
        ex.cause.is(root)
    }

    def "ApiException is a RuntimeException so callers can throw it without declaration"() {
        // ApiException must stay unchecked — every controller and service
        // throws ApiException variants without `throws` declarations.
        // Flipping to checked would force a recompile of the entire app.
        expect:
        RuntimeException.isAssignableFrom(ApiException)
    }

    // ── BadRequestException ──────────────────────────────────────────

    def "BadRequestException single-arg constructor defaults code to BAD_REQUEST"() {
        when:
        def ex = new BadRequestException('Price must be positive')

        then:
        ex.status == HttpStatus.BAD_REQUEST
        ex.code == 'BAD_REQUEST'
        ex.message == 'Price must be positive'
    }

    def "BadRequestException two-arg constructor accepts a custom code"() {
        when:
        def ex = new BadRequestException('INVALID_PRICE', 'Price must be positive')

        then:
        ex.status == HttpStatus.BAD_REQUEST
        ex.code == 'INVALID_PRICE'
        ex.message == 'Price must be positive'
    }

    // ── NotFoundException ────────────────────────────────────────────

    def "NotFoundException (resource, id) composes a default message"() {
        when:
        def ex = new NotFoundException('Listing', 42L)

        then:
        ex.status == HttpStatus.NOT_FOUND
        ex.code == 'NOT_FOUND'
        ex.message == 'Listing not found: 42'
    }

    def "NotFoundException single-arg constructor uses the supplied message verbatim"() {
        when:
        def ex = new NotFoundException('Custom not-found message')

        then:
        ex.status == HttpStatus.NOT_FOUND
        ex.code == 'NOT_FOUND'
        ex.message == 'Custom not-found message'
    }

    // ── ForbiddenException / UnauthorizedException ───────────────────

    def "ForbiddenException always carries the FORBIDDEN code"() {
        when:
        def ex = new ForbiddenException('Not your wallet')

        then:
        ex.status == HttpStatus.FORBIDDEN
        ex.code == 'FORBIDDEN'
        ex.message == 'Not your wallet'
    }

    def "UnauthorizedException no-arg constructor uses the canonical sign-in copy"() {
        // The canonical phrasing 'Sign in required' is exercised by
        // GlobalExceptionHandler.genericMessage AND by frontend i18n —
        // changing it silently would split the message between the two.
        when:
        def ex = new UnauthorizedException()

        then:
        ex.status == HttpStatus.UNAUTHORIZED
        ex.code == 'UNAUTHORIZED'
        ex.message == 'Sign in required'
    }

    def "UnauthorizedException single-arg constructor accepts a custom message"() {
        when:
        def ex = new UnauthorizedException('Session expired')

        then:
        ex.status == HttpStatus.UNAUTHORIZED
        ex.code == 'UNAUTHORIZED'
        ex.message == 'Session expired'
    }

    // ── ConflictException family ─────────────────────────────────────

    def "ConflictException carries the supplied code"() {
        when:
        def ex = new ConflictException('LISTING_NOT_AVAILABLE', 'already gone')

        then:
        ex.status == HttpStatus.CONFLICT
        ex.code == 'LISTING_NOT_AVAILABLE'
        ex.message == 'already gone'
    }

    def "InsufficientBalanceException returns PAYMENT_REQUIRED + structured amounts"() {
        // GlobalExceptionHandler reads .required + .available off this
        // subclass to build the structured `details` payload the frontend
        // turns into a precise "Top up $X" CTA. If the fields aren't
        // accessible on the exception instance the structured shape
        // silently collapses to nulls and the CTA becomes a string-parse.
        when:
        def ex = new InsufficientBalanceException(new BigDecimal('100.50'), new BigDecimal('25.00'))

        then:
        ex.status == HttpStatus.PAYMENT_REQUIRED
        ex.code == 'INSUFFICIENT_BALANCE'
        ex.required == new BigDecimal('100.50')
        ex.available == new BigDecimal('25.00')
        // Message format matters — the handler whitelists this exception's
        // message as 'safe to return verbatim'.
        ex.message.contains('100.50')
        ex.message.contains('25.00')
    }

    def "ListingNotAvailableException encodes the listing id in the message"() {
        when:
        def ex = new ListingNotAvailableException(789L)

        then:
        ex.status == HttpStatus.CONFLICT
        ex.code == 'LISTING_NOT_AVAILABLE'
        ex.message.contains('789')
    }

    def "OfferNotPendingException encodes both offer id and status"() {
        when:
        def ex = new OfferNotPendingException(555L, 'ACCEPTED')

        then:
        ex.status == HttpStatus.CONFLICT
        ex.code == 'OFFER_NOT_PENDING'
        ex.message.contains('555')
        ex.message.contains('ACCEPTED')
    }

    // ── Hierarchy contract ───────────────────────────────────────────

    def "every concrete domain exception extends ApiException"() {
        // GlobalExceptionHandler.handleApi switches on ApiException as
        // the catch type — if a new subclass forgot to extend the base
        // class it would fall through to handleAny and return 500.
        expect:
        ApiException.isAssignableFrom(BadRequestException)
        ApiException.isAssignableFrom(NotFoundException)
        ApiException.isAssignableFrom(ForbiddenException)
        ApiException.isAssignableFrom(UnauthorizedException)
        ApiException.isAssignableFrom(ConflictException)
        ApiException.isAssignableFrom(InsufficientBalanceException)
        ApiException.isAssignableFrom(ListingNotAvailableException)
        ApiException.isAssignableFrom(OfferNotPendingException)
    }

    /** Concrete subclass for testing the abstract base — ApiException
     *  is abstract so we can't instantiate it directly. */
    private static class TestApiException extends ApiException {
        TestApiException(HttpStatus s, String c, String m) { super(s, c, m) }
        TestApiException(HttpStatus s, String c, String m, Throwable t) { super(s, c, m, t) }
    }
}
