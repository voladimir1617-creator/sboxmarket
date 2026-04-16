package com.sboxmarket.service

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

/**
 * Reads a Steam user's public inventory for the s&box app (appid 590830).
 *
 * Steam Community inventory endpoints are public and unauthenticated for any
 * profile whose inventory is visible to the public — no API key required.
 * We hit the community endpoint, parse the JSON, and return a flat list of
 * {assetId, name, category, rarity, iconUrl, marketable}. Consumers map those
 * onto our own Item catalogue by name to create internal listings.
 *
 * When called on a private inventory or when Steam 429s us, we return an
 * empty list and log a warning — never throw — so callers can treat the
 * inventory as "nothing to list today, try again later".
 *
 * NOTE: s&box's Steam app id is 590830. context 2 is the standard inventory
 * context for workshop cosmetics (the same as Rust/TF2 use).
 */
@Service
@Slf4j
class SteamInventoryService {

    static final String SBOX_APP_ID = "590830"
    static final String CONTEXT_ID  = "2"

    List<Map> fetchInventory(String steamId64) {
        if (!steamId64) return []
        def url = "https://steamcommunity.com/inventory/${steamId64}/${SBOX_APP_ID}/${CONTEXT_ID}?l=english&count=500"
        def conn = (HttpURLConnection) new URL(url).openConnection()
        conn.setRequestProperty('User-Agent', 'SkinBox/1.0 (+https://sboxmarket.local)')
        conn.setRequestProperty('Accept', 'application/json')
        conn.connectTimeout = 8_000
        conn.readTimeout    = 10_000

        int status
        String body
        try {
            status = conn.responseCode
            body   = status < 300 ? conn.inputStream.getText('UTF-8') : (conn.errorStream?.getText('UTF-8') ?: '')
        } catch (Exception e) {
            log.warn("Steam inventory fetch for $steamId64 threw: ${e.message}")
            return []
        }

        if (status == 403) {
            log.info("Steam inventory for $steamId64 is private — skipping")
            return []
        }
        if (status == 429) {
            log.warn("Steam inventory rate-limited (429) for $steamId64")
            return []
        }
        if (status != 200 || !body) {
            log.warn("Steam inventory returned HTTP $status for $steamId64")
            return []
        }

        def json
        try { json = new JsonSlurper().parseText(body) }
        catch (Exception e) {
            log.warn("Steam inventory for $steamId64 was not valid JSON: ${e.message}")
            return []
        }

        // Descriptions are unique by (classid, instanceid); assets refer back
        // via those two fields. Build a lookup of descriptions, then walk the
        // assets so we return one entry per physical copy in the inventory.
        def descriptions = [:]
        json?.descriptions?.each { d ->
            def key = "${d.classid}_${d.instanceid}"
            descriptions[key] = d
        }
        def out = []
        json?.assets?.each { a ->
            def key = "${a.classid}_${a.instanceid}"
            def d = descriptions[key]
            if (d == null) return
            // Prefer icon_url_large (sharper) but fall back to icon_url. Both
            // are path fragments that need the akamaihd base + an explicit size.
            // We've hit cases where cloudflare.steamstatic.com returns 404 for
            // s&box items that resolve fine under the akamai origin, so the
            // primary URL now points at akamai and we expose both.
            def iconFrag = d.icon_url_large ?: d.icon_url
            def fullIcon = null
            // .toString() forces a plain java.lang.String rather than a
            // GStringImpl — Jackson occasionally serialises the latter as
            // an object ({values: [...], strings: [...]}), which broke the
            // frontend's `url.replace(...)` call in primitives.js when the
            // /sell page tried to render a Steam inventory thumbnail.
            if (iconFrag) {
                fullIcon = "https://steamcommunity-a.akamaihd.net/economy/image/${iconFrag}/330x192".toString()
            }
            out << [
                assetId:    a.assetid?.toString(),
                classId:    a.classid?.toString(),
                instanceId: a.instanceid?.toString(),
                name:       (d.market_hash_name ?: d.market_name ?: d.name)?.toString(),
                tradable:   ((d.tradable as Integer) ?: 0) == 1,
                marketable: ((d.marketable as Integer) ?: 0) == 1,
                type:       d.type?.toString(),
                iconUrl:    fullIcon,
                imageUrl:   fullIcon,   // alias so ItemImage can read it directly
                tags:       d.tags?.collect { [category: it.category?.toString(), name: it.name?.toString(), localized: it.localized_tag_name?.toString()] }
            ]
        }
        log.info("Fetched ${out.size()} s&box inventory items for $steamId64 (icons: ${out.count { it.iconUrl }}/${out.size()})")
        out
    }

    /**
     * Map a Steam item category tag to our internal category labels.
     * s&box tags items with (e.g.) "Hats", "Shirts", "Accessories" directly
     * in the `itemclass` or similar tag bucket — fall back to the type string.
     */
    String inferCategory(Map steamItem) {
        def name = (steamItem.name ?: '').toString().toLowerCase()
        def type = (steamItem.type ?: '').toString().toLowerCase()
        def fromType = ['hat','jacket','shirt','pants','gloves','boots','accessor','tattoo','mask','beard']
        def match = fromType.find { type.contains(it) || name.contains(it) }
        switch (match) {
            case 'hat':     return 'Hats'
            case 'jacket':  return 'Jackets'
            case 'shirt':   return 'Shirts'
            case 'pants':   return 'Pants'
            case 'gloves':  return 'Gloves'
            case 'boots':   return 'Boots'
            case 'accessor':
            case 'tattoo':
            case 'mask':
            case 'beard':   return 'Accessories'
            default:        return 'Accessories'
        }
    }
}
