package com.sboxmarket

import com.sboxmarket.model.Listing
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification
import spock.lang.Subject

/**
 * Wave 113 — regression pin for the {@code returnListingToSeller}
 * transaction-poisoning bug.
 *
 * Background. TradeService.cancel / autoCancelStaleSellerTrade /
 * autoCancelBannedSellerTrade are {@code @Transactional}. They run
 * {@code refundBuyer} (wallet credit) + {@code returnListingToSeller}
 * (listing flip back to seller inventory + {@code totalSold} counter
 * decrement) + {@code transitionTo CANCELLED} inside the SAME caller tx.
 *
 * THE BUG (pre-wave-113). The counter decrement was wrapped in a
 * swallowing try/catch:
 * <pre>
 *   try { itemRepository.decrementTotalSold(itemId) }
 *   catch (Exception e) { log.warn(...) }
 * </pre>
 * but {@code decrementTotalSold} is {@code @Modifying @Query "UPDATE
 * Item i SET ..."}. Spring's transactional proxy ran the UPDATE inside
 * the SHARED cancel tx, so any execution-time failure marked the cancel
 * tx rollback-only on the way out. The outer try/catch then absorbed
 * the throw locally and the method returned normally — but on commit
 * Spring threw {@link org.springframework.transaction.UnexpectedRollbackException}
 * and the refundBuyer wallet credit + returnListingToSeller listing
 * flip + CANCELLED transition + TRADE_CANCELLED notifications were ALL
 * rolled back, while the HTTP response said 200. Catastrophic for a
 * money-path call: the buyer "received" their refund per the API, then
 * never actually got it.
 *
 * THE FIX. Route the counter decrement through {@code deferOrRun},
 * which registers a {@code TransactionSynchronization.afterCommit} on
 * the active sync, and runs the UPDATE in a fresh REQUIRES_NEW tx
 * AFTER the cancel has durably committed. Mirrors the helper of the
 * same name in NotificationService / AuditService / PriceHistoryService
 * (waves 23 / 80 / 100).
 *
 * This spec pins two behaviours:
 *   1. With sync active (production-like path), the decrement is
 *      registered as an afterCommit synchronization rather than fired
 *      inline. The pre-fix code called the proxy directly, joining the
 *      caller's tx — so registering a sync proves the deferral is now
 *      in place.
 *   2. With NO sync active (Spock unit-test path / non-transactional
 *      caller), the decrement runs inline AND a throw is swallowed —
 *      so the existing unit-test cancel specs (TradeServiceSpec) keep
 *      working, and the counter bump still tries on every cancel.
 */
class TradeServiceDeferredDecrementSpec extends Specification {

    TradeRepository       tradeRepository       = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    ListingRepository     listingRepository     = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    AdminAuthorization    adminAuthorization    = Mock()
    ItemRepository        itemRepository        = Mock()
    SteamUserRepository   steamUserRepository   = Mock() {
        findAllById(_) >> []
    }
    TextSanitizer textSanitizer = Mock() {
        medium(_) >> { String s -> s ?: '' }
    }
    EmailService emailService = Mock() {
        canSendTo(_, _) >> false
    }
    AuditService auditService = Mock()

    private Trade tradeIn(String state, Map args = [:]) {
        new Trade(
            id:             args.id ?: 1L,
            listingId:      args.listingId ?: 100L,
            itemId:         args.itemId ?: 77L,
            itemName:       args.itemName ?: 'Wizard Hat',
            buyerUserId:    args.buyer ?: 10L,
            buyerWalletId:  args.buyerWallet ?: 500L,
            sellerUserId:   args.seller ?: 20L,
            sellerWalletId: args.sellerWallet ?: 600L,
            price:          args.price ?: new BigDecimal("50.00"),
            feeAmount:      args.fee ?: new BigDecimal("1.00"),
            state:          state
        )
    }

    /** Stand up a cancel-ready trade fixture. The listing carries a
     *  non-null item so {@code returnListingToSeller}'s decrement path
     *  is reached (the null-item guard would short-circuit otherwise). */
    private Trade primeCancelFixture() {
        def t = tradeIn('PENDING_SELLER_SEND')
        def buyerWallet = new Wallet(id: 500L, balance: new BigDecimal("0.00"), currency: 'USD')
        def item = new com.sboxmarket.model.Item(id: 77L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, status: 'SOLD', buyerUserId: 10L,
                                  sellerUserId: 20L, item: item)
        tradeRepository.findById(1L) >> Optional.of(t)
        tradeRepository.save(_) >> { Trade x -> x }
        walletRepository.findById(500L) >> Optional.of(buyerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { Listing l -> l }
        t
    }

    // ── structural pins ──────────────────────────────────────────────

    def "TradeService has the deferOrRun afterCommit helper"() {
        expect:
        // The deferral helper is the mechanism that moves the best-effort
        // counter bump out of the caller's transaction. Pin its presence
        // so a future refactor can't silently drop it. Mirrors the same
        // structural pin on NotificationService / AuditService /
        // PriceHistoryService.
        TradeService.getDeclaredMethods().any { it.name == 'deferOrRun' }
    }

    // ── deferral path (sync active) ──────────────────────────────────

    def "decrementTotalSold is DEFERRED to afterCommit when a tx sync is active (production path)"() {
        given:
        // Mock manager — its presence + an active sync trigger the
        // deferOrRun branch that REGISTERS a TransactionSynchronization
        // instead of firing the decrement inline. We don't actually open
        // a transaction; we just bind a sync to the current thread.
        def txManager = Mock(PlatformTransactionManager)
        def localSvc = new TradeService(
            tradeRepository       : tradeRepository,
            walletRepository      : walletRepository,
            transactionRepository : transactionRepository,
            listingRepository     : listingRepository,
            notificationService   : notificationService,
            banGuard              : banGuard,
            adminAuthorization    : adminAuthorization,
            steamUserRepository   : steamUserRepository,
            textSanitizer         : textSanitizer,
            emailService          : emailService,
            auditService          : auditService,
            itemRepository        : itemRepository,
            transactionManager    : txManager,
            autoReleaseDays       : 8L,
            sellerResponseDays    : 3L
        )
        primeCancelFixture()

        and: "an active synchronization context (the production cancel tx)"
        TransactionSynchronizationManager.initSynchronization()

        when:
        localSvc.cancel(10L, 1L, 'changed my mind')

        then: "the cancel completed cleanly"
        noExceptionThrown()

        and: "decrementTotalSold was NOT invoked inline — it is now deferred to afterCommit"
        0 * itemRepository.decrementTotalSold(_)

        and: "exactly one TransactionSynchronization was registered (the deferred decrement)"
        def syncs = TransactionSynchronizationManager.getSynchronizations()
        syncs.size() == 1

        cleanup:
        TransactionSynchronizationManager.clearSynchronization()
    }

    def "the registered afterCommit synchronization invokes decrementTotalSold inside a fresh REQUIRES_NEW tx"() {
        given:
        // The deferred work runs in a fresh REQUIRES_NEW tx (load-bearing
        // — inside afterCommit the parent tx is already committed with
        // "no commit following", so a plain REQUIRED save would join a
        // spent tx and never persist). Wire a Mock manager that records
        // the propagation behaviour the deferred helper requests.
        def txStatus  = Mock(TransactionStatus)
        TransactionDefinition capturedDef = null
        def txManager = Mock(PlatformTransactionManager) {
            getTransaction(_) >> { TransactionDefinition def_ ->
                capturedDef = def_
                return txStatus
            }
        }
        def localSvc = new TradeService(
            tradeRepository       : tradeRepository,
            walletRepository      : walletRepository,
            transactionRepository : transactionRepository,
            listingRepository     : listingRepository,
            notificationService   : notificationService,
            banGuard              : banGuard,
            adminAuthorization    : adminAuthorization,
            steamUserRepository   : steamUserRepository,
            textSanitizer         : textSanitizer,
            emailService          : emailService,
            auditService          : auditService,
            itemRepository        : itemRepository,
            transactionManager    : txManager,
            autoReleaseDays       : 8L,
            sellerResponseDays    : 3L
        )
        primeCancelFixture()
        TransactionSynchronizationManager.initSynchronization()

        when: "the cancel runs and registers the deferred decrement"
        localSvc.cancel(10L, 1L, 'changed my mind')

        and: "the parent commits — fire afterCommit on the registered sync"
        def syncs = TransactionSynchronizationManager.getSynchronizations()
        syncs*.afterCommit()

        then: "the deferred work opened exactly one REQUIRES_NEW tx for the counter bump"
        1 * txManager.commit(txStatus)
        capturedDef != null
        capturedDef.propagationBehavior == TransactionDefinition.PROPAGATION_REQUIRES_NEW

        and: "and the counter bump itself was issued from inside that fresh tx"
        1 * itemRepository.decrementTotalSold(77L)

        cleanup:
        TransactionSynchronizationManager.clearSynchronization()
    }

    def "a failing deferred decrement does NOT propagate out of afterCommit (no parent poisoning)"() {
        given:
        // Pre-fix shape: itemRepository.decrementTotalSold threw inside
        // the caller's tx, the @Modifying proxy marked the SHARED tx
        // rollback-only, and the cancel's commit then threw
        // UnexpectedRollbackException — every cancel-side write rolled
        // back while the API said 200. Post-fix the throw happens
        // post-commit in a fresh tx; we still SWALLOW it so a
        // catalogue-side hiccup is just a stale counter (cosmetic) and
        // never bubbles to the operator log as an error.
        def txStatus  = Mock(TransactionStatus)
        def txManager = Mock(PlatformTransactionManager) {
            getTransaction(_) >> txStatus
            // Simulate the deferred REQUIRES_NEW tx failing its commit —
            // mirrors the failure mode (lock-wait timeout, constraint,
            // dialect quirk) the pre-fix code would have poisoned the
            // parent with.
            commit(_) >> { throw new RuntimeException("simulated commit failure on the deferred decrement tx") }
        }
        def localSvc = new TradeService(
            tradeRepository       : tradeRepository,
            walletRepository      : walletRepository,
            transactionRepository : transactionRepository,
            listingRepository     : listingRepository,
            notificationService   : notificationService,
            banGuard              : banGuard,
            adminAuthorization    : adminAuthorization,
            steamUserRepository   : steamUserRepository,
            textSanitizer         : textSanitizer,
            emailService          : emailService,
            auditService          : auditService,
            itemRepository        : itemRepository,
            transactionManager    : txManager,
            autoReleaseDays       : 8L,
            sellerResponseDays    : 3L
        )
        def t = primeCancelFixture()
        TransactionSynchronizationManager.initSynchronization()

        when: "the cancel runs and registers the deferred decrement"
        localSvc.cancel(10L, 1L, 'changed my mind')

        then: "the cancel itself completed cleanly — refund credited, trade is CANCELLED"
        noExceptionThrown()
        t.state == 'CANCELLED'

        when: "the parent commits — fire afterCommit on the registered sync"
        def syncs = TransactionSynchronizationManager.getSynchronizations()
        syncs*.afterCommit()

        then: "the deferred commit failure is SWALLOWED — does NOT propagate out of afterCommit"
        noExceptionThrown()

        cleanup:
        TransactionSynchronizationManager.clearSynchronization()
    }

    // ── inline-execute fallback (no sync active) ─────────────────────

    def "decrementTotalSold runs INLINE when no PlatformTransactionManager is wired (unit-test fallback)"() {
        given:
        // No manager, no active sync — the deferOrRun helper falls back
        // to inline execution. This is the path every Spock unit test in
        // TradeServiceSpec takes (service built via the property-map
        // constructor with no Spring context), so the existing
        // decrementTotalSold cancel spec keeps passing. Without this
        // fallback every cancel test becomes a no-op.
        def localSvc = new TradeService(
            tradeRepository       : tradeRepository,
            walletRepository      : walletRepository,
            transactionRepository : transactionRepository,
            listingRepository     : listingRepository,
            notificationService   : notificationService,
            banGuard              : banGuard,
            adminAuthorization    : adminAuthorization,
            steamUserRepository   : steamUserRepository,
            textSanitizer         : textSanitizer,
            emailService          : emailService,
            auditService          : auditService,
            itemRepository        : itemRepository,
            // NO transactionManager wired (unit-test path).
            autoReleaseDays       : 8L,
            sellerResponseDays    : 3L
        )
        primeCancelFixture()

        when:
        localSvc.cancel(10L, 1L, 'changed my mind')

        then: "the counter bump still happens (cosmetic, but not silently dropped on the unit-test path)"
        1 * itemRepository.decrementTotalSold(77L)
        and:
        noExceptionThrown()
    }

    def "an inline decrement throw is SWALLOWED (mirrors the original best-effort contract)"() {
        given:
        def localSvc = new TradeService(
            tradeRepository       : tradeRepository,
            walletRepository      : walletRepository,
            transactionRepository : transactionRepository,
            listingRepository     : listingRepository,
            notificationService   : notificationService,
            banGuard              : banGuard,
            adminAuthorization    : adminAuthorization,
            steamUserRepository   : steamUserRepository,
            textSanitizer         : textSanitizer,
            emailService          : emailService,
            auditService          : auditService,
            itemRepository        : itemRepository,
            autoReleaseDays       : 8L,
            sellerResponseDays    : 3L
        )
        def t = primeCancelFixture()
        // Inline path failure — same shape as the existing "cancel still
        // completes when the totalSold decrement throws" pin in
        // TradeServiceSpec. Mirrors the original try/catch contract: a
        // best-effort counter hiccup must never abort the cancel.
        itemRepository.decrementTotalSold(_) >> { throw new RuntimeException("counter update failed") }

        when:
        localSvc.cancel(10L, 1L, 'changed my mind')

        then: "the cancel completes — buyer refunded, trade CANCELLED, throw swallowed"
        noExceptionThrown()
        t.state == 'CANCELLED'
    }
}
