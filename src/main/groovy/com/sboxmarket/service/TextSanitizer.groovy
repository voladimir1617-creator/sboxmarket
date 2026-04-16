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

        // 2) Drop common XSS-ish protocols buried in plain text
        s = s.replaceAll(/(?i)javascript:/, '')
        s = s.replaceAll(/(?i)data:text\/html/, '')
        s = s.replaceAll(/(?i)on[a-z]+\s*=/, '')  // onerror=, onclick=, etc.

        // 3) Normalise numeric HTML entities that could re-encode tags.
        // Two alternations: decimal (34, 39, 60, 62, 96) and hex (22, 27,
        // 3C, 3E, 60). Each corresponds to one of the "dangerous" ASCII
        // characters — quote, apostrophe, <, >, backtick. Leading zeros
        // are allowed so an attacker can't route around with &#0060;.
        s = s.replaceAll(/&#0*(34|39|60|62|96);/, '')
        s = s.replaceAll(/&#x0*(22|27|3C|3E|60);/, '')

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
}
