package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.TradeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.aop.support.AopUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

/**
 * Wave 113 — regression proof for the {@code returnListingToSeller}
 * transaction-poisoning bug.
 *
 * Background: TradeService.{cancel, autoCancelStaleSellerTrade,
 * autoCancelBannedSellerTrade} are all {@code @Transactional}. They run
 * {@code refundBuyer} (wallet credit), {@code returnListingToSeller}
 * (listing flip back to seller inventory + a {@code totalSold} counter
 * decrement), and {@code transitionTo} (CANCELLED) inside the SAME
 * caller transaction.
 *
 * THE BUG (pre-wave-113): the counter decrement was wrapped in a
 * swallowing try/catch:
 * <pre>
 *   try { itemRepository.decrementTotalSold(itemId) }
 *   catch (Exception e) { log.warn(...) }
 * </pre>
 * but {@code decrementTotalSold} is {@code @Modifying @Query "UPDATE
 * Item i SET ..."} — Spring's transactional proxy executed the UPDATE
 * inside the SHARED cancel tx, so any execution-time failure (lock-wait
 * timeout, dialect/driver hiccup, constraint, optimistic lock) marked the
 * cancel tx rollback-only on the way out of the proxy. The outer
 * try/catch absorbed the exception locally and the method returned
 * normally — but on commit Spring threw {@link UnexpectedRollbackException}
 * and the refundBuyer wallet credit, the returnListingToSeller listing
 * flip, the CANCELLED transition, and the TRADE_CANCELLED notifications
 * were ALL rolled back, while the HTTP response said 200. Catastrophic
 * for a money-path call: the buyer "received" their refund per the API,
 * then never actually got it.
 *
 * THE FIX: route the counter decrement through {@code deferOrRun}, which
 * registers a {@code TransactionSynchronization.afterCommit} that runs
 * the UPDATE in a fresh REQUIRES_NEW tx AFTER the cancel has durably
 * committed. A failing decrement can no longer poison the parent — the
 * worst case is a stale {@code Item.totalSold} counter (cosmetic, never
 * load-bearing for the cancel's correctness).
 *
 * This spec drives a REAL transaction (TransactionTemplate over the
 * production PlatformTransactionManager + H2). The "real write" is an
 * Item rename. The "failing best-effort write" is forced by invoking
 * {@code deferOrRun} via reflection with a Closure that throws — the
 * same code path {@code returnListingToSeller} now uses.
 *
 * Each "the fix" case is paired with a CONTROL case that throws DIRECTLY
 * inside the same transaction (the pre-fix REQUIRED-join behaviour). The
 * control proves the bug is real: the parent transaction IS poisoned and
 * the commit DOES throw. The fix case proves the deferral immunises the
 * parent.
 */
@SpringBootTest
@ActiveProfiles("test")
class TradeServiceDeferredDecrementIsolationIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx

    TradeService        tradeService
    ItemRepository      itemRepository
    ListingRepository   listingRepository
    TransactionTemplate txTemplate

    def setup() {
        tradeService      = ctx.getBean(TradeService)
        itemRepository    = ctx.getBean(ItemRepository)
        listingRepository = ctx.getBean(ListingRepository)
        txTemplate        = new TransactionTemplate(ctx.getBean(PlatformTransactionManager))
    }

    /** Seed a fresh catalogue item — our stand-in for the "real" parent
     *  write the cancel path mutates (refundBuyer wallet credit /
     *  returnListingToSeller listing flip). Unique name so the spec
     *  coexists with siblings sharing the Spring context. */
    private Item seedItem() {
        def uniq = System.nanoTime()
        itemRepository.save(new Item(
            name:        "TradeIsoItem-${uniq}",
            category:    'Hats',
            rarity:      'Limited',
            iconEmoji:   '🎩',
            supply:      10,
            totalSold:   0,
            lowestPrice: new BigDecimal('10.00')
        ))
    }

    /** Unwrap the Spring-injected bean to the raw TradeService instance.
     *  Spring's CGLIB transactional proxy intercepts method calls but
     *  private fields (including {@code transactionManager}) live on
     *  the underlying target — reflecting on the proxy reads a NULL
     *  manager and the deferOrRun helper would silently fall through
     *  to the inline branch. {@link AopUtils} bridges both CGLIB and
     *  JDK dynamic proxies via the same call. */
    private TradeService unwrap(TradeService bean) {
        if (AopUtils.isAopProxy(bean) && bean instanceof org.springframework.aop.framework.Advised) {
            return (TradeService) ((org.springframework.aop.framework.Advised) bean).getTargetSource().getTarget()
        }
        return bean
    }

    /** Invoke TradeService's private {@code deferOrRun(Closure)} helper
     *  via reflection on the UNWRAPPED bean. Mirrors the exact code path
     *  {@link TradeService#returnListingToSeller} uses for the
     *  decrementTotalSold call. */
    private void invokeDeferOrRun(Closure work) {
        def target = unwrap(tradeService)
        def m = TradeService.getDeclaredMethod('deferOrRun', Closure)
        m.accessible = true
        m.invoke(target, work)
    }

    // ── structural pins ─────────────────────────────────────────────

    def "TradeService has the deferOrRun afterCommit helper"() {
        expect:
        // The deferral helper is the mechanism that moves the best-effort
        // counter bump out of the caller's transaction. Pin its presence
        // so a future refactor can't silently drop it.
        TradeService.getDeclaredMethods().any { it.name == 'deferOrRun' }
    }

    def "TradeService has the PlatformTransactionManager wired for the deferral path"() {
        given:
        def target = unwrap(tradeService)

        expect:
        // Without a wired manager the deferOrRun helper degrades to the
        // inline-execute branch (the unit-test fallback), so the
        // afterCommit deferral never engages. Pin the wiring so a future
        // refactor can't silently regress to the inline path in production.
        target.transactionManager != null
    }

    // ── deferOrRun: a failing deferred write does NOT poison the parent ─

    def "a failing deferred best-effort write does NOT roll back the parent transaction"() {
        given: "a committed item the parent transaction will rename (the 'real' refundBuyer / listing flip stand-in)"
        def item = seedItem()
        def renamed = "renamed-deferred-fix-${System.nanoTime()}"
        def afterCommitFired = new AtomicInteger(0)

        when: "inside ONE real transaction: rename the item, then schedule a doomed deferred write"
        txTemplate.executeWithoutResult { status ->
            // The "real parent write" — stand-in for the refundBuyer wallet
            // credit + listing.status='SOLD' flip + transitionTo CANCELLED
            // that returnListingToSeller's caller has already performed.
            item.name = renamed
            itemRepository.save(item)
            // The best-effort side-effect — exact same code path
            // returnListingToSeller now uses for decrementTotalSold. A
            // RuntimeException inside the deferred closure simulates the
            // class of failure the production try/catch was written to
            // swallow (lock-wait timeout, dialect quirk, transient driver
            // hiccup against itemRepository.decrementTotalSold).
            invokeDeferOrRun {
                afterCommitFired.incrementAndGet()
                throw new RuntimeException("simulated decrementTotalSold failure")
            }
        }

        then: "the commit succeeded — no UnexpectedRollbackException"
        noExceptionThrown()

        and: "the REAL write is durably persisted — no rollback blast radius"
        itemRepository.findById(item.id).get().name == renamed

        and: "the deferred work actually ran post-commit (proves afterCommit fired, not silently dropped)"
        afterCommitFired.get() == 1
    }

    def "CONTROL: a REQUIRED-join save failure DOES poison the parent transaction"() {
        given: "a committed item the parent transaction will rename"
        def item = seedItem()
        def renamed = "renamed-deferred-control-${System.nanoTime()}"
        // Reproduce the pre-fix shape: ItemRepository.decrementTotalSold is
        // @Modifying @Query — when called inside the cancel tx its UPDATE
        // joins the SHARED tx. Any execution-time failure inside the proxy
        // marks the tx rollback-only on the way out. We model that exact
        // observable state by using a NESTED participating tx that fails
        // — Spring's standard rollback-only propagation to the OUTER tx
        // is the same end-state the pre-fix decrementTotalSold reached.
        def innerTxTemplate = new TransactionTemplate(ctx.getBean(PlatformTransactionManager))
        innerTxTemplate.propagationBehavior =
            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED

        when: "inside ONE transaction: rename the item, then participate in a doomed nested tx and swallow"
        txTemplate.executeWithoutResult { status ->
            item.name = renamed
            itemRepository.save(item)
            // Production-bug shape: the inner participating tx fails
            // (Spring sees the rollback exception, marks the SHARED tx
            // rollback-only), the outer try/catch swallows the
            // RuntimeException locally and returns normally — exactly
            // what the buggy returnListingToSeller try/catch did.
            try {
                innerTxTemplate.executeWithoutResult { innerStatus ->
                    throw new RuntimeException("simulated decrementTotalSold failure")
                }
            } catch (Exception swallowed) {
                // Swallow + return-as-if-all-is-well, then watch the
                // outer commit blow up.
            }
        }

        then: "the parent transaction was marked rollback-only — commit throws"
        thrown(UnexpectedRollbackException)

        and: "and crucially the REAL write was rolled back too — the bug's blast radius"
        itemRepository.findById(item.id).get().name != renamed
    }

    // ── no-active-tx fallback (mirrors the existing helpers) ────────

    def "deferOrRun falls back to inline execution when there is no active transaction"() {
        given: "no surrounding transaction (we're outside any executeWithoutResult)"
        def fired = new AtomicInteger(0)

        when:
        // No-tx path is exercised by the unit-test layer (Spock specs
        // built via the property-map constructor with no Spring context).
        // The contract: the helper still runs the work inline so caller
        // semantics don't break, but swallows the throw so a best-effort
        // failure can never bubble up. Pinned here for the no-tx branch
        // even though this spec is Spring-context backed — we just don't
        // wrap the call in a TransactionTemplate.
        invokeDeferOrRun {
            fired.incrementAndGet()
            throw new RuntimeException("inline-path simulated failure")
        }

        then: "the inline closure ran"
        fired.get() == 1

        and: "and the swallow kept the throw from bubbling"
        noExceptionThrown()
    }
}
