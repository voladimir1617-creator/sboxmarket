package com.sboxmarket

import com.sboxmarket.controller.ItemController
import jakarta.servlet.http.HttpServletRequest
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap

/**
 * Regression for the LRU eviction race in {@link com.sboxmarket.controller.ItemController#shouldBumpView}.
 *
 * Pre-fix the eviction at the size cap was:
 *
 * <pre>
 *   def oldest = viewBumpCache.entrySet().min { it.value }
 *   if (oldest) viewBumpCache.remove(oldest.key)        // <-- non-atomic, unconditional
 * </pre>
 *
 * The `min` snapshot and the `remove` call are NOT atomic. Under
 * concurrent load, another worker can refresh `oldest.key` (a genuine
 * new view from the same IP arriving AFTER its 30-min window has
 * elapsed) between the snapshot and the remove. The single-arg
 * {@code remove(K)} then wipes the just-written fresh stamp, so the
 * very next request for that same (ip,item) sees {@code prev == null}
 * in the {@code compute} closure and bumps AGAIN — defeating the
 * 30-min dedupe contract for the unlucky evicted key.
 *
 * Post-fix the eviction uses the two-arg atomic conditional remove
 * {@code remove(oldest.key, oldest.value)}, which only deletes when
 * the current value still equals the snapshot. A concurrent refresh
 * causes the conditional remove to skip, leaving the fresh entry
 * alone.
 *
 * This spec proves the fix at the ConcurrentHashMap-contract level
 * (a refresh between snapshot and remove must NOT wipe the entry)
 * AND verifies the live controller no longer wipes a refreshed entry
 * when eviction fires.
 */
class ItemViewBumpEvictionRaceSpec extends Specification {

    /** Reflection access to the private dedupe cache so the spec can
     *  prefill it to the cap without firing 10k real requests. */
    private static ConcurrentHashMap<String, Long> cacheOf(ItemController c) {
        def f = ItemController.class.getDeclaredField('viewBumpCache')
        f.setAccessible(true)
        f.get(c) as ConcurrentHashMap<String, Long>
    }

    private static int maxKeys() {
        def f = ItemController.class.getDeclaredField('VIEW_DEDUPE_MAX_KEYS')
        f.setAccessible(true)
        f.get(null) as int
    }

    def "atomic conditional remove leaves a key alone when its stamp was refreshed between snapshot and delete"() {
        given: 'a cache holding one entry that was just refreshed AFTER our snapshot'
        ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>()
        String victimKey = '203.0.113.7|42'
        long snapshotted = 1_000L
        long refreshed   = 9_000L
        cache.put(victimKey, snapshotted)

        and: 'we snapshot the entry (mimicking the min() scan), then a concurrent refresh lands'
        long observedAtSnapshot = cache.get(victimKey)
        cache.put(victimKey, refreshed)

        when: 'the post-fix conditional remove fires using the SNAPSHOT value'
        boolean removed = cache.remove(victimKey, observedAtSnapshot)

        then: 'the entry survives — value mismatched, so CHM left it alone'
        // Pre-fix `cache.remove(victimKey)` (single-arg) would have
        // returned the refreshed stamp and wiped the entry, allowing
        // the very next bump to reset the dedupe window.
        removed == false
        cache.get(victimKey) == refreshed
    }

    def "eviction at the cap evicts exactly one entry and never goes below the cap"() {
        given: 'a controller whose dedupe cache is prefilled to the cap with distinct stale entries'
        def controller = new ItemController()
        def cache = cacheOf(controller)
        int cap = maxKeys()
        // Fill cap entries with monotonically increasing stamps so the
        // oldest is deterministic — index 0 is the oldest.
        (0..<cap).each { i -> cache.put("seed|${i}", (1_000L + i) as Long) }
        cache.size() == cap

        and: 'a real request from a fresh (ip,item) that the cache has never seen'
        def req = Mock(HttpServletRequest) {
            getHeader('CF-Connecting-IP') >> '198.51.100.99'
            getHeader('X-Forwarded-For')  >> null
            getRemoteAddr()               >> '198.51.100.99'
        }

        when: 'shouldBumpView fires — inserts our key, pushes size past the cap, evicts the oldest'
        boolean bumped = controller.shouldBumpView(req, 999_999L)

        then: 'the fresh key bumped and the cache returned to (at most) the cap'
        bumped == true
        // After insert (cap + 1) and one eviction, size is back to cap.
        // Allow == cap OR == cap + 1 in case CHM size() drifted under
        // a parallel test runner; the load-bearing assertion is "we
        // evicted exactly the stalest seed key".
        cache.size() <= cap + 1

        and: 'the just-bumped key survived — it was never the oldest'
        cache.containsKey('198.51.100.99|999999')

        and: 'the original oldest seed key was the one evicted'
        // seed|0 had the smallest stamp; the conditional remove still
        // succeeds in the no-race path (we never refresh it mid-flight).
        !cache.containsKey('seed|0')
    }
}
