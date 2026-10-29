package com.sboxmarket.service

import groovy.transform.CompileStatic

/**
 * Typed, never-throwing result of a call to the Steam bot sidecar via
 * {@link SteamTradeBotService}. Callers branch on {@code ok} and the normalized
 * {@code errorCode}/{@code status} instead of catching exceptions.
 *
 * {@code status} mirrors the sidecar's normalized offer status strings:
 * active / accepted / declined / expired / canceled / in_escrow /
 * needs_confirmation / countered / invalid_items / unknown, plus the
 * send-path values 'sent' / 'pending'.
 */
@CompileStatic
class SteamBotResult {

    /** True when the sidecar returned a successful (2xx, ok:true) response. */
    boolean ok = false

    /** Normalized error code from the sidecar (RATE_LIMITED, ESCROW_HOLD,
     *  BAD_TRADE_URL, NOT_READY, BAD_REQUEST, ERROR ...) or a client-side code
     *  (DISABLED, TRANSPORT_ERROR, TIMEOUT, BAD_RESPONSE). Null when ok. */
    String errorCode

    /** Human-readable message for logging. */
    String message

    /** The Steam trade-offer id, when present. */
    String offerId

    /** Normalized offer status string, when present. */
    String status

    /** Whether an outgoing offer was auto-confirmed via the identity secret. */
    boolean confirmed = false

    /** Raw decoded JSON body (Map) for any extra fields the caller may need. */
    Map raw

    /** False when the sidecar said {@code retryable:false}: the same request
     *  will be refused the same way, so callers must not re-send it until
     *  its input (trade URL, asset ids) changes. True otherwise, including
     *  every client-side transport failure. */
    boolean retryable = true

    /** Prefix on a stored failure that halted re-sends, followed by the
     *  {@link #inputKey} of the refused input. */
    static final String HALT_PREFIX = 'halted '

    /** Short fingerprint of a send's input, so a halted send is retried
     *  only once the trade URL or asset ids are different. */
    static String inputKey(String tradeUrl, List assetIds) {
        String raw = (tradeUrl ?: '').trim() + '|' + ((assetIds ?: []).collect { String.valueOf(it).trim() }.sort().join(','))
        byte[] d = java.security.MessageDigest.getInstance('SHA-256').digest(raw.getBytes('UTF-8'))
        StringBuilder sb = new StringBuilder(12)
        for (int i = 0; i < 6; i++) sb.append(String.format('%02x', d[i] & 0xff))
        return sb.toString()
    }

    /** The text stored for a halted send: marker, input key, then why. */
    static String haltNote(String key, String why) {
        return HALT_PREFIX + key + ' ' + (why ?: '')
    }

    /** True when {@code note} records a halt for exactly this input. */
    static boolean isHaltedFor(String note, String key) {
        return note != null && key != null && note.startsWith(HALT_PREFIX + key + ' ')
    }

    static SteamBotResult success(Map body) {
        SteamBotResult r = new SteamBotResult()
        r.ok = true
        r.raw = body
        if (body != null) {
            // Subscript (not property) access — under @CompileStatic, body.offerId
            // would compile to getOfferId() which a plain Map does not have.
            r.offerId = body['offerId'] != null ? String.valueOf(body['offerId']) : null
            r.status = body['status'] != null ? String.valueOf(body['status']) : null
            r.confirmed = body['confirmed'] == true
        }
        return r
    }

    static SteamBotResult error(String code, String message) {
        SteamBotResult r = new SteamBotResult()
        r.ok = false
        r.errorCode = code
        r.message = message
        return r
    }

    static SteamBotResult error(String code, String message, Map body) {
        SteamBotResult r = error(code, message)
        r.raw = body
        if (body != null && body['offerId'] != null) {
            r.offerId = String.valueOf(body['offerId'])
        }
        if (body != null && body['retryable'] == Boolean.FALSE) {
            r.retryable = false
        }
        return r
    }

    /** Convenience: a disabled-mode result (no bot configured). */
    static SteamBotResult disabled() {
        return error('DISABLED', 'Steam bot is not configured (STEAM_BOT_BASE_URL unset)')
    }

    boolean isAccepted() { return ok && status == 'accepted' }
    boolean isSent() { return ok && (status == 'sent' || status == 'active' || status == 'pending') }
    /** Steam offer states that end an offer without delivery. */
    static final Set<String> TERMINAL_FAILURE_STATES = ['declined', 'expired', 'canceled', 'canceled_2fa', 'invalid_items'] as Set

    boolean isTerminalFailure() { return ok && TERMINAL_FAILURE_STATES.contains(status) }
    boolean isInEscrow() { return ok && status == 'in_escrow' }

    /** Error code of a cancel the sidecar refused because the partner had
     *  already accepted the offer (status 'accepted' or 'in_escrow'). */
    static final String ALREADY_ACCEPTED = 'ALREADY_ACCEPTED'

    /** Cancel answer: the offer can no longer be accepted, because we just
     *  cancelled it or it was already canceled / declined / expired. */
    boolean isOfferCancelled() { return ok && raw != null && raw['cancelled'] == true }

    /** Cancel answer: the partner took the item (or Steam is holding it on
     *  its way to them). Whatever the caller meant to undo has happened. */
    boolean isOfferAlreadyAccepted() { return !ok && errorCode == ALREADY_ACCEPTED }

    @Override
    String toString() {
        return "SteamBotResult(ok=${ok}, status=${status}, offerId=${offerId}, errorCode=${errorCode}, message=${message})"
    }
}
