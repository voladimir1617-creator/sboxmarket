package com.sboxmarket

import com.sboxmarket.controller.WalletController
import com.sboxmarket.dto.request.DepositRequest
import com.sboxmarket.dto.request.WithdrawRequest
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TotpService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Controller-level coverage for `/api/wallet/withdraw`. Focused on
 * the rolling 24-hour withdrawal cap added in batch 357 — the fraud
 * ceiling on compromised-account drains. Every guard-rail upstream
 * of the cap (email-verification, 2FA, balance) has existing
 * coverage in StripeServiceSpec / WalletModel tests; this spec pins
 * the cap's arithmetic.
 */
class WalletControllerSpec extends Specification {

    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    StripeService         stripeService         = Mock()
    TotpService           totpService           = Mock()
    TextSanitizer         textSanitizer         = new TextSanitizer()  // real impl — pinning sanitization behaviour

    @Subject
    WalletController controller = new WalletController(
        walletRepository:      walletRepository,
        transactionRepository: transactionRepository,
        steamUserRepository:   steamUserRepository,
        stripeService:         stripeService,
        totpService:           totpService,
        textSanitizer:         textSanitizer,
        dailyWithdrawalCap:    new BigDecimal('5000')
    )

    private HttpServletRequest reqFor(Long uid) {
        def session = Mock(HttpSession)
        session.getAttribute('steamUserId') >> uid
        def req = Mock(HttpServletRequest)
        req.session >> session
        req
    }

    private SteamUser verifiedUser(Long id = 10L) {
        new SteamUser(
            id:            id,
            steamId64:     '111',
            displayName:   'Alice',
            email:         'alice@example.com',
            emailVerified: true,
            totpSecret:    null
        )
    }

    private Wallet walletFor(BigDecimal balance = new BigDecimal('4000')) {
        new Wallet(id: 500L, username: 'steam_111', balance: balance, currency: 'USD')
    }

    private WithdrawRequest req(BigDecimal amount, String dest = 'stripe_connect_acct_x') {
        def r = new WithdrawRequest()
        r.amount = amount
        r.destination = dest
        r
    }

    def "withdraw succeeds when the requested amount + used is under the cap"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('4000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.sumWithdrawalsSince(500L, _) >> new BigDecimal('1000')  // $1k already spent

        when:
        // Requested $3500; existing $1000 + requested $3500 = $4500 ≤ $5000 cap.
        def response = controller.withdraw(req(new BigDecimal('3500')), reqFor(10L))

        then:
        // Interaction + return combined so the mock's default-null return
        // doesn't NPE the controller's `tx.id` access.
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('3500'), 'stripe_connect_acct_x') >>
            new Transaction(id: 99L, status: 'PENDING')
        response.statusCode.value() == 200
    }

    def "withdraw rejects with WITHDRAW_DAILY_CAP when request would exceed the rolling 24h cap"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('5000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        // $4,000 already spent in the last 24h.
        transactionRepository.sumWithdrawalsSince(500L, _) >> new BigDecimal('4000')

        when:
        // Requested $2,000 → $4,000 + $2,000 = $6,000 > $5,000 cap.
        controller.withdraw(req(new BigDecimal('2000')), reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAW_DAILY_CAP'
        // Critical: the Stripe request must NOT fire when the cap guard triggers.
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw rejects when cap is already exceeded and remaining headroom is zero"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('1000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        // $5,000 already spent — exactly at the cap.
        transactionRepository.sumWithdrawalsSince(500L, _) >> new BigDecimal('5000')

        when:
        // Any amount > 0 would push past the cap.
        controller.withdraw(req(new BigDecimal('0.01')), reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAW_DAILY_CAP'
        // Error message surfaces the numbers so the user knows why.
        e.message.contains('5000')
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw checks the cap AFTER the balance check (insufficient balance beats cap)"() {
        given:
        def user = verifiedUser()
        // Wallet has only $100 but the user is asking for $6000 — should
        // hit the balance check first, not the cap.
        def wallet = walletFor(new BigDecimal('100'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet

        when:
        controller.withdraw(req(new BigDecimal('6000')), reqFor(10L))

        then:
        thrown(InsufficientBalanceException)
        // Cap query never fires — balance check short-circuits.
        0 * transactionRepository.sumWithdrawalsSince(_, _)
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw sanitizes the user-supplied destination BEFORE handing it to StripeService (no log/HTML injection into Transaction.stripeReference)"() {
        // Regression: body.destination → tx.stripeReference was written raw,
        // surviving into the audit-log summary ("Withdrawal $X requested
        // … → ${destinationRef}"), the GDPR /export JSON, and the
        // user-facing approval notification body ("Reference:
        // ${tx.stripeReference}"). A destination like
        // "acct_x\r\n[INFO] forged log line\r\n<script>x</script>" would
        // forge audit lines + persist an HTML payload echoed back to the
        // wallet owner. Every sibling free-text field (stallBio, trade
        // dispute reason, offer message) routes through TextSanitizer
        // at ingest; destination must too.
        given:
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('1000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.sumWithdrawalsSince(500L, _) >> BigDecimal.ZERO
        def hostile = "acct_x\r\n[INFO] forged log line<script>alert(1)</script>"

        when:
        controller.withdraw(req(new BigDecimal('50'), hostile), reqFor(10L))

        then: 'StripeService is handed a sanitized string — no CR/LF, no <script>, no <…> tags'
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('50'), { String passed ->
            !passed.contains('\r') && !passed.contains('\n') &&
            !passed.contains('<') && !passed.contains('>') &&
            !passed.toLowerCase().contains('script')
        }) >> new Transaction(id: 99L, status: 'PENDING')
    }

    def "withdraw bypasses the cap check entirely when dailyWithdrawalCap is null (ops disable)"() {
        given:
        controller.dailyWithdrawalCap = null
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('999999'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)

        when:
        controller.withdraw(req(new BigDecimal('100000')), reqFor(10L))

        then:
        // The sum query never fires when the cap is null/zero.
        0 * transactionRepository.sumWithdrawalsSince(_, _)
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('100000'), _) >>
            new Transaction(id: 99L, status: 'PENDING')
    }

    // ════════════════════════════════════════════════════════════════
    // Batch 1068 — full endpoint coverage. The cap-math tests above
    // focused on one branch; these pin the rest of the surface.
    // ════════════════════════════════════════════════════════════════

    // ─── GET /api/wallet ────────────────────────────────────────────

    def "getWallet() anon: zero-balance snapshot, NEVER leaks demo wallet row (batch 976)"() {
        given:
        stripeService.isLive() >> false
        stripeService.dailyDepositCap >> new BigDecimal('2000')

        when:
        def resp = controller.getWallet(reqFor(null))

        then: 'never falls through to the persisted demo wallet'
        0 * walletRepository.findById(_)
        0 * walletRepository.findByUsername(_)
        0 * transactionRepository.findPendingByWallet(_)
        resp.statusCode.value() == 200
        resp.body.loggedIn == false
        resp.body.id == null
        resp.body.username == null
        resp.body.balance == BigDecimal.ZERO
        resp.body.pendingWithdrawAmt == BigDecimal.ZERO
        resp.body.dailyWithdrawCap == new BigDecimal('5000')
        resp.body.dailyWithdrawRemaining == new BigDecimal('5000')
        resp.body.dailyDepositCap == new BigDecimal('2000')
        resp.body.frozen == false
    }

    def "getWallet() signed-in: hero snapshot with pending + 24h aggregates"() {
        given:
        def user = verifiedUser()
        user.avatarUrl = 'https://cdn.example/avi.png'
        def wallet = walletFor(new BigDecimal('250.50'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.findPendingByWallet(500L) >> [
            new Transaction(id: 1L, type: 'WITHDRAW', amount: new BigDecimal('50.00')),
            new Transaction(id: 2L, type: 'DEPOSIT',  amount: new BigDecimal('30.00'))
        ]
        transactionRepository.sumWithdrawalsSince(500L, _) >> new BigDecimal('400.00')
        transactionRepository.earliestWithdrawalSince(500L, _) >> 1700_000_000_000L
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.sumDepositsSince(500L, _) >> new BigDecimal('100.00')
        transactionRepository.earliestDepositSince(500L, _) >> null
        stripeService.isLive() >> true
        stripeService.dailyDepositCap >> new BigDecimal('2000')

        when:
        def resp = controller.getWallet(reqFor(10L))

        then:
        resp.body.id == 500L
        resp.body.username == 'Alice'
        resp.body.avatarUrl == 'https://cdn.example/avi.png'
        resp.body.loggedIn == true
        resp.body.balance == new BigDecimal('250.50')
        resp.body.pendingWithdrawAmt == new BigDecimal('50.00')
        resp.body.pendingWithdrawCt == 1
        resp.body.pendingDepositAmt == new BigDecimal('30.00')
        resp.body.pendingDepositCt == 1
        resp.body.dailyWithdrawUsed == new BigDecimal('400.00')
        resp.body.dailyWithdrawRemaining == new BigDecimal('4600.00')
        resp.body.dailyWithdrawOldestAt == 1700_000_000_000L
        resp.body.dailyDepositRemaining == new BigDecimal('1900.00')
        resp.body.stripeLive == true
    }

    def "getWallet() sums BOTH 'WITHDRAW' and 'WITHDRAWAL' pending types — no legacy rows vanish"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.findPendingByWallet(500L) >> [
            new Transaction(id: 1L, type: 'WITHDRAW',   amount: new BigDecimal('10.00')),
            new Transaction(id: 2L, type: 'WITHDRAWAL', amount: new BigDecimal('20.00'))
        ]
        transactionRepository.sumWithdrawalsSince(_, _) >> BigDecimal.ZERO
        transactionRepository.earliestWithdrawalSince(_, _) >> null
        transactionRepository.countActiveDisputedDeposits(_) >> 0L
        transactionRepository.sumDepositsSince(_, _) >> BigDecimal.ZERO
        transactionRepository.earliestDepositSince(_, _) >> null
        stripeService.isLive() >> false
        stripeService.dailyDepositCap >> BigDecimal.ZERO

        when:
        def resp = controller.getWallet(reqFor(10L))

        then:
        resp.body.pendingWithdrawAmt == new BigDecimal('30.00')
        resp.body.pendingWithdrawCt == 2
    }

    def "getWallet() clamps dailyWithdrawRemaining at zero when used > cap"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.findPendingByWallet(_) >> []
        transactionRepository.sumWithdrawalsSince(_, _) >> new BigDecimal('5500')  // over cap
        transactionRepository.earliestWithdrawalSince(_, _) >> null
        transactionRepository.countActiveDisputedDeposits(_) >> 0L
        transactionRepository.sumDepositsSince(_, _) >> BigDecimal.ZERO
        transactionRepository.earliestDepositSince(_, _) >> null
        stripeService.isLive() >> false
        stripeService.dailyDepositCap >> BigDecimal.ZERO

        when:
        def resp = controller.getWallet(reqFor(10L))

        then: 'UI never renders a negative remaining'
        resp.body.dailyWithdrawRemaining == BigDecimal.ZERO
    }

    def "getWallet() exposes frozen state for the UI's 'Wallet frozen' banner"() {
        given:
        def user = verifiedUser()
        def wallet = new Wallet(
            id: 500L, username: 'steam_111', balance: new BigDecimal('100'),
            currency: 'USD', frozen: true, frozenReason: 'pending fraud review', frozenAt: 1700L
        )
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.findPendingByWallet(_) >> []
        transactionRepository.sumWithdrawalsSince(_, _) >> BigDecimal.ZERO
        transactionRepository.earliestWithdrawalSince(_, _) >> null
        transactionRepository.countActiveDisputedDeposits(_) >> 0L
        transactionRepository.sumDepositsSince(_, _) >> BigDecimal.ZERO
        transactionRepository.earliestDepositSince(_, _) >> null
        stripeService.isLive() >> false
        stripeService.dailyDepositCap >> BigDecimal.ZERO

        when:
        def resp = controller.getWallet(reqFor(10L))

        then:
        resp.body.frozen == true
        resp.body.frozenReason == 'pending fraud review'
        resp.body.frozenAt == 1700L
    }

    def "getWallet() auto-creates a wallet row the first time a new Steam user calls it"() {
        given:
        def user = verifiedUser()
        def fresh = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO, currency: 'USD')
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> null
        1 * walletRepository.save({ Wallet w ->
            w.username == 'steam_111' && w.balance == BigDecimal.ZERO
        }) >> fresh
        transactionRepository.findPendingByWallet(_) >> []
        transactionRepository.sumWithdrawalsSince(_, _) >> BigDecimal.ZERO
        transactionRepository.earliestWithdrawalSince(_, _) >> null
        transactionRepository.countActiveDisputedDeposits(_) >> 0L
        transactionRepository.sumDepositsSince(_, _) >> BigDecimal.ZERO
        transactionRepository.earliestDepositSince(_, _) >> null
        stripeService.isLive() >> false
        stripeService.dailyDepositCap >> BigDecimal.ZERO

        when:
        def resp = controller.getWallet(reqFor(10L))

        then:
        resp.body.balance == BigDecimal.ZERO
        resp.body.loggedIn == true
    }

    // ─── GET /api/wallet/spend ──────────────────────────────────────

    def "getSpendSummary() anon: 401 (spending history is PII)"() {
        when:
        controller.getSpendSummary(reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * transactionRepository.sumByWalletAndType(_, _, _)
    }

    def "getSpendSummary() signed-in user with no wallet yet: zero-history envelope"() {
        given:
        def user = verifiedUser()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> null
        walletRepository.save(_) >> new Wallet(id: 1L)   // DEMO_WALLET_ID sentinel

        when:
        def resp = controller.getSpendSummary(reqFor(10L))

        then:
        resp.body.spentLifetime == BigDecimal.ZERO
        resp.body.purchasesLifetime == 0L
        resp.body.spent30d == BigDecimal.ZERO
        resp.body.spent7d == BigDecimal.ZERO
        resp.body.spent24h == BigDecimal.ZERO
        0 * transactionRepository.sumByWalletAndType(_, _, _)
    }

    def "getSpendSummary() happy path: returns four windows of PURCHASE aggregates"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * transactionRepository.sumByWalletAndType(500L, 'PURCHASE', true) >> new BigDecimal('4321.00')
        1 * transactionRepository.countCompletedByWalletAndType(500L, 'PURCHASE') >> 87L
        // 30d / 7d / 24h windows all go through the same method signature.
        _ * transactionRepository.sumCompletedByWalletTypeSince(500L, 'PURCHASE', _) >>> [
            new BigDecimal('900'), new BigDecimal('200'), new BigDecimal('50')
        ]
        _ * transactionRepository.countCompletedByWalletTypeSince(500L, 'PURCHASE', _) >>> [15L, 3L, 1L]

        when:
        def resp = controller.getSpendSummary(reqFor(10L))

        then:
        resp.body.spentLifetime == new BigDecimal('4321.00')
        resp.body.purchasesLifetime == 87L
    }

    // ─── GET /api/wallet/activity ───────────────────────────────────
    // The wallet summary tiles. They used to read /spend, which counts
    // PURCHASE rows only, so a seller's sale credits and every deposit were
    // missing from the one summary on the wallet page.

    def "getActivitySummary() anon: 401 (a ledger summary is PII)"() {
        when:
        controller.getActivitySummary(reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * transactionRepository.summarizeCompletedByTypeSince(_, _)
    }

    def "getActivitySummary() reports deposits, sales, purchases, withdrawals and refunds separately"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.summarizeCompletedByTypeSince(500L, _) >> [
            ['DEPOSIT',    2L, new BigDecimal('150.00')] as Object[],
            ['SALE',       3L, new BigDecimal('29.40')]  as Object[],
            ['PURCHASE',   1L, new BigDecimal('9.99')]   as Object[],
            ['WITHDRAW',   1L, new BigDecimal('20.00')]  as Object[],
            // the legacy spelling lands in the same tile
            ['WITHDRAWAL', 1L, new BigDecimal('5.00')]   as Object[],
            ['REFUND',     1L, new BigDecimal('9.99')]   as Object[],
            // staff corrections are not activity
            ['ADJUSTMENT_CREDIT', 1L, new BigDecimal('1.00')] as Object[],
        ]

        when:
        def all = controller.getActivitySummary(reqFor(10L)).body.windows.all

        then:
        all.deposits    == [amount: new BigDecimal('150.00'), count: 2L]
        all.sales       == [amount: new BigDecimal('29.40'),  count: 3L]
        all.purchases   == [amount: new BigDecimal('9.99'),   count: 1L]
        all.withdrawals == [amount: new BigDecimal('25.00'),  count: 2L]
        all.refunds     == [amount: new BigDecimal('9.99'),   count: 1L]
        !all.containsKey('adjustments')
    }

    def "getActivitySummary() asks for all time and two rolling windows"() {
        given:
        def user = verifiedUser()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> walletFor()
        def sinces = []
        long before = System.currentTimeMillis()

        when:
        def body = controller.getActivitySummary(reqFor(10L)).body

        then:
        3 * transactionRepository.summarizeCompletedByTypeSince(500L, _) >> { Long w, Long since -> sinces << since; [] }
        body.windows.keySet() == ['7d', '30d', 'all'] as Set
        long day = 24L * 60L * 60L * 1000L
        sinces.contains(0L)
        sinces.any { Math.abs((before - it) - 7L * day) < 60_000L }
        sinces.any { Math.abs((before - it) - 30L * day) < 60_000L }
        body.windows['7d'].sales == [amount: BigDecimal.ZERO, count: 0L]
    }

    def "getActivitySummary() signed-in with no wallet row yet: zeros, no ledger query"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(verifiedUser())
        walletRepository.findByUsername('steam_111') >> null
        walletRepository.save(_) >> new Wallet(id: 1L)   // DEMO_WALLET_ID sentinel

        when:
        def body = controller.getActivitySummary(reqFor(10L)).body

        then:
        0 * transactionRepository.summarizeCompletedByTypeSince(_, _)
        body.windows.all.deposits == [amount: BigDecimal.ZERO, count: 0L]
        body.windows.all.sales    == [amount: BigDecimal.ZERO, count: 0L]
    }

    // ─── GET /api/wallet/transactions ───────────────────────────────

    def "getTransactions() anon: [] (never leaks demo-wallet ledger — batch 977 fix)"() {
        when:
        def resp = controller.getTransactions(reqFor(null))

        then:
        0 * transactionRepository.findByWalletIdOrderByCreatedAtDesc(_, _)
        0 * walletRepository.findById(_)
        resp.statusCode.value() == 200
        resp.body == []
    }

    def "getTransactions() signed-in: returns ledger with SQL LIMIT 500"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        def txs = [new Transaction(id: 9L, type: 'DEPOSIT', amount: new BigDecimal('10'))]
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L,
            { org.springframework.data.domain.Pageable p ->
                p.pageSize == 500 && p.pageNumber == 0
            }) >> txs

        when:
        def resp = controller.getTransactions(reqFor(10L))

        then:
        resp.body.is(txs)
    }

    def "getTransactions() stale session (steamUserId points at a deleted user): [] — never leaks the demo wallet ledger"() {
        given: 'a live session whose user row no longer exists — currentWallet() would fall through to the demo wallet'
        steamUserRepository.findById(99L) >> Optional.empty()
        // The seeded demo wallet currentWallet() falls back to. If the
        // endpoint queried its ledger, a stale session would see it —
        // the exact leak class batch 977 set out to close, but its
        // short-circuit only covered the anonymous (userId == null) case.
        walletRepository.findById(1L) >> Optional.of(
            new Wallet(id: 1L, username: 'demo', balance: new BigDecimal('250'), currency: 'USD'))

        when:
        def resp = controller.getTransactions(reqFor(99L))

        then: 'the demo wallet id is rejected before any ledger query — caller gets an empty list'
        0 * transactionRepository.findByWalletIdOrderByCreatedAtDesc(_, _)
        resp.statusCode.value() == 200
        resp.body == []
    }

    // ─── GET /api/wallet/transactions.csv ───────────────────────────

    def "exportTransactionsCsv() anon: 401 (signed-in only — tax PII)"() {
        when:
        controller.exportTransactionsCsv(null, reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * transactionRepository.findByWalletIdOrderByCreatedAtDesc(_, _)
    }

    def "exportTransactionsCsv() happy path: CSV body + Cache-Control: no-store + attachment"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        def tx = new Transaction(
            id: 1L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('25.00'), currency: 'USD',
            description: 'test', listingId: null,
            stripeReference: 'cs_abc', createdAt: 1700_000_000_000L
        )
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L, _) >> [tx]

        when:
        def resp = controller.exportTransactionsCsv(null, reqFor(10L))

        then:
        resp.statusCode.value() == 200
        resp.headers.getFirst('Cache-Control') == 'no-store'
        resp.headers.getFirst('Content-Disposition')?.contains('attachment')
        resp.headers.getFirst('Content-Disposition')?.contains('.csv')
        // Commit 5ade09d added a `# currency: USD` comment line ahead of
        // the header row + renamed `amount` to `amount (USD)` so a CSV
        // opened in a foreign locale carries the currency metadata.
        resp.body.startsWith('# currency: USD\nid,date,type,status,amount (USD),currency,description,listingId,reference\n')
        resp.body.contains('DEPOSIT')
        resp.body.contains('25.00')
    }

    def "exportTransactionsCsv() ?month=YYYY-MM filters to that calendar month (UTC)"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        long marchMs = java.time.ZonedDateTime
            .of(2026, 3, 15, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        long aprilMs = java.time.ZonedDateTime
            .of(2026, 4, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        def inMarch = new Transaction(id: 1L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('10.00'), currency: 'USD', createdAt: marchMs)
        def inApril = new Transaction(id: 2L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('99.00'), currency: 'USD', createdAt: aprilMs)
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L, _) >> [inMarch, inApril]

        when:
        def resp = controller.exportTransactionsCsv('2026-03', reqFor(10L))

        then: 'only the March row survives — filename carries the month suffix'
        resp.body.contains('10.00')
        !resp.body.contains('99.00')
        resp.headers.getFirst('Content-Disposition')?.contains('-2026-03-')
    }

    def "exportTransactionsCsv() malformed ?month= silently returns full history (bookmark compat)"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        def tx1 = new Transaction(id: 1L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('10'), currency: 'USD', createdAt: 1L)
        def tx2 = new Transaction(id: 2L, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('20'), currency: 'USD', createdAt: 2L)
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L, _) >> [tx1, tx2]

        when: '2026-13 fails the YYYY-MM regex → drops filter'
        def resp = controller.exportTransactionsCsv('2026-13', reqFor(10L))

        then:
        resp.body.contains('10')
        resp.body.contains('20')
        !resp.headers.getFirst('Content-Disposition')?.contains('-2026-13-')
    }

    // ─── POST /api/wallet/deposit ───────────────────────────────────

    def "deposit() anon: 401 (never hits Stripe — would target the demo wallet)"() {
        given:
        def body = new DepositRequest(amount: new BigDecimal('50'))

        when:
        controller.deposit(body, reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * stripeService.createDepositSession(_, _)
    }

    def "deposit() frozen wallet: WALLET_FROZEN with staff reason in the message"() {
        given:
        def user = verifiedUser()
        def wallet = new Wallet(
            id: 500L, username: 'steam_111', balance: new BigDecimal('100'),
            currency: 'USD', frozen: true, frozenReason: 'pending fraud review'
        )
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        def body = new DepositRequest(amount: new BigDecimal('50'))

        when:
        controller.deposit(body, reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'WALLET_FROZEN'
        e.message.contains('pending fraud review')
        0 * stripeService.createDepositSession(_, _)
    }

    def "deposit() happy path: forwards (walletId, amount) to StripeService and returns the session payload"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        def sessionPayload = [sessionUrl: 'https://checkout.stripe.com/pay/cs_abc', sessionId: 'cs_abc']
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * stripeService.createDepositSession(500L, new BigDecimal('75.00')) >> sessionPayload
        def body = new DepositRequest(amount: new BigDecimal('75.00'))

        when:
        def resp = controller.deposit(body, reqFor(10L))

        then:
        resp.body.is(sessionPayload)
    }

    // ─── POST /api/wallet/withdraw — gate ladder (extends cap tests) ─

    def "withdraw() anon: 401 (never enters the gate ladder)"() {
        when:
        controller.withdraw(req(new BigDecimal('50')), reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() frozen wallet: WALLET_FROZEN short-circuits ahead of email + TOTP + cap checks"() {
        given:
        def user = verifiedUser()
        def wallet = new Wallet(
            id: 500L, username: 'steam_111', balance: new BigDecimal('100'),
            currency: 'USD', frozen: true, frozenReason: 'dispute'
        )
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet

        when:
        controller.withdraw(req(new BigDecimal('50')), reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'WALLET_FROZEN'
        0 * transactionRepository.countActiveDisputedDeposits(_)
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() user with no email on profile: EMAIL_REQUIRED"() {
        given:
        def user = verifiedUser()
        user.email = null
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet

        when:
        controller.withdraw(req(new BigDecimal('50')), reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'EMAIL_REQUIRED'
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() unverified email: EMAIL_NOT_VERIFIED"() {
        given:
        def user = verifiedUser()
        user.emailVerified = false
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet

        when:
        controller.withdraw(req(new BigDecimal('50')), reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'EMAIL_NOT_VERIFIED'
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() 2FA enabled + missing totpCode: TOTP_REQUIRED"() {
        given:
        def user = verifiedUser()
        user.totpSecret = 'JBSWY3DPEHPK3PXP'
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        def r = req(new BigDecimal('50'))
        r.totpCode = ''

        when:
        controller.withdraw(r, reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'TOTP_REQUIRED'
        0 * totpService.verify(*_)
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() 2FA enabled + bad code: TOTP_INVALID, lastTotpStep NOT persisted"() {
        given:
        def user = verifiedUser()
        user.totpSecret = 'JBSWY3DPEHPK3PXP'
        user.lastTotpStep = 42L
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * totpService.verify('JBSWY3DPEHPK3PXP', '000000', 42L) >> -1L
        def r = req(new BigDecimal('50'))
        r.totpCode = '000000'

        when:
        controller.withdraw(r, reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'TOTP_INVALID'
        0 * steamUserRepository.save(_)
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() 2FA good code: persists fresh lastTotpStep (replay defense)"() {
        given:
        def user = verifiedUser()
        user.totpSecret = 'JBSWY3DPEHPK3PXP'
        user.lastTotpStep = 10L
        def wallet = walletFor(new BigDecimal('1000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        1 * totpService.verify('JBSWY3DPEHPK3PXP', '123456', 10L) >> 11L
        1 * steamUserRepository.save({ SteamUser u -> u.lastTotpStep == 11L })
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.sumWithdrawalsSince(_, _) >> BigDecimal.ZERO
        def r = req(new BigDecimal('50'), 'steam_trade_url')
        r.totpCode = '123456'

        when:
        def resp = controller.withdraw(r, reqFor(10L))

        then:
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('50'), 'steam_trade_url') >>
            new Transaction(id: 777L, status: 'PENDING')
        resp.body.transactionId == 777L
    }

    def "withdraw() active chargeback on ANY deposit: WITHDRAW_DISPUTE_HOLD with count in message"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('500'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * transactionRepository.countActiveDisputedDeposits(500L) >> 2L

        when:
        controller.withdraw(req(new BigDecimal('50')), reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAW_DISPUTE_HOLD'
        e.message.contains('2 unresolved deposit dispute')
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() null destination forwarded as empty-string to service"() {
        given:
        controller.dailyWithdrawalCap = null
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('500'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        def r = req(new BigDecimal('50'), null)

        when:
        controller.withdraw(r, reqFor(10L))

        then: 'service sees "" so its downstream regex/length check works on a String, never null'
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('50'), '') >>
            new Transaction(id: 1L, status: 'PENDING')
    }

    def "withdraw() exact-balance withdrawal is allowed (balance == amount is not 'insufficient')"() {
        given: 'wallet balance equals the requested amount to the cent'
        controller.dailyWithdrawalCap = null
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('250.00'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L

        when: 'the user cashes out their whole balance'
        def resp = controller.withdraw(req(new BigDecimal('250.00')), reqFor(10L))

        then: 'the `balance < amount` guard treats an exact match as sufficient'
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('250.00'), _) >>
            new Transaction(id: 5L, status: 'PENDING')
        resp.statusCode.value() == 200
    }

    def "withdraw() dispute-hold is checked BEFORE the daily-cap query (a held wallet never reaches the cap math)"() {
        given: 'a wallet with an active dispute AND cap headroom'
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('5000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * transactionRepository.countActiveDisputedDeposits(500L) >> 1L

        when:
        controller.withdraw(req(new BigDecimal('50')), reqFor(10L))

        then: 'dispute hold short-circuits — the cap sum query is never issued'
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAW_DISPUTE_HOLD'
        0 * transactionRepository.sumWithdrawalsSince(_, _)
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() 2FA code is consumed only AFTER the dispute-hold gate (a held wallet does not burn the code)"() {
        given: 'a 2FA user whose wallet is on dispute hold'
        def user = verifiedUser()
        user.totpSecret = 'JBSWY3DPEHPK3PXP'
        user.lastTotpStep = 10L
        def wallet = walletFor(new BigDecimal('500'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * transactionRepository.countActiveDisputedDeposits(500L) >> 1L
        def r = req(new BigDecimal('50'))
        r.totpCode = '123456'

        when:
        controller.withdraw(r, reqFor(10L))

        then: 'the dispute-hold throw fires before TOTP verify — the code is not consumed, no user save'
        thrown(BadRequestException)
        0 * totpService.verify(*_)
        0 * steamUserRepository.save(_)
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() daily-cap boundary: request that lands EXACTLY on the cap is allowed"() {
        given: 'used $4,000 of a $5,000 cap, requesting exactly the $1,000 remainder'
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('5000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.sumWithdrawalsSince(500L, _) >> new BigDecimal('4000')

        when: 'sum ($4000) + request ($1000) == cap ($5000) — the guard uses strict >, so equal passes'
        def resp = controller.withdraw(req(new BigDecimal('1000')), reqFor(10L))

        then:
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('1000'), _) >>
            new Transaction(id: 6L, status: 'PENDING')
        resp.statusCode.value() == 200
    }

    def "withdraw() one cent over the cap is rejected"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('5000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.sumWithdrawalsSince(500L, _) >> new BigDecimal('4999.99')

        when: 'sum ($4999.99) + request ($0.02) = $5000.01 > $5000 cap'
        controller.withdraw(req(new BigDecimal('0.02')), reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAW_DAILY_CAP'
        0 * stripeService.requestWithdrawal(*_)
    }

    // ─── POST /api/wallet/withdraw/{id}/cancel ──────────────────────

    def "cancelWithdraw() anon: 401 (never reaches StripeService)"() {
        when:
        controller.cancelWithdraw(9L, reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * stripeService.cancelPendingWithdrawal(_, _)
    }

    def "cancelWithdraw() happy path: forwards (walletId, txId) and returns service envelope"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * stripeService.cancelPendingWithdrawal(500L, 9L) >>
            [status: 'CANCELLED', refunded: new BigDecimal('25')]

        when:
        def resp = controller.cancelWithdraw(9L, reqFor(10L))

        then:
        resp.body.status == 'CANCELLED'
        resp.body.refunded == new BigDecimal('25')
    }

    def "cancelWithdraw() already-processed: IllegalStateException → CANNOT_CANCEL"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * stripeService.cancelPendingWithdrawal(500L, 9L) >>
            { throw new IllegalStateException('already COMPLETED') }

        when:
        controller.cancelWithdraw(9L, reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'CANNOT_CANCEL'
        e.message.contains('already COMPLETED')
    }

    def "cancelWithdraw() non-withdrawal tx: IllegalArgumentException → INVALID_TX"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        // IllegalArgumentException is the not-a-withdrawal signal. (A
        // cross-wallet attempt is now a ForbiddenException — see below.)
        1 * stripeService.cancelPendingWithdrawal(500L, 9L) >>
            { throw new IllegalArgumentException('Transaction is not a withdrawal') }

        when:
        controller.cancelWithdraw(9L, reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_TX'
    }

    def "cancelWithdraw() missing tx: NotFoundException propagates as 404"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        // StripeService throws NotFoundException directly; the controller
        // no longer catches/remaps it — it propagates to the handler (404).
        1 * stripeService.cancelPendingWithdrawal(500L, 9L) >>
            { throw new com.sboxmarket.exception.NotFoundException('Transaction', 9L) }

        when:
        controller.cancelWithdraw(9L, reqFor(10L))

        then:
        thrown(com.sboxmarket.exception.NotFoundException)
    }

    def "cancelWithdraw() cross-wallet attempt: ForbiddenException propagates as 403"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        // A withdrawal owned by another wallet is an authorization failure —
        // StripeService throws ForbiddenException (403), the controller does
        // not remap it, it propagates to the handler.
        1 * stripeService.cancelPendingWithdrawal(500L, 9L) >>
            { throw new com.sboxmarket.exception.ForbiddenException('Not your withdrawal') }

        when:
        controller.cancelWithdraw(9L, reqFor(10L))

        then:
        thrown(com.sboxmarket.exception.ForbiddenException)
    }

    // ─── POST /api/wallet/confirm-deposit ───────────────────────────

    def "confirmDeposit() anon: 401 (never reaches Stripe — session binding is server-side)"() {
        when:
        controller.confirmDeposit([sessionId: 'cs_abc'], reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * stripeService.completeDeposit(_)
    }

    def "confirmDeposit() happy path: completes session, returns fresh wallet balance"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('175.00'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        // BUG 4 fix: the response balance comes from a post-completeDeposit
        // re-read by wallet id, not the pre-credit snapshot.
        walletRepository.findById(500L) >> Optional.of(wallet)
        1 * stripeService.completeDeposit('cs_abc')

        when:
        def resp = controller.confirmDeposit([sessionId: 'cs_abc'], reqFor(10L))

        then:
        resp.body.newBalance == new BigDecimal('175.00')
    }

    def "confirmDeposit() re-reads the wallet by id AFTER completeDeposit — never returns the stale pre-credit balance (BUG 4)"() {
        given: 'currentWallet() resolves the pre-credit snapshot ($50)'
        def user = verifiedUser()
        // The snapshot the controller holds before completeDeposit runs.
        def staleSnapshot = walletFor(new BigDecimal('50.00'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> staleSnapshot
        // completeDeposit (or the webhook racing it) commits the $75
        // credit in its own transaction — the snapshot above never sees
        // it, so a fresh findById is the only way to read the true value.
        def committed = new Wallet(id: 500L, username: 'steam_111',
            balance: new BigDecimal('125.00'), currency: 'USD')
        1 * stripeService.completeDeposit('cs_fresh')
        // The post-credit re-read by primary key returns the committed row.
        1 * walletRepository.findById(500L) >> Optional.of(committed)

        when:
        def resp = controller.confirmDeposit([sessionId: 'cs_fresh'], reqFor(10L))

        then: 'response carries the committed $125, not the stale $50 snapshot'
        resp.body.newBalance == new BigDecimal('125.00')
    }

    def "confirmDeposit() falls back to the snapshot balance if the post-credit re-read finds nothing"() {
        given: 'an unusual case — the wallet row is not returned by the id re-read'
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('90.00'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * stripeService.completeDeposit('cs_x')
        1 * walletRepository.findById(500L) >> Optional.empty()

        when:
        def resp = controller.confirmDeposit([sessionId: 'cs_x'], reqFor(10L))

        then: 'no NPE — degrades to the snapshot balance rather than failing the request'
        resp.body.newBalance == new BigDecimal('90.00')
    }

    def "confirmDeposit() loses the credit race to the webhook: swallows the optimistic-lock failure, returns the already-credited balance"() {
        given: 'the webhook committed the credit first — the wallet @Version flip aborts this transaction'
        def user = verifiedUser()
        // The pre-credit snapshot currentWallet() resolves before the race.
        def staleSnapshot = walletFor(new BigDecimal('25.00'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> staleSnapshot
        1 * stripeService.completeDeposit('cs_race') >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException('wallets', 500L)
        }
        // BUG 4 fix: even after losing the race, the post-completeDeposit
        // re-read by id sees the balance the WEBHOOK committed — so the
        // user whose deposit succeeded still gets the correct number.
        def webhookCredited = new Wallet(id: 500L, username: 'steam_111',
            balance: new BigDecimal('225.00'), currency: 'USD')
        1 * walletRepository.findById(500L) >> Optional.of(webhookCredited)

        when:
        def resp = controller.confirmDeposit([sessionId: 'cs_race'], reqFor(10L))

        then: 'no 500 — the user whose deposit succeeded sees the correct post-credit balance'
        resp.statusCode.value() == 200
        resp.body.newBalance == new BigDecimal('225.00')
    }

    def "confirmDeposit() a non-lock failure from completeDeposit still propagates"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * stripeService.completeDeposit('cs_bad') >> {
            throw new IllegalStateException('Session / wallet mismatch')
        }

        when:
        controller.confirmDeposit([sessionId: 'cs_bad'], reqFor(10L))

        then: 'only OptimisticLockingFailureException is swallowed — real errors surface'
        thrown(IllegalStateException)
    }

    // ════════════════════════════════════════════════════════════════
    // Race-loss error mapping. Two concurrent /withdraw or /cancel
    // requests on the same wallet race on the Wallet @Version field;
    // Hibernate aborts the loser with OptimisticLockingFailureException
    // and the outer @Transactional rolls back fully — so there is
    // NEVER a double-debit or double-credit. But without the catch the
    // loser sees a raw 500 even though their request just lost a race
    // their other tab won. These specs pin the friendly remap:
    //
    //   /withdraw race-loss              → BadRequestException WITHDRAW_RACE
    //   /withdraw/{id}/cancel race-loss  → BadRequestException CANCEL_RACE
    //
    // Both must surface a retryable code with a useful message — not
    // a 500 — so the SPA can toast "try again" cleanly.
    // ════════════════════════════════════════════════════════════════

    def "withdraw() race-loss: OptimisticLockingFailureException is remapped to WITHDRAW_RACE (not a raw 500)"() {
        given: 'a clean withdrawal that passes every gate (balance, dispute hold, cap) on its way to the Stripe call'
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('500'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.sumWithdrawalsSince(_, _) >> BigDecimal.ZERO
        // The race: a concurrent /withdraw on the same wallet flipped
        // @Version under our feet, Hibernate aborts this one. The catch
        // in the controller must remap to WITHDRAW_RACE — anything else
        // surfaces as a raw 500 to a user whose other tab just succeeded.
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('50'), _) >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException('wallets', 500L)
        }

        when:
        controller.withdraw(req(new BigDecimal('50')), reqFor(10L))

        then: 'mapped to a retryable WITHDRAW_RACE — message tells the user to refresh + retry'
        def e = thrown(BadRequestException)
        e.code == 'WITHDRAW_RACE'
        e.message.toLowerCase().contains('try again')
        // The flush + re-read after the Stripe call is what surfaces the
        // race-loss inside the catch (not at commit time, where a 500
        // would escape). We do NOT assert on flush count — explicit
        // belt-and-braces; production may rely on AUTO flush too.
    }

    def "withdraw() race-loss: a generic non-lock failure from requestWithdrawal is NOT swallowed as WITHDRAW_RACE"() {
        given: 'a real downstream failure — must not be miscategorised as a race loss'
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('500'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.sumWithdrawalsSince(_, _) >> BigDecimal.ZERO
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('50'), _) >> {
            throw new RuntimeException('Stripe Connect account suspended')
        }

        when:
        controller.withdraw(req(new BigDecimal('50')), reqFor(10L))

        then: 'the catch is narrow — only OptimisticLockingFailureException maps to WITHDRAW_RACE; everything else surfaces'
        thrown(RuntimeException)
    }

    def "cancelWithdraw() race-loss: OptimisticLockingFailureException is remapped to CANCEL_RACE (not a raw 500)"() {
        given: 'a self-cancel that races an admin reject (or a second tab) on the same tx'
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        // The cancelPendingWithdrawal call commits in its own tx and
        // throws inside the controller's catch when the wallet @Version
        // flip aborts. Without the catch, the user sees a raw 500 even
        // though their cancel may already have landed via the racer.
        1 * stripeService.cancelPendingWithdrawal(500L, 9L) >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException('wallets', 500L)
        }

        when:
        controller.cancelWithdraw(9L, reqFor(10L))

        then: 'mapped to CANCEL_RACE so the SPA toasts "refresh and try again" — no 500'
        def e = thrown(BadRequestException)
        e.code == 'CANCEL_RACE'
        e.message.toLowerCase().contains('refresh')
    }

    def "cancelWithdraw() race-loss: the catch is narrow — non-lock RuntimeException propagates (not silently swallowed)"() {
        given: 'a non-race downstream failure — must surface, not get hidden behind CANCEL_RACE'
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * stripeService.cancelPendingWithdrawal(500L, 9L) >> {
            throw new RuntimeException('Stripe SDK transport error')
        }

        when:
        controller.cancelWithdraw(9L, reqFor(10L))

        then: 'only OptimisticLockingFailureException is mapped to CANCEL_RACE; transport errors surface'
        thrown(RuntimeException)
    }

    // ════════════════════════════════════════════════════════════════
    // Sub-cent amount normalisation. The WithdrawRequest DTO caps
    // magnitude (@DecimalMin 1.00 / @DecimalMax 10000.00) but not
    // scale, so a JSON body {"amount": 1.001} reaches the controller
    // at 3dp. StripeService.requestWithdrawal HALF_UP-rounds to 2dp
    // immediately before debiting — so the wallet ledger is always
    // 2dp-clean — but the controller's PRE-flight guards (balance
    // check, 24h cap math) were running against the RAW sub-cent
    // value. That created two user-hostile over-rejections that
    // disagreed with what the service would actually persist:
    //   • $1.00 balance + 1.001 → InsufficientBalance (would have
    //     normalised to $1.00 and succeeded).
    //   • sum=$4999.99 + 0.014 → WITHDRAW_DAILY_CAP (would have
    //     normalised to $0.01 and landed exactly on cap, allowed).
    // Fix: normalise body.amount ONCE at the top of withdraw() and
    // use the normalised value for every guard AND the service call.
    // ════════════════════════════════════════════════════════════════

    def "withdraw() sub-cent amount that normalises down to fit the balance is allowed (not InsufficientBalance)"() {
        given: 'user has exactly $1.00 and submits 1.001 (a JS rounding artefact)'
        controller.dailyWithdrawalCap = null
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('1.00'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L

        when: '1.001 HALF_UP-normalises to 1.00, which fits the wallet exactly'
        def resp = controller.withdraw(req(new BigDecimal('1.001')), reqFor(10L))

        then: 'pre-flight no longer over-rejects; service receives the normalised 1.00, not raw 1.001'
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('1.00'), _) >>
            new Transaction(id: 1L, status: 'PENDING')
        resp.statusCode.value() == 200
    }

    def "withdraw() sub-cent amount that normalises down to fit the cap is allowed (not WITHDRAW_DAILY_CAP)"() {
        given: '$4999.99 already drawn against a $5000 cap; user submits 0.014'
        def user = verifiedUser()
        def wallet = walletFor(new BigDecimal('5000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.sumWithdrawalsSince(500L, _) >> new BigDecimal('4999.99')

        when: '0.014 HALF_UP-normalises to 0.01, so sum + amount = $5000.00 == cap (allowed under strict >)'
        def resp = controller.withdraw(req(new BigDecimal('0.014')), reqFor(10L))

        then: 'cap guard now agrees with the service: passes, and the service sees the normalised 0.01'
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('0.01'), _) >>
            new Transaction(id: 2L, status: 'PENDING')
        resp.statusCode.value() == 200
    }

    // ─── POST /api/wallet/connect/onboard ───────────────────────────
    // Stripe Connect payout onboarding (the KYC step). The controller
    // reuses the withdraw path's auth + ban + freeze posture and
    // delegates the Stripe work to StripeService.

    def "connectOnboard() anon: 401 (never reaches StripeService)"() {
        when:
        controller.connectOnboard(reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * stripeService.createConnectOnboardingLink(_)
    }

    def "connectOnboard() happy path: returns the onboarding URL from StripeService"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * stripeService.createConnectOnboardingLink(500L) >>
            [onboardingUrl: 'https://connect.stripe.com/setup/acct_1', live: true, accountId: 'acct_1']

        when:
        def resp = controller.connectOnboard(reqFor(10L))

        then:
        resp.statusCode.value() == 200
        resp.body.onboardingUrl == 'https://connect.stripe.com/setup/acct_1'
        resp.body.accountId == 'acct_1'
    }

    def "connectOnboard() frozen wallet: WALLET_FROZEN, never starts onboarding"() {
        given:
        def user = verifiedUser()
        def wallet = new Wallet(
            id: 500L, username: 'steam_111', balance: new BigDecimal('100'),
            currency: 'USD', frozen: true, frozenReason: 'fraud review'
        )
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet

        when:
        controller.connectOnboard(reqFor(10L))

        then:
        def e = thrown(BadRequestException)
        e.code == 'WALLET_FROZEN'
        0 * stripeService.createConnectOnboardingLink(_)
    }

    // ─── GET /api/wallet/connect/status ─────────────────────────────

    def "connectStatus() anon: 401"() {
        when:
        controller.connectStatus(reqFor(null))

        then:
        thrown(UnauthorizedException)
        0 * stripeService.connectStatus(_)
    }

    def "connectStatus() happy path: forwards the StripeService status envelope"() {
        given:
        def user = verifiedUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * stripeService.connectStatus(500L) >>
            [live: true, hasAccount: true, payoutsEnabled: true, onboardingNeeded: false, accountId: 'acct_1']

        when:
        def resp = controller.connectStatus(reqFor(10L))

        then:
        resp.statusCode.value() == 200
        resp.body.payoutsEnabled == true
        resp.body.onboardingNeeded == false
    }

    def "connectStatus() new user with no real wallet yet: clean 'needs onboarding' envelope, never touches StripeService"() {
        given: 'a signed-in user whose wallet resolves to the demo sentinel (id == 1)'
        def user = verifiedUser()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> null
        walletRepository.save(_) >> new Wallet(id: 1L)   // DEMO_WALLET_ID
        stripeService.isLive() >> true

        when:
        def resp = controller.connectStatus(reqFor(10L))

        then: 'reports onboardingNeeded without leaking the demo wallet or calling Stripe'
        resp.body.onboardingNeeded == true
        resp.body.payoutsEnabled == false
        resp.body.hasAccount == false
        0 * stripeService.connectStatus(_)
    }
}
