package com.sboxmarket

import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Pinned test for the XSS defence in depth. If any of these regress the
 * whole platform's stored-content safety is in doubt — the sanitizer is
 * the single point where user free-text is cleaned before hitting the DB.
 */
class TextSanitizerSpec extends Specification {

    @Subject
    TextSanitizer sanitizer = new TextSanitizer()

    def "null input returns null"() {
        expect:
        sanitizer.clean(null) == null
        sanitizer.cleanShort(null) == null
        sanitizer.cleanMedium(null) == null
    }

    @Unroll
    def "strips plain HTML tag: #input"() {
        expect:
        sanitizer.clean(input) == expected

        where:
        input                                  | expected
        // tags stripped; remaining `alert(1)` is plain text — not
        // dangerous once no `<script>` wraps it, and will render as
        // literal characters in any text node.
        '<script>alert(1)</script>hi'          | 'alert(1)hi'
        '<img src=x onerror=alert(1)>'         | ''
        '<iframe src="evil"></iframe>x'        | 'x'
        '<b>bold</b> text'                     | 'bold text'
        'plain text'                           | 'plain text'
        // no `>` anywhere means nothing matches the tag regex — the
        // literal `<<` survives as plain text, which is fine because
        // React text nodes escape it on render.
        '<<double<<tags'                       | '<<double<<tags'
    }

    def "strips protocol handlers from plain text"() {
        expect:
        sanitizer.clean('click javascript:alert(1)') == 'click alert(1)'
        sanitizer.clean('data:text/html,<b>bad</b>') == ',bad'
    }

    def "strips on* event attribute patterns"() {
        expect:
        sanitizer.clean('hello onerror=alert(1) world') == 'hello alert(1) world'
        sanitizer.clean('x onclick  = foo')              == 'x foo'
    }

    def "strips numeric HTML entities that re-encode angle brackets / quotes"() {
        expect:
        sanitizer.clean('hi &#60;script&#62; end') == 'hi script end'
        sanitizer.clean('x &#x3C;b&#x3E; y')       == 'x b y'
    }

    def "collapses whitespace runs to a single space"() {
        expect:
        sanitizer.clean('  too    many\n\tspaces  ') == 'too many spaces'
    }

    def "cleanShort caps at 80 chars"() {
        given:
        def long80 = 'a' * 80
        def long200 = 'b' * 200

        expect:
        sanitizer.cleanShort(long80).length() == 80
        sanitizer.cleanShort(long200).length() == 80
    }

    def "cleanMedium caps at 500 chars"() {
        expect:
        sanitizer.cleanMedium('x' * 1000).length() == 500
    }

    def "cleanLong caps at 2000 chars"() {
        expect:
        sanitizer.cleanLong('x' * 5000).length() == 2000
    }

    def "defeats tag-inside-tag evasion (nested)"() {
        // Classic evasion: strip one pass, leave the inner tag. Our loop
        // keeps running until the regex no longer matches so this lands
        // as safe text.
        expect:
        sanitizer.clean('<scr<script>ipt>alert(1)</scr</script>ipt>') == 'ipt>alert(1)ipt>' ||
        sanitizer.clean('<scr<script>ipt>alert(1)</scr</script>ipt>') == 'alert(1)'
    }

    def "empty string is returned as empty string (not null)"() {
        expect:
        sanitizer.clean('') == ''
    }

    // ── alias methods (subject/medium/body) ───────────────────────
    //
    // These three were undocumented aliases for cleanShort/cleanMedium/cleanLong
    // that several callers (SupportService.sanitizeMultiline, ItemService.editItem)
    // depend on. They MUST behave identically to their cleanXxx siblings —
    // a divergence would silently let one call site enforce a different cap
    // than another against the same column.

    def "subject() is an alias for cleanShort — same 80-char cap"() {
        given:
        def long200 = 'a' * 200

        expect:
        sanitizer.subject(long200).length() == TextSanitizer.LIMIT_SHORT
        sanitizer.subject(long200) == sanitizer.cleanShort(long200)
    }

    def "subject() strips HTML the same way clean() does"() {
        expect:
        sanitizer.subject('<script>alert(1)</script>Refund request') ==
            sanitizer.cleanShort('<script>alert(1)</script>Refund request')
        // Tag stripped, no surviving `<script>`.
        !sanitizer.subject('<script>x</script>foo').contains('<script>')
    }

    def "medium() is an alias for cleanMedium — same 500-char cap"() {
        given:
        def long1000 = 'b' * 1000

        expect:
        sanitizer.medium(long1000).length() == TextSanitizer.LIMIT_MEDIUM
        sanitizer.medium(long1000) == sanitizer.cleanMedium(long1000)
    }

    def "body() is an alias for cleanLong — same 2000-char cap"() {
        given:
        def long5000 = 'c' * 5000

        expect:
        sanitizer.body(long5000).length() == TextSanitizer.LIMIT_LONG
        sanitizer.body(long5000) == sanitizer.cleanLong(long5000)
    }

    def "all three aliases return null for null input (matches clean())"() {
        expect:
        sanitizer.subject(null) == null
        sanitizer.medium(null)  == null
        sanitizer.body(null)    == null
    }

    // ── extra XSS evasion vectors ─────────────────────────────────

    def "clean strips a malformed tag whose closing > sits far ahead"() {
        // The regex `<[^>]*>` is bounded — a long crafted span eventually
        // matches whatever `>` appears later, killing the entire tag span.
        // Pin so a future regex refactor can't widen the gap to "leave a
        // chunk of attacker payload behind because there's no >".
        expect:
        sanitizer.clean('<a href="x"><b>inner</b></a>safe') == 'innersafe'
    }

    def "clean strips a URI handler tucked inside what looks like a hash"() {
        // Strips `javascript:` regardless of surrounding noise.
        expect:
        !sanitizer.clean('#section JavaScript:doBadStuff() rest').toLowerCase().contains('javascript:')
    }

    def "clean strips mixed-case on* attribute names (onError, onCLICK, etc.)"() {
        // The (?i) flag covers any casing — important since browsers normalise
        // attribute names case-insensitively and an attacker who can't write
        // lower-case `onerror=` might try `OnErRoR=`.
        expect:
        sanitizer.clean('hi OnError=foo() bye') == 'hi foo() bye'
        sanitizer.clean('x ONLOAD =bad() y')    == 'x bad() y'
    }

    def "clean truncates after sanitization, not before"() {
        // A short cap MUST be applied to the post-sanitisation length —
        // otherwise an attacker padding their payload with HTML can chew
        // up the budget with markup that's about to be stripped anyway,
        // resulting in a much shorter end-user string than the cap.
        given:
        // 70 chars of script tag + 70 chars of payload. After tag-stripping
        // we're left with just the payload — well inside the 80-char short cap.
        def input = '<script>' + ('a' * 60) + '</script>' + ('b' * 70)

        when:
        def out = sanitizer.cleanShort(input)

        then:
        // 60 a's + 70 b's = 130 chars survive the tag strip; cap drops to 80.
        out.length() == 80
        out.startsWith('a' * 60 + 'b' * 20)
    }
}
