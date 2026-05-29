package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.StripeService
import spock.lang.Specification

/**
 * Wave 126 multi-pod / webhook-race regression pin for
 * {@link StripeService#sweepStalePendingDeposits}.
 *
 * Two race classes pinned together:
 *
 *   1. MULTI-POD: `findStalePending` was read concurrently by every pod's
 *      4-hourly sweeper, and both pods saw the SAME `status=PENDING` row.
 *      The old code then ran the DEPOSIT_EXPIRED bell push on BOTH pods
 *      before either pod's saveAll committed — user received "your
 *      deposit expired" TWICE for one stale Stripe session.
 *
 *   2. STRIPE WEBHOOK OUT-OF-BAND: a webhook landing between the sweeper
 *      read and the UPDATE flips the row to CONFIRMED. The old
 *      unconditional saveAll(stale) would have STOMPED that with EXPIRED,
 *      wiping a real wallet credit's audit row AND firing a misleading
 *      "deposit expired" notification on top of the credited balance.
 *
 * Fix: per-row claimExpirePending conditional UPDATE flips
 * status PENDING→EXPIRED only WHERE status is still PENDING and returns
 * 1 to the winning path. Pre-fix the sweeper used saveAll without a
 * status guard.
 */
class StripeServiceStaleDepositClaimSpec extends Specification {

    TransactionRepository transactionRepository = Mock()
    WalletRepository      walletRepository      = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    NotificationService   notificationService   = Mock()

    StripeService service = new StripeService(
        transactionRepository: transactionRepository,
        walletRepository:      walletRepository,
        steamUserRepository:   steamUserRepository,
        notificationService:   notificationService,
        secretKey:             'sk_test_replace_me',
        publishableKey:        'pk_test_replace_me',
        webhookSecret:         'whsec_replace_me',
        successUrl:            'http://localhost/ok',
        cancelUrl:             'http://localhost/cancel',
        currency:              'usd'
    )

    private Transaction makeStale(long id, long walletId) {
        new Transaction(
            id:        id,
            walletId:  walletId,
            type:      'DEPOSIT',
            status:    'PENDING',
            amount:    new BigDecimal('25.00'),
            createdAt: System.currentTimeMillis() - (49L * 60L * 60L * 1000L)
        )
    }

    // ── (1) Winning claim → notification fires ────────────────────────────

    def "sweepStalePendingDeposits fires DEPOSIT_EXPIRED when claimExpirePending returns 1 (this pod won)"() {
        given:
        def tx = makeStale(1001L, 100L)
        transactionRepository.findStalePending('DEPOSIT', _) >> [tx]
        transactionRepository.claimExpirePending(1001L) >> 1
        walletRepository.findById(100L) >> Optional.of(new Wallet(id: 100L, username: 'steam_76561198000000001'))
        steamUserRepository.findBySteamId64('76561198000000001') >> new SteamUser(id: 42L)

        when:
        service.sweepStalePendingDeposits()

        then:
        1 * notificationService.safePush(42L, 'DEPOSIT_EXPIRED', _, _, 1001L, _)
    }

    // ── (2) Losing claim → notification skipped ───────────────────────────

    def "sweepStalePendingDeposits bails on losing claim — no notification, no wallet lookup"() {
        given: "sibling pod (or a Stripe webhook completing the deposit) beat us to the UPDATE"
        def tx = makeStale(2002L, 200L)
        transactionRepository.findStalePending('DEPOSIT', _) >> [tx]
        transactionRepository.claimExpirePending(2002L) >> 0

        when:
        service.sweepStalePendingDeposits()

        then: "no wallet lookup runs — losing pod must bail before any notify path"
        0 * walletRepository.findById(_)
        0 * steamUserRepository.findBySteamId64(_)
        0 * notificationService.safePush(_, _, _, _, _, _)
    }

    // ── (3) sweep does NOT call saveAll (legacy unconditional path gone) ──

    def "sweepStalePendingDeposits does NOT call saveAll — every row goes through the conditional claim"() {
        given:
        def tx = makeStale(3003L, 300L)
        transactionRepository.findStalePending('DEPOSIT', _) >> [tx]
        transactionRepository.claimExpirePending(3003L) >> 1
        walletRepository.findById(300L) >> Optional.empty()

        when:
        service.sweepStalePendingDeposits()

        then: "the legacy unconditional saveAll could have stomped a freshly-CONFIRMED webhook row with EXPIRED, wiping the wallet-credit audit. The conditional UPDATE is the only persistence path."
        0 * transactionRepository.saveAll(_)
    }

    // ── (4) Mixed batch — webhook-completed rows skipped ──────────────────

    def "sweepStalePendingDeposits in a 4-row batch where 2 rows webhook-completed only fires 2 notifications"() {
        given: "4 stale rows but 4002 and 4004 already flipped CONFIRMED by Stripe webhook between read and claim"
        def txs = (4001L..4004L).collect { makeStale(it, it + 1000L) }
        transactionRepository.findStalePending('DEPOSIT', _) >> txs
        transactionRepository.claimExpirePending(4001L) >> 1
        transactionRepository.claimExpirePending(4002L) >> 0
        transactionRepository.claimExpirePending(4003L) >> 1
        transactionRepository.claimExpirePending(4004L) >> 0

        walletRepository.findById(_) >> { Long wId -> Optional.of(new Wallet(id: wId, username: "steam_${wId}")) }
        steamUserRepository.findBySteamId64(_) >> { String sid -> new SteamUser(id: sid as Long) }

        when:
        service.sweepStalePendingDeposits()

        then: "exactly 2 DEPOSIT_EXPIRED pushes — for 4001 and 4003 only. 4002 and 4004 stay CONFIRMED with their wallet credit intact."
        2 * notificationService.safePush(_, 'DEPOSIT_EXPIRED', _, _, _, _)
    }

    // ── (5) Empty input — no claim, no notification ───────────────────────

    def "sweepStalePendingDeposits does nothing when findStalePending returns empty"() {
        given:
        transactionRepository.findStalePending('DEPOSIT', _) >> []

        when:
        service.sweepStalePendingDeposits()

        then:
        0 * transactionRepository.claimExpirePending(_)
        0 * notificationService.safePush(_, _, _, _, _, _)
    }
}
