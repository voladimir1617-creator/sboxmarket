package com.sboxmarket.util

import com.sboxmarket.exception.BadRequestException

/**
 * Bounded parse for money / fraction amounts that arrive from a client.
 *
 * Why this exists: `new BigDecimal("1e-100000000")` is a cheap 12-char
 * parse, but the value carries a scale of 100,000,000. The first
 * `setScale(2, HALF_UP)` on it has to build 10^100000000 as a BigInteger
 * — minutes of CPU and hundreds of MB on one request thread. The same
 * holds for `"1e100000000"` (scale -100,000,000) and for zero with a huge
 * scale (`"0e-100000000"`), which slips past every `signum()`/range check.
 * Range checks (`> 0`, `<= 1,000,000`) do not help: compareTo looks at the
 * magnitude, not the scale, so these values sail through and blow up at
 * the cents normalisation that follows.
 *
 * This helper rejects such input with a 400 BEFORE any arithmetic:
 *   - the raw text is capped at {@link #MAX_LENGTH} characters (a JS
 *     double's longest toString is ~24), so no long digit strings either;
 *   - the parsed scale must sit in [{@link #MIN_SCALE}, {@link #MAX_SCALE}]
 *     and the precision at or under {@link #MAX_PRECISION}.
 * The scale ceiling is 20, not 2, on purpose: browsers send float
 * artifacts like 0.30000000000000004 (scale 17) or a maxDiscount of
 * 0.19999999999999996, and those must keep rounding to cents as before.
 * Setting scale 20 down to 2 is trivial work; only scales in the
 * thousands and up are expensive.
 *
 * Range / floor / ceiling rules stay at each call site: this only makes
 * the value safe to do arithmetic on.
 */
final class MoneyParse {

    private MoneyParse() { /* static-only */ }

    static final int MAX_LENGTH    = 32
    static final int MAX_SCALE     = 20
    static final int MIN_SCALE     = -10
    static final int MAX_PRECISION = 24

    static final String DEFAULT_CODE = 'INVALID_PRICE'

    /** Parse with the default INVALID_PRICE error code. */
    static BigDecimal parse(Object raw, String field) {
        parse(raw, field, DEFAULT_CODE)
    }

    /**
     * Parse `raw` (String, Number or BigDecimal, as a Map request body
     * carries it) into a BigDecimal whose scale and precision are safe to
     * do arithmetic on. Throws BadRequestException(code) for null, blank,
     * over-long, non-numeric or out-of-range-scale input. Never echoes the
     * raw input back in the message.
     */
    static BigDecimal parse(Object raw, String field, String code) {
        if (raw == null) {
            throw new BadRequestException(code, "${field} is required")
        }
        if (raw instanceof BigDecimal) {
            return requireSane((BigDecimal) raw, field, code)
        }
        String s = raw.toString().trim()
        if (s.isEmpty()) {
            throw new BadRequestException(code, "${field} is required")
        }
        if (s.length() > MAX_LENGTH) {
            throw new BadRequestException(code, "${field} must be a valid number")
        }
        BigDecimal bd
        try {
            bd = new BigDecimal(s)
        } catch (NumberFormatException e) {
            throw new BadRequestException(code, "${field} must be a valid number", e)
        }
        requireSane(bd, field, code)
    }

    /** True when `v`'s scale/precision is in the safe range (null is not). */
    static boolean isSane(BigDecimal v) {
        v != null && v.scale() <= MAX_SCALE && v.scale() >= MIN_SCALE && v.precision() <= MAX_PRECISION
    }

    /**
     * Guard an already-bound BigDecimal (Jackson DTO field, service
     * argument). Null passes through; callers keep their own null rules.
     */
    static BigDecimal requireSane(BigDecimal v, String field, String code = DEFAULT_CODE) {
        if (v == null) return null
        if (!isSane(v)) {
            throw new BadRequestException(code, "${field} must be a valid number")
        }
        v
    }
}
