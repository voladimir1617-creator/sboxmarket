package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.service.PriceHistoryService
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency regression for {@link PriceHistoryService#record} idempotency
 * cache eviction.
 *
 * The old eviction path was a classic check-then-act on a CHM entrySet:
 *
 *   recentWrites.entrySet().removeAll { it.value < cutoff }
 *
 * `removeAll(Closure)` delegates to the iterator's `remove()` which calls
 * the UNCONDITIONAL single-arg `map.remove(key)` on CHM. Under concurrent
 * load, a different thread can refresh `key`'s stamp to `now` via
 * `recentWrites.compute(...)` BETWEEN the predicate's stale-snapshot read
 * and the iterator's `remove()`. The eviction sweep then wipes the
 * just-refreshed claim, and the very next `record()` call for that same
 * (item|day|price|bump) sees `prev == null` and claims again — so a
 * settle-retry or overlapping Steam-sync poll fires the deferred write
 * TWICE inside the dedupe window. Because the writer accumulates volume
 * on the same-day row (`latest.volume = (latest.volume ?: 0) + bump`),
 * the duplicate event's bump is added twice. The idempotency window is
 * silently defeated for the unlucky evicted key.
 *
 * This is the same bug shape as ItemController.viewBumpCache (batch 1219,
 * pinned by ItemViewBumpRaceSpec) — the sibling fix never got migrated
 * here. The fix is the same: replace the unconditional iterator-remove
 * with `recentWrites.remove(K, V)`, which only deletes when the value
 * still matches the snapshot. A concurrent refresh that beats the sweep
 * causes a clean skip.
 *
 * Pin shape: pre-seed the cache with > MAX_KEYS entries whose stamps are
 * JUST stale (the just-aged-out boundary, where the race actually fires
 * — not 60s stale, which would simply pass the predicate and have no
 * race window with a concurrent refresh). Race a refresher thread that
 * re-stamps a small set of those seeded keys via record() against a
 * sweeper thread whose unique-bump keys re-trip the size cap and drive
 * the eviction code path. Pre-fix the sweeper's unconditional
 * iterator-remove wipes the refresher's just-refreshed stamps, and the
 * NEXT refresher call observes `prev == null` and fires another save().
 * Post-fix the conditional remove(K, V) skips any entry whose value was
 * just refreshed.
 */
class PriceHistoryServiceEvictionRaceSpec extends Specification {

    PriceHistoryRepository priceHistoryRepository = Mock()
    PriceHistoryService service

    def setup() {
        service = new PriceHistoryService(
            priceHistoryRepository: priceHistoryRepository
        )
    }

    def "concurrent eviction sweep does NOT wipe a just-refreshed idempotency stamp"() {
        given: "the idempotency cache pre-loaded past the 5000-key cap"
        // Reach into the private CHM directly. Seed mostly stale entries
        // so the eviction sweep has plenty to remove (keeps the loop body
        // hot under contention) AND pre-seed the refresher's TARGET keys
        // with just-aged-out stamps. The race lives at the boundary
        // between "the predicate sees stale" and "the entry has just
        // been refreshed by a concurrent compute()" — without seeding
        // the target keys stale, the predicate always returns false for
        // them, the unconditional iterator-remove never fires for them,
        // and the bug stays asleep.
        def recentWrites = recentWritesOf(service)
        long now = System.currentTimeMillis()
        long staleStamp = now - 60_000L            // 60s old — definitely past 5s window
        long boundaryStamp = now - 5_001L          // just barely past the cutoff

        // 5000 background stale entries — sustain the size-cap trip across
        // the test so every record() call enters the eviction branch.
        5_000.times { i -> recentWrites.put("bg|seed|$i", staleStamp) }

        and: "a refresher item whose target keys are pre-seeded at the just-stale boundary"
        Item targetItem = new Item(id: 7L, name: 'Target')
        // Pre-compute the target idemKeys the refresher will hammer. The
        // refresher cycles through a SMALL pool of distinct bumps so the
        // refresh pressure spreads across multiple entries (more chances
        // for any one of them to lose the race against a sweep). All are
        // seeded with boundaryStamp so the FIRST refresh on each one
        // will see prev != null but `(now - prev) >= WINDOW_MS` → claim
        // → re-stamp; concurrent sweeps see the boundaryStamp snapshot
        // and (pre-fix) wipe the just-refreshed value.
        def fmt = new java.text.SimpleDateFormat('MMM dd, yyyy')
        fmt.timeZone = TimeZone.getTimeZone('UTC')
        String today = fmt.format(new Date())
        List<Integer> targetBumps = (1..16).toList()
        targetBumps.each { b ->
            recentWrites.put("7|${today}|1.00|${b}".toString(), boundaryStamp)
        }

        AtomicInteger targetSaves = new AtomicInteger(0)
        priceHistoryRepository.findLatestByItem(7L) >> Optional.empty()
        priceHistoryRepository.findLatestByItem(9_999L) >> Optional.empty()
        priceHistoryRepository.save(_ as PriceHistory) >> { PriceHistory p ->
            // Only count saves for the refresher's TARGET item id; the
            // sweeper's deliberate unique-bump pattern means EVERY sweeper
            // record() call is a fresh idemKey that legitimately fires a
            // save, and those would drown out the leak signal.
            if (p?.item?.id == 7L) targetSaves.incrementAndGet()
            p
        }

        and: "a sweeper item that uses a different id and unique bumps so every call re-trips the cap"
        Item sweeperItem = new Item(id: 9_999L, name: 'Sweeper')

        when: "a refresher and sweeper thread race for 1 second"
        def pool = Executors.newFixedThreadPool(8)
        def latch = new CountDownLatch(1)
        AtomicInteger refresherCalls = new AtomicInteger(0)
        AtomicInteger sweeperCalls = new AtomicInteger(0)
        long deadline = System.currentTimeMillis() + 1_000L

        // Multiple refresher threads to maximise the chance of catching
        // a sweeper mid-iteration over one of the target keys. Each
        // refresher cycles through the bump pool so different threads
        // refresh different keys concurrently.
        4.times { t ->
            pool.submit {
                latch.await()
                int local = t
                while (System.currentTimeMillis() < deadline) {
                    int b = targetBumps.get(local++ % targetBumps.size())
                    service.record(targetItem, new BigDecimal('1.00'), b)
                    refresherCalls.incrementAndGet()
                }
            }
        }
        // Multiple sweeper threads to drive the eviction sweep under
        // contention from many angles. Each iteration uses a unique bump
        // so its idemKey is fresh, claims always succeed, and the
        // eviction branch fires every time.
        4.times { t ->
            pool.submit {
                latch.await()
                int local = 100_000 * (t + 1)
                while (System.currentTimeMillis() < deadline) {
                    service.record(sweeperItem, new BigDecimal('2.00'), local++)
                    sweeperCalls.incrementAndGet()
                }
            }
        }
        latch.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        then: "the threads did meaningful work"
        refresherCalls.get() > 100
        sweeperCalls.get()  > 100

        and: "debug output (captured in system-err of the test report)"
        debugDump(targetSaves, refresherCalls, sweeperCalls, recentWrites, targetBumps.size())

        and: "the refresher's target keys produced at most ONE save each — the size of the bump pool"
        // Each target idemKey was pre-seeded just past the dedupe window,
        // so the FIRST refresh on each is the legitimate "stamp expired,
        // claim again" call → one save per bump. After that, every key
        // is freshly stamped and inside the window, so every subsequent
        // refresh in the test MUST be deduped. Total saves on the target
        // can never exceed the bump pool size — anything more is the
        // eviction sweep wiping a just-refreshed stamp and the next
        // refresher call mistakenly re-claiming.
        //
        // Pre-fix: the sweep wipes stamps and saves leak ABOVE bump
        // pool size (often dramatically so, scaling with sweep wins).
        // Post-fix: saves == bump pool size, deterministically.
        targetSaves.get() <= targetBumps.size()
    }

    /** Reach the private ConcurrentHashMap via reflection so the test
     *  can seed it past the size cap without needing a back-door API on
     *  the service itself. */
    private static ConcurrentHashMap<String, Long> recentWritesOf(PriceHistoryService svc) {
        def f = PriceHistoryService.getDeclaredField('recentWrites')
        f.accessible = true
        (ConcurrentHashMap<String, Long>) f.get(svc)
    }

    private static boolean debugDump(AtomicInteger saves, AtomicInteger refresher,
                                     AtomicInteger sweeper, ConcurrentHashMap rw, int bumpPoolSize) {
        System.err.println("DBG targetSaves=${saves.get()} bumpPool=${bumpPoolSize} refresher=${refresher.get()} sweeper=${sweeper.get()} cacheSize=${rw.size()}")
        return true
    }
}
