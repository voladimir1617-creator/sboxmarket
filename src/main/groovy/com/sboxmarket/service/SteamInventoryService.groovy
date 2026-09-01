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
     *  Steam community endpoint are typically multi-minute.
     *
     *  Bounded at the same soft cap as the positive cache so a sustained
     *  rate-limit storm against many distinct users can't grow this map
     *  without bound. Previously had no cap at all — if Steam IP-banned
     *  us for an hour while a 100k-user platform was running, the map
     *  could accumulate 100k entries before any of them expired. */
    private static final long NEG_CACHE_TTL_MS = 300_000L
    private static final int  NEG_CACHE_MAX = 5000
    private final java.util.concurrent.ConcurrentHashMap<String, Long> negativeCache = new java.util.concurrent.ConcurrentHashMap<>()

    /**
     * Why the last fetch for a user returned what it did.
     *
     * ── Six different failures used to be one empty list ──────────────────
     * {@code fetchInventory} returns {@code []} for a private profile, a 429,
     * any non-200, unparseable JSON, an unexpected JSON shape, a transport
     * exception — and for a genuinely empty inventory. The UI rendered all
     * seven as "No s&box items in your Steam inventory", so "Steam is throttling
     * us", "your profile is private" and "we could not read Steam's reply" were
     * all presented to the seller as the settled fact that they own nothing.
     * The only one that was ever distinguishable was the negative-cache probe,
     * and that lumped 403 and 429 together and reported both as `rate_limited`.
     *
     * That is this operator's most expensive recurring defect — absence
     * rendered as a successful answer — sitting on the first screen of the sell
     * flow. Recording the outcome costs one map write per fetch and lets the
     * seller be told which of the seven actually happened.
     */
    static final String OUTCOME_OK            = 'ok'
    /** 200 OK, well-formed, but no assets for app 590830 / context 2. Either a
     *  genuinely empty inventory or the wrong app/context — indistinguishable
     *  from here, and deliberately NOT reported as plain "empty", because the
     *  app id and context are hardcoded and a wrong one looks exactly like
     *  owning nothing. */
    static final String OUTCOME_EMPTY         = 'empty_or_wrong_context'
    static final String OUTCOME_PRIVATE       = 'private_profile'
    static final String OUTCOME_RATE_LIMITED  = 'rate_limited'
    static final String OUTCOME_UPSTREAM      = 'upstream_error'
    static final String OUTCOME_MALFORMED     = 'malformed_response'
    static final String OUTCOME_NETWORK       = 'network_error'

    private static final int OUTCOME_MAX = 5000
    private final java.util.concurrent.ConcurrentHashMap<String, Map> lastOutcome = new java.util.concurrent.ConcurrentHashMap<>()

    /**
     * How many assets Steam says the user actually has, versus how many we
     * asked for and mapped.
     *
     * ── Why this is tracked ───────────────────────────────────────────────
     * The fetch URL is hardcoded to {@code count=500} and we do NOT paginate
     * (Steam pages via {@code start_assetid} + {@code more_items}). A seller
     * holding more than 500 s&box assets therefore got a silently TRUNCATED
     * inventory: the 501st item onward simply was not there, and looked
     * exactly like an item he does not own. Nothing anywhere said "there is
     * more" — the same absence-read-as-success shape as the seven empty-list
     * causes, but on a NON-empty response, so none of that machinery fired.
     *
     * Steam returns {@code total_inventory_count} on every inventory reply.
     * Comparing it against what we mapped costs nothing and turns a silent
     * wrong answer into a statable one.
     */
    private static final int TRUNC_MAX = 5000
    private final java.util.concurrent.ConcurrentHashMap<String, Map> lastTruncation = new java.util.concurrent.ConcurrentHashMap<>()

    /**
     * {@code [total: <what Steam says he owns>, shown: <what we returned>]}
     * when the last fetch was truncated, else null. Callers use this to tell
     * the seller his list is incomplete instead of letting him believe the
     * missing items are items he does not have.
     */
    Map truncationFor(String steamId64) {
        if (!steamId64) return null
        return lastTruncation.get(steamId64)
    }

    /** Record (or clear) the truncation verdict for a user, bounded. */
    private void recordTruncation(String steamId64, Integer total, int shown) {
        if (!steamId64) return
        if (total == null || total <= shown) {
            lastTruncation.remove(steamId64)
            return
        }
        if (lastTruncation.size() >= TRUNC_MAX) {
            try {
                def first = lastTruncation.keys().nextElement()
                if (first != null) lastTruncation.remove(first)
            } catch (NoSuchElementException ignored) { /* raced to empty */ }
        }
        lastTruncation.put(steamId64, [total: total, shown: shown] as Map)
    }

    /** Record the outcome of a fetch, bounded like the other two maps. */
    private List<Map> recordOutcome(String steamId64, String outcome, String detail = null, List<Map> items = []) {
        if (steamId64) {
            if (lastOutcome.size() >= OUTCOME_MAX) {
                try {
                    def first = lastOutcome.keys().nextElement()
                    if (first != null) lastOutcome.remove(first)
                } catch (NoSuchElementException ignored) { /* raced to empty */ }
            }
            lastOutcome.put(steamId64, [outcome: outcome, detail: detail,
                                        at: System.currentTimeMillis()] as Map)
        }
        return items
    }

    /**
     * Why the last inventory fetch for this user produced what it did — one of
     * the {@code OUTCOME_*} constants, plus an optional operator-facing detail
     * and the timestamp. Null when we have never fetched for them.
     *
     * The caller uses this to say something true when the list is empty. An
     * empty list with {@code OUTCOME_OK}/{@code OUTCOME_EMPTY} genuinely means
     * "nothing to sell"; every other outcome means "we could not find out",
     * which is a different sentence and often a different remedy.
     */
    Map lastOutcomeFor(String steamId64) {
        if (!steamId64) return null
        return lastOutcome.get(steamId64)
    }

    /** Test-friendly clear hook. Production code should never call this. */
    void clearCache() { inventoryCache.clear(); negativeCache.clear(); lastOutcome.clear() }

    /** Record a negative-cache entry with bounded eviction. Same pattern as
     *  the positive cache's soft cap — when the map fills, drop one entry
     *  before inserting the new one so sustained 429 storms across many
     *  distinct users can't grow this map without bound. The dropped key
     *  comes from `keys().nextElement()` (ConcurrentHashMap enumeration
     *  order is unspecified — not strictly FIFO, but deterministic enough
     *  to bound size, which is the only invariant that matters here). */
    private void recordNegative(String steamId64, long untilMs) {
        if (negativeCache.size() >= NEG_CACHE_MAX) {
            try {
                def first = negativeCache.keys().nextElement()
                if (first != null) negativeCache.remove(first)
            } catch (NoSuchElementException ignored) { /* raced to empty */ }
        }
        negativeCache.put(steamId64, untilMs)
    }

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
        // Drain the response into local variables and release the socket
        // BEFORE branching on status — otherwise an early `return []` from
        // a 403/429/non-200 path skips `conn.disconnect()` and the socket
        // sits in the keep-alive pool half-read until the JVM GC's the
        // wrapper, slowly leaking sockets on a long-lived container under
        // sustained rate-limit pressure. The outer service callers were
        // already paying the disconnect cost for the happy path (the JVM
        // does it implicitly when the input stream is fully consumed)
        // but the error paths had no such drain. Snapshot Retry-After
        // here too while the conn is still open.
        HttpURLConnection conn = null
        int status
        String body
        String retryAfterHeader = null
        try {
            conn = (HttpURLConnection) new URL(url).openConnection()
            conn.setRequestProperty('User-Agent', 'SkinBox/1.0 (+https://skinbox.market)')
            conn.setRequestProperty('Accept', 'application/json')
            conn.connectTimeout = 8_000
            conn.readTimeout    = 10_000
            status = conn.responseCode
            body   = status < 300 ? conn.inputStream.getText('UTF-8') : (conn.errorStream?.getText('UTF-8') ?: '')
            if (status == 429) retryAfterHeader = conn.getHeaderField('Retry-After')
        } catch (Exception e) {
            log.warn("Steam inventory fetch for $steamId64 threw: ${e.message}")
            return recordOutcome(steamId64, OUTCOME_NETWORK, e.message)
        } finally {
            try { conn?.disconnect() } catch (Exception ignored) {}
        }

        if (status == 403) {
            log.info("Steam inventory for $steamId64 is private — skipping")
            // Cache the private-inventory verdict so a frustrated user
            // hammering Refresh doesn't fire one outbound call per click.
            // Same TTL as 429 since both are "back off" signals.
            recordNegative(steamId64, now + NEG_CACHE_TTL_MS)
            // Distinct from the 429 below. Both trip the same negative cache,
            // and before this both were reported to the seller as
            // "rate_limited" — so a user whose profile was private was told to
            // wait and retry, forever, instead of being told to make their
            // inventory public.
            return recordOutcome(steamId64, OUTCOME_PRIVATE,
                    'Steam returned 403 — inventory is private or friends-only')
        }
        if (status == 429) {
            log.warn("Steam inventory rate-limited (429) for $steamId64")
            // Honour Steam's Retry-After when present (rare on the
            // community endpoint, but documented for the partner API
            // and cheap to read). Falls back to our default 5-minute
            // backoff when missing or unparseable. Reads the snapshot
            // taken above — the connection has already been released
            // by the finally block, so we can't call .getHeaderField
            // on it any more.
            long backoffMs = NEG_CACHE_TTL_MS
            try {
                if (retryAfterHeader) {
                    long sec = Long.parseLong(retryAfterHeader.trim())
                    if (sec > 0 && sec <= 3600) backoffMs = sec * 1000L
                }
            } catch (Exception ignored) { /* fall through to default */ }
            recordNegative(steamId64, now + backoffMs)
            return recordOutcome(steamId64, OUTCOME_RATE_LIMITED,
                    "Steam returned 429; backing off ${backoffMs}ms".toString())
        }
        if (status != 200 || !body) {
            log.warn("Steam inventory returned HTTP $status for $steamId64")
            return recordOutcome(steamId64, OUTCOME_UPSTREAM,
                    "Steam returned HTTP ${status}".toString())
        }

        def json
        try { json = new JsonSlurper().parseText(body) }
        catch (Exception e) {
            log.warn("Steam inventory for $steamId64 was not valid JSON: ${e.message}")
            return recordOutcome(steamId64, OUTCOME_MALFORMED,
                    "Steam's reply was not valid JSON: ${e.message}".toString())
        }

        Map mapResult = mapInventoryShape(json, steamId64)
        def out = (mapResult['items'] ?: []) as List<Map>
        boolean malformedShape = mapResult['malformed'] == true
        if (malformedShape) {
            // 200 OK with a body we could parse but not understand. NOT the
            // same as an empty inventory: we failed to read the answer rather
            // than reading an answer of zero.
            return recordOutcome(steamId64, OUTCOME_MALFORMED,
                    'Steam returned a shape we could not map (missing assets/descriptions)')
        }
        // Truncation check BEFORE we report success. `count=500` is a cap, not
        // a promise, and we do not paginate — so a bigger inventory comes back
        // quietly short. Steam always sends total_inventory_count.
        Integer totalOwned = null
        try {
            def raw = json?.total_inventory_count
            if (raw != null) totalOwned = (raw as Number).intValue()
        } catch (Exception ignored) { /* absent or unparseable — treat as unknown */ }
        recordTruncation(steamId64, totalOwned, out.size())
        if (totalOwned != null && totalOwned > out.size()) {
            log.warn("Steam inventory for ${steamId64} TRUNCATED: Steam reports ${totalOwned} assets, " +
                     "we fetched ${out.size()} (count=500, no pagination)")
        }
        recordOutcome(steamId64, out.isEmpty() ? OUTCOME_EMPTY : OUTCOME_OK,
                out.isEmpty() ? "no app ${SBOX_APP_ID} / context ${CONTEXT_ID} assets in the response".toString() : null)
        log.info("Fetched ${out.size()} s&box inventory items for $steamId64 (icons: ${out.count { it.iconUrl }}/${out.size()})")
        // Cache the result. Bounded soft-cap eviction at CACHE_MAX so a
        // long-running container doesn't accumulate unbounded entries.
        // ConcurrentHashMap.keys() enumeration order is unspecified (not
        // strictly FIFO) but bounding the size is the only invariant that
        // matters here. NoSuchElementException is possible if the map raced
        // to empty between the size check and the iterator pull — swallow
        // it because the put below will still respect the cap on the next
        // entry.
        if (inventoryCache.size() >= CACHE_MAX) {
            try {
                def first = inventoryCache.keys().nextElement()
                if (first != null) inventoryCache.remove(first)
            } catch (NoSuchElementException ignored) { /* raced to empty */ }
        }
        inventoryCache.put(steamId64, [at: System.currentTimeMillis(), items: out] as Map)
        out
    }

    /**
     * Walk a parsed Steam inventory JSON tree and return the flat row list.
     *
     * Descriptions are unique by (classid, instanceid); assets refer back via
     * those two fields. We build a lookup of descriptions, then walk the
     * assets so we emit one row per physical copy in the inventory.
     *
     * The whole walk is wrapped in a try/catch because Steam occasionally
     * returns shapes we don't expect (200 OK with `{"success": false}`, a
     * `descriptions` array containing null entries, a `tags` list whose
     * members lack `category`, etc.). Without this guard a single null
     * dereference — `d.classid` when d is null — escapes the caller as an
     * NPE, but fetchInventory is documented as "never throw" because GET
     * /api/steam/inventory, POST /api/steam/list and /list-bulk all call it
     * without a surrounding try/catch, so any escape 500s a user-facing
     * endpoint. Degrade to [] like every other failure path.
     *
     * Package-private for direct Spock coverage of the shape-tolerance.
     */
    List<Map> mapInventoryJson(json, String steamId64) {
        return (mapInventoryShape(json, steamId64)['items'] ?: []) as List<Map>
    }

    /**
     * The shape-aware core of {@link #mapInventoryJson}. Returns
     * {@code [items: List<Map>, malformed: boolean]}.
     *
     * The `malformed` flag is the entire reason this exists. Both a genuinely
     * empty inventory and a reply we could not understand produce an empty
     * item list, and reporting the second as the first tells a seller they own
     * nothing on the strength of a response we failed to read. `mapInventoryJson`
     * stays as a thin wrapper so its existing direct Spock coverage of the
     * shape-tolerance is unchanged.
     */
    // Package-private, matching mapInventoryJson, for direct Spock coverage of
    // the empty-vs-unreadable distinction.
    Map mapInventoryShape(json, String steamId64) {
        def out = []
        try {
            def descriptions = [:]
            json?.descriptions?.each { d ->
                if (d == null) return
                def key = "${d.classid}_${d.instanceid}"
                descriptions[key] = d
            }
            json?.assets?.each { a ->
                if (a == null) return
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
                    tags:       d.tags?.findAll { it != null }?.collect { [category: it.category?.toString(), name: it.name?.toString(), localized: it.localized_tag_name?.toString()] }
                ]
            }
        } catch (Exception e) {
            log.warn("Steam inventory for $steamId64 had unexpected shape: ${e.message}")
            return [items: [], malformed: true]
        }
        // A 200 with neither assets nor descriptions is not a well-formed empty
        // inventory — Steam returns `{"success": false}` in that shape too — so
        // treat it as unreadable rather than as a confident zero.
        boolean unreadable = (json == null) || (json?.assets == null && json?.descriptions == null)
        return [items: out, malformed: unreadable]
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
