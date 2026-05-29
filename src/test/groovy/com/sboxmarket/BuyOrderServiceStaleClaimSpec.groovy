package com.sboxmarket

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * Wave 125 multi-pod race regression pin for
 * {@link BuyOrderService#sweepStaleBuyOrders}.
 *
 * The bug: `findStaleActive` was read concurrently by every pod's daily
 * sweeper on the same 24h heartbeat, and both pods saw the SAME
 * `status=ACTIVE` rows. The old code then ran the full BUY_ORDER_EXPIRED
 * fan-out (TRADES-bucketed email + bell push) on BOTH pods before either
 * pod's `save()` landed — buyer received the auto-expire reminder TWICE.
 *
 * The fix is a conditional UPDATE (`claimExpire`) that flips
 * `status` from ACTIVE→EXPIRED atomically and returns the
 * affected-row count: 1 = this pod owns the fan-out, 0 = sibling pod
 * already claimed it (or a concurrent buyer cancel / fill flipped
 * status) and the losing pod bails before any notify or email runs.
 *
 * Same shape as wave 112 (WatchlistAlertService.claimForFiring),
 * wave 120 (FraudAnalysisService cluster claim), and wave 124
 * (BidService ending-soon claim).
 */
class BuyOrderServiceStaleClaimSpec extends Specification {

    BuyOrderRepository buyOrderRepository = Mock()
    ListingRepository listingRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()
    EmailService emailService = Mock()
    BanGuard banGuard = Mock()

    BuyOrderService service

    def setup() {
        service = new BuyOrderService(
            buyOrderRepository:  buyOrderRepository,
            listingRepository:   listingRepository,
            steamUserRepository: steamUserRepository,
            notificationService: notificationService,
            emailService:        emailService,
            banGuard:            banGuard
        )
    }

    private BuyOrder makeOrder(long id) {
        new BuyOrder(
            id:          id,
            buyerUserId: 42L,
            itemId:      99L,
            itemName:    'Wizard Hat',
            maxPrice:    new BigDecimal('10.00'),
            status:      'ACTIVE',
            updatedAt:   System.currentTimeMillis() - (35L * 24L * 60L * 60L * 1000L)
        )
    }

    // ── (1) Winning claim — UPDATE returns 1 → fan-out fires ──────────────

    def "sweepStaleBuyOrders fires fan-out when claimExpire returns 1 (this pod won)"() {
        given:
        def order = makeOrder(1001L)
        buyOrderRepository.findStaleActive(_) >> [order]
        buyOrderRepository.claimExpire(1001L, _) >> 1
        steamUserRepository.findById(42L) >> Optional.of(
            new com.sboxmarket.model.SteamUser(id: 42L, email: 'b@x', displayName: 'B', emailVerified: true)
        )
        emailService.canSendTo(_, 'TRADES') >> true

        when:
        service.sweepStaleBuyOrders()

        then: "single bell push + single email"
        1 * notificationService.push(42L, 'BUY_ORDER_EXPIRED', _, _, 1001L, _)
        1 * emailService.sendBuyOrderExpired(_, _, _, _, _)
    }

    // ── (2) Losing claim — UPDATE returns 0 → fan-out skipped ─────────────

    def "sweepStaleBuyOrders bails when claimExpire returns 0 (sibling pod beat us)"() {
        given: "the order was returned by findStaleActive BEFORE the sibling pod's UPDATE landed"
        def order = makeOrder(2002L)
        buyOrderRepository.findStaleActive(_) >> [order]
        buyOrderRepository.claimExpire(2002L, _) >> 0

        when:
        service.sweepStaleBuyOrders()

        then: "no buyer lookup runs — claim losing pod must bail BEFORE the recipient lookup"
        0 * steamUserRepository.findById(_)

        and: "no bell push, no email — the only notify path for this row belongs to the sibling pod"
        0 * notificationService.push(_, _, _, _, _, _)
        0 * emailService.sendBuyOrderExpired(_, _, _, _, _)
    }

    // ── (3) Mixed batch — some claims win, some lose ──────────────────────

    def "sweepStaleBuyOrders in a 4-row batch where this pod wins 2 claims fires exactly 2 fan-outs"() {
        given:
        def orders = (3001L..3004L).collect { makeOrder(it) }
        buyOrderRepository.findStaleActive(_) >> orders

        // This pod wins 3001 + 3003; sibling pod won 3002 + 3004.
        buyOrderRepository.claimExpire(3001L, _) >> 1
        buyOrderRepository.claimExpire(3002L, _) >> 0
        buyOrderRepository.claimExpire(3003L, _) >> 1
        buyOrderRepository.claimExpire(3004L, _) >> 0

        steamUserRepository.findById(_) >> Optional.of(
            new com.sboxmarket.model.SteamUser(id: 42L, email: 'b@x', displayName: 'B', emailVerified: true)
        )
        emailService.canSendTo(_, 'TRADES') >> true

        when:
        service.sweepStaleBuyOrders()

        then: "exactly 2 bell pushes — one for each won claim"
        2 * notificationService.push(_, 'BUY_ORDER_EXPIRED', _, _, _, _)
        2 * emailService.sendBuyOrderExpired(_, _, _, _, _)
    }

    // ── (4) Empty input — no claim ever runs ──────────────────────────────

    def "sweepStaleBuyOrders does nothing when findStaleActive returns empty"() {
        given:
        buyOrderRepository.findStaleActive(_) >> []

        when:
        service.sweepStaleBuyOrders()

        then:
        0 * buyOrderRepository.claimExpire(_, _)
        0 * notificationService.push(_, _, _, _, _, _)
        0 * emailService.sendBuyOrderExpired(_, _, _, _, _)
    }

    // ── (5) Claim does NOT call the legacy buyOrderRepository.save ────────

    def "sweepStaleBuyOrders does NOT call save() — the conditional UPDATE handles persistence"() {
        given:
        def order = makeOrder(5005L)
        buyOrderRepository.findStaleActive(_) >> [order]
        buyOrderRepository.claimExpire(5005L, _) >> 1
        steamUserRepository.findById(_) >> Optional.empty()

        when:
        service.sweepStaleBuyOrders()

        then: "no .save(order) — a redundant entity flush would dirty-write every column and could collide with a concurrent buyer cancel / fill on the same row"
        0 * buyOrderRepository.save(_)
    }
}
