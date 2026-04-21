package com.sboxmarket

import com.sboxmarket.util.CsvUtil
import spock.lang.Specification

/**
 * Unit coverage for the shared CSV-safe cell encoder (batch 978).
 * Every CSV export in the app (wallet transactions, admin withdrawals
 * / audit / fraud / trades / tickets / users / per-user listings +
 * transactions, buy orders) flows through this one helper. A
 * regression here would re-open a CSV formula injection path across
 * the entire export surface, so we cover the OWASP cases explicitly.
 */
class CsvUtilSpec extends Specification {

    def "null renders as empty string"() {
        expect:
        CsvUtil.safeCell(null) == ''
    }

    def "plain text passes through unchanged"() {
        expect:
        CsvUtil.safeCell('hello')  == 'hello'
        CsvUtil.safeCell('Alice')  == 'Alice'
        CsvUtil.safeCell('12345')  == '12345'
        CsvUtil.safeCell('')       == ''
    }

    def "cell containing a comma gets wrapped in double quotes"() {
        expect:
        CsvUtil.safeCell('Smith, John') == '"Smith, John"'
    }

    def "embedded double quotes get doubled"() {
        expect:
        CsvUtil.safeCell('he said "hi"') == '"he said ""hi"""'
    }

    def "embedded newline triggers quoting"() {
        expect:
        CsvUtil.safeCell('line1\nline2') == '"line1\nline2"'
    }

    def "embedded CR triggers quoting"() {
        expect:
        CsvUtil.safeCell('a\rb').contains('"')
    }

    // ── OWASP CSV Injection (the real reason this utility exists) ───

    def "cell starting with = is prefixed with single quote so Excel treats it as text"() {
        // `=cmd|'/C calc'!A0` in a bank's downloaded statement would
        // run `calc.exe` when an accountant opens the file.
        when:
        def escaped = CsvUtil.safeCell("=cmd|'/C calc'!A0")

        then:
        escaped.startsWith("'=")
    }

    def "cell starting with + is prefixed"() {
        when:
        // Embedded quote → outer wrap `"..."`; the `'+` prefix lives
        // INSIDE the wrap, not at the absolute start of the encoded cell.
        def encoded = CsvUtil.safeCell('+HYPERLINK("https://evil.example")')

        then:
        encoded.contains("'+HYPERLINK")
        encoded.startsWith('"')   // wrapped because of embedded quote
    }

    def "cell starting with - is prefixed"() {
        // Excel's `=` and `-` both trigger formula evaluation.
        expect:
        CsvUtil.safeCell('-1+1').startsWith("'-")
    }

    def "cell starting with @ is prefixed"() {
        // @SUM(A:A) — an older Excel formula syntax that some locales still honour.
        expect:
        CsvUtil.safeCell('@SUM(A:A)').startsWith("'@")
    }

    def "cell starting with TAB is prefixed"() {
        expect:
        CsvUtil.safeCell('\t=1+1').startsWith("'")
    }

    def "cell starting with CR is prefixed"() {
        when:
        def encoded = CsvUtil.safeCell('\r=1+1')

        then:
        // CR triggers both the formula-prefix AND the needs-quoting
        // branch (CR counts as a special char for CSV wrapping). So
        // the encoded form is a quote-wrapped string with `'` inside.
        encoded.startsWith('"')
        encoded.contains("'\r=1+1")
    }

    def "formula-triggering cell with a comma inside still wraps in quotes AFTER prefixing"() {
        when:
        def escaped = CsvUtil.safeCell('=IF(1,2,3)')

        then:
        // Comma → must be quoted. Leading `=` → prefixed with `'`.
        escaped == '"\'=IF(1,2,3)"'
    }

    def "formula-triggering cell with an embedded quote escapes the quote too"() {
        when:
        def escaped = CsvUtil.safeCell('=HYPERLINK("http://evil")')

        then:
        // Starts with `=` → prefix `'`. Contains `"` → wrap + double.
        escaped == '"\'=HYPERLINK(""http://evil"")"'
    }

    def "non-formula-triggering special chars in the middle don't get prefixed"() {
        // `-` only triggers when it's the FIRST character. A hyphen in
        // the middle of a normal string should pass through plain.
        expect:
        CsvUtil.safeCell('ABC-123') == 'ABC-123'
        CsvUtil.safeCell('hello=world') == 'hello=world'
    }

    def "non-string input is stringified safely"() {
        expect:
        CsvUtil.safeCell(42)    == '42'
        CsvUtil.safeCell(3.14)  == '3.14'
        CsvUtil.safeCell(true)  == 'true'
    }
}
