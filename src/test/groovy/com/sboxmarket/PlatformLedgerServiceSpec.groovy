package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PlatformLedgerService
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * The platform's fees must land in a real account.
 *
 * Before {@link PlatformLedgerService} existed, every fee this business
 * charged was credited to NOBODY: TradeService.release paid the seller
 * {@code price - feeAmount} and the fee simply evaporated; the Trade
 * Protection premium debited the buyer with no counterparty. Revenue was a
 * residual you could only reconstruct by summing Trade.feeAmount after the
 * fact, and the processing cost of taking the money in was not recorded
 * anywhere at all — so the platform could not tell whether it was above or
 * below the line.
 *
 * These pin the ledger's arithmetic and its direction. The direction is the
 * part worth pinning hardest: a cost posted as a credit would make a losing
 * business look profitable, which is precisely the blindness being removed.
 */
class PlatformLedgerServiceSpec extends Specification {

    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()

    /** The treasury row the service finds/creates, with a live balance. */
    Wallet treasury = new Wallet(id: 99L, username: PlatformLedgerService.TREASURY_USERNAME,
                                 balance: BigDecimal.ZERO, currency: 'USD')

    List<Transaction> written = []

    @Subject
    PlatformLedgerService service = new PlatformLedgerService(
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        processingFeePercent  : new BigDecimal('2.9'),
        processingFeeFixed    : new BigDecimal('0.30')
    )

    def setup() {
        walletRepository.findByUsername(PlatformLedgerService.TREASURY_USERNAME) >> treasury
        walletRepository.save(_) >> { Wallet w -> w }
        transactionRepository.save(_) >> { Transaction t -> written << t; t.id = written.size() as Long; t }
    }

    // ── Revenue credits the treasury ────────────────────────────────

    def "a trade fee CREDITS the treasury and writes a FEE row"() {
        when:
        def tx = service.postTradeFee(new BigDecimal('2.50'), 7L, 42L, 'AK-47 | Redline')

        then: "the platform's account actually goes up — this is the whole defect"
        treasury.balance == new BigDecimal('2.50')

        and: "and the row is itemised, typed, and joinable back to the listing"
        tx.type      == PlatformLedgerService.TYPE_FEE
        tx.status    == 'COMPLETED'
        tx.amount    == new BigDecimal('2.50')
        tx.walletId  == 99L
        tx.listingId == 42L
        tx.description.contains('trade #7')
    }

    def "a protection premium CREDITS the treasury under its own type"() {
        when:
        service.postProtectionFee(new BigDecimal('1.20'), 8L, 43L, 'Glock | Fade')

        then:
        treasury.balance == new BigDecimal('1.20')
        written.last().type == PlatformLedgerService.TYPE_PROTECTION_FEE
    }

    // ── Cost debits the treasury ────────────────────────────────────

    def "a processing cost DEBITS the treasury — a cost booked as revenue would invert the margin"() {
        when: "a 100.00 deposit at 2.9% + 0.30 fixed"
        service.postDepositProcessingCost(new BigDecimal('100.00'), 'cs_test_1')

        then: "the treasury goes NEGATIVE — the platform paid to receive that money"
        treasury.balance == new BigDecimal('-3.20')
        written.last().type == PlatformLedgerService.TYPE_PROCESSING_COST

        and: "the row's own amount stays positive; direction lives in the type"
        written.last().amount == new BigDecimal('3.20')
    }

    def "a protection payout DEBITS the treasury"() {
        when:
        service.postProtectionPayout(new BigDecimal('40.00'), 9L, 44L)

        then:
        treasury.balance == new BigDecimal('-40.00')
        written.last().type == PlatformLedgerService.TYPE_PROTECTION_PAYOUT
    }

    def "a reversed protection claim books only what was actually RECOVERED"() {
        given: "a 40.00 claim was paid, but the clawback only recovered 15.00"
        service.postProtectionPayout(new BigDecimal('40.00'), 9L, 44L)

        when:
        service.postProtectionReversal(new BigDecimal('15.00'), 9L, 44L)

        then: "the 25.00 the buyer already spent stays on the books as a real loss"
        treasury.balance == new BigDecimal('-25.00')
        written.last().type == PlatformLedgerService.TYPE_PROTECTION_REVERSAL
    }

    def "revenue and cost net against each other in one account"() {
        when:
        service.postTradeFee(new BigDecimal('10.00'), 1L, 1L, 'a')
        service.postDepositProcessingCost(new BigDecimal('100.00'), 'cs_1')   // -3.20
        service.postProtectionFee(new BigDecimal('1.00'), 2L, 2L, 'b')

        then: "10.00 - 3.20 + 1.00 — the balance IS the margin"
        treasury.balance == new BigDecimal('7.80')
        service.margin() == new BigDecimal('7.80')
    }

    // ── Arithmetic ──────────────────────────────────────────────────

    @Unroll
    def "processing cost on a \$#deposit deposit is \$#expected"() {
        expect:
        service.estimateProcessingCost(new BigDecimal(deposit)) == new BigDecimal(expected)

        where:
        deposit  || expected
        '10.00'  || '0.59'    // 0.29 + 0.30
        '20.00'  || '0.88'    // 0.58 + 0.30
        '100.00' || '3.20'    // 2.90 + 0.30
        '500.00' || '14.80'   // 14.50 + 0.30
    }

    def "the cost estimate rounds HALF_UP to whole cents, never truncating"() {
        expect: "2.9% of 5.00 = 0.145 → 0.445 total, which must round UP to 0.45"
        service.estimateProcessingCost(new BigDecimal('5.00')) == new BigDecimal('0.45')
    }

    def "a fee is rounded to cents before it reaches the balance"() {
        when:
        service.postTradeFee(new BigDecimal('2.005'), 1L, 1L, 'x')

        then:
        treasury.balance == new BigDecimal('2.01')
        written.last().amount.scale() == 2
    }

    // ── Non-postings ────────────────────────────────────────────────

    @Unroll
    def "a #label fee writes no row and moves no money"() {
        when:
        def tx = service.postTradeFee(amount, 1L, 1L, 'x')

        then: "a zero-value ledger row is noise, not information"
        tx == null
        written.isEmpty()
        treasury.balance == BigDecimal.ZERO

        where:
        label      | amount
        'null'     | null
        'zero'     | BigDecimal.ZERO
        'negative' | new BigDecimal('-1.00')
        'sub-cent' | new BigDecimal('0.004')   // rounds to 0.00
    }

    def "a zero-amount deposit books no processing cost"() {
        expect:
        service.estimateProcessingCost(BigDecimal.ZERO) == null
        service.postDepositProcessingCost(BigDecimal.ZERO, 'cs_1') == null
        written.isEmpty()
    }

    // ── The treasury account itself ─────────────────────────────────

    def "the treasury username cannot collide with a user wallet"() {
        given: "every wallet-creation path in the app"
        def userWallet   = "steam_76561199000000001"
        def seedWallet   = "demo"

        expect: "no construction can produce the reserved name"
        PlatformLedgerService.TREASURY_USERNAME != userWallet
        PlatformLedgerService.TREASURY_USERNAME != seedWallet
        !PlatformLedgerService.TREASURY_USERNAME.startsWith('steam_')
    }

    def "the treasury is created on first use when it does not exist"() {
        given:
        WalletRepository repo = Mock()
        def created = []
        def svc = new PlatformLedgerService(
            walletRepository: repo, transactionRepository: transactionRepository,
            processingFeePercent: new BigDecimal('2.9'), processingFeeFixed: new BigDecimal('0.30'))

        when:
        svc.postTradeFee(new BigDecimal('1.00'), 1L, 1L, 'x')

        then: "both lookups miss, so the treasury is created rather than the fee being dropped"
        2 * repo.findByUsername(PlatformLedgerService.TREASURY_USERNAME) >> null

        and: "two saves: the INSERT at zero, then the balance update carrying the fee"
        2 * repo.save({ Wallet w -> w.username == PlatformLedgerService.TREASURY_USERNAME }) >> { Wallet w ->
            w.id = 500L
            created << [balance: w.balance]
            w
        }
        created.size() == 2
        created[0].balance == BigDecimal.ZERO          // created empty…
        created[1].balance == new BigDecimal('1.00')   // …then credited
    }

    def "a REVENUE posting refuses to silently vanish when the treasury cannot be resolved"() {
        given: "a repository that never yields a treasury row"
        WalletRepository repo = Mock()
        repo.findByUsername(_) >> null
        repo.save(_) >> null
        def svc = new PlatformLedgerService(
            walletRepository: repo, transactionRepository: transactionRepository,
            processingFeePercent: new BigDecimal('2.9'), processingFeeFixed: new BigDecimal('0.30'))

        when:
        svc.postTradeFee(new BigDecimal('5.00'), 1L, 1L, 'x')

        then: "dropping revenue quietly is the exact failure this class exists to end"
        def e = thrown(IllegalStateException)
        e.message.contains('FEE')
    }

    def "margin reads zero, not null, before any posting exists"() {
        given:
        WalletRepository repo = Mock()
        repo.findByUsername(_) >> null
        def svc = new PlatformLedgerService(walletRepository: repo, transactionRepository: transactionRepository)

        expect:
        svc.margin() == new BigDecimal('0.00')
    }

    // ── Type taxonomy ───────────────────────────────────────────────

    def "every ledger type is classified as revenue or cost, and none as both"() {
        given:
        def revenue = PlatformLedgerService.REVENUE_TYPES
        def cost    = PlatformLedgerService.COST_TYPES

        // Every TYPE_ constant the class declares, found by REFLECTION rather
        // than listed by hand. A hand-written list is a census that only looks
        // where it was told to, so the type it forgets is exactly the one that
        // drops out of margin() unnoticed. DISPUTE_COST was added on
        // 2026-09-20 and this block needed no edit, which is the point.
        and: "the types the class actually declares"
        def declared = PlatformLedgerService.declaredFields
            .findAll { it.name.startsWith('TYPE_') && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .collect { it.accessible = true; it.get(null) as String }

        expect: "the reflection found something — an empty census proves nothing"
        declared.size() >= 6

        and: "an unclassified type would silently drop out of the margin arithmetic"
        revenue.intersect(cost).isEmpty()
        (revenue + cost).containsAll(declared)

        and: "and nothing is classified that is not a declared type"
        declared.containsAll(revenue + cost)
    }

    def "every ledger type fits the transactions.type column"() {
        expect: "V1__baseline.sql declares type VARCHAR(32)"
        (PlatformLedgerService.REVENUE_TYPES + PlatformLedgerService.COST_TYPES)
            .every { it.length() <= 32 }
    }
}
