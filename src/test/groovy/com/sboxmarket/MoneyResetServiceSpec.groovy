package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.MoneyResetService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Drives {@link MoneyResetService} against real repositories and a real
 * database — the in-memory H2 the {@code test} profile configures
 * ({@code jdbc:h2:mem:sboxmarket-test}). <b>The operator's live H2 file is
 * never opened by this spec, or by anything in the suite.</b>
 *
 * The fixture reproduces the SHAPE of the operator's real database as measured
 * on 2026-09-01: nine wallets, one of them the ownerless seeded {@code demo}
 * holding $250.00 against zero transaction rows, and forty-four transactions of
 * which nine are {@code dev_}-referenced DEPOSITs totalling $30,575.00.
 */
@SpringBootTest
@ActiveProfiles("test")
class MoneyResetServiceSpec extends Specification {

    @Autowired ApplicationContext ctx

    MoneyResetService     service
    WalletRepository      wallets
    TransactionRepository txns
    AuditLogRepository    audit

    def setup() {
        service = ctx.getBean(MoneyResetService)
        wallets = ctx.getBean(WalletRepository)
        txns    = ctx.getBean(TransactionRepository)
        audit   = ctx.getBean(AuditLogRepository)

        // The `test` profile seeds a demo catalogue, so start from a known
        // empty money state rather than from whatever the seeder left.
        txns.deleteAll()
        wallets.deleteAll()
    }

    /** One wallet. */
    private Wallet w(String username, String balance) {
        wallets.save(new Wallet(username: username, balance: new BigDecimal(balance), currency: 'USD'))
    }

    /** One transaction. */
    private Transaction t(Long walletId, String type, String amount, String ref) {
        txns.save(new Transaction(
            walletId: walletId, type: type, status: 'COMPLETED',
            amount: new BigDecimal(amount), currency: 'USD', stripeReference: ref))
    }

    /**
     * <b>The operator's real money state, transcribed exactly</b> — all nine
     * wallets and all forty-four transaction rows, read out of the live H2 file
     * read-only on 2026-09-01 and reproduced here in the in-memory database.
     *
     * Transcribed in full rather than condensed on purpose: the property this
     * fixture has to carry is that <b>eight of the nine wallets reconcile to
     * their own history</b>, and that is a statement about every row summing
     * correctly. A representative subset cannot express it — an earlier version
     * of this fixture used one PURCHASE row instead of twenty-six and the
     * reconciliation case failed, correctly.
     */
    private void seedProductionShape() {
        def demo     = w('demo', '250.00')                       // id 1 — seeded, no owner, no rows
        def seller   = w('steam_76561199000000001', '30373.25')  // id 2
        def buyer2   = w('steam_76561199000000002', '23.92')     // id 3
        def buyer3   = w('steam_76561199000000003', '50.00')     // id 4
        def idle4    = w('steam_76561199000000004', '0.00')      // id 5
        def salesW   = w('steam_76561199000000005', '0.51')      // id 6
        def idle6    = w('steam_76561199000000006', '0.00')      // id 7
        def owner    = w('steam_76561199839805014', '100.00')    // id 33 — the real account
        def treasury = w('__platform_treasury__', '0.02')        // id 65

        // ── the nine fabricated deposits: $30,575.00, every one dev_-referenced
        t(seller.id, 'DEPOSIT', '50.00',    'dev_1780588946696')
        t(seller.id, 'DEPOSIT', '250.00',   'dev_1780818253792')
        t(owner.id,  'DEPOSIT', '100.00',   'dev_1782351713871')
        t(seller.id, 'DEPOSIT', '10000.00', 'dev_1782449659569')
        t(seller.id, 'DEPOSIT', '10000.00', 'dev_1782449659620')
        t(seller.id, 'DEPOSIT', '10000.00', 'dev_1782449659657')
        t(seller.id, 'DEPOSIT', '100.00',   'dev_1782449730772')
        t(buyer2.id, 'DEPOSIT', '25.00',    'dev_1788248144636')
        t(buyer3.id, 'DEPOSIT', '50.00',    'dev_1788249615583')

        // ── 26 PURCHASE rows: $277.10
        ['0.52', '0.54', '0.59', '1.42', '0.60', '10.50', '15.58', '230.00',
         '0.46', '0.52', '0.60', '0.60', '0.67', '0.68', '0.81', '0.94',
         '1.01', '1.03', '1.09', '1.16', '1.24', '1.31', '1.32', '1.93'
        ].each { t(seller.id, 'PURCHASE', it, 'wallet') }
        t(seller.id, 'PURCHASE', '0.90', 'auction')
        t(buyer2.id, 'PURCHASE', '1.08', 'wallet')

        // ── 6 REFUND rows: $248.21
        ['0.54', '0.59', '0.90', '0.60', '15.58', '230.00']
            .each { t(seller.id, 'REFUND', it, 'trade_cancel') }

        // ── the trade proceeds, denominated in the fabricated money above
        t(salesW.id,   'SALE', '0.51', 'trade')
        t(seller.id,   'SALE', '1.06', 'trade')
        t(treasury.id, 'FEE',  '0.02', 'trade')
    }

    // ── THE DRY RUN CHANGES NOTHING ─────────────────────────────────

    def "plan() is read-only — it writes no row and moves no balance"() {
        given:
        seedProductionShape()
        long txnBefore = txns.count()
        long walletBefore = wallets.count()
        long auditBefore = audit.count()
        def balancesBefore = wallets.findAll().collectEntries { [(it.id): it.balance] }

        when: 'the operator asks for the plan'
        def p = service.plan()
        String report = service.render(p)

        then: 'a report is produced'
        report.contains('DRY RUN')
        report.contains('nothing has been changed')

        and: 'and NOTHING moved — not a row, not a cent'
        txns.count() == txnBefore
        wallets.count() == walletBefore
        audit.count() == auditBefore
        wallets.findAll().every { it.balance == balancesBefore[it.id] }
    }

    // ── THE ARITHMETIC THE OPERATOR READS ───────────────────────────

    def "the plan totals the fabricated deposits exactly"() {
        given:
        seedProductionShape()

        when:
        def p = service.plan()

        then: 'nine dev_ deposits, $30,575.00 — the measured figure'
        p.devDepositCount == 9L
        p.devDepositTotal == new BigDecimal('30575.00')

        and: 'and ZERO deposits from any other source: no Stripe charge ever landed'
        p.nonDevDepositCount == 0L
    }

    def "the plan totals every wallet balance that would be zeroed"() {
        given:
        seedProductionShape()

        when:
        def p = service.plan()

        then: '250.00 + 30373.25 + 23.92 + 50.00 + 100.00 + 0.51 + 0.02'
        p.balanceTotal == new BigDecimal('30797.70')
    }

    def "the plan breaks the removal down per transaction type"() {
        given:
        seedProductionShape()

        when:
        def p = service.plan()

        then:
        p.txnByType['DEPOSIT'].count == 9L
        p.txnByType['DEPOSIT'].total == new BigDecimal('30575.00')
        p.txnByType['SALE'].count == 2L
        p.txnByType['SALE'].total == new BigDecimal('1.57')
        p.txnByType['FEE'].count == 1L
        p.txnByType['FEE'].total == new BigDecimal('0.02')

        and: 'and the total is every row, because every row is downstream of a dev_ deposit'
        p.txnTotalCount == txns.count()
    }

    def "the ownerless seeded wallet is named as NOT explained by any transaction"() {
        given: 'the demo wallet holds $250.00 that SeedService hardcoded'
        seedProductionShape()

        when:
        def p = service.plan()
        def demo = p.wallets.find { it.username == 'demo' }

        then: 'it is flagged, because it is the one balance no ledger explains'
        demo.balance == new BigDecimal('250.00')
        demo.txnCount == 0L
        !demo.reconciles

        and: 'and the report says so in words, naming the source'
        service.render(p).contains('SeedService hardcodes this')
    }

    def "every other wallet reconciles to its own history — the ledger is consistent, just fictional"() {
        given:
        seedProductionShape()

        when:
        def p = service.plan()

        then: 'exactly one wallet fails to reconcile, and it is the seeded one'
        p.wallets.findAll { !it.reconciles }*.username == ['demo']
    }

    // ── THE DESTRUCTIVE RUN ─────────────────────────────────────────

    def "execute() deletes every transaction and zeroes every balance"() {
        given:
        seedProductionShape()
        def p = service.plan()

        when:
        service.execute(p)

        then: 'the fabricated ledger is gone'
        txns.count() == 0L

        and: 'every balance is zero'
        wallets.findAll().every { it.balance.compareTo(BigDecimal.ZERO) == 0 }
    }

    def "execute() KEEPS the wallet rows — deleting them would let SeedService re-mint the \$250"() {
        given: '''SeedService.seed() re-creates the demo wallet with a 250.00
                  balance whenever walletRepository.count() == 0. A reset that
                  deleted wallet rows would re-fabricate the exact fiction it
                  just removed, on the next boot.'''
        seedProductionShape()
        long before = wallets.count()
        def p = service.plan()

        when:
        service.execute(p)

        then: 'every wallet row survives, so count() never returns to 0'
        wallets.count() == before
        wallets.count() > 0
    }

    // ── THE ACTION LEAVES A TRACE ───────────────────────────────────

    def "execute() writes exactly one MONEY_RESET audit row, carrying the totals"() {
        given:
        seedProductionShape()
        long auditBefore = audit.count()
        def p = service.plan()

        when:
        def entry = service.execute(p)

        then: 'one row, of the right type'
        audit.count() == auditBefore + 1
        entry.eventType == AuditService.MONEY_RESET

        and: 'with a NULL actor — nobody signed in did this, an operator env var did'
        entry.actorUserId == null

        and: 'and the summary carries the numbers, so the row alone tells the story'
        entry.summary.contains('30575.00')
        entry.summary.contains('30797.70')
        entry.summary.contains('44') || entry.summary.contains(String.valueOf(p.txnTotalCount))
    }

    def "the audit row survives the reset — audit history is preserved, not cleared"() {
        given: 'some pre-existing audit history'
        seedProductionShape()
        audit.save(new com.sboxmarket.model.AuditLog(
            eventType: AuditService.USER_SIGN_IN, summary: 'pre-existing history'))
        long before = audit.count()
        def p = service.plan()

        when:
        service.execute(p)

        then: 'nothing was deleted from the audit log — only added to'
        audit.count() == before + 1
        audit.findAll().any { it.summary == 'pre-existing history' }
    }

    // ── THE REPORT IS THE DELIVERABLE ───────────────────────────────

    def "the report states what is KEPT, not only what is removed"() {
        given:
        seedProductionShape()

        when:
        String report = service.render(service.plan())

        then: 'the preserved things are named explicitly'
        report.contains('WOULD KEEP')
        report.contains('STEAM_USERS')
        report.contains('listings')
        report.contains('trades')

        and: 'including the honest caveat about fabricated revenue that survives'
        report.contains('TRADES.FEE_AMOUNT is KEPT')
    }

    def "the report tells the operator exactly how to execute, and that he has not"() {
        given:
        seedProductionShape()

        when:
        def p = service.plan()
        p.executeRequested = false
        String report = service.render(p)

        then:
        report.contains('NOT requested')
        report.contains('SBOX_MONEY_RESET_ENABLED=true SBOX_MONEY_RESET_EXECUTE=true')
    }

    def "a non-dev deposit is called out LOUDLY — that would be real money"() {
        given: 'a deposit that did NOT come from the fabrication path'
        seedProductionShape()
        def anyWallet = wallets.findAll().first()
        t(anyWallet.id, 'DEPOSIT', '19.99', 'cs_live_realstripesession')

        when:
        def p = service.plan()
        String report = service.render(p)

        then: 'the count is non-zero and the report shouts about it'
        p.nonDevDepositCount == 1L
        report.contains('NON-ZERO')
        report.contains('READ THESE BEFORE EXECUTING')
    }
}
