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
    /**
     * The processor cut PASSED THROUGH to the user — the amount withheld from
     * a deposit credit or from a payout so the platform does not absorb it.
     *
     * This is NOT platform profit and must never be read as such. It is the
     * offsetting leg of {@link #TYPE_PROCESSING_COST}: the platform still pays
     * the processor (the debit), and the user reimburses it (this credit). The
     * pair nets to ~zero by design, and the residual is the honest signal — it
     * is the sub-cent rounding the platform gives away plus any gap between the
     * CONFIGURED rate and the processor's real one.
     *
     * Booking only the debit (what the code did before pass-through pricing)
     * would count the cost twice: once against the user's credit and again
     * against the platform's margin, making a break-even deposit read as a
     * $3.20 loss.
     */
    static final String TYPE_PROCESSING_RECOVERY = 'PROCESSING_RECOVERY'
    // Cost (debit treasury):
    /** Payment-processor cut on a deposit or payout — money the platform never receives. */
    static final String TYPE_PROCESSING_COST   = 'PROCESSING_COST'
    /** A Trade Protection claim paid out to a buyer — the cover being consumed. */
    static final String TYPE_PROTECTION_PAYOUT = 'PROTECTION_PAYOUT'

    /** Types that CREDIT the treasury. */
    static final List<String> REVENUE_TYPES =
        [TYPE_FEE, TYPE_PROTECTION_FEE, TYPE_PROTECTION_REVERSAL, TYPE_PROCESSING_RECOVERY].asImmutable()
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

    /**
     * Processor percentage rate on a PAYOUT (money out), as a percent.
     * Stripe Connect US: roughly 0.25% + $0.25 per payout. Separate from the
     * deposit rate because the two legs are priced differently and a single
     * knob would force the operator to be wrong on one of them.
     */
    @Value('${platform.payout-fee-percent:0.25}') BigDecimal payoutFeePercent

    /** Fixed per-payout processor charge in dollars. Stripe Connect US: $0.25. */
    @Value('${platform.payout-fee-fixed:0.25}') BigDecimal payoutFeeFixed

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
     * Book BOTH legs of a pass-through processor charge: the cost the platform
     * pays the processor, and the recovery the user reimbursed.
     *
     * <h4>Why two legs and not zero</h4>
     *
     * Under pass-through pricing the user is credited NET, so the processor's
     * cut never touches platform margin. The naive conclusions are both wrong:
     *
     * <ul>
     *   <li><b>Keep only the cost debit</b> (what the code did before the
     *       pricing decision) and the cost is counted TWICE — once in the
     *       user's reduced credit, again against margin. A $100 deposit that
     *       is economically break-even reads as a $3.20 loss, and
     *       UNIT-ECONOMICS.md's break-even table stays permanently wrong.</li>
     *   <li><b>Book nothing at all</b> and the arithmetic nets right but
     *       becomes unobservable: an operator on a negotiated rate who never
     *       set {@code platform.processing-fee-percent} would be under- or
     *       over-charging every user with nothing in the books to show it.</li>
     * </ul>
     *
     * Booking both keeps {@code treasury.balance} economically correct AND
     * leaves the residual visible. The residual is real money and is expected
     * to be slightly NEGATIVE: {@code feeCharged} is rounded DOWN in the user's
     * favour while {@code cost} is the honest HALF_UP estimate, so the platform
     * eats up to $0.01 per transaction by design.
     *
     * Best-effort by contract, and atomic: both rows are written inside ONE
     * deferred transaction, so the debit can never land without its recovery.
     * A half-posted pair would understate margin by the full processor cut —
     * exactly the failure this method exists to prevent.
     *
     * @param cost       what the processor takes (HALF_UP estimate, or the real figure if known)
     * @param feeCharged what was actually withheld from the user (FLOOR-rounded)
     */
    void postPassThroughProcessing(BigDecimal cost, BigDecimal feeCharged,
                                   String reference, String costDescription,
                                   String recoveryDescription) {
        if (cost == null && feeCharged == null) return
        deferOrRun {
            debit(TYPE_PROCESSING_COST, cost, reference ?: 'stripe', costDescription, null)
            credit(TYPE_PROCESSING_RECOVERY, feeCharged, reference ?: 'stripe', recoveryDescription, null)
        }
    }

    /**
     * Book the payment-processor cut on a completed deposit as a cost.
     *
     * Retained for the ABSORBED-cost case (the platform eats the processor's
     * cut and credits the user gross). Under the pass-through pricing decision
     * the deposit path calls {@link #postPassThroughProcessing} instead, which
     * adds the offsetting recovery leg — calling THIS method on a net-credited
     * deposit double-counts the cost.
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

    /**
     * Processor cost for a PAYOUT of {@code gross}, rounded to cents.
     *
     * The money-out twin of {@link #estimateProcessingCost}, priced off
     * {@code payout-fee-*} rather than {@code processing-fee-*}. Using the
     * deposit rate here would overstate the cost of every withdrawal by more
     * than 10x (2.9% + $0.30 against roughly 0.25% + $0.25) and drag the
     * measured margin negative on a leg that is very nearly free.
     *
     * HALF_UP, deliberately — this estimates what the processor ACTUALLY
     * takes. Only the figure charged to the USER rounds in the user's favour
     * (see {@link #payoutFeeCharged}).
     */
    BigDecimal estimatePayoutCost(BigDecimal gross) {
        if (gross == null || gross <= BigDecimal.ZERO) return null
        def pct   = (payoutFeePercent ?: BigDecimal.ZERO) as BigDecimal
        def fixed = (payoutFeeFixed ?: BigDecimal.ZERO) as BigDecimal
        def cost = (gross * pct / new BigDecimal('100')) + fixed
        cost = cost.setScale(2, RoundingMode.HALF_UP)
        cost > BigDecimal.ZERO ? cost : null
    }

    /**
     * Un-book a pass-through recovery the platform gave back — a reversed
     * payout where the user is re-credited the GROSS they were debited.
     *
     * The withdrawal booked a cost debit (real, already paid to the processor,
     * NOT refunded when a transfer reverses) and an offsetting recovery credit
     * (the user's reimbursement). Re-crediting the user gross hands that
     * reimbursement back, so the recovery credit is now fiction. Debiting the
     * same amount cancels it and leaves the original cost standing alone,
     * which is the true outcome: the platform ate that payout.
     *
     * Booked as {@link #TYPE_PROCESSING_COST} rather than a negative recovery
     * so {@code REVENUE_TYPES}/{@code COST_TYPES} keep their invariant that a
     * row's amount is always positive and its direction lives in its type.
     */
    void postPassThroughRecoveryReversal(BigDecimal recovered, String reference, String description) {
        if (recovered == null || recovered <= BigDecimal.ZERO) return
        deferOrRun {
            debit(TYPE_PROCESSING_COST, recovered, reference ?: 'stripe', description, null)
        }
    }

    // ── Pass-through pricing (what the USER is charged) ─────────────

    /**
     * The processor fee PASSED THROUGH to the user on a deposit of
     * {@code gross} — the amount withheld from their wallet credit.
     *
     * <h4>Rounding goes to the user, always</h4>
     *
     * Rounded DOWN ({@link RoundingMode#FLOOR}), never HALF_UP. On a $33.33
     * deposit the true 2.9% + $0.30 is $1.266557: HALF_UP charges the user
     * $1.27, FLOOR charges $1.26. The half-cent difference is trivial per
     * transaction and the DIRECTION is not — a fee that rounds toward the
     * house on every single deposit is the shape of a defect nobody notices
     * until a regulator or a forum thread does. FLOOR makes the platform eat
     * the sub-cent, so the user's credit is rounded UP in every case where
     * the arithmetic is not exact.
     *
     * {@link #estimateProcessingCost} deliberately keeps HALF_UP: that one
     * estimates what the processor ACTUALLY takes, and biasing it toward the
     * user would understate a real cost. The gap between the two is the
     * treasury residual, and it is supposed to be there.
     */
    BigDecimal depositFeeCharged(BigDecimal gross) {
        feeCharged(gross, processingFeePercent, processingFeeFixed)
    }

    /** What a {@code gross} deposit actually credits to the wallet. */
    BigDecimal depositNetCredit(BigDecimal gross) {
        netOf(gross, depositFeeCharged(gross))
    }

    /**
     * The processor fee passed through to the user on a payout of
     * {@code gross} — deducted from what they receive, not from the wallet
     * debit. Same FLOOR rounding, same reason.
     */
    BigDecimal payoutFeeCharged(BigDecimal gross) {
        feeCharged(gross, payoutFeePercent, payoutFeeFixed)
    }

    /** What a {@code gross} withdrawal actually pays out to the user. */
    BigDecimal payoutNet(BigDecimal gross) {
        netOf(gross, payoutFeeCharged(gross))
    }

    /**
     * True when the fee would swallow the whole transaction — a $0.30 deposit
     * at 2.9% + $0.30, or any amount under a misconfigured fixed leg.
     *
     * Callers MUST refuse rather than proceed. Without this the arithmetic
     * runs off the end quietly: a net credit of zero charges a card for
     * nothing, and a NEGATIVE net would (before the wallets CHECK constraint)
     * have driven a user's balance below zero from a deposit.
     */
    boolean feeExceedsAmount(BigDecimal gross, BigDecimal fee) {
        if (gross == null) return true
        def net = netOf(gross, fee)
        net == null || net <= BigDecimal.ZERO
    }

    private BigDecimal feeCharged(BigDecimal gross, BigDecimal pctCfg, BigDecimal fixedCfg) {
        if (gross == null || gross <= BigDecimal.ZERO) return BigDecimal.ZERO.setScale(2)
        def pct   = (pctCfg ?: BigDecimal.ZERO) as BigDecimal
        def fixed = (fixedCfg ?: BigDecimal.ZERO) as BigDecimal
        def fee = (gross * pct / new BigDecimal('100')) + fixed
        // FLOOR, not DOWN: identical for the positive amounts this handles,
        // but FLOOR stays correct if a negative ever reaches here.
        fee = fee.setScale(2, RoundingMode.FLOOR)
        fee < BigDecimal.ZERO ? BigDecimal.ZERO.setScale(2) : fee
    }

    private BigDecimal netOf(BigDecimal gross, BigDecimal fee) {
        if (gross == null) return null
        ((gross as BigDecimal).setScale(2, RoundingMode.HALF_UP)
            - ((fee ?: BigDecimal.ZERO) as BigDecimal).setScale(2, RoundingMode.HALF_UP))
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
