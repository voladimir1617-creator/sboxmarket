package com.sboxmarket.config

import groovy.util.logging.Slf4j
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Component

/**
 * Defence-in-depth wrapper for HttpSession.setAttribute that silently strips
 * NUL (0x00) bytes from String values before they reach Spring Session's
 * JDBC save path. A NUL inside a TEXT column of spring_session is what
 * detonated the site on 2026-05-02 (PSQLException SQLSTATE 22021 on every
 * read), and V59 added a Postgres CHECK constraint as the absolute backstop.
 *
 * Why this filter exists ABOVE V59:
 *   The DB CHECK fails the request with a ConstraintViolationException —
 *   visible to the user as a 500. This filter intercepts one layer earlier
 *   so a stray NUL surfaces as a logged WARN (with stack trace pointing at
 *   the offending setAttribute call site) and the request still succeeds
 *   with a sanitized value. Useful for diagnosing future regressions:
 *   the WARN names the attribute and the stack trace names the caller.
 *
 * Audit (2026-05-02 / commit f9737d9) confirmed every existing setAttribute
 * call site passes server-side Long values, so this filter should never
 * fire in normal operation. If it DOES fire, fix the upstream call site —
 * don't lean on this scrubber.
 *
 * Filter order: HIGHEST_PRECEDENCE + 100 (set in SessionAttributeNulStripConfig)
 *   — after Spring Session's SessionRepositoryFilter (~MIN_VALUE+50) so
 *   request.getSession() returns Spring Session's session, which we then
 *   wrap with NUL-stripping setAttribute. Ahead of every business filter.
 */
@Component
@Slf4j
class SessionAttributeNulStripFilter implements Filter {

    @Override
    void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        if (!(req instanceof HttpServletRequest)) {
            chain.doFilter(req, res)
            return
        }
        HttpServletRequest httpReq = (HttpServletRequest) req
        HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(httpReq) {
            @Override
            HttpSession getSession() {
                HttpSession s = super.getSession()
                s == null ? null : new NulStrippingHttpSession(s)
            }
            @Override
            HttpSession getSession(boolean create) {
                HttpSession s = super.getSession(create)
                s == null ? null : new NulStrippingHttpSession(s)
            }
        }
        chain.doFilter(wrapped, res)
    }
}

/**
 * HttpSession decorator: setAttribute strips NUL bytes from String values;
 * every other call delegates straight through. Kept in the same file as the
 * filter because it has no other call site and the two classes are useless
 * apart.
 */
@Slf4j
class NulStrippingHttpSession implements HttpSession {

    /** NUL constructed via char arithmetic so the source file itself never
     *  has to carry a literal 0x00 (which some editors / tools strip). */
    private static final char NUL_CHAR = (char) 0
    private static final String NUL_STR = String.valueOf(NUL_CHAR)

    private final HttpSession delegate

    NulStrippingHttpSession(HttpSession delegate) { this.delegate = delegate }

    @Override
    void setAttribute(String name, Object value) {
        if (value instanceof String && ((String) value).indexOf((int) NUL_CHAR) >= 0) {
            String s = (String) value
            String stripped = s.replace(NUL_STR, '')
            log.warn("Stripped NUL byte from session attribute '${name}' " +
                    "(orig len=${s.length()}, new len=${stripped.length()}) - " +
                    "fix the upstream call site, this filter is defence-in-depth only",
                    new Throwable("setAttribute call site"))
            delegate.setAttribute(name, stripped)
            return
        }
        delegate.setAttribute(name, value)
    }

    @Override long getCreationTime() { delegate.creationTime }
    @Override String getId() { delegate.id }
    @Override long getLastAccessedTime() { delegate.lastAccessedTime }
    @Override ServletContext getServletContext() { delegate.servletContext }
    @Override void setMaxInactiveInterval(int interval) { delegate.maxInactiveInterval = interval }
    @Override int getMaxInactiveInterval() { delegate.maxInactiveInterval }
    @Override Object getAttribute(String name) { delegate.getAttribute(name) }
    @Override Enumeration<String> getAttributeNames() { delegate.attributeNames }
    @Override void removeAttribute(String name) { delegate.removeAttribute(name) }
    @Override void invalidate() { delegate.invalidate() }
    @Override boolean isNew() { delegate.isNew() }
}
