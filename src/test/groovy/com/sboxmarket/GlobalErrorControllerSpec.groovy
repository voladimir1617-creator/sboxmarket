package com.sboxmarket

import com.sboxmarket.controller.GlobalErrorController
import jakarta.servlet.RequestDispatcher
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pins the branded error-page contract that replaced Spring's Whitelabel:
 *   - text/html browser request → SkinBox-themed HTML (no "Whitelabel"
 *     string, no "/error" path leak), unless the path is /api/** in
 *     which case JSON wins regardless of Accept header.
 *   - application/json + every /api/** → clean JSON envelope with
 *     status-code-specific user-readable copy. NEVER echoes the raw
 *     exception message (that's how Whitelabel used to leak SQL/stack
 *     to anonymous visitors).
 */
class GlobalErrorControllerSpec extends Specification {

    @Subject
    GlobalErrorController controller = new GlobalErrorController()

    def "browser HTML request on a non-API path returns branded HTML, not Whitelabel"() {
        given:
        def req = new MockHttpServletRequest('GET', '/error')
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404)
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, '/missing')
        def res = new MockHttpServletResponse()

        when:
        controller.handleHtmlError(req, res)

        then:
        res.status == 404
        res.contentType.startsWith('text/html')
        res.contentAsString.toLowerCase().contains('skinbox')
        res.contentAsString.contains('404')
        // Critical: never leak framework strings
        !res.contentAsString.toLowerCase().contains('whitelabel')
        !res.contentAsString.contains('/error')
    }

    def "browser HTML request on an /api/** path forces JSON, even with text/html Accept"() {
        // A bot crawling /api/listings/99 with Accept: text/html shouldn't
        // get a SkinBox-themed "Page not found" HTML page — that 200's a
        // crawler past a missing resource. Force JSON for API paths.
        given:
        def req = new MockHttpServletRequest('GET', '/error')
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404)
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, '/api/listings/99')
        def res = new MockHttpServletResponse()

        when:
        controller.handleHtmlError(req, res)

        then:
        res.status == 404
        res.contentType.startsWith('application/json')
        res.contentAsString.contains('"status":404')
        res.contentAsString.contains('"path":"/api/listings/99"')
        !res.contentAsString.toLowerCase().contains('whitelabel')
    }

    def "JSON request returns clean envelope with status + error + safe message + path"() {
        given:
        def req = new MockHttpServletRequest('GET', '/error')
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500)
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, '/api/listings/something')

        when:
        def response = controller.handleJsonError(req)

        then:
        response.statusCode == HttpStatus.INTERNAL_SERVER_ERROR
        Map body = response.body as Map
        body.status == 500
        body.error == 'Internal Server Error'
        body.path == '/api/listings/something'
        body.timestamp != null
        // Safe-message copy must NOT contain a stack trace fragment / SQL bit
        !(body.message as String).contains('Exception')
        !(body.message as String).contains('SELECT')
    }

    def "missing ERROR_STATUS_CODE attribute defaults to 500"() {
        given:
        def req = new MockHttpServletRequest('GET', '/error')
        // No ERROR_STATUS_CODE attribute set
        def res = new MockHttpServletResponse()

        when:
        controller.handleHtmlError(req, res)

        then:
        res.status == 500
        res.contentType.startsWith('text/html')
        res.contentAsString.contains('500')
    }

    def "status code 429 (rate limited) gets the right user copy"() {
        given:
        def req = new MockHttpServletRequest('GET', '/error')
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 429)
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, '/api/listings')

        when:
        def response = controller.handleJsonError(req)

        then:
        Map body = response.body as Map
        body.status == 429
        body.error == 'Too Many Requests'
        // The safe-message map covers 429 specifically
        (body.message as String).toLowerCase().contains('slow down')
    }

    def "status code 401 message points to sign-in"() {
        given:
        def req = new MockHttpServletRequest('GET', '/error')
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 401)
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, '/api/wallet')

        when:
        def response = controller.handleJsonError(req)

        then:
        Map body = response.body as Map
        body.status == 401
        body.error == 'Unauthorized'
        (body.message as String).toLowerCase().contains('sign in')
    }
}
