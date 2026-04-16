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
 * This filter runs BEFORE RateLimitFilter (order 5) and CsrfFilter
 * (order 3) so an oversize body is rejected before any CSRF cookie
 * dance or bucket lookup fires.
 */
@Component
@Order(2)
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
