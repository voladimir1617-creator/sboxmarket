package com.sboxmarket.controller

import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Client-side error reporting endpoint (batch 678). When React's
 * ErrorBoundary catches a render crash, the handler posts the error
 * here so the server logs capture what production users are actually
 * hitting. Without this, a crashing component disappears into the
 * user's browser console and nobody on the ops side ever sees it.
 *
 * Anonymous callers are allowed — unauthenticated visitors crash too,
 * and authenticating this endpoint would silently drop exactly the
 * first-visit / sign-in-flow errors we most want to catch.
 *
 * Defences:
 *   - Body-size cap (2MB default filter) catches any crafted flood.
 *   - Per-field char caps in this controller.
 *   - RateLimitFilter gates POSTs on /api/client-errors via the
 *     /api/client prefix so a malicious page can't burn log storage.
 *
 * Logs at WARN so a hot crash fires a PagerDuty alert if one is
 * configured, and stays discoverable via `grep client-error` in the
 * container logs.
 */
@RestController
@RequestMapping('/api/client-errors')
@Slf4j
class ClientErrorController {

    private static final int MAX_MESSAGE = 500
    private static final int MAX_STACK   = 4000
    private static final int MAX_URL     = 500
    private static final int MAX_UA      = 300

    @PostMapping
    ResponseEntity<Map> report(@RequestBody(required = false) Map body, HttpServletRequest req) {
        def message = clip((body?.message as String), MAX_MESSAGE)
        def stack   = clip((body?.stack   as String), MAX_STACK)
        def url     = clip(stripQuery(body?.url as String), MAX_URL)
        def ua      = clip((body?.userAgent as String) ?: req.getHeader('User-Agent'), MAX_UA)
        if (!message && !stack) {
            // Nothing to log. 204 rather than 400 so a crashing client
            // loop doesn't wedge on a retry storm hitting a 400 wall.
            return ResponseEntity.noContent().build()
        }
        def uidRaw = req?.session?.getAttribute(SteamAuthController.SESSION_USER_ID)
        def uid = uidRaw instanceof Long ? uidRaw : null
        log.warn("CLIENT-ERROR uid=${uid ?: 'anon'} url=${url ?: '-'} ua='${ua ?: '-'}' msg='${message ?: '-'}' stack=${stack ?: '-'}")
        ResponseEntity.ok([received: true])
    }

    /**
     * Drop the query string and fragment before logging the page URL.
     * The React ErrorBoundary posts `location.href`, and several app URLs
     * carry secrets in the query string — the unsubscribe link
     * (?email=&t=<HMAC>), the Stripe deposit return (?session_id=cs_live_…),
     * and email-verification links. A render crash on any of those pages
     * would otherwise write the live token/email straight into ops logs
     * (which ship to the aggregator at INFO+). Keep only scheme+host+path,
     * which is all that's useful for triaging where the crash happened.
     */
    private static String stripQuery(String url) {
        if (url == null) return null
        int cut = url.length()
        int q = url.indexOf('?'); if (q >= 0 && q < cut) cut = q
        int h = url.indexOf('#'); if (h >= 0 && h < cut) cut = h
        cut < url.length() ? url.substring(0, cut) + '?…' : url
    }

    private static String clip(String s, int max) {
        if (s == null) return null
        // Scrub control chars that would let a client-supplied field forge
        // or overwrite a log line: NUL, CRLF, lone LF, AND lone CR. The
        // earlier version handled '\r\n' and '\n' but not a bare '\r' — a
        // lone carriage return still returns the cursor to column 0 in a
        // terminal / log viewer and can overwrite the preceding output,
        // which is enough to spoof a log entry.
        def cleaned = s.replace('\u0000', '').replace('\r\n', ' ')
                       .replace('\n', ' ').replace('\r', ' ')
        cleaned.length() > max ? cleaned.substring(0, max) : cleaned
    }
}
