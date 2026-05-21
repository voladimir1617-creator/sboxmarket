package com.sboxmarket.service

import com.sboxmarket.event.AuctionBidPlacedEvent
import groovy.util.logging.Slf4j
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-listing SSE fan-out for auction bid updates.
 *
 * Subscribers call {@link #subscribe(Long)} from AuctionStreamController.
 * BidService publishes AuctionBidPlacedEvent via ApplicationEventPublisher;
 * Spring's transactional event listener forwards it to {@link #onBid} in
 * the AFTER_COMMIT phase so clients never see a bid that later rolled
 * back. A scheduled heartbeat keeps the connection alive through idle
 * proxies (nginx default is 60s).
 *
 * Capacity — each listing caps at MAX_PER_LISTING concurrent subscribers
 * so a popular auction can't exhaust process memory. Over cap, new
 * subscriptions complete immediately; clients will fall back to polling.
 */
@Service
@Slf4j
class AuctionEventBus {

    private static final int MAX_PER_LISTING = 200
    private static final long SSE_NO_TIMEOUT = 0L

    /** Equality probe for {@code ConcurrentHashMap.remove(key, value)} — a
     *  CopyOnWriteArrayList's equals() is list-content equality, so passing
     *  an empty list evicts the mapping iff it is currently empty. Used
     *  ONLY as a probe; never stored in {@link #subs} or added to. */
    private static final List<SseEmitter> EMPTY_LIST = new CopyOnWriteArrayList<>()

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> subs = new ConcurrentHashMap<>()

    /**
     * Register a new SSE subscriber for a listing. The returned emitter
     * self-cleans on completion, timeout, or error. Caller (controller)
     * returns it directly to Spring MVC.
     */
    SseEmitter subscribe(Long listingId) {
        def emitter = new SseEmitter(SSE_NO_TIMEOUT)
        // computeIfAbsent + a re-check after add: a concurrently-reaped
        // listing list could be evicted from `subs` between our lookup and
        // our add, which would orphan this emitter (no bids, no heartbeat,
        // no cleanup). Loop until the list we added to is the one still
        // mapped — the window is tiny and contention here is low.
        CopyOnWriteArrayList<SseEmitter> list
        while (true) {
            list = subs.computeIfAbsent(listingId, { k -> new CopyOnWriteArrayList<SseEmitter>() })
            if (list.size() >= MAX_PER_LISTING) {
                log.warn("AuctionEventBus subscribe cap hit for listing ${listingId} (${list.size()}/${MAX_PER_LISTING})")
                // At cap the list is full (never empty), so it stays mapped
                // and keeps serving its existing subscribers — nothing to
                // clean up here; this emitter is just completed immediately.
                try { emitter.complete() } catch (Exception ignored) {}
                return emitter
            }
            list.add(emitter)
            if (subs.get(listingId).is(list)) break
            // Lost a race with a reaper that evicted `list`; undo and retry.
            list.remove(emitter)
        }
        // Evict the per-listing list from the map once it goes empty so a
        // process that serves many distinct auctions over its lifetime does
        // not accumulate an unbounded set of empty CopyOnWriteArrayLists.
        // `subs.remove(key, value)` only evicts when the mapping is still
        // this exact (empty) list, so it cannot drop a list another thread
        // has since repopulated.
        final CopyOnWriteArrayList<SseEmitter> bound = list
        Runnable remove = {
            bound.remove(emitter)
            if (bound.isEmpty()) subs.remove(listingId, EMPTY_LIST)
        }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError({ Throwable t -> remove.run() })
        try {
            // Send a hello so the client knows the stream is open; lets
            // EventSource's onopen fire without waiting for the first bid.
            emitter.send(SseEmitter.event()
                .name('hello')
                .data([listingId: listingId, now: System.currentTimeMillis()]))
        } catch (Exception ignored) {
            remove.run()
        }
        emitter
    }

    /**
     * Fan an AuctionBidPlacedEvent out to every subscriber of its listing.
     * Runs in AFTER_COMMIT so we never leak an un-committed bid to clients.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onBid(AuctionBidPlacedEvent ev) {
        def list = subs.get(ev.listingId)
        if (list == null || list.isEmpty()) return
        // NOTE: the public SSE payload deliberately omits currentBidderId.
        // The stream is fanned out to every subscriber including anonymous
        // viewers, so it must not expose the top bidder's real user id —
        // that would bypass the redaction BidService.historyFor applies to
        // third parties. Only the display-name snapshot goes out; clients
        // that need the viewer's own top-bidder state read it from the
        // listing fetch, which is computed per-viewer.
        Map payload = [
            listingId        : ev.listingId,
            kind             : ev.kind,
            currentBid       : ev.currentBid?.toPlainString(),
            currentBidderName: ev.currentBidderName,
            bidCount         : ev.bidCount,
            expiresAt        : ev.expiresAt,
            status           : ev.status,
            now              : ev.now
        ]
        def event = SseEmitter.event().name('bid').data(payload)
        def dead = [] as List<SseEmitter>
        for (em in list) {
            try {
                em.send(event)
            } catch (Exception ignored) {
                dead.add(em)
            }
        }
        if (!dead.isEmpty()) list.removeAll(dead)
    }

    /**
     * Periodic heartbeat — keeps idle proxies from timing out long-lived
     * SSE connections. Clients ignore `heartbeat` events; their presence
     * is enough to reset proxy idle timers.
     */
    @Scheduled(fixedDelay = 20_000L)
    void heartbeat() {
        long now = System.currentTimeMillis()
        def event = SseEmitter.event().name('heartbeat').data(now)
        subs.each { listingId, list ->
            def dead = [] as List<SseEmitter>
            for (em in list) {
                try { em.send(event) }
                catch (Exception ignored) { dead.add(em) }
            }
            if (!dead.isEmpty()) list.removeAll(dead)
            // Backstop for the per-emitter cleanup: drop any list that has
            // gone empty so `subs` cannot accumulate stale entries even if
            // an onCompletion/onError callback was never fired. Atomic —
            // only evicts while the mapping is still this empty list.
            if (list.isEmpty()) subs.remove(listingId, EMPTY_LIST)
        }
    }

    /** Test / debug hook — active subscriber count for a listing. */
    int subscriberCount(Long listingId) {
        subs.get(listingId)?.size() ?: 0
    }
}
