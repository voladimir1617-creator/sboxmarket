package com.sboxmarket

import com.sboxmarket.controller.BlockedPathsController
import com.sboxmarket.dto.ErrorResponse
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Subject

/**
 * Direct unit coverage for the hard-404 controller that fronts the
 * sensitive admin paths (`/h2-console`, `/swagger-ui`, `/api-docs`,
 * `/actuator`) plus the `/api/**` fall-through. There's no routing
 * involved — we only need to prove:
 *
 *   - `blocked()` always returns 404 with an empty body (no framework
 *     leak, no implementation hint).
 *   - `apiNotFound()` returns 404 with an `ErrorResponse` carrying the
 *     correct `code` (`NOT_FOUND`), `message` (`Unknown API endpoint`),
 *     and the correlation id from MDC so traces can find it in logs.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class BlockedPathsControllerSpec extends Specification {

    @Subject
    BlockedPathsController controller = new BlockedPathsController()

    def "blocked() returns 404 NOT_FOUND with an empty body"() {
        when:
        def resp = controller.blocked()

        then:
        resp.statusCode == HttpStatus.NOT_FOUND
        // Body is Void — there is no content to leak.
        resp.body == null
    }

    def "apiNotFound() returns the canonical NOT_FOUND ErrorResponse"() {
        given: 'a correlation id in MDC as CorrelationIdFilter would set'
        MDC.put('cid', 'test-correlation-0001')

        when:
        def resp = controller.apiNotFound()

        then:
        resp.statusCode == HttpStatus.NOT_FOUND
        resp.body instanceof ErrorResponse

        and: 'client-parseable fields match the documented shape'
        resp.body.code == 'NOT_FOUND'
        resp.body.message == 'Unknown API endpoint'
        resp.body.correlationId == 'test-correlation-0001'

        cleanup:
        MDC.clear()
    }

    def "apiNotFound() without a correlation id still returns a stable body"() {
        given: 'MDC empty — a probe request that predates CorrelationIdFilter'
        MDC.clear()

        when:
        def resp = controller.apiNotFound()

        then: 'no NPE — correlationId is simply null for this response'
        resp.statusCode == HttpStatus.NOT_FOUND
        resp.body.code == 'NOT_FOUND'
        resp.body.message == 'Unknown API endpoint'
        resp.body.correlationId == null
    }
}
