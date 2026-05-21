package com.sboxmarket.service

import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Minimal RFC 6238 TOTP implementation — compatible with Google Authenticator,
 * Authy, 1Password, etc. 30-second step, 6-digit code, SHA-1. No external
 * dependency (we already ship javax.crypto).
 *
 * Secret format: Base32 per RFC 4648 so the user's authenticator app can
 * scan a standard otpauth:// URL.
 *
 * Replay protection: caller passes in the user's last used step id; this
 * service refuses codes whose step ≤ lastStep and returns the new step on
 * success so the caller can persist it.
 */
@Service
@Slf4j
class TotpService {

    private static final int STEP_SECONDS = 30
    private static final int DIGITS       = 6
    /** Accept codes ±1 step (30s) on each side to tolerate clock drift. */
    private static final int WINDOW       = 1

    private static final String BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
    private static final SecureRandom RNG = new SecureRandom()

    /** Generate a fresh 20-byte secret, Base32 encoded. */
    String generateSecret() {
        def bytes = new byte[20]
        RNG.nextBytes(bytes)
        base32(bytes)
    }

    /**
     * Build the otpauth:// URL that authenticator apps turn into a QR code.
     * Issuer is baked in so the entry in the user's app reads
     *   SkinBox (user@example.com)
     */
    String otpauthUrl(String secretBase32, String accountLabel) {
        def issuer = 'SkinBox'
        def label  = URLEncoder.encode("${issuer}:${accountLabel ?: 'account'}", 'UTF-8')
        "otpauth://totp/${label}?secret=${secretBase32}&issuer=${issuer}&algorithm=SHA1&digits=${DIGITS}&period=${STEP_SECONDS}"
    }

    /**
     * Verifies the presented 6-digit code against the secret. Returns the
     * matching step id on success (caller must persist `user.lastTotpStep`
     * to prevent replay within the same window), or -1 on failure.
     */
    long verify(String secretBase32, String code, Long lastStep) {
        if (!secretBase32 || !code) return -1L
        def cleaned = code.replaceAll(/\s+/, '')
        if (!(cleaned ==~ /\d{6}/)) return -1L

        def secret = unbase32(secretBase32)
        // A secret that decodes to nothing (empty / all non-Base32 chars)
        // cannot key an HMAC — SecretKeySpec rejects an empty key. Fail the
        // verification cleanly instead of throwing out of the auth path.
        if (!secret) return -1L

        def now = System.currentTimeMillis() / 1000L
        def currentStep = (now / STEP_SECONDS) as long
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            long step = currentStep + offset
            if (lastStep != null && step <= lastStep) continue   // replay guard
            if (constantTimeEquals(codeFor(secret, step), cleaned)) return step
        }
        -1L
    }

    /** Compute the 6-digit TOTP code for a given step. */
    String codeFor(byte[] secret, long step) {
        def buf = ByteBuffer.allocate(8).putLong(step).array()
        def mac = Mac.getInstance('HmacSHA1')
        mac.init(new SecretKeySpec(secret, 'HmacSHA1'))
        def hash = mac.doFinal(buf)
        int offset = hash[hash.length - 1] & 0xf
        int binary = ((hash[offset]     & 0x7f) << 24) |
                     ((hash[offset + 1] & 0xff) << 16) |
                     ((hash[offset + 2] & 0xff) << 8)  |
                      (hash[offset + 3] & 0xff)
        def otp = binary % (int) Math.pow(10, DIGITS)
        String.format('%0' + DIGITS + 'd', otp)
    }

    // ── Backup / recovery codes ─────────────────────────────────────

    /** Number of backup codes to mint at enrollment / regeneration. Ten is
     *  the GitHub / Google / AWS IAM convention — enough to outlast years of
     *  authenticator migrations without becoming a burden to store. */
    static final int BACKUP_CODE_COUNT = 10

    /**
     * Generate `BACKUP_CODE_COUNT` one-time human-readable codes. Format is
     * `xxxx-xxxx-xxxx` (12 Crockford-base32 chars with hyphens) — long enough
     * for ~60 bits of entropy, short enough for a user to type on phone after
     * losing their authenticator.
     *
     * Return shape: `[plaintext: [...], hashed: 'h1 h2 ...']` — the plaintext
     * list is shown to the user exactly once and then discarded; the hashed
     * string is persisted on the user row.
     */
    Map generateBackupCodes() {
        def plain = []
        def hashes = []
        BACKUP_CODE_COUNT.times {
            def raw = new byte[8]
            RNG.nextBytes(raw)
            // 12 chars Crockford-ish base32 so users don't have to
            // disambiguate 0/O or 1/l/I in their hand-written copy.
            def clean = base32(raw).toLowerCase().replaceAll(/[^0-9a-hjkmnp-z]/, '').take(12)
            while (clean.size() < 12) clean = clean + 'x'
            def pretty = clean[0..3] + '-' + clean[4..7] + '-' + clean[8..11]
            plain    << pretty
            hashes   << sha256Hex(pretty)
        }
        [ plaintext: plain, hashed: hashes.join(' ') ]
    }

    /**
     * Check whether the supplied recovery code matches one of the user's
     * unused hashes. Returns the updated hash-list string with the matched
     * hash removed (single-use), or null if no match.
     */
    String consumeRecoveryCode(String storedHashes, String candidate) {
        if (!storedHashes || !candidate) return null
        def normalized = candidate.trim().toLowerCase().replaceAll(/[^0-9a-z-]/, '')
        if (!normalized) return null
        def wantHash = sha256Hex(normalized)
        def parts = storedHashes.split(/\s+/).findAll { it }
        // Match with a constant-time compare so a timing side-channel can't
        // be used to probe which recovery hashes are stored. Scan every
        // entry (no early break) for the same reason.
        boolean matched = false
        def remaining = []
        parts.each { hash ->
            if (constantTimeEquals(hash, wantHash)) {
                matched = true
            } else {
                remaining << hash
            }
        }
        if (!matched) return null
        remaining.join(' ')
    }

    /** SHA-256 lowercase hex — exposed so the ProfileController can hash
     *  a user-supplied candidate the same way the store does. */
    String sha256Hex(String input) {
        def md = MessageDigest.getInstance('SHA-256')
        def out = md.digest(input.getBytes('UTF-8'))
        out.collect { String.format('%02x', it) }.join('')
    }

    /**
     * Length-independent constant-time string compare. Used for both the TOTP
     * code check and recovery-hash matching so verification time does not leak
     * how many leading characters of a guess were correct. Returns false for
     * any null argument.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false
        MessageDigest.isEqual(a.getBytes('UTF-8'), b.getBytes('UTF-8'))
    }

    // ── Base32 codec (RFC 4648 — authenticator-app compatible) ──────

    private static String base32(byte[] bytes) {
        if (!bytes) return ''
        def sb = new StringBuilder()
        int buffer = bytes[0] & 0xff, next = 1, bitsLeft = 8
        while (bitsLeft > 0 || next < bytes.length) {
            if (bitsLeft < 5) {
                if (next < bytes.length) {
                    buffer <<= 8
                    buffer |= (bytes[next++] & 0xff)
                    bitsLeft += 8
                } else {
                    int pad = 5 - bitsLeft
                    buffer <<= pad
                    bitsLeft += pad
                }
            }
            int index = 0x1F & (buffer >>> (bitsLeft - 5))
            bitsLeft -= 5
            sb.append(BASE32_ALPHABET.charAt(index))
        }
        sb.toString()
    }

    private static byte[] unbase32(String encoded) {
        def s = encoded.toUpperCase().replaceAll(/[^A-Z2-7]/, '')
        def out = new ByteArrayOutputStream()
        int buffer = 0, bitsLeft = 0
        s.each { c ->
            buffer = (buffer << 5) | BASE32_ALPHABET.indexOf(c as String)
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write(((buffer >>> (bitsLeft - 8)) & 0xff) as int)
                bitsLeft -= 8
            }
        }
        out.toByteArray()
    }
}
