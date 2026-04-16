package com.sboxmarket.controller

import com.sboxmarket.dto.ErrorResponse
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Explicit 404 for URLs that used to serve sensitive admin surfaces. If an
 * attacker types any of these we want a hard 404 — NOT the SPA shell (which
 * the generic `addViewController` catch-all would otherwise serve).
 *
 * Handled here instead of a filter so the response status and body come
 * from a real controller bean that the exception handler can't redecorate.
 *
 * Ordering guarantee: `@RestController` methods beat `addViewController`
 * because Spring MVC runs RequestMappingHandlerMapping (order=0) before
 * the SimpleUrlHandlerMapping created by view controllers (order=1).
 */
@RestController
class BlockedPathsController {

    @RequestMapping([
        '/h2-console',
        '/h2-console/',
        '/h2-console/**',
        '/swagger-ui.html',
        '/swagger-ui',
        '/swagger-ui/**',
        '/api-docs',
        '/api-docs/**',
        '/v3/api-docs',
        '/v3/api-docs/**',
        // Actuator endpoints are fully disabled at the Spring Boot level, but
        // the SPA catch-all was happily serving index.html for /actuator/**,
        // which looked to scanners like "the endpoint exists, response is
        // just HTML". Return a real 404 so probes can't even confirm the
        // framework is Spring Boot from this path.
        '/actuator',
        '/actuator/',
        '/actuator/**'
    ])
    ResponseEntity<Void> blocked() {
        ResponseEntity.status(HttpStatus.NOT_FOUND).build()
    }

    /**
     * Catch-all for unmapped `/api/**` paths. Spring's MVC handler mapping
     * picks the most specific match first, so real API controllers (e.g.
     * `/api/listings`, `/api/profile/me`) still win. This only fires for
     * genuinely unrecognised paths like `/api/profile` (bare) or
     * `/api/doesnotexist`, which previously slipped through to the SPA
     * catch-all and returned `text/html` with 200 — masking the fact that
     * the endpoint didn't exist and letting scanners enumerate API shape.
     *
     * Declared in its own handler so the more specific "blocked" paths
     * above still match first in the sorted handler list.
     */
    @RequestMapping(['/api', '/api/', '/api/**'])
    ResponseEntity<ErrorResponse> apiNotFound() {
        // Return the same ErrorResponse shape as GlobalExceptionHandler so
        // clients can parse every 4xx/5xx with a single branch. Includes
        // the correlation id so this endpoint is traceable in the server
        // log even though it doesn't come through the handler.
        def body = new ErrorResponse(
            code         : 'NOT_FOUND',
            message      : 'Unknown API endpoint',
            correlationId: MDC.get('cid')
        )
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }
}
