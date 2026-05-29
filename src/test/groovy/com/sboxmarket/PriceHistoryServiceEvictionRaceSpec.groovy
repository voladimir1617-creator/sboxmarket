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
 * load, a different writer thread can refresh `key`'s stamp to `now` via
 * `recentWrites.compute(...)` BETWEEN the predicate's stale-snapshot read
 * and the iterator's `remove()`. The eviction sweep then wipes the
 * just-refreshed claim, and the very next `record()` call for the same
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
 * Pin shape: the test directly stresses the eviction code path by
 * pre-loading the cache past the size cap with stale stamps, then racing
 * a "refresher" thread (which keeps a target key fresh by calling
 * `record()`) against a "sweeper" thread (which triggers eviction by
 * calling `record()` with a fresh distinct key). Pre-fix the unconditional
 * iterator-remove occasionally wipes the target key's fresh stamp during
 * the sweep and the next refresher call passes the idempotency check —
 * surfacing as the refresher's repo.save() being invoked more than once
 * per logical refresh. Post-fix the target stamp survives every sweep
 * and the repo.save() count matches the refresher's "won the claim" count.
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
        given: "the idempotency cache pre-loaded past the 5000-key cap with STALE stamps"
        // Reach into the private CHM and seed it with > MAX_KEYS entries
        // whose timestamps are older than the 5s window — so any eviction
        // sweep finds plenty of legitimate targets and the sweep loop
        // actually runs over the seeded entries every time it triggers.
        def recentWrites = recentWritesOf(service)
        long stale = System.currentTimeMillis() - 60_000L  // 60s old, well outside window
        // 5001 stale entries — first call to record() will trip the
        // size-cap check and run the eviction sweep.
        5_001.times { i -> recentWrites.put("stale|seed|$i", stale) }

        and: "a target key that the refresher thread will keep fresh"
        // The refresher targets a single (item, day, price, bump) shape.
        // The save() stub records how many times the deferred write
        // actually reached the repo.
        Item targetItem = new Item(id: 7L, name: 'Target')
        AtomicInteger saveCount = new AtomicInteger(0)
        priceHistoryRepository.findLatestByItem(7L) >> Optional.empty()
        priceHistoryRepository.findLatestByItem(9_999L) >> Optional.empty()
        priceHistoryRepository.save(_ as PriceHistory) >> { PriceHistory p ->
            // Count only saves for the refresher's TARGET item. The
            // sweeper deliberately writes a unique bump every iteration
            // so each of its idemKeys is fresh and IT fires a save per
            // call; including those in the count would drown out any
            // leak from the refresher.
            if (p?.item?.id == 7L) saveCount.incrementAndGet()
            p
        }

        and: "a sweeper item that uses a different id, so its keys never collide"
        // Each sweeper record() call uses a UNIQUE bump value, so its
        // idemKey is fresh every iteration — every call adds a new entry
        // and re-trips the size cap, forcing the eviction sweep to run
        // repeatedly while the refresher is hammering the same target.
        Item sweeperItem = new Item(id: 9_999L, name: 'Sweeper')
        priceHistoryRepository.findLatestByItem(9_999L) >> Optional.empty()

        when: "a refresher thread and a sweeper thread race for 500ms"
        def pool = Executors.newFixedThreadPool(2)
        def latch = new CountDownLatch(1)
        AtomicInteger refresherCalls = new AtomicInteger(0)
        AtomicInteger sweeperCalls = new AtomicInteger(0)
        long deadline = System.currentTimeMillis() + 500L

        pool.submit {
            latch.await()
            // The refresher keeps hammering the SAME (item, price, bump)
            // shape. Inside the 5s idempotency window, after the FIRST
            // successful claim only one save() should ever happen — every
            // subsequent call should hit the dedupe and skip the deferred
            // write. The race-pin is: if the sweeper wipes our stamp
            // mid-flight, the NEXT call mistakenly claims again and a
            // second save() leaks through.
            while (System.currentTimeMillis() < deadline) {
                service.record(targetItem, new BigDecimal('1.00'), 1)
                refresherCalls.incrementAndGet()
            }
        }
        pool.submit {
            latch.await()
            // The sweeper allocates a unique key each iteration so the
            // size cap is re-tripped continuously; this drives the
            // eviction code path under contention with the refresher.
            int local = 0
            while (System.currentTimeMillis() < deadline) {
                int uniqueBump = 100_000 + local++
                service.record(sweeperItem, new BigDecimal('2.00'), uniqueBump)
                sweeperCalls.incrementAndGet()
            }
        }
        latch.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        then: "the refresher made many calls and the sweeper drove many evictions"
        refresherCalls.get() > 100
        sweeperCalls.get()  > 100

        and: "the deferred write fired EXACTLY ONCE for the refresher's target key"
        // Without the fix, the sweeper's unconditional iterator-remove
        // occasionally races and wipes the target's fresh stamp during a
        // sweep — every subsequent refresher.record() call before the
        // next refresh observes `prev == null`, claims, and fires another
        // save(). The 5s window NEVER closes during this test (we only
        // run for 500ms), so a correct implementation MUST land exactly
        // one save() for the refresher's target — anything more is the
        // eviction race leaking duplicate volume bumps.
        saveCount.get() == 1
    }

    /** Reach the private ConcurrentHashMap via reflection so the test
     *  can seed it past the size cap without needing a back-door API on
     *  the service itself. */
    private static ConcurrentHashMap<String, Long> recentWritesOf(PriceHistoryService svc) {
        def f = PriceHistoryService.getDeclaredField('recentWrites')
        f.accessible = true
        (ConcurrentHashMap<String, Long>) f.get(svc)
    }

    private static boolean debugDump(AtomicInteger saveCount, AtomicInteger refresherCalls,
                                     AtomicInteger sweeperCalls, ConcurrentHashMap recentWrites) {
        def fmt = new java.text.SimpleDateFormat('MMM dd, yyyy')
        fmt.timeZone = TimeZone.getTimeZone('UTC')
        def targetKey = "7|" + fmt.format(new Date()) + "|1.00|1"
        System.err.println("DBG Saves=${saveCount.get()} refresher=${refresherCalls.get()} sweeper=${sweeperCalls.get()} cacheSize=${recentWrites.size()} targetPresent=${recentWrites.get(targetKey) != null}")
        return true
    }
}
