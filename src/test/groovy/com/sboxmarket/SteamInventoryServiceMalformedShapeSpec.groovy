package com.sboxmarket

import com.sboxmarket.service.SteamInventoryService
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression cover for the "never throw on unexpected Steam shape" contract.
 *
 * Bug: `mapInventoryJson` (formerly inlined inside `fetchInventory` after the
 * JSON parse) iterated `descriptions` and `assets` with no try/catch and no
 * null-element guard. Steam Community 200-OKs occasionally come back with:
 *   - a `descriptions` array containing null entries (transient backend hiccup)
 *   - a `tags` list whose members are null
 *   - `descriptions` / `assets` as a non-iterable type (`{"success": false}`-ish
 *     payloads with a bare error string where the array should be)
 * Any of those tripped `d.classid` → NPE that escaped `fetchInventory`,
 * violating its documented "never throw" contract and 500-ing the
 * /api/steam/inventory, POST /list and POST /list-bulk endpoints.
 *
 * The fix wraps the walk in a try/catch + a `d == null` / `a == null` guard
 * and filters null tag entries. These specs would fail before the fix
 * (NPE / IllegalArgumentException would escape) and pass after.
 */
class SteamInventoryServiceMalformedShapeSpec extends Specification {

    @Subject
    SteamInventoryService service = new SteamInventoryService()

    def "mapInventoryJson tolerates a null entry inside descriptions[]"() {
        // Steam has been observed returning a partially-populated descriptions
        // array with `null` placeholders for items mid-update. Before the fix,
        // `d.classid` on a null element threw NPE that escaped fetchInventory.
        given:
        def json = new JsonSlurper().parseText('''
        {
            "assets": [{"assetid":"1","classid":"100","instanceid":"0"}],
            "descriptions": [
                null,
                {"classid":"100","instanceid":"0","market_hash_name":"Wizard Hat",
                 "tradable":1,"marketable":1,"type":"Cosmetic Hat",
                 "icon_url":"abc","tags":[{"category":"itemclass","name":"hat"}]}
            ]
        }
        ''')

        when:
        def rows = service.mapInventoryJson(json, '111')

        then:
        noExceptionThrown()
        rows.size() == 1
        rows[0].name == 'Wizard Hat'
        rows[0].assetId == '1'
    }

    def "mapInventoryJson tolerates a null entry inside assets[]"() {
        given:
        def json = new JsonSlurper().parseText('''
        {
            "assets": [
                null,
                {"assetid":"2","classid":"100","instanceid":"0"}
            ],
            "descriptions": [
                {"classid":"100","instanceid":"0","market_hash_name":"Cargo Pants",
                 "tradable":1,"marketable":1}
            ]
        }
        ''')

        when:
        def rows = service.mapInventoryJson(json, '111')

        then:
        noExceptionThrown()
        rows.size() == 1
        rows[0].name == 'Cargo Pants'
        rows[0].assetId == '2'
    }

    def "mapInventoryJson tolerates a null entry inside a description's tags[]"() {
        // Before the fix, `d.tags.collect { it.category }` on a list containing
        // a null tag threw NPE — the most realistic real-world hiccup.
        given:
        def json = new JsonSlurper().parseText('''
        {
            "assets": [{"assetid":"3","classid":"200","instanceid":"0"}],
            "descriptions": [
                {"classid":"200","instanceid":"0","market_hash_name":"Cosmetic Gloves",
                 "tradable":1,"marketable":1,
                 "tags":[null, {"category":"itemclass","name":"gloves","localized_tag_name":"Gloves"}]}
            ]
        }
        ''')

        when:
        def rows = service.mapInventoryJson(json, '111')

        then:
        noExceptionThrown()
        rows.size() == 1
        // The non-null tag survived; the null was filtered without aborting the row.
        rows[0].tags.size() == 1
        rows[0].tags[0].name == 'gloves'
    }

    def "mapInventoryJson tolerates descriptions[] being absent entirely"() {
        // `{"success": false}`-style payloads omit descriptions; the walk must
        // still terminate cleanly and return [] (no descriptions → no rows).
        given:
        def json = new JsonSlurper().parseText('''
        {
            "assets": [{"assetid":"4","classid":"300","instanceid":"0"}]
        }
        ''')

        when:
        def rows = service.mapInventoryJson(json, '111')

        then:
        noExceptionThrown()
        rows == []
    }

    def "mapInventoryJson tolerates a description whose classid/instanceid are missing"() {
        // A description without classid/instanceid still produces a key
        // ("null_null"), but that's never referenced by any asset, so the
        // asset walk doesn't match — no row emitted, no throw.
        given:
        def json = new JsonSlurper().parseText('''
        {
            "assets": [{"assetid":"5","classid":"400","instanceid":"0"}],
            "descriptions": [
                {"market_hash_name":"Orphan"}
            ]
        }
        ''')

        when:
        def rows = service.mapInventoryJson(json, '111')

        then:
        noExceptionThrown()
        rows == []
    }

    def "mapInventoryJson returns [] when the parsed root is null"() {
        // Belt-and-braces: a totally-null tree is the trivial degenerate case.
        when:
        def rows = service.mapInventoryJson(null, '111')

        then:
        noExceptionThrown()
        rows == []
    }

    def "mapInventoryJson degrades to [] when descriptions is a non-iterable scalar"() {
        // Steam has been observed returning `{"descriptions": "<error string>"}`
        // when a backend partial-failure flips through. Iterating a string with
        // .each yields Character — `c.classid` is a no-op via Groovy property
        // lookup (returns null), so the descriptions map ends up populated with
        // null-keyed entries. The asset walk then can't find a match — no row,
        // no throw. The try/catch is the safety net if any of that ever changes.
        given:
        def json = new JsonSlurper().parseText('''
        {
            "assets": [{"assetid":"6","classid":"500","instanceid":"0"}],
            "descriptions": "rate limited"
        }
        ''')

        when:
        def rows = service.mapInventoryJson(json, '111')

        then:
        noExceptionThrown()
        rows == []
    }
}
