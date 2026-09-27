package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * Wave 129 multi-pod race regression pin for
 * {@link TradeService#sweepReviewNudge} and
 * {@link TradeService#sweepSlowSellerWarning}.
 *
 * Multi-pod bug: both pods read the same candidates on the heartbeat,
 * both fired the notification (REVIEW_REMINDER / TRADE_SLOW_SELLER +
 * TRADE_SELLER_NUDGE) AND both stamped the dedupe column. The per-trade
 * "one nudge total" guarantee the partial-index columns were added to
 * provide was actually one-per-pod-per-trade.
 *
 * Fix: claimReviewNudge + claimSlowSellerWarning conditional UPDATEs
 * stamp the dedupe column only WHERE it's still NULL, returning 1 to
 * the winning pod / 0 to the losing pod.
 *
 * Same shape as waves 124, 125, 126, 127, 128.
 */
class TradeServiceSweepClaimSpec extends Specification {

    TradeRepository tradeRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()
    BanGuard banGuard = Mock()

    TradeService service

    def setup() {
        service = new TradeService(
            tradeRepository:     tradeRepository,
            steamUserRepository: steamUserRepository,
            notificationService: notificationService,
            banGuard:            banGuard,
            sellerResponseDays:  3L
        )
    }

    private Trade makeNudgeCandidate(long id) {
        new Trade(
            id:           id,
            buyerUserId:  42L,
            sellerUserId: 7L,
            itemName:     'Wizard Hat',
            state:        'VERIFIED',
            settledAt:    System.currentTimeMillis() - (72L * 60L * 60L * 1000L)
        )
    }

    private Trade makeSlowSellerCandidate(long id) {
        new Trade(
            id:           id,
            buyerUserId:  42L,
            sellerUserId: 7L,
            itemName:     'Wizard Hat',
            state:        'PENDING_SELLER_ACCEPT',
            updatedAt:    System.currentTimeMillis() - (26L * 60L * 60L * 1000L)
        )
    }

    // ── (1) sweepReviewNudge winning claim → REVIEW_REMINDER fires ───────

    def "sweepReviewNudge fires REVIEW_REMINDER when claimReviewNudge returns 1"() {
        given:
        def trade = makeNudgeCandidate(1001L)
        tradeRepository.findReviewNudgeCandidates(_) >> [trade]
        tradeRepository.claimReviewNudge(1001L, _) >> 1
        steamUserRepository.findAllById(_) >> []

        when:
        service.sweepReviewNudge()

        then:
        1 * notificationService.push(42L, 'REVIEW_REMINDER', _, _, 1001L, _)
    }

    // ── (2) sweepReviewNudge losing claim → fan-out skipped ──────────────

    def "sweepReviewNudge bails on losing claim — no REVIEW_REMINDER, no save"() {
        given: "sibling pod already nudged or buyer reviewed out-of-band"
        def trade = makeNudgeCandidate(2002L)
        tradeRepository.findReviewNudgeCandidates(_) >> [trade]
        tradeRepository.claimReviewNudge(2002L, _) >> 0
        steamUserRepository.findAllById(_) >> []

        when:
        service.sweepReviewNudge()

        then:
        0 * notificationService.push(_, _, _, _, _, _)
        0 * tradeRepository.save(_)
    }

    // ── (3) sweepReviewNudge does NOT call save() on winning claim ───────

    def "sweepReviewNudge does NOT call save() — claim UPDATE is the only persistence path"() {
        given:
        def trade = makeNudgeCandidate(3003L)
        tradeRepository.findReviewNudgeCandidates(_) >> [trade]
        tradeRepository.claimReviewNudge(3003L, _) >> 1
        steamUserRepository.findAllById(_) >> []

        when:
        service.sweepReviewNudge()

        then:
        0 * tradeRepository.save(_)
    }

    // ── (4) sweepSlowSellerWarning winning claim → both pushes fire ──────

    def "sweepSlowSellerWarning fires both TRADE_SLOW_SELLER + TRADE_SELLER_NUDGE on winning claim"() {
        given:
        def trade = makeSlowSellerCandidate(4004L)
        tradeRepository.findSlowSellerUnwarned(_) >> [trade]
        tradeRepository.claimSlowSellerWarning(4004L, _) >> 1
        steamUserRepository.findAllById(_) >> []
        steamUserRepository.findById(7L) >> Optional.empty()

        when:
        service.sweepSlowSellerWarning()

        then: "buyer gets the heads-up that the seller is slow"
        1 * notificationService.push(42L, 'TRADE_SLOW_SELLER', _, _, 4004L, _)

        and: "seller gets their nudge to act"
        1 * notificationService.push(7L, 'TRADE_SELLER_NUDGE', _, _, 4004L, _)
    }

    // ── (5) sweepSlowSellerWarning losing claim → both pushes skipped ────

    def "sweepSlowSellerWarning bails on losing claim — buyer and seller both stay un-pinged"() {
        given:
        def trade = makeSlowSellerCandidate(5005L)
        tradeRepository.findSlowSellerUnwarned(_) >> [trade]
        tradeRepository.claimSlowSellerWarning(5005L, _) >> 0
        steamUserRepository.findAllById(_) >> []

        when:
        service.sweepSlowSellerWarning()

        then: "neither party is double-pinged — sibling pod already warned them"
        0 * notificationService.push(_, _, _, _, _, _)
        0 * tradeRepository.save(_)
    }

    // ── (6) Mixed batch — only won claims fire ───────────────────────────

    def "sweepReviewNudge mixed-batch: 2 won out of 4 fires exactly 2 REVIEW_REMINDER pushes"() {
        given:
        def trades = (6001L..6004L).collect { makeNudgeCandidate(it) }
        tradeRepository.findReviewNudgeCandidates(_) >> trades
        tradeRepository.claimReviewNudge(6001L, _) >> 1
        tradeRepository.claimReviewNudge(6002L, _) >> 0
        tradeRepository.claimReviewNudge(6003L, _) >> 1
        tradeRepository.claimReviewNudge(6004L, _) >> 0
        steamUserRepository.findAllById(_) >> []

        when:
        service.sweepReviewNudge()

        then:
        2 * notificationService.push(_, 'REVIEW_REMINDER', _, _, _, _)
    }
}
