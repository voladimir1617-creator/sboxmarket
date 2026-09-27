package com.sboxmarket

import com.sboxmarket.model.FraudSignalClaim
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.WalletPaymentFailure
import com.sboxmarket.repository.FraudSignalClaimRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletPaymentFailureRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.StripeService
import com.stripe.model.PaymentIntent
import org.springframework.dao.DataIntegrityViolationException
import spock.lang.Specification
import spock.lang.Subject

/**
 * Wave 148 multi-pod card-testing regression pin for {@link StripeService}.
 *
 * AUDIT GAP CLOSED: the card-testing fraud detector counted
 * payment_intent.payment_failed events in a PER-JVM
 * {@code ConcurrentHashMap<Long, List<Long>>} only. On a multi-pod deploy
 * Stripe load-balances webhook deliveries, so an attacker spreading declines
 * across pods accumulated only a fraction of the count on any single pod,
 * never crossed the per-pod CARD_TEST_THRESHOLD, and the
 * CARD_TESTING_DETECTED alert never fired — distributed card-testing evaded
 * the threshold entirely.
 *
 * FIX: each failure INSERTs a {@link WalletPaymentFailure} row and the
 * threshold is evaluated against the DB aggregate
 * {@code countByWalletIdAndFailedAtAfter(walletId, cutoff)} — summed across
 * EVERY pod's rows. The cross-pod ALERT is deduped via a claim in the
 * existing fraud_signal_claims ledger (V70) so the bell fans out exactly once
 * per wallet per window cluster-wide. All fraud-tracking DB ops run in an
 * isolated REQUIRES_NEW transaction so a tracking failure can never break the
 * Stripe webhook path. The per-JVM maps survive as a fast-path cache.
 *
 * These tests pin four behaviours, driving handlePaymentIntentFailed directly
 * (the payment_intent.payment_failed handler) with a mocked PaymentIntent:
 *
 *   1. The cross-pod count reaching CARD_TEST_THRESHOLD fires the alert
 *      exactly ONCE (per-wallet-per-window dedupe).
 *   2. Failures whose timestamps fall OUTSIDE the trailing window don't
 *      count — the windowed-count query's cutoff excludes them, so a wallet
 *      under the threshold within-window never alerts.
 *   3. A cross-pod DUPLICATE alert is suppressed — when the cluster claim
 *      reports the wallet's window already alerted (existsBySignature true,
 *      or the INSERT loses the UNIQUE race), no second fan-out happens.
 *   4. A DB error anywhere in fraud-tracking does NOT throw — the webhook
 *      path is never broken by a fraud-tracking failure.
 */
class StripeServiceCardTestingCrossPodSpec extends Specification {

    WalletRepository               walletRepository               = Mock()
    TransactionRepository          transactionRepository          = Mock()
    WalletPaymentFailureRepository walletPaymentFailureRepository = Mock()
    FraudSignalClaimRepository     fraudSignalClaimRepository     = Mock()
    SteamUserRepository            steamUserRepository            = Mock()
    NotificationService            notificationService            = Mock()

    @Subject
    StripeService service = new StripeService(
        walletRepository:               walletRepository,
        transactionRepository:          transactionRepository,
        walletPaymentFailureRepository: walletPaymentFailureRepository,
        fraudSignalClaimRepository:     fraudSignalClaimRepository,
        steamUserRepository:            steamUserRepository,
        notificationService:            notificationService,
        // transactionManager intentionally LEFT NULL → runIsolated runs the
        // tracking work inline (the context-less unit posture).
        secretKey:                      'sk_test_replace_me',
        publishableKey:                 'pk_test_replace_me',
        webhookSecret:                  'whsec_replace_me',
        successUrl:                     'http://localhost/ok',
        cancelUrl:                      'http://localhost/cancel',
        currency:                       'usd'
    )

    /** Build a mocked PaymentIntent tagged with the given walletId. */
    private PaymentIntent piFor(Long walletId) {
        Mock(PaymentIntent) {
            getId() >> "pi_${walletId}_${System.nanoTime()}"
            getMetadata() >> [walletId: walletId.toString()]
            getLastPaymentError() >> null
        }
    }

    def "cross-pod count reaching the threshold fires CARD_TESTING_DETECTED exactly once"() {
        given: 'two admins to fan out to'
        def admins = [new SteamUser(id: 1L, role: 'ADMIN'), new SteamUser(id: 2L, role: 'ADMIN')]
        steamUserRepository.findByRole('ADMIN') >> admins

        and: 'the DB count climbs 1 → 2 → 3 across the three deliveries (source of truth)'
        // Each failure INSERTs a row; the windowed count is the authoritative
        // cross-pod aggregate. Returns 1, then 2, then 3 in sequence.
        walletPaymentFailureRepository.countByWalletIdAndFailedAtAfter(42L, _) >>> [1L, 2L, 3L]

        and: 'the cluster alert claim is unclaimed → this pod wins the fan-out'
        fraudSignalClaimRepository.existsBySignature(_) >> false

        when: 'three declines land for the same wallet'
        service.handlePaymentIntentFailed(piFor(42L))
        service.handlePaymentIntentFailed(piFor(42L))
        service.handlePaymentIntentFailed(piFor(42L))

        then: 'a WalletPaymentFailure row is inserted on each of the three failures'
        3 * walletPaymentFailureRepository.save({ WalletPaymentFailure f -> f.walletId == 42L && f.failedAt != null })

        and: 'the cluster claim is taken exactly once (only when the count first hits the threshold)'
        1 * fraudSignalClaimRepository.save({ FraudSignalClaim c -> c.signature.startsWith('card_test_alert:42:') })

        and: 'the alert fans out to BOTH admins exactly once total (one push each, not per-pod-spam)'
        1 * notificationService.safePush(1L, 'CARD_TESTING_DETECTED', _, _, 42L, '/admin?tab=users')
        1 * notificationService.safePush(2L, 'CARD_TESTING_DETECTED', _, _, 42L, '/admin?tab=users')
    }

    def "failures outside the window don't count — wallet stays under threshold, no alert"() {
        given: 'admins exist'
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 1L, role: 'ADMIN')]

        and: 'the windowed count never reaches the threshold because old rows fall before the cutoff'
        // Three declines arrive, but two of them are older than the 1h window,
        // so countByWalletIdAndFailedAtAfter (cutoff = now - 1h) only ever
        // sees the in-window rows: 1, then 1, then 2 — never >= 3.
        walletPaymentFailureRepository.countByWalletIdAndFailedAtAfter(77L, _) >>> [1L, 1L, 2L]

        when: 'three declines land but the windowed count stays sub-threshold'
        service.handlePaymentIntentFailed(piFor(77L))
        service.handlePaymentIntentFailed(piFor(77L))
        service.handlePaymentIntentFailed(piFor(77L))

        then: 'rows are still recorded each time'
        3 * walletPaymentFailureRepository.save(_)

        and: 'but the windowed count never crosses the threshold → NO claim, NO alert'
        0 * fraudSignalClaimRepository.existsBySignature(_)
        0 * fraudSignalClaimRepository.save(_)
        0 * notificationService.safePush(*_)
    }

    def "the windowed-count query is asked for the trailing-window cutoff (old failures excluded by construction)"() {
        given:
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 1L, role: 'ADMIN')]
        long before = System.currentTimeMillis()
        Long capturedCutoff = null
        walletPaymentFailureRepository.countByWalletIdAndFailedAtAfter(88L, _) >> { args ->
            capturedCutoff = args[1] as Long
            1L
        }

        when:
        service.handlePaymentIntentFailed(piFor(88L))
        long after = System.currentTimeMillis()

        then: 'the count is scoped to (now - 1h), i.e. a trailing 1h window — anything older is excluded'
        capturedCutoff != null
        // cutoff ≈ now - 3_600_000ms; bracket it against the call window.
        capturedCutoff >= before - (60L * 60L * 1000L) - 5_000L
        capturedCutoff <= after  - (60L * 60L * 1000L) + 5_000L
    }

    def "cross-pod duplicate alert is suppressed when a sibling pod already claimed the window (existence check)"() {
        given: 'admins exist and the DB count is already over the threshold'
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 1L, role: 'ADMIN')]
        walletPaymentFailureRepository.countByWalletIdAndFailedAtAfter(99L, _) >> 5L

        and: 'a SIBLING pod already alerted this wallet-window → the claim row exists'
        fraudSignalClaimRepository.existsBySignature(_) >> true

        when: 'a decline lands here too (cross-pod retry / parallel delivery)'
        service.handlePaymentIntentFailed(piFor(99L))

        then: 'the row is recorded (count is shared) but we do NOT re-claim and do NOT re-fan the bell'
        1 * walletPaymentFailureRepository.save(_)
        0 * fraudSignalClaimRepository.save(_)
        0 * notificationService.safePush(*_)
    }

    def "cross-pod duplicate alert is suppressed when the claim INSERT loses the UNIQUE race (DataIntegrityViolation)"() {
        given: 'count over threshold, existence check passes here AND on a sibling pod'
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 1L, role: 'ADMIN')]
        walletPaymentFailureRepository.countByWalletIdAndFailedAtAfter(101L, _) >> 4L
        fraudSignalClaimRepository.existsBySignature(_) >> false

        and: 'our claim INSERT then loses the race to the UNIQUE index'
        fraudSignalClaimRepository.save(_) >> { throw new DataIntegrityViolationException('idx_fraud_signal_claims_signature') }

        when:
        service.handlePaymentIntentFailed(piFor(101L))

        then: 'the race resolves to duplicate-skip — no fan-out, and the violation does NOT propagate'
        0 * notificationService.safePush(*_)
        noExceptionThrown()
    }

    def "a DB error while INSERTing the failure row does NOT throw (webhook path never breaks)"() {
        given:
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 1L, role: 'ADMIN')]
        walletPaymentFailureRepository.save(_) >> { throw new RuntimeException('connection reset') }

        when: 'a single decline lands while the DB is unhappy'
        service.handlePaymentIntentFailed(piFor(55L))

        then: 'the fraud-tracking failure is swallowed — no exception bubbles to the webhook controller'
        noExceptionThrown()
    }

    def "a DB error while COUNTING degrades to the fast-path cache and does NOT throw"() {
        given:
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 1L, role: 'ADMIN')]
        walletPaymentFailureRepository.countByWalletIdAndFailedAtAfter(56L, _) >> { throw new RuntimeException('query timeout') }

        when:
        service.handlePaymentIntentFailed(piFor(56L))

        then: 'the count failure is swallowed; the in-memory cache (size 1) keeps it sub-threshold → no alert, no throw'
        noExceptionThrown()
        0 * notificationService.safePush(*_)
    }

    def "with no DB repo wired the detector degrades to the per-JVM cache (single-process behaviour, no NPE)"() {
        given: 'a context-less build with the optional fraud repos unwired'
        service.walletPaymentFailureRepository = null
        service.fraudSignalClaimRepository = null
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 1L, role: 'ADMIN')]

        when: 'three declines land for the same wallet — the in-memory cache alone reaches the threshold'
        service.handlePaymentIntentFailed(piFor(33L))
        service.handlePaymentIntentFailed(piFor(33L))
        service.handlePaymentIntentFailed(piFor(33L))

        then: 'no DB calls, no NPE, and the single-process path still fans the alert once on the 3rd failure'
        0 * walletPaymentFailureRepository.save(_)
        1 * notificationService.safePush(1L, 'CARD_TESTING_DETECTED', _, _, 33L, '/admin?tab=users')
        noExceptionThrown()
    }
}
