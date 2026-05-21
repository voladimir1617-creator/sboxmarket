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

    /** Per-user cache (60s TTL). A user reloading /sell twice in a row,
     *  paging through their inventory, then triggering a bulk-list,
     *  used to fire 3+ outbound Steam HTTP calls in 10 seconds. The
     *  cache collapses repeated fetches to one call/min/user, easing
     *  Steam's rate-limit pressure and shaving the perceived latency
     *  on mobile (the second open is now a memory probe, not a 10s
     *  network round-trip). Bounded at 5000 entries with FIFO
     *  eviction so a long-running container can't accumulate
     *  unbounded state. */
    private static final long CACHE_TTL_MS = 60_000L
    private static final int  CACHE_MAX = 5000
    private final java.util.concurrent.ConcurrentHashMap<String, Map> inventoryCache = new java.util.concurrent.ConcurrentHashMap<>()

    /** Negative cache for 429 / 403 responses. When Steam rate-limits us
     *  or the inventory is private, we don't want a frantic user smashing
     *  "Refresh" to amplify our request rate against an already-pressured
     *  endpoint. A 5-minute TTL on the negative entry stops follow-up
     *  fetches and serves an empty list straight from memory. The TTL is
     *  longer than the positive cache because rate-limit windows on the
     *  Steam community endpoint are typically multi-minute. */
    private static final long NEG_CACHE_TTL_MS = 300_000L
    private final java.util.concurrent.ConcurrentHashMap<String, Long> negativeCache = new java.util.concurrent.ConcurrentHashMap<>()

    /** Test-friendly clear hook. Production code should never call this. */
    void clearCache() { inventoryCache.clear(); negativeCache.clear() }

    /** When did the upstream block us last (negative cache)? Returns the
     *  unix-ms timestamp at which the block expires, or null if not blocked.
     *  Used by the on-demand sync path to surface "Steam rate-limited us,
     *  try in X minutes" to the user instead of pretending the empty result
     *  was a real sync. */
    Long blockedUntilMs(String steamId64) {
        if (!steamId64) return null
        Long until = negativeCache.get(steamId64)
        if (until == null) return null
        if (System.currentTimeMillis() >= until) {
            negativeCache.remove(steamId64)
            return null
        }
        until
    }

    /** Force-clear cached state for one user. Wired to the on-demand
     *  "Re-sync now" button so an explicit user action ALWAYS retries
     *  Steam — the user clicking Refresh after a rate-limit is exactly
     *  the moment to drop the negative entry and probe upstream again. */
    void clearCacheFor(String steamId64) {
        if (!steamId64) return
        inventoryCache.remove(steamId64)
        negativeCache.remove(steamId64)
    }

    List<Map> fetchInventory(String steamId64) {
        if (!steamId64) return []
        long now = System.currentTimeMillis()
        // Negative-cache probe — if Steam recently 429'd or 403'd us for
        // this user, serve an empty list straight from memory rather than
        // burning another outbound call against a known-failing bucket.
        Long blockedUntil = negativeCache.get(steamId64)
        if (blockedUntil != null) {
            if (now < blockedUntil) return []
            negativeCache.remove(steamId64)
        }
        // Cache probe — short-circuit if we have a fresh hit.
        def cached = inventoryCache.get(steamId64)
        if (cached != null) {
            long age = now - (cached.at as long)
            if (age < CACHE_TTL_MS) {
                return (cached.items as List<Map>)
            }
        }
        def url = "https://steamcommunity.com/inventory/${steamId64}/${SBOX_APP_ID}/${CONTEXT_ID}?l=english&count=500"

        // Connection construction itself can throw — `new URL(...)` throws
        // MalformedURLException and `openConnection()` throws IOException —
        // so it has to live inside the guard alongside the actual request.
        // This class's contract (see the class javadoc) is "never throw":
        // GET /api/steam/inventory, POST /api/steam/list and /list-bulk all
        // call fetchInventory without a try/catch, so an escape here 500s a
        // user-facing endpoint instead of degrading to an empty inventory.
        HttpURLConnection conn
        int status
        String body
        try {
            conn = (HttpURLConnection) new URL(url).openConnection()
            conn.setRequestProperty('User-Agent', 'SkinBox/1.0 (+https://skinbox.market)')
            conn.setRequestProperty('Accept', 'application/json')
            conn.connectTimeout = 8_000
            conn.readTimeout    = 10_000
            status = conn.responseCode
            body   = status < 300 ? conn.inputStream.getText('UTF-8') : (conn.errorStream?.getText('UTF-8') ?: '')
        } catch (Exception e) {
            log.warn("Steam inventory fetch for $steamId64 threw: ${e.message}")
            return []
        }

        if (status == 403) {
            log.info("Steam inventory for $steamId64 is private — skipping")
            // Cache the private-inventory verdict so a frustrated user
            // hammering Refresh doesn't fire one outbound call per click.
            // Same TTL as 429 since both are "back off" signals.
            negativeCache.put(steamId64, now + NEG_CACHE_TTL_MS)
            return []
        }
        if (status == 429) {
            log.warn("Steam inventory rate-limited (429) for $steamId64")
            // Honour Steam's Retry-After when present (rare on the
            // community endpoint, but documented for the partner API
            // and cheap to read). Falls back to our default 5-minute
            // backoff when missing or unparseable.
            long backoffMs = NEG_CACHE_TTL_MS
            try {
                def retryAfter = conn.getHeaderField('Retry-After')
                if (retryAfter) {
                    long sec = Long.parseLong(retryAfter.trim())
                    if (sec > 0 && sec <= 3600) backoffMs = sec * 1000L
                }
            } catch (Exception ignored) { /* fall through to default */ }
            negativeCache.put(steamId64, now + backoffMs)
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
        // Cache the result. FIFO eviction at the soft cap so a long-
        // running container doesn't accumulate unbounded entries.
        if (inventoryCache.size() >= CACHE_MAX) {
            def first = inventoryCache.keys().nextElement()
            if (first != null) inventoryCache.remove(first)
        }
        inventoryCache.put(steamId64, [at: System.currentTimeMillis(), items: out] as Map)
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
