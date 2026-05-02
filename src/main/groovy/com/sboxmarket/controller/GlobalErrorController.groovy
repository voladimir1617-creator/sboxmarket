package com.sboxmarket.controller

import groovy.util.logging.Slf4j
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

import java.time.Instant

/**
 * Replaces Spring's Whitelabel error page. Two response shapes:
 *   - text/html requests get a small branded HTML page that doesn't
 *     leak framework strings (no "Whitelabel", no "/error", no
 *     "this application has no explicit mapping").
 *   - everything else (Accept: application/json, missing accept,
 *     or any /api/** path) gets a clean JSON envelope.
 *
 * Whitelabel must be disabled (server.error.whitelabel.enabled: false)
 * in application.yml so Spring doesn't race us for /error.
 *
 * Inline HTML rather than a ModelAndView so we don't need to drag in
 * a Thymeleaf / Freemarker dependency just for one page.
 */
@RestController
@Slf4j
class GlobalErrorController implements ErrorController {

    @RequestMapping(value = '/error', produces = MediaType.TEXT_HTML_VALUE)
    void handleHtmlError(HttpServletRequest req, HttpServletResponse res) {
        int status = resolveStatus(req)
        String path = (req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as String) ?: ''
        // API paths should never render HTML even if a misbehaving client
        // sends Accept: text/html — they'd 200 a bot scanning /api/* with
        // a "page not found" page. Force JSON for API surfaces.
        if (path.startsWith('/api/')) {
            res.setStatus(status)
            res.setContentType(MediaType.APPLICATION_JSON_VALUE)
            res.writer.write(toJson(status, path))
            res.writer.flush()
            return
        }
        res.setStatus(status)
        res.setContentType(MediaType.TEXT_HTML_VALUE)
        res.setCharacterEncoding('UTF-8')
        res.writer.write(renderHtml(status))
        res.writer.flush()
    }

    @RequestMapping('/error')
    ResponseEntity<Map> handleJsonError(HttpServletRequest req) {
        int status = resolveStatus(req)
        String path = (req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as String) ?: ''
        Map body = [
            status   : status,
            error    : reasonFor(status),
            message  : safeMessage(status),
            path     : path,
            timestamp: Instant.now().toString()
        ]
        return ResponseEntity.status(status).body(body)
    }

    private static int resolveStatus(HttpServletRequest req) {
        Object raw = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)
        if (raw instanceof Integer) return (int) raw
        if (raw instanceof Number) return ((Number) raw).intValue()
        return HttpStatus.INTERNAL_SERVER_ERROR.value()
    }

    private static String reasonFor(int status) {
        try {
            HttpStatus s = HttpStatus.resolve(status)
            return s != null ? s.reasonPhrase : 'Error'
        } catch (Exception ignore) {
            return 'Error'
        }
    }

    /**
     * Never echo the raw exception message — that's how Spring's
     * Whitelabel page leaks stack-trace fragments and SQL bits to
     * unauthenticated visitors. Map status codes to user-readable copy.
     */
    private static String safeMessage(int status) {
        switch (status) {
            case 400: return 'The request was malformed.'
            case 401: return 'Sign in to continue.'
            case 403: return 'You do not have permission for that.'
            case 404: return 'The page or resource was not found.'
            case 405: return 'That method is not allowed here.'
            case 409: return 'The request conflicts with the current state.'
            case 410: return 'This resource is no longer available.'
            case 413: return 'The request body is too large.'
            case 415: return 'The request media type is not supported.'
            case 418: return "I'm a teapot."
            case 429: return 'You are sending requests too quickly. Slow down and retry.'
            case 500: return 'Something went wrong on our end. The team has been notified.'
            case 502: return 'Upstream service did not respond.'
            case 503: return 'Service is temporarily unavailable.'
            case 504: return 'Upstream service timed out.'
            default:
                if (status >= 500) return 'Something went wrong on our end.'
                if (status >= 400) return 'The request could not be processed.'
                return 'An unexpected error occurred.'
        }
    }

    private static String renderHtml(int status) {
        String reason = reasonFor(status)
        String message = safeMessage(status)
        return '''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>''' + status + ' &middot; ' + escapeHtml(reason) + ''' &middot; skinbox.market</title>
<link rel="stylesheet" href="/css/design.css">
<style>
  html,body{margin:0;padding:0;background:var(--bg,#0b0b0c);color:var(--text,#eaeaea);font-family:Inter,system-ui,-apple-system,sans-serif;-webkit-font-smoothing:antialiased}
  .err-shell{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:48px 24px;box-sizing:border-box}
  .err-card{max-width:520px;width:100%;text-align:center}
  .err-code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:96px;font-weight:300;letter-spacing:-2px;line-height:1;margin:0 0 8px;color:var(--text,#eaeaea);opacity:.92}
  .err-reason{font-size:18px;font-weight:500;letter-spacing:.02em;text-transform:uppercase;margin:0 0 18px;color:var(--muted,#8a8a93)}
  .err-msg{font-size:15px;line-height:1.55;margin:0 0 28px;color:var(--text,#eaeaea);opacity:.78}
  .err-actions{display:flex;gap:10px;justify-content:center;flex-wrap:wrap}
  .err-btn{display:inline-flex;align-items:center;gap:8px;padding:9px 16px;border-radius:8px;border:1px solid var(--border,#2a2a31);background:transparent;color:var(--text,#eaeaea);text-decoration:none;font-size:13px;font-weight:500;letter-spacing:.01em;transition:transform .12s ease, box-shadow .12s ease, background .12s ease}
  .err-btn:hover{transform:translateY(-1px);background:rgba(255,255,255,.04)}
  .err-btn.primary{background:var(--cta,#3b82f6);border-color:var(--cta,#3b82f6);color:#fff}
  .err-btn.primary:hover{background:color-mix(in oklab, var(--cta,#3b82f6) 88%, white)}
  .err-mark{display:inline-block;margin:0 0 22px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;letter-spacing:.16em;text-transform:uppercase;color:var(--muted,#8a8a93);opacity:.7}
</style>
</head>
<body>
  <main class="err-shell">
    <div class="err-card">
      <div class="err-mark">skinbox.market</div>
      <h1 class="err-code">''' + status + '''</h1>
      <div class="err-reason">''' + escapeHtml(reason) + '''</div>
      <p class="err-msg">''' + escapeHtml(message) + '''</p>
      <div class="err-actions">
        <a href="/" class="err-btn primary">Back to home</a>
        <a href="/market" class="err-btn">Browse market</a>
      </div>
    </div>
  </main>
</body>
</html>'''
    }

    private static String toJson(int status, String path) {
        return '{"status":' + status +
            ',"error":"' + escapeJson(reasonFor(status)) + '"' +
            ',"message":"' + escapeJson(safeMessage(status)) + '"' +
            ',"path":"' + escapeJson(path) + '"' +
            ',"timestamp":"' + Instant.now().toString() + '"}'
    }

    private static String escapeJson(String s) {
        if (s == null) return ''
        return s.replace('\\', '\\\\').replace('"', '\\"').replace('\n', ' ').replace('\r', ' ')
    }

    private static String escapeHtml(String s) {
        if (s == null) return ''
        return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }
}
