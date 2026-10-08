package com.sboxmarket.service

import org.springframework.stereotype.Component

/**
 * Single place where every piece of user-provided free-text is cleaned before
 * it hits the database. The contract: the sanitized string is ALWAYS safe to
 * render as plain text OR as the inner text of a React/HTML element. No HTML
 * tags of any kind survive — we're plain-text-first, because the only place
 * free-text appears in the UI is inside text nodes (item descriptions,
 * support messages, offer notes, stall descriptions).
 *
 * What this does:
 *   - strips tags entirely (including `<script>`, `<img>`, `<iframe>`)
 *   - decodes + re-encodes the few ampersand-style entities we expect
 *   - collapses whitespace runs to a single space
 *   - trims to a max length so an attacker can't inflate the database
 *
 * What this is NOT:
 *   - an HTML whitelisting sanitizer. If we ever want to render user HTML
 *     we'll pull in JSoup with a strict allowlist. For now plain text is
 *     sufficient because no user text is ever injected as HTML on render.
 */
@Component
class TextSanitizer {

    // Length caps for every "kind" of user text in the app. Enforced at this
    // layer so callers can't forget to pass a limit.
    static final int LIMIT_SHORT   = 80        // labels, names, subjects
    static final int LIMIT_MEDIUM  = 500       // descriptions, ban reasons
    static final int LIMIT_LONG    = 2000      // support messages, ticket bodies

    /** Strip all HTML, normalise whitespace, cap at LIMIT_LONG. */
    String clean(String input) { clean(input, LIMIT_LONG) }

    String cleanShort(String input)  { clean(input, LIMIT_SHORT) }
    String cleanMedium(String input) { clean(input, LIMIT_MEDIUM) }
    String cleanLong(String input)   { clean(input, LIMIT_LONG) }

    String clean(String input, int maxLen) {
        if (input == null) return null
        def s = input.toString()

        // 1) Drop any HTML/XML tag — greedy but bounded, handles nested cases
        //    because we run it twice if the first pass found a match.
        while (s =~ /<[^>]*>/) { s = s.replaceAll(/<[^>]*>/, '') }

        // 2) Drop common XSS-ish protocols buried in plain text. The
        //    data: matcher now covers every executable MIME (svg, html,
        //    xml, javascript-pseudo) instead of just text/html — a raw
        //    `data:image/svg+xml` URL can contain an inline <script>.
        //    Also covers every RFC 4329 / WHATWG-accepted JS alias
        //    (text/javascript, text/ecmascript, application/x-javascript,
        //    application/ecmascript) plus text/xml — all functionally
        //    identical to application/javascript / application/xml in
        //    every shipping browser, so an attacker crafting
        //    `data:text/javascript,alert(1)` would otherwise survive a
        //    strip that only flagged the canonical MIMEs.
        s = s.replaceAll(/(?i)javascript:/, '')
        s = s.replaceAll(/(?i)vbscript:/, '')
        s = s.replaceAll(/(?i)data:(text\/(html|javascript|ecmascript|xml)|image\/svg|application\/(xhtml|xml|javascript|x-javascript|ecmascript))/, '')
        s = s.replaceAll(/(?i)on[a-z]+\s*=/, '')  // onerror=, onclick=, etc.

        // 3) Normalise numeric HTML entities that could re-encode tags.
        // Two alternations: decimal (34, 39, 60, 62, 96) and hex (22, 27,
        // 3C, 3E, 60). Each corresponds to one of the "dangerous" ASCII
        // characters — quote, apostrophe, <, >, backtick. Leading zeros
        // are allowed so an attacker can't route around with &#0060;.
        // The (?i) on the hex variant catches `&#X3C;` (capital X)
        // bypass — Java regex is case-sensitive by default, so without
        // the flag an attacker using `&#X3C;script&#X3E;` would survive
        // the strip and later decode to `<script>` in any HTML-aware
        // renderer.
        s = s.replaceAll(/&#0*(34|39|60|62|96);/, '')
        s = s.replaceAll(/(?i)&#x0*(22|27|3C|3E|60);/, '')

        // 4) Collapse whitespace, trim
        s = s.replaceAll(/\s+/, ' ').trim()

        // 5) Length cap
        if (s.length() > maxLen) s = s.substring(0, maxLen)
        s
    }

    /** Clean a short subject line (used by support tickets + offer notes). */
    String subject(String input) { clean(input, LIMIT_SHORT) }

    /** Clean a medium-length free-text field (stall descriptions, ban reasons). */
    String medium(String input) { clean(input, LIMIT_MEDIUM) }

    /** Clean a long-form message (support ticket body / CSR note). */
    String body(String input) { clean(input, LIMIT_LONG) }

    /**
     * Newline-preserving long-form clean. {@link #body} collapses EVERY
     * whitespace run, \n included, which flattens a multi-paragraph support
     * message into one line. This runs {@code sanitizer.body} per line and
     * rejoins with \n, clamps blank-line runs to one, and caps the result at
     * LIMIT_LONG. Static and routed through the given instance's body() so
     * every support path (user, CSR, admin) shares one implementation.
     */
    static String multiline(TextSanitizer sanitizer, String body) {
        if (body == null) return null
        // Normalise CRLF / lone CR so the split is consistent across clients.
        def lines = body.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1)
        def cleaned = new StringBuilder()
        int blankRun = 0
        for (String line : lines) {
            def c = sanitizer.body(line) ?: ''
            if (c.isEmpty()) {
                blankRun++
                if (blankRun > 1) continue          // clamp blank-line runs
            } else {
                blankRun = 0
            }
            if (cleaned.length() > 0) cleaned.append('\n')
            cleaned.append(c)
        }
        def result = cleaned.toString().trim()
        if (result.length() > LIMIT_LONG) {
            result = result.substring(0, LIMIT_LONG)
        }
        result
    }
}
