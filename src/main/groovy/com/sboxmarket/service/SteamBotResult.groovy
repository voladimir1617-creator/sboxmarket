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
        return r
    }

    /** Convenience: a disabled-mode result (no bot configured). */
    static SteamBotResult disabled() {
        return error('DISABLED', 'Steam bot is not configured (STEAM_BOT_BASE_URL unset)')
    }

    boolean isAccepted() { return ok && status == 'accepted' }
    boolean isSent() { return ok && (status == 'sent' || status == 'active' || status == 'pending') }
    boolean isTerminalFailure() { return ok && (status == 'declined' || status == 'expired' || status == 'canceled' || status == 'canceled_2fa' || status == 'invalid_items') }
    boolean isInEscrow() { return ok && status == 'in_escrow' }

    @Override
    String toString() {
        return "SteamBotResult(ok=${ok}, status=${status}, offerId=${offerId}, errorCode=${errorCode}, message=${message})"
    }
}
