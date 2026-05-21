package com.sboxmarket

import com.sboxmarket.event.AuctionBidPlacedEvent
import com.sboxmarket.service.AuctionEventBus
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Coverage for the per-listing SSE fan-out bus.
 *
 * Load-bearing concerns pinned here:
 *   - subscribe registers an emitter and reports a live count.
 *   - The capacity cap completes over-cap emitters and never grows the list.
 *   - onBid fans the AuctionBidPlacedEvent ONLY to its own listing.
 *   - The public `bid` payload omits currentBidderId — an anonymous viewer
 *     must never receive the top bidder's real user id.
 *   - Dead emitters (send throws) are dropped from the per-listing list.
 *   - The per-listing list is EVICTED from the backing map once it goes
 *     empty — both via per-emitter cleanup and the heartbeat backstop — so
 *     a process serving many distinct auctions cannot leak empty lists.
 *   - Concurrent subscribe/cleanup never orphans an emitter or leaks a map
 *     entry.
 *
 * Notes on the test doubles: a real SseEmitter is used throughout. A
 * `complete()`d emitter throws on the next `send(...)` (Spring 6.1
 * behaviour), which is how a "dead" connection is simulated deterministically
 * without standing up an MVC container.
 */
class AuctionEventBusSpec extends Specification {

    @Subject
    AuctionEventBus bus = new AuctionEventBus()

    private AuctionBidPlacedEvent bidEvent(Map a = [:]) {
        new AuctionBidPlacedEvent(
            (a.listingId ?: 100L) as Long,
            (a.kind ?: 'bid') as String,
            (a.containsKey('currentBid') ? a.currentBid : new BigDecimal('12.50')) as BigDecimal,
            (a.containsKey('currentBidderId') ? a.currentBidderId : 777L) as Long,
            (a.containsKey('currentBidderName') ? a.currentBidderName : 'topbidder') as String,
            (a.bidCount ?: 3) as Integer,
            (a.expiresAt ?: 9_999_999L) as Long,
            (a.status ?: 'ACTIVE') as String
        )
    }

    /** A dead emitter — completed, so the bus's next send() to it throws. */
    private SseEmitter deadEmitter() {
        def e = new SseEmitter(0L)
        e.complete()
        e
    }

    // ---- subscribe / count -------------------------------------------

    def "subscribe registers an emitter and reports a live count"() {
        when:
        def emitter = bus.subscribe(100L)

        then:
        emitter != null
        bus.subscriberCount(100L) == 1
    }

    def "subscriberCount is zero for a listing nobody subscribed to"() {
        expect:
        bus.subscriberCount(424242L) == 0
    }

    def "multiple subscribers to one listing all register"() {
        when:
        bus.subscribe(100L)
        bus.subscribe(100L)
        bus.subscribe(100L)

        then:
        bus.subscriberCount(100L) == 3
    }

    def "subscribers to different listings are isolated"() {
        when:
        bus.subscribe(1L)
        bus.subscribe(2L)
        bus.subscribe(2L)

        then:
        bus.subscriberCount(1L) == 1
        bus.subscriberCount(2L) == 2
    }

    // ---- capacity cap -------------------------------------------------

    def "over-cap subscriptions complete immediately and do not grow the list"() {
        given: 'the listing is filled to the 200-subscriber cap'
        200.times { bus.subscribe(500L) }

        when: 'one more client subscribes'
        def overflow = bus.subscribe(500L)

        then: 'it is handed back already complete and the list stays at the cap'
        overflow != null
        bus.subscriberCount(500L) == 200
    }

    // ---- onBid fan-out ------------------------------------------------

    def "onBid delivers a bid event to a live subscriber"() {
        given:
        def emitter = bus.subscribe(100L)

        when:
        bus.onBid(bidEvent(listingId: 100L))

        then: 'the live emitter is still registered (send did not throw)'
        noExceptionThrown()
        bus.subscriberCount(100L) == 1
    }

    def "onBid only fans out to subscribers of the event's own listing"() {
        given: 'listing 999 holds a dead emitter; listing 100 holds a live one'
        // If onBid for listing 100 wrongly touched listing 999, the bus would
        // send to (and reap) the dead emitter — shrinking 999's count to 0.
        bus.subscribe(100L)
        listFor(999L).add(deadEmitter())

        when: 'a bid lands on listing 100'
        bus.onBid(bidEvent(listingId: 100L))

        then: 'listing 999 is completely untouched — no cross-listing fan-out'
        bus.subscriberCount(999L) == 1
        bus.subscriberCount(100L) == 1
    }

    def "onBid for a listing with no subscribers is a no-op"() {
        when:
        bus.onBid(bidEvent(listingId: 314159L))

        then:
        noExceptionThrown()
        bus.subscriberCount(314159L) == 0
    }

    def "onBid drops a dead emitter from the listing list"() {
        given: 'one live and one dead subscriber on the same listing'
        bus.subscribe(100L)            // live
        def list = listFor(100L)
        list.add(deadEmitter())        // dead — bus.send will throw for it
        assert bus.subscriberCount(100L) == 2

        when:
        bus.onBid(bidEvent(listingId: 100L))

        then: 'only the live emitter remains'
        bus.subscriberCount(100L) == 1
    }

    // ---- bidder-id redaction (security) ------------------------------

    def "the public bid payload never carries currentBidderId"() {
        given: 'a capturing emitter records every SSE event the bus sends it'
        def captured = []
        listFor(100L).add(capturingEmitter(captured))

        when:
        bus.onBid(bidEvent(listingId: 100L, currentBidderId: 999_001L,
                           currentBidderName: 'ghost'))

        then: 'exactly one bid event went out'
        captured.size() == 1

        and: 'its payload exposes the display name but NOT the real user id'
        def payload = dataOf(captured[0])
        payload.currentBidderName == 'ghost'
        !payload.containsKey('currentBidderId')
        // the secret id value appears nowhere in the whole serialised event
        !payload.toString().contains('999001')
    }

    def "the bid payload carries the fields the panel needs"() {
        given:
        def captured = []
        listFor(100L).add(capturingEmitter(captured))

        when:
        bus.onBid(bidEvent(listingId: 100L, kind: 'buy-now',
                           currentBid: new BigDecimal('44.00'),
                           bidCount: 9, status: 'SOLD'))

        then:
        def payload = dataOf(captured[0])
        payload.listingId == 100L
        payload.kind == 'buy-now'
        payload.currentBid == '44.00'
        payload.bidCount == 9
        payload.status == 'SOLD'
    }

    def "currentBid is sent as a plain-string, never scientific notation"() {
        given:
        def captured = []
        listFor(100L).add(capturingEmitter(captured))

        when: 'a bid amount that BigDecimal.toString() would render as 1E+2'
        bus.onBid(bidEvent(listingId: 100L,
                           currentBid: new BigDecimal('1E+2')))

        then:
        dataOf(captured[0]).currentBid == '100'
    }

    def "a null currentBid does not blow up the payload"() {
        given:
        def captured = []
        listFor(100L).add(capturingEmitter(captured))

        when:
        bus.onBid(bidEvent(listingId: 100L, currentBid: null))

        then:
        noExceptionThrown()
        dataOf(captured[0]).currentBid == null
    }

    // ---- heartbeat ----------------------------------------------------

    def "heartbeat drops dead emitters but keeps live ones"() {
        given:
        bus.subscribe(100L)            // live
        listFor(100L).add(deadEmitter())
        assert bus.subscriberCount(100L) == 2

        when:
        bus.heartbeat()

        then:
        bus.subscriberCount(100L) == 1
    }

    def "heartbeat with no subscribers anywhere is a no-op"() {
        when:
        bus.heartbeat()

        then:
        noExceptionThrown()
    }

    // ---- the leak fix: empty per-listing lists are evicted -----------

    def "a listing list is evicted from the map once its last subscriber dies (heartbeat)"() {
        given: 'a listing whose only subscriber is a dead connection'
        listFor(100L).add(deadEmitter())
        assert mapKeys().contains(100L)

        when: 'the heartbeat sweep runs'
        bus.heartbeat()

        then: 'the now-empty list is gone from the backing map — no leak'
        bus.subscriberCount(100L) == 0
        !mapKeys().contains(100L)
    }

    def "an emptied listing list is evicted by onBid+heartbeat, not left dangling"() {
        given:
        listFor(100L).add(deadEmitter())

        when: 'a bid empties the list, then the heartbeat backstop sweeps'
        bus.onBid(bidEvent(listingId: 100L))
        bus.heartbeat()

        then:
        !mapKeys().contains(100L)
    }

    def "a listing list with a surviving subscriber is NOT evicted"() {
        given: 'one live + one dead subscriber'
        bus.subscribe(100L)
        listFor(100L).add(deadEmitter())

        when:
        bus.heartbeat()

        then: 'dead one reaped, list kept because a live subscriber remains'
        bus.subscriberCount(100L) == 1
        mapKeys().contains(100L)
    }

    def "many short-lived listings do not accumulate empty lists"() {
        given: '50 distinct listings each get a single dead subscriber'
        (1L..50L).each { listFor(it).add(deadEmitter()) }
        assert mapKeys().size() == 50

        when: 'the heartbeat sweeps them all'
        bus.heartbeat()

        then: 'every empty list is evicted — the map is fully drained'
        mapKeys().isEmpty()
    }

    // ---- thread-safety ------------------------------------------------

    def "concurrent subscribes to one listing all register without loss"() {
        given:
        int threads = 24
        def pool = Executors.newFixedThreadPool(threads)
        def startGate = new CountDownLatch(1)
        def done = new CountDownLatch(threads)

        when:
        threads.times {
            pool.submit({
                startGate.await()
                try { bus.subscribe(100L) } finally { done.countDown() }
            })
        }
        startGate.countDown()
        done.await(5, TimeUnit.SECONDS)
        pool.shutdown()

        then: 'no add was lost to a race'
        bus.subscriberCount(100L) == threads
    }

    def "concurrent subscribe + heartbeat sweep never orphans a live emitter"() {
        given: 'a heartbeat hammering the map while subscribers pour in'
        int threads = 16
        def pool = Executors.newFixedThreadPool(threads + 2)
        def startGate = new CountDownLatch(1)
        def done = new CountDownLatch(threads)
        def stopSweeper = new java.util.concurrent.atomic.AtomicBoolean(false)

        def sweeper = pool.submit({
            startGate.await()
            while (!stopSweeper.get()) { bus.heartbeat() }
        })

        when:
        threads.times {
            pool.submit({
                startGate.await()
                try { bus.subscribe(100L) } finally { done.countDown() }
            })
        }
        startGate.countDown()
        done.await(5, TimeUnit.SECONDS)
        stopSweeper.set(true)
        sweeper.get(5, TimeUnit.SECONDS)
        pool.shutdown()

        then: 'every live subscriber is still mapped — the reaper never dropped a list mid-subscribe'
        bus.subscriberCount(100L) == threads
    }

    // ---- helpers ------------------------------------------------------

    /** Reach into the private subs map to grab (creating if needed) the
     *  CopyOnWriteArrayList for a listing — lets tests seed dead emitters. */
    private List<SseEmitter> listFor(Long listingId) {
        def subs = subsMap()
        subs.computeIfAbsent(listingId,
            { k -> new java.util.concurrent.CopyOnWriteArrayList<SseEmitter>() })
    }

    private Map subsMap() {
        def f = AuctionEventBus.getDeclaredField('subs')
        f.setAccessible(true)
        (Map) f.get(bus)
    }

    private Set mapKeys() {
        new HashSet(subsMap().keySet())
    }

    /** An SseEmitter that records every event the bus fans to it. The bus
     *  calls the SseEventBuilder overload of send(), so that is the one we
     *  intercept (overriding only send(Object) would miss every event). */
    private SseEmitter capturingEmitter(List sink) {
        new SseEmitter(0L) {
            @Override
            void send(SseEmitter.SseEventBuilder builder) { sink << builder }
        }
    }

    /** Pull the Map payload out of a captured SseEventBuilder. build()
     *  yields the DataWithMediaType set; the bus sets exactly one Map. */
    private Map dataOf(Object sseEvent) {
        def builder = (SseEmitter.SseEventBuilder) sseEvent
        def datum = builder.build().collect { it.data }.find { it instanceof Map }
        (Map) datum
    }
}
