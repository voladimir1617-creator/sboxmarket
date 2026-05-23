package com.sboxmarket.util

import com.sboxmarket.exception.BadRequestException

/**
 * Shared upstream length caps for raw `@RequestBody Map` text fields
 * across the controller layer.
 *
 * Why this exists: most controllers in this codebase accept a Map body
 * and pass individual fields to a service whose TextSanitizer silently
 * truncates the value (cleanShort = 80, medium = 500, body = 2000).
 * That meant an attacker could POST a 1 MB description / note / message
 * — Spring would parse it, Jackson would allocate a String for it, the
 * service would sanitize it, and only the final 500 chars would land
 * in the DB. The wasted memory + GC pressure (and the bandwidth amp
 * implicit in "send any field, get back a structured response") is
 * the real cost.
 *
 * `requireMax` is a small, allocation-free pre-check that throws a
 * structured 400 with a stable error code before the field reaches
 * the service. Tests that pass small payloads (under the cap) are
 * unaffected — they were the typical case anyway.
 *
 * Caps are deliberately set 4-10x larger than the sanitizer's final
 * truncation so a user typing a slightly-too-long description still
 * gets a sanitized cap rather than a 400 ("BIO_TOO_LONG: 5000 chars
 * max" lands far past anything a real bio reaches; the sanitizer
 * silently shrinks the result to 500 on the way to the DB).
 */
final class InputLimits {

    private InputLimits() { /* static-only */ }

    /** Hard upstream caps. Service-layer sanitizer caps are tighter. */
    static final int SHORT_LABEL    = 200    // names, labels, subjects, reasons
    static final int MEDIUM_TEXT    = 5000   // descriptions, replies, notes, bios
    static final int LONG_TEXT      = 20000  // ticket bodies, multi-paragraph reports

    /**
     * Throws BadRequestException(code) if `value` is non-null and its
     * length exceeds `max`. Null / shorter values pass through untouched.
     * Use directly in a controller before passing the field to a service.
     */
    static String requireMax(String value, int max, String code, String fieldLabel) {
        if (value != null && value.length() > max) {
            throw new BadRequestException(code,
                "${fieldLabel} must be ${max} characters or fewer (got ${value.length()})")
        }
        value
    }

    /** Convenience: pull a String field from a Map and cap it in one call. */
    static String requireMax(Map body, String key, int max, String code, String fieldLabel) {
        requireMax(body?.get(key) as String, max, code, fieldLabel)
    }
}
