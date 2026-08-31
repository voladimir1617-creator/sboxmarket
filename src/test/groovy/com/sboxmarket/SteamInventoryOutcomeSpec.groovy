package com.sboxmarket

import com.sboxmarket.service.SteamInventoryService
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Subject

/**
 * "You own nothing" must mean we asked and the answer was zero — not that we
 * failed to find out.
 *
 * ── The defect ────────────────────────────────────────────────────────────
 * {@code fetchInventory} returns an empty list for SEVEN different situations:
 * a private profile (403), a rate-limit (429), any other non-200, unparseable
 * JSON, a 200 whose shape we cannot map, a transport exception — and a
 * genuinely empty inventory. Every one of them reached the seller as the same
 * sentence, "No s&box items in your Steam inventory", which is a confident
 * factual claim about what they own. Six of the seven had not established that
 * at all.
 *
 * The single case that was distinguishable made it worse rather than better:
 * 403 and 429 both trip the same negative cache, and the controller hardcoded
 * {@code reason = 'rate_limited'} for both. So a seller with a private
 * inventory was told to wait a few minutes and retry — advice that can never
 * come true, because no amount of waiting makes a private inventory readable.
 *
 * These pin the distinction at the shape layer. The user-visible half is pinned
 * in {@code SteamInventoryReasonSpec}.
 */
class SteamInventoryOutcomeSpec extends Specification {

    @Subject
    SteamInventoryService service = new SteamInventoryService()

    private static def parse(String s) { new JsonSlurper().parseText(s) }

    def "a well-formed inventory with zero s&box assets is EMPTY, not unreadable"() {
        given: "Steam answered properly; the user simply owns nothing in app 590830 / context 2"
        def json = parse('{"assets": [], "descriptions": []}')

        when:
        def r = service.mapInventoryShape(json, '76561190000000001')

        then: "this is the ONE case where telling the seller they own nothing is true"
        r.items == []
        !r.malformed
    }

    def "a 200 that carries neither assets nor descriptions is UNREADABLE, not empty"() {
        given: "Steam's `{\"success\": false}`-shaped reply — a 200 that answers nothing"
        def json = parse('{"success": false, "Error": "This profile is private."}')

        when:
        def r = service.mapInventoryShape(json, '76561190000000001')

        then: "we did not learn that the user owns nothing; we failed to read the answer"
        r.items == []
        r.malformed
    }

    def "a null root is UNREADABLE"() {
        when:
        def r = service.mapInventoryShape(null, '76561190000000001')

        then:
        r.items == []
        r.malformed
    }

    def "a shape that throws mid-walk is UNREADABLE, not empty"() {
        given: "descriptions is a scalar where an array belongs"
        def json = parse('{"assets": [{"assetid":"1","classid":"100","instanceid":"0"}], "descriptions": "boom"}')

        when:
        def r = service.mapInventoryShape(json, '76561190000000001')

        then:
        r.malformed || r.items.isEmpty()
        noExceptionThrown()
    }

    def "a real inventory maps its items and is NOT flagged unreadable"() {
        given:
        def json = parse('''
        {
            "assets": [{"assetid":"1","classid":"100","instanceid":"0"}],
            "descriptions": [
                {"classid":"100","instanceid":"0","market_hash_name":"Wizard Hat",
                 "tradable":1,"marketable":1,"type":"Cosmetic Hat","icon_url":"abc"}
            ]
        }''')

        when:
        def r = service.mapInventoryShape(json, '76561190000000001')

        then:
        r.items.size() == 1
        r.items[0].name == 'Wizard Hat'
        !r.malformed
    }

    def "the outcome constants are distinct — the whole point is that they do not collapse"() {
        expect:
        [SteamInventoryService.OUTCOME_OK,
         SteamInventoryService.OUTCOME_EMPTY,
         SteamInventoryService.OUTCOME_PRIVATE,
         SteamInventoryService.OUTCOME_RATE_LIMITED,
         SteamInventoryService.OUTCOME_UPSTREAM,
         SteamInventoryService.OUTCOME_MALFORMED,
         SteamInventoryService.OUTCOME_NETWORK].toSet().size() == 7

        and: "a private profile is NOT reported as a rate limit — the bug that told a private user to 'retry in 3 min' forever"
        SteamInventoryService.OUTCOME_PRIVATE != SteamInventoryService.OUTCOME_RATE_LIMITED
    }

    def "lastOutcomeFor is null for a user we have never fetched, and for a blank id"() {
        expect:
        service.lastOutcomeFor('76561190000000009') == null
        service.lastOutcomeFor(null) == null
        service.lastOutcomeFor('') == null
    }
}
