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
 * Concurrency + structural regression for {@link PriceHistoryService#record}'s
 * idempotency-cache eviction path.
 *
 * Old eviction was a classic check-then-act on a CHM entrySet:
 *
 *     recentWrites.entrySet().removeAll { it.value < cutoff }
 *
 * `removeAll(Closure)` in Groovy iterates and calls {@code iterator.remove()}
 * for each match, which on a {@link ConcurrentHashMap} delegates to the
 * UNCONDITIONAL single-arg {@code map.remove(key)} — so a concurrent
 * {@code recentWrites.compute(...)} that refreshes the key's stamp to
 * {@code now} BETWEEN the predicate's stale-snapshot read and the
 * iterator's remove() gets wiped out. The very next {@code record()}
 * call for that {@code (item|day|price|bump)} sees {@code prev == null}
 * in compute and claims again — so a settle-retry or overlapping Steam
 * sync fires the deferred write TWICE inside the 5s dedupe window. The
 * writer accumulates volume on the same-day row, so the duplicate event's
 * bump lands TWICE — silent volume inflation on the sparkline.
 *
 * Same bug shape as {@link com.sboxmarket.controller.ItemController}'s
 * viewBumpCache eviction (batch 1219, pinned by
 * {@link ItemViewBumpRaceSpec}) — the sibling fix never got migrated
 * here. The fix replaces the unconditional iterator-remove with
 * {@code recentWrites.remove(K, V)}, the two-arg conditional that only
 * deletes when the value still matches the snapshot. A refresh that
 * beats the sweep causes a clean skip.
 *
 * Pinned two ways:
 *  1. STRUCTURAL: the bytecode of {@code record()} must NOT call
 *     {@code java.util.Set.removeAll(Object)} on the recentWrites
 *     entrySet — that's the API path the old broken code used. The fix
 *     uses {@code ConcurrentHashMap.remove(Object, Object)}.
 *  2. BEHAVIOURAL: a stress test pre-populates the cache past the size
 *     cap with just-stale boundary stamps for a small pool of target
 *     idemKeys, then races refresher threads (re-stamp targets) against
 *     sweeper threads (drive the eviction code path with unique bumps).
 *     Total saves on the refresher target MUST equal the bump pool size:
 *     each key's first refresh legitimately claims (stamp expired) and
 *     fires one save; every subsequent refresh inside the 1s window MUST
 *     dedupe. Saves above the pool size = leaked claims = the eviction
 *     race firing.
 */
class PriceHistoryServiceEvictionRaceSpec extends Specification {

    PriceHistoryRepository priceHistoryRepository = Mock()
    PriceHistoryService service

    def setup() {
        service = new PriceHistoryService(
            priceHistoryRepository: priceHistoryRepository
        )
    }

    // ── (1) Structural pin: eviction must use atomic conditional remove ────

    def "record() eviction must NOT call Set.removeAll on the idempotency cache"() {
        // Bytecode scan: the broken impl was
        //   recentWrites.entrySet().removeAll { it.value < cutoff }
        // which compiles to a call into java.util.Set.removeAll(Object) via
        // Groovy's removeAll DGM. The fixed impl uses the two-arg
        // ConcurrentHashMap.remove(Object, Object) inside an explicit
        // iteration. Pinning the byte-level API choice catches any future
        // refactor that re-introduces the unconditional iterator-remove.
        expect:
        def cls = PriceHistoryService.class
        def bin = cls.getResource('PriceHistoryService.class').bytes
        // Scan the constant pool for the broken method ref. A real
        // hexscan would walk the constant pool table, but a substring
        // match on the UTF-8 bytes of the descriptor is enough: if the
        // string "removeAll" + the CHM entrySet receiver shows up in the
        // class file, the broken pattern is back.
        String binStr = new String(bin, 'ISO-8859-1')
        // The recentWrites.entrySet().removeAll(...) call leaves
        // "entrySet" + "removeAll" as adjacent constant pool entries
        // referenced from the same code attribute. The fix never
        // calls removeAll on a Set returned by entrySet() in this
        // method, so the pattern must be absent.
        !(binStr.contains('removeAll') && binStr.contains('entrySet'))
    }

    // ── (2) Behavioural pin: idempotency holds under eviction contention ──

    def "concurrent eviction sweep does NOT wipe a just-refreshed idempotency stamp"() {
        given: "the idempotency cache pre-loaded past the 5000-key cap"
        // Reach into the private CHM directly. Seed mostly stale entries
        // so the eviction sweep has plenty to remove (keeps the loop body
        // hot under contention) AND pre-seed the refresher's TARGET keys
        // with just-aged-out stamps. The race lives at the boundary
        // between "the predicate sees stale" and "the entry has just
        // been refreshed by a concurrent compute()" — without seeding
        // the target keys stale, the predicate always returns false for
        // them and the bug stays asleep.
        def recentWrites = recentWritesOf(service)
        long now = System.currentTimeMillis()
        long staleStamp    = now - 60_000L     // 60s old — definitely past 5s window
        long boundaryStamp = now - 5_001L      // just barely past the cutoff

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
        // will see prev != null AND `(now - prev) >= WINDOW_MS` → claim
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
            // Only count saves for the refresher's TARGET item id. The
            // sweeper deliberately uses unique bumps so every sweeper
            // record() call legitimately fires a save, and those would
            // drown out the leak signal.
            if (p?.item?.id == 7L) targetSaves.incrementAndGet()
            p
        }

        and: "a sweeper item that uses a different id and unique bumps so every call re-trips the cap"
        Item sweeperItem = new Item(id: 9_999L, name: 'Sweeper')

        when: "refresher and sweeper threads race for 1 second"
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

        and: "the refresher's target keys produced at most ONE save each"
        // Each target idemKey was pre-seeded just past the dedupe window,
        // so the FIRST refresh on each is the legitimate "stamp expired,
        // claim again" call → one save per bump. After that, every key
        // is freshly stamped and inside the window, so every subsequent
        // refresh in the test MUST be deduped. Total saves on the target
        // can never exceed the bump pool size — anything more is the
        // eviction sweep wiping a just-refreshed stamp and the next
        // refresher call mistakenly re-claiming.
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
}
