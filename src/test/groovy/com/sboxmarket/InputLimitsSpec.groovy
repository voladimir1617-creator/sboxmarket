package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.util.InputLimits
import spock.lang.Specification

/**
 * Unit coverage for the shared upstream length-cap helper. Every controller
 * that accepts a raw `@RequestBody Map` (support tickets, reviews, offers,
 * announcements, profile updates) calls into `requireMax` BEFORE handing the
 * field to a service. A regression here would let a 1 MB description /
 * note / message ride through Spring + Jackson and waste a String
 * allocation per request — the exact bandwidth-amplification path the
 * helper was added to close.
 *
 * The class is tiny but it's also the single chokepoint, so the OWASP-style
 * boundary cases (null, empty, exact-at-cap, one-over-cap) are pinned here.
 */
class InputLimitsSpec extends Specification {

    // ── String-form requireMax(value, max, code, label) ──────────────

    def "null value passes through untouched"() {
        // Controllers commonly call requireMax on optional fields; null is the
        // "field absent" signal and must NOT raise.
        expect:
        InputLimits.requireMax((String) null, 100, 'CODE', 'field') == null
    }

    def "empty string passes through untouched"() {
        expect:
        InputLimits.requireMax('', 100, 'CODE', 'field') == ''
    }

    def "value shorter than cap is returned verbatim"() {
        expect:
        InputLimits.requireMax('hello', 100, 'CODE', 'field') == 'hello'
    }

    def "value exactly at the cap is allowed — boundary is inclusive"() {
        // Off-by-one regression guard. A user typing a 200-char label (the
        // exact SHORT_LABEL cap) must not be rejected.
        given:
        def atCap = 'x' * 200

        expect:
        InputLimits.requireMax(atCap, 200, 'SHORT_LABEL_TOO_LONG', 'name') == atCap
    }

    def "value one character over the cap throws BadRequestException with the supplied code"() {
        given:
        def oneOver = 'x' * 201

        when:
        InputLimits.requireMax(oneOver, 200, 'NAME_TOO_LONG', 'name')

        then:
        def e = thrown(BadRequestException)
        e.code == 'NAME_TOO_LONG'
        // Operators need both the cap AND the offending length in the
        // message so a 400 on the wire is self-diagnosing.
        e.message.contains('200')
        e.message.contains('201')
        e.message.contains('name')
    }

    def "value far over the cap still throws (no silent truncation at this layer)"() {
        given: 'a 1 MB payload — the exact bandwidth-amp path the helper closes'
        def huge = 'x' * (1024 * 1024)

        when:
        InputLimits.requireMax(huge, InputLimits.MEDIUM_TEXT, 'BIO_TOO_LONG', 'bio')

        then:
        def e = thrown(BadRequestException)
        e.code == 'BIO_TOO_LONG'
    }

    // ── Map-form requireMax(body, key, max, code, label) ─────────────

    def "Map overload reads the field by key and applies the cap"() {
        given:
        def body = [name: 'Alice']

        expect:
        InputLimits.requireMax(body, 'name', 100, 'NAME_TOO_LONG', 'name') == 'Alice'
    }

    def "Map overload treats a missing key as null (no throw)"() {
        // Optional fields commonly aren't present in the body; absence
        // must not be conflated with "too long".
        given:
        def body = [other: 'value']

        expect:
        InputLimits.requireMax(body, 'name', 100, 'NAME_TOO_LONG', 'name') == null
    }

    def "Map overload survives a null body (defensive — Spring may hand us one)"() {
        expect:
        InputLimits.requireMax((Map) null, 'name', 100, 'NAME_TOO_LONG', 'name') == null
    }

    def "Map overload coerces non-String values via `as String`"() {
        // Jackson can hand us numbers/booleans when the client posts a
        // typed JSON value into a string field. The helper coerces so the
        // length check still runs (and the underlying sanitizer gets a
        // String to chew on too).
        given:
        def body = [count: 12345]

        expect:
        InputLimits.requireMax(body, 'count', 100, 'BAD', 'count') == '12345'
    }

    def "Map overload throws when the value at the key exceeds the cap"() {
        given:
        def body = [note: 'x' * 300]

        when:
        InputLimits.requireMax(body, 'note', 200, 'NOTE_TOO_LONG', 'note')

        then:
        def e = thrown(BadRequestException)
        e.code == 'NOTE_TOO_LONG'
        e.message.contains('300')
    }

    // ── Documented caps are stable contract ──────────────────────────

    def "documented cap constants match the production values controllers rely on"() {
        // These constants are referenced by name across every text-accepting
        // controller. Changing them is a deliberate breaking change — pin
        // the values so an accidental edit shows up in CI.
        expect:
        InputLimits.SHORT_LABEL == 200
        InputLimits.MEDIUM_TEXT == 5000
        InputLimits.LONG_TEXT   == 20000
    }
}
