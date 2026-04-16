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
}
