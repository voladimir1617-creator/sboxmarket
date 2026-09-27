package com.sboxmarket.service

import com.sboxmarket.config.MoneyMode
import com.sboxmarket.config.MoneyResetGate
import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.math.BigDecimal

/**
 * <b>Removes the fabricated money state from this database, so real Stripe keys
 * can go in against a ledger that is empty rather than one that is wrong.</b>
 *
 * <h3>What "fabricated" means here, measured rather than assumed</h3>
 *
 * A read-only forensic pass over the operator's live H2 file on 2026-09-01
 * established that <b>every cent in it is invented</b>:
 *
 * <ul>
 *   <li><b>9 DEPOSIT rows, $30,575.00, every one carrying a {@code dev_*}
 *       reference.</b> That prefix is written by exactly one code path —
 *       {@code StripeService.devModeDeposit} — which credits a wallet against no
 *       payment of any kind. It is now gated by {@link com.sboxmarket.config.DevCreditGate}.</li>
 *   <li><b>Zero deposits with any other reference.</b> Not one live charge, not
 *       one test-mode charge. No Stripe webhook has ever been processed
 *       ({@code PROCESSED_STRIPE_EVENTS} is empty), and there are no WITHDRAW
 *       rows at all, so nothing ever left either.</li>
 *   <li><b>8 of the 9 wallets reconcile exactly to their own transaction
 *       history.</b> The ledger is internally consistent — it is simply
 *       denominated in money that does not exist.</li>
 * </ul>
 *
 * <h3>Why EVERY transaction row goes, not only the deposits</h3>
 *
 * This is the one judgement call in the tool, so it is argued rather than
 * assumed.
 *
 * The PURCHASE / SALE / REFUND / FEE rows are not themselves fabrications — they
 * are the honest record of test trades, and they reconcile. But every one of
 * them is <b>denominated in the fabricated deposits</b>: the $230.00 PURCHASE
 * was paid with money from a {@code dev_} row, and the $1.06 SALE credit and the
 * $0.02 treasury FEE are its proceeds. With exactly zero real deposits in the
 * table, there is no subset of these rows that describes real money.
 *
 * Deleting the deposits while keeping the spending would leave every wallet's
 * history summing NEGATIVE against a zeroed balance — a ledger that reconciles
 * to nothing, which is a <i>new</i> fiction invented by the cleanup. The point
 * of this tool is to stop asserting things that are not true, so it does not get
 * to author a fresh untrue thing on the way out. All 44 rows go, and the dry run
 * prints the per-type breakdown so the operator can see precisely what that
 * costs before he agrees to it.
 *
 * <h3>Why balances are ZEROED and wallet rows are KEPT</h3>
 *
 * Two independent reasons, and the second is the one that would bite:
 *
 * <ol>
 *   <li>A wallet is keyed {@code username = "steam_<steamId64>"} and is the
 *       account's money-side identity. Deleting the row orphans the user and
 *       breaks their wallet page; the brief is explicitly to preserve
 *       accounts.</li>
 *   <li><b>{@code SeedService.seed()} re-creates the {@code demo} wallet with
 *       {@code balance = 250.00} whenever {@code walletRepository.count() == 0}.</b>
 *       That guard is an idempotency probe, not a gate. So a reset that DELETED
 *       wallet rows would leave a database that re-fabricates $250.00 on its
 *       next boot — the exact fiction this tool exists to remove, restored
 *       automatically, by a code path nobody would think to look at. Keeping all
 *       nine rows (zeroed) holds {@code count()} at 9 forever, so the seeder
 *       stays a permanent no-op and the money cannot come back.</li>
 * </ol>
 *
 * <h3>What is deliberately NOT touched</h3>
 *
 * <ul>
 *   <li><b>{@code STEAM_USERS}, {@code LISTINGS}, {@code ITEMS}, {@code TRADES},
 *       {@code OFFERS}, {@code BIDS}, {@code PRICE_HISTORY}, {@code LOADOUTS}</b>
 *       — the operator's test data, and the narrative of what happened. A trade
 *       row says "this item moved between these two accounts at this price"; it
 *       is a record of an EVENT, not a claim on platform funds, so it survives a
 *       money reset the way a receipt survives emptying a till.</li>
 *   <li><b>{@code AUDIT_LOG}</b> — append-only by contract, and the thing an
 *       operator would reconstruct this cleanup from. Destroying audit history
 *       during a privileged action is the defect {@code a4d6d25} just fixed one
 *       level up; doing it FROM a privileged action would be worse.</li>
 *   <li><b>Prices generally.</b> The distinction the tool draws is between a
 *       BALANCE (a claim on money the platform holds, which becomes a real
 *       liability the moment real keys go in) and a PRICE (a statement about
 *       what something is worth). Only balances are reset. {@code LISTINGS.PRICE}
 *       and {@code ITEMS.STEAM_PRICE} are catalogue data and stay.</li>
 * </ul>
 *
 * One consequence is called out rather than silently accepted: {@code TRADES}
 * retains {@code FEE_AMOUNT}, which feeds the admin dashboard's
 * {@code sumFeesSince()} "revenue" figures. Those numbers are fabricated-revenue
 * and they will survive this reset. They are reported by the dry run under
 * KEPT — deleting trade history to clean up a dashboard stat would take real
 * test data to fix a cosmetic number, which is the wrong trade.
 *
 * @see MoneyResetGate
 * @see com.sboxmarket.config.DevCreditGate
 */
@Service
@Slf4j
class MoneyResetService {

    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired AuditLogRepository auditLogRepository

    /** Audit event type for the reset itself. Registered as a constant on
     *  {@link AuditService} too, so the admin audit tab's filter list stays the
     *  one authority on what event types exist. */
    static final String EVENT_TYPE = AuditService.MONEY_RESET

    /** Reference prefix that identifies an in-process fabricated deposit. Written
     *  by {@code StripeService.devModeDeposit} and by nothing else. */
    static final String DEV_REFERENCE_PREFIX = 'dev_'

    /** The immutable outcome of a {@link #plan()} — what WOULD change, what
     *  would not, and the totals. Rendered by {@link #render}. */
    static class Plan {
        MoneyMode mode
        boolean executeRequested
        List<Map> wallets = []          // id, username, balance, ledger, txnCount, reconciles
        Map<String, Map> txnByType = [:] // type -> [count: n, total: BigDecimal]
        long txnTotalCount = 0
        BigDecimal txnTotalAmount = BigDecimal.ZERO
        BigDecimal balanceTotal = BigDecimal.ZERO
        long devDepositCount = 0
        BigDecimal devDepositTotal = BigDecimal.ZERO
        long nonDevDepositCount = 0
        Map<String, Long> kept = [:]     // table -> row count
    }

    /**
     * Read-only. Builds the report and changes nothing.
     *
     * Separated from {@link #execute} so the dry run cannot possibly write:
     * it is not {@code @Transactional} for writing, it holds no repository
     * mutation call, and the {@code CommandLineRunner} calls this one FIRST and
     * unconditionally — so the operator sees the plan on every invocation,
     * including the destructive one.
     */
    @Transactional(readOnly = true)
    Plan plan() {
        Plan p = new Plan()
        List<Transaction> txns = transactionRepository.findAll()
        List<Wallet> wallets = walletRepository.findAll()

        txns.each { t ->
            String type = t.type ?: 'UNKNOWN'
            Map bucket = p.txnByType.get(type)
            if (bucket == null) { bucket = [count: 0L, total: BigDecimal.ZERO]; p.txnByType.put(type, bucket) }
            bucket.count = ((long) bucket.count) + 1L
            bucket.total = ((BigDecimal) bucket.total).add(t.amount ?: BigDecimal.ZERO)
            p.txnTotalCount += 1L
            p.txnTotalAmount = p.txnTotalAmount.add(t.amount ?: BigDecimal.ZERO)
            if (t.type == 'DEPOSIT') {
                if (t.stripeReference?.startsWith(DEV_REFERENCE_PREFIX)) {
                    p.devDepositCount += 1L
                    p.devDepositTotal = p.devDepositTotal.add(t.amount ?: BigDecimal.ZERO)
                } else {
                    p.nonDevDepositCount += 1L
                }
            }
        }

        wallets.sort { a, b -> (a.id ?: 0L) <=> (b.id ?: 0L) }
        wallets.each { w ->
            def own = txns.findAll { it.walletId == w.id && it.status == 'COMPLETED' }
            BigDecimal ledger = BigDecimal.ZERO
            own.each { t ->
                BigDecimal amt = t.amount ?: BigDecimal.ZERO
                if (t.type in ['DEPOSIT', 'SALE', 'REFUND', 'FEE', 'ADJUSTMENT_CREDIT']) ledger = ledger.add(amt)
                else if (t.type in ['PURCHASE', 'WITHDRAW']) ledger = ledger.subtract(amt)
            }
            BigDecimal bal = w.balance ?: BigDecimal.ZERO
            p.wallets << [
                id        : w.id,
                username  : w.username,
                balance   : bal,
                ledger    : ledger,
                txnCount  : (long) own.size(),
                reconciles: bal.compareTo(ledger) == 0
            ]
            p.balanceTotal = p.balanceTotal.add(bal)
        }

        // Row counts for the things we are NOT touching, so "preserved" is a
        // measured claim in the report rather than a promise in a comment.
        p.kept = keptCounts()
        return p
    }

    /**
     * Counts of the tables this tool leaves alone. Best-effort per table: a
     * missing/renamed table must degrade to "unknown" in the report rather than
     * abort a read-only dry run.
     */
    protected Map<String, Long> keptCounts() {
        Map<String, Long> out = [:]
        out.put('audit_log', safeCount { auditLogRepository.count() })
        out.put('wallets (rows, zeroed not deleted)', safeCount { walletRepository.count() })
        return out
    }

    private long safeCount(Closure<Long> c) {
        try { return c.call() ?: 0L } catch (Exception e) { return -1L }
    }

    /**
     * <b>Destructive.</b> Deletes every transaction row and zeroes every wallet
     * balance, and writes the audit row for having done so.
     *
     * <h3>The audit row is written in the SAME transaction, on purpose</h3>
     *
     * {@link AuditService#log} defers its save until after the caller's
     * transaction commits, which is exactly right for a money-path operation
     * that must not be rolled back by a failing audit write. <b>It is the wrong
     * shape here</b>, and the difference matters: for a deposit, losing the
     * audit row costs a trail entry; for this, losing the audit row means the
     * entire ledger vanished with nothing anywhere saying who did it or when.
     *
     * So this method writes the {@link AuditLog} through the repository directly,
     * inside the one transaction that performs the deletion, and it writes it
     * BEFORE the deletes. Either both land or neither does. A reset that cannot
     * be recorded does not happen — which is the inverse of the defect
     * {@code a4d6d25} fixed, and the same principle.
     *
     * @return the audit row that was written
     */
    @Transactional
    AuditLog execute(Plan p) {
        String summary = "MONEY RESET: deleted ${p.txnTotalCount} transaction rows " +
            "(${p.devDepositCount} fabricated dev_ deposits totalling \$${p.devDepositTotal}) " +
            "and zeroed \$${p.balanceTotal} across ${p.wallets.size()} wallet balances. " +
            "Accounts, listings, items, trades and audit history preserved."

        // FIRST, and in this transaction. See the javadoc above.
        AuditLog entry = auditLogRepository.save(new AuditLog(
            actorUserId  : null,           // not a user action — an operator-run tool
            actorName    : null,
            subjectUserId: null,
            subjectName  : null,
            eventType    : EVENT_TYPE,
            resourceId   : null,
            summary      : summary.take(500),
            ipAddress    : null,
            userAgent    : null,
            createdAt    : System.currentTimeMillis()
        ))

        transactionRepository.deleteAll()

        walletRepository.findAll().each { w ->
            if ((w.balance ?: BigDecimal.ZERO).compareTo(BigDecimal.ZERO) != 0) {
                w.balance = BigDecimal.ZERO
                walletRepository.save(w)
            }
        }

        log.warn(summary)
        return entry
    }

    /**
     * Renders the plan exactly as the operator reads it. Kept as a pure
     * String-returning function (rather than printing) so a spec can assert on
     * the real text the operator sees, not on a reconstruction of it.
     */
    String render(Plan p) {
        StringBuilder b = new StringBuilder()
        String rule = '=' * 78
        b.append("\n").append(rule).append("\n")
        b.append(p.executeRequested
            ? '  MONEY RESET — EXECUTING (this run WILL delete)\n'
            : '  MONEY RESET — DRY RUN (nothing has been changed)\n')
        b.append(rule).append("\n")
        b.append("  deployment            : ${p.mode?.name() ?: 'UNCLASSIFIED'}\n")
        b.append("  invoked by            : ${MoneyResetGate.OPT_IN_ENV_VAR}=${MoneyResetGate.OPT_IN_VALUE}\n")
        b.append("  destructive execution : ${p.executeRequested ? 'REQUESTED (' + MoneyResetGate.EXECUTE_ENV_VAR + '=true)' : 'NOT requested — ' + MoneyResetGate.EXECUTE_ENV_VAR + ' is unset'}\n")

        b.append("\n-- WOULD REMOVE: wallet balances (rows kept, balance set to 0.00) ").append('-' * 11).append("\n")
        b.append(String.format('  %-4s %-26s %14s %14s %6s %10s%n',
            'id', 'username', 'balance', 'own ledger', 'txns', 'after'))
        p.wallets.each { w ->
            b.append(String.format('  %-4s %-26s %14s %14s %6s %10s%n',
                w.id, trim((String) w.username, 26),
                money((BigDecimal) w.balance), money((BigDecimal) w.ledger),
                w.txnCount,
                '0.00'))
        }
        b.append(String.format('  %-4s %-26s %14s%n', '', 'TOTAL REMOVED', money(p.balanceTotal)))

        // The one wallet whose balance is not explained by its own history is
        // called out by name — it is the seeded $250 and it is the reason the
        // rows are zeroed rather than deleted.
        def unreconciled = p.wallets.findAll { !it.reconciles }
        if (unreconciled) {
            b.append("\n  NOT explained by any transaction row (seeded balance, no owner):\n")
            unreconciled.each { w ->
                b.append("    wallet ${w.id} '${w.username}' holds ${money((BigDecimal) w.balance)} " +
                         "with ${w.txnCount} transaction rows — SeedService hardcodes this.\n")
            }
        }

        b.append("\n-- WOULD REMOVE: transaction rows, by type ").append('-' * 35).append("\n")
        b.append(String.format('  %-22s %8s %16s%n', 'type', 'rows', 'total amount'))
        p.txnByType.keySet().sort().each { String t ->
            Map v = p.txnByType.get(t)
            b.append(String.format('  %-22s %8s %16s%n', t, v.count, money((BigDecimal) v.total)))
        }
        b.append(String.format('  %-22s %8s %16s%n', 'TOTAL REMOVED', p.txnTotalCount, money(p.txnTotalAmount)))

        b.append("\n  of the DEPOSIT rows:\n")
        b.append("    ${p.devDepositCount} carry a '${DEV_REFERENCE_PREFIX}' reference (fabricated, no payment) totalling ${money(p.devDepositTotal)}\n")
        b.append("    ${p.nonDevDepositCount} carry any other reference (a real Stripe charge would appear here)\n")
        if (p.nonDevDepositCount > 0) {
            b.append("    ^^ NON-ZERO. Real payment rows may exist. READ THESE BEFORE EXECUTING.\n")
        }

        b.append("\n-- WOULD KEEP ").append('-' * 63).append("\n")
        b.append("  accounts + Steam identities (STEAM_USERS)   preserved, roles untouched\n")
        b.append("  listings, items, price history              preserved\n")
        b.append("  trades, offers, bids, buy orders            preserved\n")
        p.kept.each { k, v ->
            b.append(String.format('  %-43s %s rows%n', k, v < 0 ? 'unknown' : v.toString()))
        }
        b.append("  + 1 new ${EVENT_TYPE} audit row recording this reset\n")

        b.append("\n  NOTE: TRADES.FEE_AMOUNT is KEPT and still feeds the admin dashboard's\n")
        b.append("        fee/revenue figures. Those totals remain fabricated-revenue after\n")
        b.append("        this reset. Removing them would mean deleting real test trades.\n")

        b.append("\n").append(rule).append("\n")
        b.append(p.executeRequested
            ? "  EXECUTING NOW.\n"
            : "  Nothing was changed. To execute, re-run with BOTH:\n" +
              "      ${MoneyResetGate.OPT_IN_ENV_VAR}=true ${MoneyResetGate.EXECUTE_ENV_VAR}=true\n")
        b.append(rule).append("\n")
        return b.toString()
    }

    private static String money(BigDecimal v) {
        (v ?: BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    private static String trim(String s, int n) {
        s == null ? '' : (s.length() <= n ? s : s.substring(0, n - 1) + '~')
    }
}
