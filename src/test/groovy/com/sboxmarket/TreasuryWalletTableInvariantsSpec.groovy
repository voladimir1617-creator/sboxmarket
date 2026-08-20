package com.sboxmarket

import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PlatformLedgerService
import org.springframework.data.jpa.repository.Query
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * The treasury shares a table with user wallets. Two things about that table
 * were written for user wallets only, and both break when the platform's own
 * account moves in.
 *
 * <h3>1. The non-negative CHECK constraint</h3>
 *
 * V254 added {@code CHECK (balance >= 0)} on {@code wallets} — correct and
 * load-bearing for user wallets, because a negative balance defeats every
 * {@code balance < price} guard in the money paths. But the treasury is
 * DESIGNED to go negative: deposits credit the user the gross amount while the
 * processor keeps 2.9% + $0.30, so the platform is underwater until trade
 * volume catches up (UNIT-ECONOMICS.md puts break-even at 1.6-4.3 trades per
 * deposited dollar).
 *
 * Left unfixed, the FIRST live deposit dies: the treasury is created at 0.00,
 * the first posting against it is a PROCESSING_COST debit, {@code 0.00 - 3.20}
 * violates the constraint, and the surrounding deposit transaction is marked
 * rollback-only — customer's card charged, wallet never credited. A constraint
 * written to protect user funds would have become the thing that ate them.
 *
 * <h3>2. The total-balances aggregate</h3>
 *
 * {@code sumAllBalances()} feeds the admin dashboard's {@code totalEscrow} —
 * "how much user money are we holding", the platform's LIABILITY. The
 * treasury's balance is the opposite of a liability, and it can be negative, so
 * including it corrupts the figure in whichever direction its sign happens to
 * point. That is the number an operator reaches for to answer "can we cover a
 * withdrawal run".
 *
 * <h3>Why these are asserted against source text</h3>
 *
 * The constraint is Postgres/Flyway and prod-only — dev and CI run H2 with
 * {@code ddl-auto: update} and Flyway disabled (application.yml), so NO
 * behavioural test in this suite can ever execute it. Asserting the shipped SQL
 * is the only available teeth. The same reasoning the existing
 * {@code SeedNeverRunsInProdSpec} uses for the seeder's profile gate.
 */
class TreasuryWalletTableInvariantsSpec extends Specification {

    private static final File MIGRATIONS = new File('src/main/resources/db/migration')

    private static String migration(String name) {
        def f = new File(MIGRATIONS, name)
        f.exists() ? f.text : null
    }

    // ── The CHECK constraint ────────────────────────────────────────

    def "the original non-negative CHECK still exists (guard the guard)"() {
        given: "if V254 were renamed or deleted, every assertion below is vacuous"
        def sql = migration('V254__wallets_balance_nonneg_check.sql')

        expect:
        sql != null
        sql.contains('chk_wallets_balance_nonneg')
        sql.contains('balance >= 0')
    }

    def "a later migration exempts the treasury from that CHECK"() {
        given:
        def sql = migration('V256__wallets_balance_check_exempt_treasury.sql')

        expect: "without this the first live deposit rolls back on a constraint violation"
        sql != null

        and: "the old constraint is dropped first — you cannot redefine a CHECK in place"
        sql.contains('DROP CONSTRAINT IF EXISTS chk_wallets_balance_nonneg')

        and: "and re-added with the treasury carved out"
        sql.contains('ADD CONSTRAINT chk_wallets_balance_nonneg')
        sql.contains('balance >= 0 OR username =')
    }

    def "the exemption names the exact reserved username the service uses"() {
        given: "a typo here silently reverts the fix — the clause would never match"
        def sql = migration('V256__wallets_balance_check_exempt_treasury.sql')

        expect:
        sql.contains("'${PlatformLedgerService.TREASURY_USERNAME}'")
    }

    def "the exemption cannot let a USER wallet go negative"() {
        given: "user wallets are steam_<id> or demo; neither can equal the reserved name"
        def sql = migration('V256__wallets_balance_check_exempt_treasury.sql')

        expect: "the carve-out is an equality on a name no wallet-creation path produces"
        sql.contains("username = '${PlatformLedgerService.TREASURY_USERNAME}'")

        and: "not a LIKE, a prefix, or anything else a user wallet could satisfy"
        !(sql =~ /username\s+LIKE/)
    }

    // ── The liability aggregate ─────────────────────────────────────

    private static String sumAllBalancesQuery() {
        Method m = WalletRepository.methods.find { it.name == 'sumAllBalances' }
        m?.getAnnotation(Query)?.value()
    }

    def "sumAllBalances still exists and is @Query-driven (guard the guard)"() {
        expect:
        sumAllBalancesQuery() != null
    }

    def "sumAllBalances excludes the platform treasury from the user-liability total"() {
        given:
        def jpql = sumAllBalancesQuery()

        expect: "the platform's own margin is not money it owes its users"
        jpql.contains(PlatformLedgerService.TREASURY_USERNAME)
        jpql.contains('username <>')
    }

    def "the literal in the JPQL matches the constant the service posts against"() {
        given: "HQL cannot safely reference the static field, so the two must be kept in step"
        def jpql = sumAllBalancesQuery()

        expect:
        jpql.contains("'${PlatformLedgerService.TREASURY_USERNAME}'")
    }
}
