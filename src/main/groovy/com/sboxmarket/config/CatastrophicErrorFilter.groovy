package com.sboxmarket.config

import groovy.util.logging.Slf4j
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component

import java.nio.charset.StandardCharsets

/**
 * Last-resort filter that intercepts catastrophic, infrastructure-level
 * exceptions (Postgres outage, JDBC bind errors, integrity violations) and
 * serves a static branded HTML 500 panel WITHOUT touching the session,
 * the database, or the Spring MVC error pipeline.
 *
 * **Why this exists (the FOURTH NUL-byte session outage in 24h, 2026-05-03):**
 * When `spring_session` rows held a NUL byte at rest, every SELECT through
 * `SessionRepositoryFilter` threw `PSQLException` SQLSTATE 22021. The
 * Servlet container then dispatched to `/error` for an error page — but
 * `/error` ALSO ran through the same SessionRepositoryFilter, which threw
 * the same exception again. Tomcat fell through to its raw stub error
 * page, leaking framework strings to every visitor.
 *
 * **Fix shape:**
 * Register THIS filter at the highest precedence so it runs BEFORE
 * Spring Session's filter, anything Spring MVC, and the error dispatch.
 * Wrap `chain.doFilter` in try/catch. On any Postgres / DataAccess /
 * IntegrityViolation exception (matched by FQN so we don't have to import
 * Spring Data classes that may not be on the classpath), bypass the
 * normal error pipeline entirely and write a self-contained HTML 500
 * page directly to the response. Inline HTML — never a classpath
 * resource read — because resource loading itself can fail in
 * pathological JVM states.
 *
 * **What this is NOT:**
 *   - Not a replacement for `GlobalErrorController` — that handles
 *     normal 4xx/5xx with a richer page. This filter ONLY fires when
 *     the database/session subsystem itself is on fire.
 *   - Not a Spring Session sanitizer — `SessionCookieSanitizerFilter` +
 *     `SessionAttributeNulStripFilter` + V60 CHECK constraints handle
 *     the input/storage side. This filter is the read-side circuit
 *     breaker for when those defences fail.
 *
 * **Order:** Set explicitly via `CatastrophicErrorFilterConfig`
 * (`Ordered.HIGHEST_PRECEDENCE`).  The cookie sanitizer is bumped to
 * `HIGHEST_PRECEDENCE + 1` so this filter wraps EVERYTHING below
 * including the sanitizer's own work — if cookie sanitization itself
 * blows up (it shouldn't, it's pure string ops, but defence-in-depth),
 * the user still sees a branded 500 instead of a Tomcat stub.
 */
@Component
@Slf4j
class CatastrophicErrorFilter implements Filter {

    /** Exception class names treated as "DB/session subsystem on fire".
     *  Matched by FQN walked along the cause chain so a Spring-translated
     *  wrapper or a JDBC-driver-specific subclass still trips the catch.
     *  Add new classes here when a new outage flavour shows up. */
    private static final Set<String> CATASTROPHIC = [
        'org.postgresql.util.PSQLException',
        'org.springframework.dao.DataAccessResourceFailureException',
        'org.springframework.dao.DataIntegrityViolationException',
        'org.springframework.jdbc.CannotGetJdbcConnectionException',
        'org.springframework.jdbc.UncategorizedSQLException',
        'org.springframework.transaction.CannotCreateTransactionException'
    ] as Set

    @Override
    void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        if (!(req instanceof HttpServletRequest) || !(res instanceof HttpServletResponse)) {
            chain.doFilter(req, res)
            return
        }
        HttpServletRequest httpReq = (HttpServletRequest) req
        HttpServletResponse httpRes = (HttpServletResponse) res
        try {
            chain.doFilter(httpReq, httpRes)
        } catch (Throwable t) {
            if (!isCatastrophic(t)) {
                throw t
            }
            // Don't touch session here — that's exactly what's broken.
            // Reset the response so we don't fight a partial body the
            // downstream filter may have already started writing. If
            // committed (already streamed bytes), there's nothing we
            // can do — let the exception propagate to the container.
            if (httpRes.committed) {
                log.error("CATASTROPHIC-ERROR (response already committed, cannot rewrite) path=${httpReq.requestURI}", t)
                throw t
            }
            try { httpRes.resetBuffer() } catch (Exception ignore) { /* not committed but resetBuffer threw — proceed */ }
            httpRes.setStatus(500)
            String accept = httpReq.getHeader('Accept') ?: ''
            String path = httpReq.requestURI ?: '/'
            log.error("CATASTROPHIC-ERROR exception=${t.class.name} path=${path} — serving static panel (session-bypass)", t)
            if (path.startsWith('/api/') || accept.contains('application/json')) {
                writeJson(httpRes)
            } else {
                writeHtml(httpRes)
            }
        }
    }

    /** True if the exception (or any cause within the first 16 frames of the
     *  chain) matches a name in {@link #CATASTROPHIC}. Bounded depth prevents
     *  a self-referential cause chain from looping. */
    private static boolean isCatastrophic(Throwable t) {
        Throwable cur = t
        int depth = 0
        while (cur != null && depth < 16) {
            if (CATASTROPHIC.contains(cur.class.name)) return true
            cur = cur.cause
            depth++
        }
        false
    }

    private static void writeHtml(HttpServletResponse res) {
        res.setContentType('text/html')
        res.setCharacterEncoding('UTF-8')
        writeBody(res, STATIC_HTML)
    }

    private static void writeJson(HttpServletResponse res) {
        res.setContentType('application/json')
        res.setCharacterEncoding('UTF-8')
        writeBody(res, STATIC_JSON)
    }

    /**
     * Write the panel body, surviving whichever output sink the failed
     * downstream handler had already selected.
     *
     * The catch in {@link #doFilter} can fire AFTER a downstream component
     * (a JSON {@code @RestController} via Jackson, the static-resource
     * handler, a file download) has already called
     * {@code response.getOutputStream()}. The Servlet contract makes the
     * writer and the output stream mutually exclusive: once one is taken,
     * asking for the other throws {@link IllegalStateException}.
     * {@code resetBuffer()} clears the buffered body but does NOT undo
     * that selection — so a plain {@code response.getWriter()} here would
     * itself throw, defeating the entire last-resort filter and dropping
     * the visitor onto the raw Tomcat stub page.
     *
     * Try the writer first (the normal, uncontended case). If the stream
     * was already claimed, fall back to the byte stream. Exactly one of
     * the two is always available, so the branded panel is delivered
     * either way.
     */
    private static void writeBody(HttpServletResponse res, String body) {
        try {
            res.writer.write(body)
            res.writer.flush()
        } catch (IllegalStateException streamAlreadyTaken) {
            // getWriter() refused — getOutputStream() was already selected
            // downstream. Write raw UTF-8 bytes through the stream instead.
            def out = res.outputStream
            out.write(body.getBytes(StandardCharsets.UTF_8))
            out.flush()
        }
    }

    /** Self-contained HTML — no external CSS, no font fetches, nothing
     *  that could itself fail. Rendered identically whether design.css
     *  is reachable or not. */
    private static final String STATIC_HTML = '''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>500 · skinbox.market</title>
<style>
  html,body{margin:0;padding:0;background:#0b0b0c;color:#eaeaea;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,system-ui,sans-serif;-webkit-font-smoothing:antialiased}
  .err-shell{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:48px 24px;box-sizing:border-box}
  .err-card{max-width:520px;width:100%;text-align:center}
  .err-mark{display:inline-block;margin:0 0 22px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;letter-spacing:.16em;text-transform:uppercase;color:#8a8a93;opacity:.7}
  .err-code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:96px;font-weight:300;letter-spacing:-2px;line-height:1;margin:0 0 8px;opacity:.92}
  .err-reason{font-size:18px;font-weight:500;letter-spacing:.02em;text-transform:uppercase;margin:0 0 18px;color:#8a8a93}
  .err-msg{font-size:15px;line-height:1.55;margin:0 0 28px;opacity:.78}
  .err-actions{display:flex;gap:10px;justify-content:center;flex-wrap:wrap}
  .err-btn{display:inline-flex;align-items:center;padding:9px 16px;border-radius:8px;border:1px solid #2a2a31;background:transparent;color:#eaeaea;text-decoration:none;font-size:13px;font-weight:500;letter-spacing:.01em}
  .err-btn.primary{background:#3b82f6;border-color:#3b82f6;color:#fff}
</style>
</head>
<body>
  <main class="err-shell">
    <div class="err-card">
      <div class="err-mark">skinbox.market</div>
      <h1 class="err-code">500</h1>
      <div class="err-reason">Service unavailable</div>
      <p class="err-msg">A background system is temporarily down. Refresh in a moment, or try again shortly. The team has been paged.</p>
      <div class="err-actions">
        <a href="/" class="err-btn primary">Back to home</a>
        <a href="/market" class="err-btn">Browse market</a>
      </div>
    </div>
  </main>
</body>
</html>'''

    private static final String STATIC_JSON =
        '{"status":500,"error":"Service Unavailable","message":"A background system is temporarily down. Retry in a moment.","catastrophic":true}'
}
