package com.sboxmarket

import com.sboxmarket.service.SteamInventoryService
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Subject

/**
 * The Sell grid's trade-lock badge counts days down to Steam's own unlock
 * date. These pin where that date may come from: only what Steam sent, never
 * an estimate, and never on an item Steam already calls tradable.
 */
class SteamInventoryTradeLockDateSpec extends Specification {

    @Subject
    SteamInventoryService service = new SteamInventoryService()

    private static final long OCT_23_0700_UTC = java.time.Instant.parse('2026-10-23T07:00:00Z').toEpochMilli()

    private List<Map> rows(String descriptionJson) {
        def json = new JsonSlurper().parseText("""
        { "assets": [{"assetid":"1","classid":"100","instanceid":"0"}],
          "descriptions": [ ${descriptionJson} ] }
        """)
        service.mapInventoryJson(json, '111')
    }

    def "a locked item takes Steam's cache_expiration as its unlock date"() {
        expect:
        rows('{"classid":"100","instanceid":"0","market_hash_name":"Wizard Hat","tradable":0,' +
             '"cache_expiration":"2026-10-23T07:00:00Z"}')[0].tradableAfter == OCT_23_0700_UTC
    }

    def "a locked item takes the date from Steam's owner line"() {
        expect:
        rows('{"classid":"100","instanceid":"0","market_hash_name":"Wizard Hat","tradable":0,' +
             '"owner_descriptions":[{"type":"html","value":" "},' +
             '{"type":"html","value":"Tradable/Marketable After Oct 23, 2026 (7:00:00) GMT"}]}')[0]
            .tradableAfter == OCT_23_0700_UTC
    }

    def "a locked item Steam gave no date for has none"() {
        expect:
        rows('{"classid":"100","instanceid":"0","market_hash_name":"Wizard Hat","tradable":0}')[0]
            .tradableAfter == null
    }

    def "a tradable item never carries a lock date, whatever the description says"() {
        expect:
        rows('{"classid":"100","instanceid":"0","market_hash_name":"Wizard Hat","tradable":1,' +
             '"cache_expiration":"2026-10-23T07:00:00Z"}')[0].tradableAfter == null
    }

    def "an unreadable date is dropped, not guessed"() {
        expect:
        rows('{"classid":"100","instanceid":"0","market_hash_name":"Wizard Hat","tradable":0,' +
             '"cache_expiration":"next week",' +
             '"owner_descriptions":[{"value":"Tradable After Foo 99, 2026 (7:00:00) GMT"}]}')[0]
            .tradableAfter == null
    }
}
