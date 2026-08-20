package com.sboxmarket.service

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

import java.math.RoundingMode

/**
 * The platform's own ledger account — the missing counterparty.
 *
 * <h3>The hole this closes</h3>
 *
 * Every fee this business charges was, until now, credited to NOBODY.
 * {@code TradeService.release()} paid the seller {@code price - feeAmount}
 * and the fee simply evaporated: no platform wallet, no treasury account,
 * no {@code FEE} transaction type. Same for the Trade Protection fee —
 * {@code TradeProtectionService.purchase()} debited the buyer and posted
 * nothing on the other side. Platform revenue existed only as a residual
 * in the Stripe balance, reconstructable after the fact by summing
 * {@code Trade.feeAmount}.
 *
 * That is not an accounting nicety. Deposits credit the GROSS amount while
 * Stripe takes 2.9% + $0.30 on the way in, so the platform is paying real
 * money per deposit and earning it back only through trade volume. Whether
 * that nets out is arithmetic — but the code could not perform the
 * arithmetic, because only one side of it was ever written down. A business
 * that cannot see its own margin cannot tell when it is losing.
 *
 * <h3>The design</h3>
 *
 * One reserved {@link Wallet} row (username {@link #TREASURY_USERNAME}) is
 * the platform's account. Every revenue event CREDITS it, every cost event
 * DEBITS it, and each posting writes a {@link Transaction} row against it
 * using the same shape as every user-facing ledger row. So:
 *
 * <pre>treasury.balance == lifetime revenue - lifetime cost == margin</pre>
 *
 * and the {@code transactions} table carries the itemised breakdown, keyed
 * by {@code type} and joinable to the listing that produced it.
 *
 * <h3>Why a reserved username and not a config id</h3>
 *
 * Real wallets are keyed {@code steam_<steamId64>} (SteamAuthService,
 * WalletController, ListingController, CartController) or {@code demo}
 * (SeedService). {@link #TREASURY_USERNAME} is not reachable by any of
 * those constructions, so no user session can ever resolve to the treasury
 * — there is no code path that turns a logged-in user into this wallet.
 * A configured numeric id, by contrast, drifts between environments and
 * silently aims at a real user's wallet when it is wrong.
 *
 * <h3>Transaction-boundary contract</h3>
 *
 * The distinction matters and is deliberate:
 *
 * <ul>
 *   <li><b>Fee postings participate in the caller's transaction.</b> The
 *       platform fee is money WE are moving, in the same commit as the
 *       seller credit. If the fee posting fails, the release must fail and
 *       be retried — a release that pays the seller but loses the fee row
 *       is exactly the silent-margin-leak this class exists to end.</li>
 *   <li><b>Cost postings are best-effort.</b> A Stripe processing fee is a
 *       record of an EXTERNAL fact that already happened at Stripe. Failing
 *       the user's deposit because we could not write our own cost row
 *       would be strictly worse than a missing row — the user paid, and
 *       they must be credited. Those call sites wrap in try/catch and log.</li>
 * </ul>
 *
 * Treasury lookup/creation runs in its own REQUIRES_NEW transaction so a
 * first-ever-posting insert race cannot mark the caller's transaction
 * rollback-only and kill a real trade release. Mirrors the
 * {@code runInIsolatedTx} pattern in TradeService / BidService.
 */
@Service
@Slf4j
class PlatformLedgerService {

    /**
     * Reserved wallet username for the platform's own account. Deliberately
     * shaped so it cannot collide with a user wallet: user wallets are
     * {@code steam_<steamId64>} and the seed wallet is {@code demo}.
     */
    static final String TREASURY_USERNAME = '__platform_treasury__'

    // ── Ledger types ────────────────────────────────────────────────
    // Revenue (credit treasury):
    /** The per-trade platform commission taken from the seller on release. */
    static final String TYPE_FEE               = 'FEE'
    /** The Trade Protection premium the buyer pays at opt-in. */
    static final String TYPE_PROTECTION_FEE    = 'PROTECTION_FEE'
    /**
     * Recovery of a Trade Protection claim that staff later reversed —
     * credited back to the treasury because the platform got the money back.
     * A separate type rather than a negative {@link #TYPE_PROTECTION_PAYOUT}
     * so every row's `amount` stays positive and the direction stays readable
     * from the type alone, the way DEPOSIT and WITHDRAW already work.
     */
    static final String TYPE_PROTECTION_REVERSAL = 'PROTECTION_REVERSAL'
    // Cost (debit treasury):
    /** Payment-processor cut on a deposit — money the platform never receives. */
    static final String TYPE_PROCESSING_COST   = 'PROCESSING_COST'
    /** A Trade Protection claim paid out to a buyer — the cover being consumed. */
    static final String TYPE_PROTECTION_PAYOUT = 'PROTECTION_PAYOUT'

    /** Types that CREDIT the treasury. */
    static final List<String> REVENUE_TYPES =
        [TYPE_FEE, TYPE_PROTECTION_FEE, TYPE_PROTECTION_REVERSAL].asImmutable()
    /** Types that DEBIT the treasury. */
    static final List<String> COST_TYPES = [TYPE_PROCESSING_COST, TYPE_PROTECTION_PAYOUT].asImmutable()

    /**
     * Payment-processor percentage rate applied to a deposit, as a percent
     * (2.9 == 2.9%). Stripe US standard card pricing is 2.9% + $0.30.
     * Configurable because negotiated rates and non-card methods differ —
     * an operator on a different rate MUST set this or the margin figure
     * is wrong in the optimistic direction.
     */
    @Value('${platform.processing-fee-percent:2.9}') BigDecimal processingFeePercent

    /** Fixed per-transaction processor charge in dollars. Stripe US: $0.30. */
    @Value('${platform.processing-fee-fixed:0.30}') BigDecimal processingFeeFixed

    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired(required = false) PlatformTransactionManager transactionManager

    // ── Treasury account ────────────────────────────────────────────

    /**
     * The platform's wallet, created on first use.
     *
     * Runs the INSERT in its own REQUIRES_NEW transaction: two concurrent
     * first-ever fee postings both miss the find and race the
     * {@code wallets(username)} unique index. Without the isolation the
     * loser's DataIntegrityViolationException marks the CALLER's
     * transaction rollback-only, which would fail a real trade release over
     * a bookkeeping row. With it, the loser's insert rolls back alone and
     * we simply re-read the winner's committed row — the same
     * catch-and-re-find shape WalletController.currentWallet uses for the
     * identical race on a user's first wallet access.
     */
    Wallet treasury() {
        def existing = walletRepository.findByUsername(TREASURY_USERNAME)
        if (existing != null) return existing
        Wallet created = null
        runInIsolatedTx {
            def again = walletRepository.findByUsername(TREASURY_USERNAME)
            if (again != null) { created = again; return }
            try {
                created = walletRepository.save(new Wallet(
                    username: TREASURY_USERNAME,
                    balance : BigDecimal.ZERO,
                    currency: 'USD'
                ))
                log.info('Created platform treasury wallet (username={})', TREASURY_USERNAME)
            } catch (DataIntegrityViolationException race) {
                created = walletRepository.findByUsername(TREASURY_USERNAME)
            }
        }
        // A Spock mock with no stub for findByUsername returns null from the
        // re-read; fall through to one last direct lookup so a real context
        // that raced still gets the winner's row.
        created ?: walletRepository.findByUsername(TREASURY_USERNAME)
    }

    // ── Revenue ─────────────────────────────────────────────────────

    /**
     * Book the per-trade platform commission as revenue.
     *
     * Participates in the CALLER's transaction — see the class doc. Returns
     * null (and moves no money) for a null / non-positive fee, which is the
     * legitimate case for a $0 trade or a fee-waived release; a zero-amount
     * ledger row would be noise, not information.
     */
    Transaction postTradeFee(BigDecimal amount, Long tradeId, Long listingId, String itemName) {
        credit(TYPE_FEE, amount, 'trade',
            "Platform fee on trade #${tradeId}${itemName ? " (${itemName})" : ''}".toString(), listingId)
    }

    /** Book a Trade Protection premium as revenue. */
    Transaction postProtectionFee(BigDecimal amount, Long tradeId, Long listingId, String itemName) {
        credit(TYPE_PROTECTION_FEE, amount, 'trade_protection',
            "Trade Protection premium on trade #${tradeId}${itemName ? " (${itemName})" : ''}".toString(), listingId)
    }

    // ── Cost ────────────────────────────────────────────────────────

    /**
     * Book the payment-processor cut on a completed deposit as a cost.
     *
     * The platform credits the user the GROSS deposit while the processor
     * keeps a slice of it, so this posting is the ONLY record that the
     * money-in leg costs anything at all. Without it the treasury balance
     * reads as pure profit and the break-even question in UNIT-ECONOMICS.md
     * cannot be answered from the data.
     *
     * This is an ESTIMATE from the configured rate, not the processor's
     * reported figure — see {@link #estimateProcessingCost}. Best-effort by
     * contract: callers must not let a failure here roll back a real credit.
     */
    Transaction postDepositProcessingCost(BigDecimal depositAmount, String reference) {
        def cost = estimateProcessingCost(depositAmount)
        if (cost == null) return null
        def description = "Estimated processing cost on \$${depositAmount?.toPlainString()} deposit " +
            "(${processingFeePercent}% + \$${processingFeeFixed})".toString()
        Transaction result = null
        // DEFERRED TO AFTER COMMIT — a plain try/catch at the call site cannot
        // make this best-effort, and believing otherwise is how a bookkeeping
        // row eats a real deposit. A repository save that violates a DB
        // constraint throws from inside Spring's @Transactional proxy, which
        // calls setRollbackOnly() BEFORE the exception surfaces to any catch
        // block up the stack. The caller's catch would then log "wallet
        // credited correctly" while the commit throws UnexpectedRollbackException
        // and rolls the whole deposit back — the user's card charged, their
        // wallet empty. The same trap is documented at PurchaseService:243.
        //
        // Running after the caller commits also removes the over-count risk of a
        // bare REQUIRES_NEW: no cost is booked for a deposit that never landed.
        // Mirrors PurchaseService.deferOrRun exactly.
        deferOrRun {
            result = debit(TYPE_PROCESSING_COST, cost, reference ?: 'stripe', description, null)
        }
        // Non-null only on the inline path (no active transaction — unit tests
        // and non-transactional callers). Under a real deposit transaction the
        // row is written after commit, so there is nothing to hand back yet.
        result
    }

    /** Book a Trade Protection claim paid to a buyer as a cost. */
    Transaction postProtectionPayout(BigDecimal amount, Long tradeId, Long listingId) {
        debit(TYPE_PROTECTION_PAYOUT, amount, 'trade_protection',
            "Trade Protection claim paid on trade #${tradeId}".toString(), listingId)
    }

    /**
     * Un-book a protection payout that staff reversed — credits the treasury
     * with the amount ACTUALLY recovered, which is not always the full cover:
     * the clawback is clamped at the buyer's available balance, so a buyer who
     * already spent the payout leaves a shortfall the platform really did eat.
     * Passing the recovered figure (not {@code coverageAmount}) keeps the
     * margin honest about that shortfall instead of quietly erasing it.
     */
    Transaction postProtectionReversal(BigDecimal recovered, Long tradeId, Long listingId) {
        credit(TYPE_PROTECTION_REVERSAL, recovered, 'trade_protection_reversal',
            "Trade Protection claim reversed on trade #${tradeId}".toString(), listingId)
    }

    /**
     * Processor cost for a deposit of {@code amount}, rounded to cents.
     *
     * Returns null for a null / non-positive deposit so no row is written.
     * The percentage leg is computed on the gross and the fixed leg added
     * whole — matching how processors actually charge — then rounded ONCE at
     * the end, so a batch of deposits sums to the same figure the processor
     * invoices rather than accumulating a rounding drift per row.
     */
    BigDecimal estimateProcessingCost(BigDecimal amount) {
        if (amount == null || amount <= BigDecimal.ZERO) return null
        def pct   = (processingFeePercent ?: BigDecimal.ZERO) as BigDecimal
        def fixed = (processingFeeFixed ?: BigDecimal.ZERO) as BigDecimal
        def cost = (amount * pct / new BigDecimal('100')) + fixed
        cost = cost.setScale(2, RoundingMode.HALF_UP)
        cost > BigDecimal.ZERO ? cost : null
    }

    // ── Reporting ───────────────────────────────────────────────────

    /**
     * Current platform margin — the treasury balance, which by construction
     * is lifetime revenue minus lifetime cost. Zero (not null) when no
     * posting has ever been made, so callers can render it unconditionally.
     */
    BigDecimal margin() {
        def w = walletRepository.findByUsername(TREASURY_USERNAME)
        ((w?.balance ?: BigDecimal.ZERO) as BigDecimal).setScale(2, RoundingMode.HALF_UP)
    }

    // ── Internals ───────────────────────────────────────────────────

    private Transaction credit(String type, BigDecimal amount, String reference,
                               String description, Long listingId) {
        post(type, amount, reference, description, listingId, true)
    }

    private Transaction debit(String type, BigDecimal amount, String reference,
                              String description, Long listingId) {
        post(type, amount, reference, description, listingId, false)
    }

    /**
     * Write one treasury posting.
     *
     * `amount` on the Transaction row is always stored POSITIVE — the
     * direction lives in `type` ({@link #REVENUE_TYPES} vs
     * {@link #COST_TYPES}), matching how DEPOSIT and WITHDRAW already
     * coexist as positive amounts on a user wallet. Only the treasury
     * BALANCE moves signed.
     */
    private Transaction post(String type, BigDecimal amount, String reference,
                             String description, Long listingId, boolean isCredit) {
        if (amount == null) return null
        def scaled = (amount as BigDecimal).setScale(2, RoundingMode.HALF_UP)
        if (scaled <= BigDecimal.ZERO) return null
        def wallet = treasury()
        if (wallet == null) {
            // Never silently drop revenue. A missing treasury on a REVENUE
            // posting is a wiring failure worth failing loudly for; the cost
            // callers already wrap in try/catch, so they degrade to a log.
            throw new IllegalStateException(
                "Platform treasury wallet unavailable — refusing to drop a ${type} posting of \$${scaled}")
        }
        wallet.balance = (wallet.balance ?: BigDecimal.ZERO) + (isCredit ? scaled : -scaled)
        walletRepository.save(wallet)
        def tx = transactionRepository.save(new Transaction(
            walletId:        wallet.id,
            type:            type,
            status:          'COMPLETED',
            amount:          scaled,
            currency:        wallet.currency ?: 'USD',
            stripeReference: reference,
            description:     description,
            listingId:       listingId
        ))
        log.info('Platform ledger {} {} \${} — {}', type, isCredit ? 'credit' : 'debit', scaled, description)
        tx
    }

    /** REQUIRES_NEW, falling back to inline when no transaction manager is
     *  wired (unit-test contexts built without a Spring context). */
    private void runInIsolatedTx(Closure work) {
        if (transactionManager == null) { work(); return }
        def tt = new TransactionTemplate(transactionManager)
        tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        tt.executeWithoutResult { work() }
    }

    /**
     * Run after the caller's transaction commits, in a transaction of its own —
     * or inline when there is no active transaction. Exceptions are swallowed
     * and logged on BOTH paths: this is the only shape in which "best-effort"
     * is actually true for a repository write. Copied deliberately from
     * PurchaseService.deferOrRun rather than reinvented, so the two
     * best-effort-write sites behave identically.
     */
    private void deferOrRun(Closure work) {
        if (transactionManager != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override void afterCommit() {
                    try {
                        runInIsolatedTx(work)
                    } catch (Exception e) {
                        log.error("Deferred platform-ledger posting failed — margin understated by this row: ${e.message}", e)
                    }
                }
            })
        } else {
            try {
                work()
            } catch (Exception e) {
                log.error("Platform-ledger posting failed (no active tx) — margin understated by this row: ${e.message}", e)
            }
        }
    }
}
