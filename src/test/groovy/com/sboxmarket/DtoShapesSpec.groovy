package com.sboxmarket

import com.sboxmarket.dto.ErrorResponse
import com.sboxmarket.dto.response.PageResponse
import spock.lang.Specification

import java.time.Instant

/**
 * Direct shape coverage for the two shared response DTOs. Both ship in
 * EVERY API response (ErrorResponse on the 4xx/5xx path, PageResponse on
 * every paged list endpoint) so a regression in the constructor / factory
 * shape would silently break the wire contract for the whole app.
 *
 *   - ErrorResponse: pinned timestamp default + factory shorthand
 *   - PageResponse:  pinned of() factory + generic-payload preservation
 */
class DtoShapesSpec extends Specification {

    // ── ErrorResponse ────────────────────────────────────────────────

    def "ErrorResponse no-arg constructor stamps a non-null timestamp"() {
        // Every 4xx/5xx body carries timestamp so a client can correlate
        // by wall clock; null timestamp would deserialise to a "1970-01-01"
        // surprise on a strict client.
        when:
        def e = new ErrorResponse()

        then:
        e.timestamp != null
        e.timestamp instanceof Instant
    }

    def "ErrorResponse map-arg constructor populates each field"() {
        // Groovy's map constructor is what GlobalExceptionHandler uses on
        // every error branch. The explicit field round-trip pins each
        // wire field name.
        when:
        def details = [field: 'price', reason: 'must be positive']
        def e = new ErrorResponse(
            code         : 'INVALID_PRICE',
            message      : 'Price must be positive',
            path         : '/api/listings',
            correlationId: 'abc12345',
            details      : details
        )

        then:
        e.code == 'INVALID_PRICE'
        e.message == 'Price must be positive'
        e.path == '/api/listings'
        e.correlationId == 'abc12345'
        e.details.is(details)
        // timestamp still defaults even when omitted from the map.
        e.timestamp != null
    }

    def "ErrorResponse.of factory leaves details + correlationId null"() {
        // The of() factory is used by the simpler controllers that don't
        // have MDC context to plant — the missing fields must come back
        // as JSON nulls, not 'undefined' or crash.
        when:
        def e = ErrorResponse.of('BAD_REQUEST', 'oops', '/api/x')

        then:
        e.code == 'BAD_REQUEST'
        e.message == 'oops'
        e.path == '/api/x'
        e.correlationId == null
        e.details == null
        e.timestamp != null
    }

    def "ErrorResponse details map carries through unchanged for validation field errors"() {
        // GlobalExceptionHandler.handleValidation puts a nested map under
        // details.fields — clients walk it as the source of truth for
        // per-field error highlighting. Round-trip preservation matters.
        given:
        def fields = [name: 'must not be blank', email: 'must be a valid address']

        when:
        def e = new ErrorResponse(
            code   : 'VALIDATION_FAILED',
            message: 'Request body failed validation',
            details: [fields: fields]
        )

        then:
        e.details.fields == fields
        e.details.fields.name == 'must not be blank'
        e.details.fields.email == 'must be a valid address'
    }

    // ── PageResponse ─────────────────────────────────────────────────

    def "PageResponse.of populates every paging field"() {
        when:
        def page = PageResponse.of(['a', 'b', 'c'], 100L, 10, 20)

        then:
        page.items == ['a', 'b', 'c']
        page.total == 100L
        page.limit == 10
        page.offset == 20
    }

    def "PageResponse preserves generic payload type through factory"() {
        // The generic type parameter is erased at runtime but element
        // identity must survive the factory unchanged — otherwise a
        // List<Listing> page would mysteriously decay to Object[].
        given:
        def listings = [
            [id: 1L, name: 'AK-47 Redline'],
            [id: 2L, name: 'AWP Asiimov']
        ]

        when:
        def page = PageResponse.of(listings, 2L, 50, 0)

        then:
        page.items.size() == 2
        page.items[0].is(listings[0])
        page.items[1].is(listings[1])
        page.total == 2L
    }

    def "PageResponse.of with an empty page still emits the paging window"() {
        // Empty result set is the most common edge case — the client
        // still needs total / limit / offset back so the "Showing 0 of
        // 0" UI renders without divide-by-zero NaNs.
        when:
        def page = PageResponse.of([], 0L, 25, 0)

        then:
        page.items == []
        page.items.isEmpty()
        page.total == 0L
        page.limit == 25
        page.offset == 0
    }

    def "PageResponse map constructor honours each field explicitly"() {
        when:
        def page = new PageResponse(items: [1, 2, 3], total: 99L, limit: 3, offset: 0)

        then:
        page.items == [1, 2, 3]
        page.total == 99L
        page.limit == 3
        page.offset == 0
    }
}
