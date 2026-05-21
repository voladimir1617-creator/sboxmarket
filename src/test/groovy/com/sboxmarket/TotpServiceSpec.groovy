package com.sboxmarket

import com.sboxmarket.service.TotpService
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * RFC 6238 TOTP verification. Correctness / security properties we need:
 *
 *   1) generateSecret returns a valid, high-entropy Base32 string
 *   2) otpauthUrl builds a scannable link
 *   3) verify() accepts the code generated right now
 *   4) verify() accepts codes from the adjacent steps (±1 drift window)
 *      and rejects codes two or more steps away
 *   5) verify() rejects a wrong code
 *   6) verify() rejects malformed / null / empty / non-Base32 input
 *      cleanly (no exception thrown out of the auth path)
 *   7) replay protection: once a step is used, re-verifying the SAME step
 *      (even with a correct code) must fail; codes for earlier steps are
 *      also locked out
 *   8) recovery codes are unique, single-use, and case/whitespace tolerant
 */
class TotpServiceSpec extends Specification {

    @Subject
    TotpService service = new TotpService()

    // ── Secret generation ───────────────────────────────────────────

    def "generateSecret returns a non-empty Base32-alphabet string"() {
        when:
        def secret = service.generateSecret()

        then:
        secret != null
        secret.length() >= 32  // 20 bytes → ~32 Base32 chars
        secret ==~ /[A-Z2-7]+/
    }

    def "generateSecret produces distinct secrets (entropy sanity check)"() {
        when:
        def secrets = (1..50).collect { service.generateSecret() }

        then:
        secrets.toSet().size() == 50
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

    def "otpauthUrl tolerates a null account label"() {
        expect:
        service.otpauthUrl('JBSWY3DPEHPK3PXP', null).startsWith('otpauth://totp/')
    }

    // ── codeFor — RFC 6238 vector ───────────────────────────────────

    def "codeFor matches the RFC 6238 SHA-1 test vector"() {
        given:
        // RFC 6238 Appendix B: ASCII secret "12345678901234567890".
        byte[] secret = '12345678901234567890'.getBytes('US-ASCII')
        // T = 59s  →  step = 1  →  expected code 94287082 → last 6 = 287082
        def step = 1L

        expect:
        service.codeFor(secret, step) == '287082'
    }

    def "codeFor always returns exactly 6 digits, zero-padded"() {
        given:
        def secret = unbase32(service.generateSecret())

        expect:
        (0L..200L).every { service.codeFor(secret, it) ==~ /\d{6}/ }
    }

    // ── verify — happy path & drift window ──────────────────────────

    def "verify accepts a freshly-generated code and returns a step id"() {
        given:
        def secret = service.generateSecret()
        def step = currentStep()
        def code = service.codeFor(unbase32(secret), step)

        when:
        def resultStep = service.verify(secret, code, null)

        then:
        resultStep == step
    }

    @Unroll
    def "verify accepts a code from offset #offset within the ±1 drift window"() {
        given:
        def secret = service.generateSecret()
        def step = currentStep() + offset
        def code = service.codeFor(unbase32(secret), step)

        expect:
        service.verify(secret, code, null) == step

        where:
        offset << [-1, 0, 1]
    }

    @Unroll
    def "verify rejects a code from offset #offset outside the drift window"() {
        given:
        def secret = service.generateSecret()
        def step = currentStep() + offset
        def code = service.codeFor(unbase32(secret), step)

        expect:
        service.verify(secret, code, null) == -1L

        where:
        offset << [-2, 2, -5, 5]
    }

    def "verify tolerates whitespace in the code"() {
        given:
        def secret = service.generateSecret()
        def step = currentStep()
        def code = service.codeFor(unbase32(secret), step)

        when:
        def spaced = code[0..2] + ' ' + code[3..5]
        def result = service.verify(secret, spaced, null)

        then:
        result == step
    }

    // ── verify — rejection paths ────────────────────────────────────

    def "verify rejects a wrong code"() {
        given:
        def secret = service.generateSecret()
        // Find a 6-digit string that is NOT any code in the live window so
        // the test is deterministic regardless of wall-clock.
        def step = currentStep()
        def live = (-1..1).collect { service.codeFor(unbase32(secret), step + it) }
        def wrong = (0..999999).collect { String.format('%06d', it) }
                                .find { !live.contains(it) }

        expect:
        service.verify(secret, wrong, null) == -1L
    }

    @Unroll
    def "verify rejects non-6-digit input: #desc"() {
        given:
        def secret = service.generateSecret()

        expect:
        service.verify(secret, input, null) == -1L

        where:
        desc            | input
        'too short'     | '12345'
        'too long'      | '1234567'
        'not digits'    | 'abcdef'
        'mixed'         | '12ab56'
        'empty string'  | ''
        'just spaces'   | '   '
    }

    def "verify returns -1 on null secret or null code"() {
        expect:
        service.verify(null, '123456', null) == -1L
        service.verify('ABCDEFGH', null, null) == -1L
        service.verify('', '123456', null) == -1L
    }

    def "verify returns -1 on a non-Base32 secret instead of throwing"() {
        // A secret made only of characters outside the Base32 alphabet
        // decodes to an empty key — SecretKeySpec would otherwise throw
        // IllegalArgumentException out of the auth path.
        when:
        def result = service.verify('!!!!----????', '123456', null)

        then:
        noExceptionThrown()
        result == -1L
    }

    // ── Replay protection ───────────────────────────────────────────

    def "verify refuses replay — same step blocked by lastStep"() {
        given:
        def secret = service.generateSecret()
        def step = currentStep()
        def code = service.codeFor(unbase32(secret), step)

        when:
        // First call succeeds and returns the step
        def firstStep = service.verify(secret, code, null)
        // Second call with lastStep = firstStep must refuse
        def secondStep = service.verify(secret, code, firstStep)

        then:
        firstStep == step
        secondStep == -1L
    }

    def "verify locks out codes for steps at or before lastStep"() {
        given:
        def secret = service.generateSecret()
        def step = currentStep()
        // The code for the CURRENT step, but the user has already consumed
        // a later step (clock-ahead authenticator on the previous login).
        def code = service.codeFor(unbase32(secret), step)

        expect:
        // lastStep == current step → current code rejected
        service.verify(secret, code, step) == -1L
        // lastStep one ahead of current → still rejected
        service.verify(secret, code, step + 1) == -1L
    }

    def "verify still accepts a never-before-seen later step after a replay guard"() {
        given:
        def secret = service.generateSecret()
        def step = currentStep()
        // lastStep is the previous step; the current step has not been used.
        def code = service.codeFor(unbase32(secret), step)

        expect:
        service.verify(secret, code, step - 1) == step
    }

    // ── Backup / recovery codes ─────────────────────────────────────

    def "generateBackupCodes mints 10 human-readable codes and a space-separated hash list"() {
        when:
        def out = service.generateBackupCodes()

        then:
        out.plaintext instanceof List
        out.plaintext.size() == 10
        // Codes are 12-char lowercase alnum split into 3 groups of 4 by '-'.
        out.plaintext.every { it ==~ /[0-9a-z]{4}-[0-9a-z]{4}-[0-9a-z]{4}/ }
        out.plaintext.toSet().size() == 10   // uniqueness
        // Hash list has 10 space-separated SHA-256 hex strings.
        def parts = (out.hashed as String).split(/\s+/)
        parts.size() == 10
        parts.every { it ==~ /[0-9a-f]{64}/ }
    }

    def "generateBackupCodes hashes are the SHA-256 of the plaintext codes"() {
        when:
        def out = service.generateBackupCodes()
        def hashes = (out.hashed as String).split(/\s+/).toList()

        then:
        // Every plaintext code's hash is present in the stored list.
        out.plaintext.every { hashes.contains(service.sha256Hex(it as String)) }
    }

    def "consumeRecoveryCode matches a live code and returns the trimmed hash list"() {
        given:
        def out = service.generateBackupCodes()
        def picked = out.plaintext[3] as String

        when:
        def remaining = service.consumeRecoveryCode(out.hashed as String, picked)

        then:
        remaining != null
        // The consumed hash should be gone, the other 9 should remain.
        remaining.split(/\s+/).size() == 9
        !remaining.split(/\s+/).toList().contains(service.sha256Hex(picked))
    }

    def "consumeRecoveryCode is single-use — a consumed code no longer matches"() {
        given:
        def out = service.generateBackupCodes()
        def picked = out.plaintext[0] as String

        when:
        def afterFirst = service.consumeRecoveryCode(out.hashed as String, picked)
        // Re-presenting the same code against the trimmed list must fail.
        def afterSecond = service.consumeRecoveryCode(afterFirst, picked)

        then:
        afterFirst != null
        afterSecond == null
    }

    def "consumeRecoveryCode consumes each of the 10 codes exactly once"() {
        given:
        def out = service.generateBackupCodes()
        def store = out.hashed as String

        when:
        out.plaintext.each { code ->
            store = service.consumeRecoveryCode(store, code as String)
        }

        then:
        // All ten consumed in turn → store is now empty (no hashes left).
        store != null
        store.split(/\s+/).findAll { it }.isEmpty()
    }

    def "consumeRecoveryCode returns null on a wrong code"() {
        given:
        def out = service.generateBackupCodes()

        expect:
        service.consumeRecoveryCode(out.hashed as String, 'bogus-code-here') == null
        service.consumeRecoveryCode(out.hashed as String, '') == null
        service.consumeRecoveryCode(null, (out.plaintext[0] as String)) == null
        service.consumeRecoveryCode(out.hashed as String, null) == null
    }

    def "consumeRecoveryCode returns null when the candidate normalizes to empty"() {
        given:
        def out = service.generateBackupCodes()

        expect:
        // Characters outside [0-9a-z-] are stripped; an all-symbol candidate
        // collapses to the empty string and must not match anything.
        service.consumeRecoveryCode(out.hashed as String, '!!! @@@ ###') == null
        service.consumeRecoveryCode(out.hashed as String, '   ') == null
    }

    def "consumeRecoveryCode is case-insensitive and ignores surrounding whitespace"() {
        given:
        def out = service.generateBackupCodes()
        def picked = out.plaintext[0] as String

        expect:
        service.consumeRecoveryCode(out.hashed as String, '  ' + picked.toUpperCase() + '  ') != null
    }

    def "consumeRecoveryCode removes only the matched hash, leaving the rest intact"() {
        given:
        def out = service.generateBackupCodes()
        def originalHashes = (out.hashed as String).split(/\s+/).toList()
        def picked = out.plaintext[5] as String
        def pickedHash = service.sha256Hex(picked)

        when:
        def remaining = service.consumeRecoveryCode(out.hashed as String, picked).split(/\s+/).toList()

        then:
        // Exactly the 9 non-picked hashes survive, order/content otherwise preserved.
        remaining.toSet() == (originalHashes - pickedHash).toSet()
        remaining.size() == 9
    }

    // ── sha256Hex ───────────────────────────────────────────────────

    def "sha256Hex matches a known SHA-256 vector"() {
        expect:
        // SHA-256("abc") — FIPS 180-4 example.
        service.sha256Hex('abc') ==
            'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static long currentStep() {
        def now = System.currentTimeMillis() / 1000L
        (now / 30) as long
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
