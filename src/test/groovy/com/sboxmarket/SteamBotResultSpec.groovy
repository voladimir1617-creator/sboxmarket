package com.sboxmarket

import com.sboxmarket.service.SteamBotResult
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Pinned test for the Steam-bot sidecar result object. The trade / escrow
 * flow branches on these factories + status predicates to decide whether an
 * outgoing offer was sent, accepted, escrow-held, or terminally failed —
 * misreading the bot's normalized status (e.g. treating a 'declined' as a
 * success, or a numeric offerId crashing under @CompileStatic subscript
 * access) would corrupt the item-delivery state machine. This is the one
 * service-package class that previously had no spec.
 */
@Subject(SteamBotResult)
class SteamBotResultSpec extends Specification {

    def "success(body) flags ok and extracts offerId / status / confirmed"() {
        when:
        def r = SteamBotResult.success([offerId: 'OFFER123', status: 'sent', confirmed: true, extra: 'kept'])

        then:
        r.ok
        r.offerId == 'OFFER123'
        r.status == 'sent'
        r.confirmed
        r.errorCode == null
        r.raw.extra == 'kept'          // raw body preserved for callers needing extra fields
    }

    def "success coerces a NON-string offerId via subscript access (the @CompileStatic Map gotcha)"() {
        when: "the sidecar returns offerId as a JSON number, and status as a non-string"
        def r = SteamBotResult.success([offerId: 1234567890L, status: 42])

        then: "subscript + String.valueOf coerces without a MissingMethodException (body.offerId would compile to getOfferId())"
        r.ok
        r.offerId == '1234567890'
        r.status == '42'
    }

    def "success(null) is ok with no offer fields and never throws"() {
        when:
        def r = SteamBotResult.success(null)

        then:
        r.ok
        r.offerId == null
        r.status == null
        !r.confirmed
        r.raw == null
    }

    def "success leaves confirmed false unless the body says confirmed == true"() {
        expect:
        SteamBotResult.success([:]).confirmed == false
        SteamBotResult.success([confirmed: 'true']).confirmed == false   // string 'true' is NOT boolean true
        SteamBotResult.success([confirmed: false]).confirmed == false
        SteamBotResult.success([confirmed: true]).confirmed == true
    }

    def "error(code, message) is not ok and carries no offer fields"() {
        when:
        def r = SteamBotResult.error('RATE_LIMITED', 'slow down')

        then:
        !r.ok
        r.errorCode == 'RATE_LIMITED'
        r.message == 'slow down'
        r.offerId == null
        r.status == null
    }

    def "error(code, message, body) keeps the raw body and pulls offerId when present"() {
        when:
        def r = SteamBotResult.error('ESCROW_HOLD', 'held', [offerId: 999, detail: 'x'])

        then:
        !r.ok
        r.errorCode == 'ESCROW_HOLD'
        r.offerId == '999'             // numeric offerId coerced here too
        r.raw.detail == 'x'
    }

    def "error(code, message, null) tolerates a null body"() {
        when:
        def r = SteamBotResult.error('ERROR', 'boom', null)

        then:
        !r.ok
        r.errorCode == 'ERROR'
        r.offerId == null
        r.raw == null
    }

    def "disabled() is a not-ok DISABLED result"() {
        when:
        def r = SteamBotResult.disabled()

        then:
        !r.ok
        r.errorCode == 'DISABLED'
        r.message.contains('Steam bot is not configured')
    }

    @Unroll
    def "isSent() is #expected for ok status '#status'"() {
        expect:
        SteamBotResult.success([status: status]).isSent() == expected

        where:
        status      | expected
        'sent'      | true
        'active'    | true
        'pending'   | true
        'accepted'  | false
        'declined'  | false
        'in_escrow' | false
        null        | false
    }

    @Unroll
    def "isTerminalFailure() is #expected for ok status '#status'"() {
        expect:
        SteamBotResult.success([status: status]).isTerminalFailure() == expected

        where:
        status          | expected
        'declined'      | true
        'expired'       | true
        'canceled'      | true
        'canceled_2fa'  | true
        'invalid_items' | true
        'sent'          | false
        'accepted'      | false
        'in_escrow'     | false
        null            | false
    }

    @Unroll
    def "isAccepted()=#acc / isInEscrow()=#esc for ok status '#status'"() {
        given:
        def r = SteamBotResult.success([status: status])

        expect:
        r.isAccepted() == acc
        r.isInEscrow() == esc

        where:
        status       | acc   | esc
        'accepted'   | true  | false
        'in_escrow'  | false | true
        'sent'       | false | false
        null         | false | false
    }

    @Unroll
    def "every status predicate is false on a NOT-ok result (status '#status' ignored when ok is false)"() {
        given: "an error result that nonetheless carries a status string"
        def r = SteamBotResult.error('ERROR', 'x')
        r.status = status

        expect: "ok is the gate — no predicate fires on a failed call"
        !r.isSent()
        !r.isAccepted()
        !r.isTerminalFailure()
        !r.isInEscrow()

        where:
        status << ['sent', 'accepted', 'declined', 'in_escrow']
    }

    def "toString includes the diagnostic fields for logging"() {
        expect:
        SteamBotResult.success([offerId: 'A1', status: 'sent']).toString() ==
            'SteamBotResult(ok=true, status=sent, offerId=A1, errorCode=null, message=null)'
    }
}
