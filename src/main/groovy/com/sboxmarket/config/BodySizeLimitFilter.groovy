package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import groovy.util.logging.Slf4j
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Rejects any POST/PUT/PATCH whose declared `Content-Length` exceeds
 * `MAX_BODY_BYTES` with a 413 Payload Too Large, BEFORE Tomcat starts
 * draining the body.
 *
 * Why this filter exists:
 *   - Spring Boot's `spring.servlet.multipart.max-*` only gates
 *     `multipart/form-data` uploads.
 *   - Tomcat's `max-http-form-post-size` only gates
 *     `application/x-www-form-urlencoded` bodies.
 *   - Neither caps a raw `application/json` POST, so a client could
 *     ship a 100MB JSON body and hold a Tomcat worker hostage until
 *     Jackson either succeeds or blows up the heap.
 *
 * Cap chosen: 2MB. Our largest legitimate payload is the 50-item cart
 * checkout (< 10KB) plus support ticket bodies (< 4KB). 2MB leaves a
 * generous safety margin and still rejects abuse at the front door.
 *
 * This filter runs BEFORE ApiKeyAuthFilter (order 2), RateLimitFilter
 * (order 5) and CsrfFilter (order 3) so an oversize body is rejected
 * before any bearer-token lookup, CSRF cookie
 * dance or bucket lookup fires.
 */
@Component
@Order(1)
@Slf4j
class BodySizeLimitFilter extends OncePerRequestFilter {

    private static final long MAX_BODY_BYTES = 2L * 1024L * 1024L

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        def method = req.method?.toUpperCase()
        if (method in ['POST', 'PUT', 'PATCH']) {
            long contentLength
            try {
                contentLength = req.contentLengthLong
            } catch (Exception ignore) {
                contentLength = req.contentLength as long
            }
            // Reject chunked transfer-encoding explicitly. A client
            // sending `Transfer-Encoding: chunked` with no Content-Length
            // header sets contentLength to -1 — without this reject, the
            // > MAX_BODY_BYTES check silently passes and a malicious
            // caller can stream an arbitrarily large body through
            // Jackson. Every legitimate client (browsers, Stripe
            // webhooks, curl) defaults to identity transfer + a
            // Content-Length header. Empty-body POSTs (logout etc)
            // have content-length=0 and pass through unchanged.
            def transferEncoding = req.getHeader('Transfer-Encoding')
            if (transferEncoding != null && transferEncoding.toLowerCase().contains('chunked')) {
                log.warn("Rejecting ${method} ${req.requestURI}: Transfer-Encoding: chunked disallowed (streaming-body DoS risk)")
                resp.status = 411
                resp.contentType = 'application/json'
                resp.setHeader('Connection', 'close')
                resp.writer.write('{"code":"LENGTH_REQUIRED","message":"Chunked transfer-encoding is not accepted — include a Content-Length header."}')
                return
            }
            if (contentLength > MAX_BODY_BYTES) {
                log.warn("Rejecting oversize ${method} ${req.requestURI}: content-length=${contentLength} > ${MAX_BODY_BYTES}")
                resp.status = 413
                resp.contentType = 'application/json'
                resp.setHeader('Connection', 'close')
                resp.writer.write('{"code":"PAYLOAD_TOO_LARGE","message":"Request body exceeds the 2MB cap."}')
                return
            }
        }
        chain.doFilter(req, resp)
    }
}
