package com.sboxmarket

import com.sboxmarket.controller.ItemController
import jakarta.servlet.http.HttpServletRequest
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency regression for {@link com.sboxmarket.controller.ItemController#shouldBumpView}.
 *
 * The old impl was a classic check-then-act on a {@code ConcurrentHashMap}:
 * {@code get(key)} followed by {@code put(key, now)} on the no-prior branch.
 * When N threads call {@code shouldBumpView} for the SAME (ip,item) key in
 * the same instant, every thread reads {@code prev == null}, every thread
 * calls {@code put}, and every thread returns {@code true} — so a refresh
 * storm from a single IP inflates the item's view counter by ONE PER
 * CONCURRENT REQUEST instead of the documented one-per-30-min cap.
 *
 * That's the abuse path the 30-min dedupe was added to close (see batch
 * 413 docstring on {@code viewBumpCache}). The fix is to make the
 * "first writer wins" branch atomic via {@code putIfAbsent} so exactly
 * one thread sees {@code prev == null} and returns {@code true}; every
 * concurrent peer sees the just-written stamp and returns {@code false}.
 */
class ItemViewBumpRaceSpec extends Specification {

    def "shouldBumpView is atomic per (ip,item) — only one of N concurrent calls bumps"() {
        given: "a fresh controller with an empty dedupe cache"
        def controller = new ItemController()

        and: "many threads racing the same (ip, item) key"
        int threads = 64
        def pool = Executors.newFixedThreadPool(threads)
        def latch = new CountDownLatch(1)
        def bumps = new AtomicInteger(0)
        def req = Mock(HttpServletRequest) {
            getHeader('CF-Connecting-IP') >> '203.0.113.7'
            getHeader('X-Forwarded-For')  >> null
            getRemoteAddr()               >> '203.0.113.7'
        }

        when:
        threads.times {
            pool.submit {
                latch.await()
                if (controller.shouldBumpView(req, 42L)) bumps.incrementAndGet()
            }
        }
        latch.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        then: "exactly one thread wins the dedupe race"
        // Pre-fix this would equal `threads` (every racer reads prev=null,
        // every racer bumps). Post-fix it MUST equal 1 — the 30-min
        // window contract demands at most one bump per (ip, item) per
        // window, irrespective of how many requests land in the same
        // instant.
        bumps.get() == 1
    }
}
