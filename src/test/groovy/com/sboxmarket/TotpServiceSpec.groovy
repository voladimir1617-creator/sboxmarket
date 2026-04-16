package com.sboxmarket

import com.sboxmarket.service.TotpService
import spock.lang.Specification
import spock.lang.Subject

/**
 * RFC 6238 TOTP verification. Correctness properties we need:
 *
 *   1) generateSecret returns a valid Base32 string
 *   2) otpauthUrl builds a scannable link
 *   3) verify() accepts the code generated right now
 *   4) verify() rejects a wrong code
 *   5) verify() rejects a malformed code (not 6 digits)
 *   6) replay protection: once a step is used, re-verifying the SAME step
 *      (even with a correct code) must fail
 *   7) null inputs return -1 cleanly
 */
class TotpServiceSpec extends Specification {

    @Subject
    TotpService service = new TotpService()

    def "generateSecret returns a non-empty Base32-alphabet string"() {
        when:
        def secret = service.generateSecret()

        then:
        secret != null
        secret.length() >= 32  // 20 bytes → ~32 Base32 chars
        secret ==~ /[A-Z2-7]+/
    }

    def "otpauthUrl builds a well-formed otpauth:// link"() {
        given:
        def url = service.otpauthUrl('JBSWY3DPEHPK3PXP', 'alice@example.com')

        expect:
        url.startsWith('otpauth://totp/')
        url.contains('secret=JBSWY3DPEHPK3PXP')
        url.contains('issuer=SkinBox')
        url.contains('algorithm=SHA1')
        url.contains('digits=6')
        url.contains('period=30')
    }

    def "verify accepts a freshly-generated code and returns a step id"() {
        given:
        def secret = service.generateSecret()
        def now = System.currentTimeMillis() / 1000L
        def step = (now / 30) as long
        def secretBytes = unbase32(secret)
        def code = service.codeFor(secretBytes, step)

        when:
        def resultStep = service.verify(secret, code, null)

        then:
        resultStep == step
    }

    def "verify rejects a wrong code"() {
        given:
        def secret = service.generateSecret()

        when:
        def result = service.verify(secret, '123456', null)

        then:
        result == -1L
    }

    def "verify rejects non-6-digit input"() {
        given:
        def secret = service.generateSecret()

        expect:
        service.verify(secret, '12345',  null) == -1L   // too short
        service.verify(secret, '1234567', null) == -1L  // too long
        service.verify(secret, 'abcdef', null) == -1L   // not digits
    }

    def "verify returns -1 on null secret or null code"() {
        expect:
        service.verify(null, '123456', null) == -1L
        service.verify('ABCDEFGH', null, null) == -1L
    }

    def "verify refuses replay — same step blocked by lastStep"() {
        given:
        def secret = service.generateSecret()
        def now = System.currentTimeMillis() / 1000L
        def step = (now / 30) as long
        def secretBytes = unbase32(secret)
        def code = service.codeFor(secretBytes, step)

        when:
        // First call succeeds and returns the step
        def firstStep = service.verify(secret, code, null)
        // Second call with lastStep = firstStep must refuse
        def secondStep = service.verify(secret, code, firstStep)

        then:
        firstStep == step
        secondStep == -1L
    }

    def "verify tolerates whitespace in the code"() {
        given:
        def secret = service.generateSecret()
        def now = System.currentTimeMillis() / 1000L
        def step = (now / 30) as long
        def secretBytes = unbase32(secret)
        def code = service.codeFor(secretBytes, step)

        when:
        def spaced = code[0..2] + ' ' + code[3..5]
        def result = service.verify(secret, spaced, null)

        then:
        result == step
    }

    // Re-implement the Base32 decoder from the service so the spec can
    // exercise codeFor directly with raw bytes. Kept minimal and only
    // used in this test class.
    private static byte[] unbase32(String s) {
        def alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
        def clean = s.toUpperCase().replaceAll(/[^A-Z2-7]/, '')
        def out = new ByteArrayOutputStream()
        int buffer = 0, bitsLeft = 0
        clean.each { c ->
            buffer = (buffer << 5) | alphabet.indexOf(c as String)
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write(((buffer >>> (bitsLeft - 8)) & 0xff) as int)
                bitsLeft -= 8
            }
        }
        out.toByteArray()
    }
}
