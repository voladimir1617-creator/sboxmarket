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

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> subs = new ConcurrentHashMap<>()

    /**
     * Register a new SSE subscriber for a listing. The returned emitter
     * self-cleans on completion, timeout, or error. Caller (controller)
     * returns it directly to Spring MVC.
     */
    SseEmitter subscribe(Long listingId) {
        def emitter = new SseEmitter(SSE_NO_TIMEOUT)
        def list = subs.computeIfAbsent(listingId, { k -> new CopyOnWriteArrayList<SseEmitter>() })
        if (list.size() >= MAX_PER_LISTING) {
            log.warn("AuctionEventBus subscribe cap hit for listing ${listingId} (${list.size()}/${MAX_PER_LISTING})")
            try { emitter.complete() } catch (Exception ignored) {}
            return emitter
        }
        list.add(emitter)
        Runnable remove = { list.remove(emitter) }
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
        Map payload = [
            listingId        : ev.listingId,
            kind             : ev.kind,
            currentBid       : ev.currentBid?.toPlainString(),
            currentBidderId  : ev.currentBidderId,
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
        subs.values().each { list ->
            def dead = [] as List<SseEmitter>
            for (em in list) {
                try { em.send(event) }
                catch (Exception ignored) { dead.add(em) }
            }
            if (!dead.isEmpty()) list.removeAll(dead)
        }
    }

    /** Test / debug hook — active subscriber count for a listing. */
    int subscriberCount(Long listingId) {
        subs.get(listingId)?.size() ?: 0
    }
}
