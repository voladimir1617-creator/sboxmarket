package com.sboxmarket

import com.sboxmarket.controller.WalletController
import com.sboxmarket.dto.request.DepositRequest
import com.sboxmarket.dto.request.WithdrawRequest
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TotpService
import com.sboxmarket.service.security.BanGuard
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression spec for the banGuard hole on every money-path endpoint
 * in WalletController. AdminService's contract is explicit ("every
 * state-changing endpoint in the app MUST call assertNotBanned(userId)
 * first" — header comment lines 40-42) and every sibling controller
 * (SteamInventoryController.listFromSteam, SupportController fraud-report,
 * BidService / BuyOrderService / OfferService write ops) honours it.
 *
 * /api/wallet/deposit, /api/wallet/withdraw, /api/wallet/withdraw/{id}/cancel
 * and /api/wallet/confirm-deposit were the matching hole — banUser()
 * flips `user.banned=true` and cancels listings + offers but DOES NOT
 * freeze the wallet (`wallet.frozen` stays false). So a banned user
 * with a fresh-baked session could still drain their wallet via
 * /withdraw before staff applied a separate freeze, fund it via
 * /deposit, replay cancel-races against staff rejections, or push
 * the synchronous confirm-deposit path. Pre-fix, every one of those
 * surfaced 200/expected-flow responses. The fix wires BanGuard into
 * the controller and calls `banGuard?.assertNotBanned(user.id)` right
 * after the auth gate on each write endpoint.
 *
 * Each spec below asserts:
 *   1) the banGuard call is dispatched with the live session uid, and
 *   2) the call's ForbiddenException short-circuits the rest of the
 *      handler — the downstream Stripe / repo work never runs.
 *
 * Read endpoints (/api/wallet, /api/wallet/spend, /api/wallet/transactions,
 * /api/wallet/transactions.csv) are deliberately NOT gated — a banned
 * user can still see their own balance + ledger so they can verify the
 * ban and open an appeal ticket.
 */
class WalletControllerBanGuardSpec extends Specification {

    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    StripeService         stripeService         = Mock()
    TotpService           totpService           = Mock()
    TextSanitizer         textSanitizer         = new TextSanitizer()
    BanGuard              banGuard              = Mock()

    @Subject
    WalletController controller = new WalletController(
        walletRepository:      walletRepository,
        transactionRepository: transactionRepository,
        steamUserRepository:   steamUserRepository,
        stripeService:         stripeService,
        totpService:           totpService,
        textSanitizer:         textSanitizer,
        banGuard:              banGuard,
        dailyWithdrawalCap:    new BigDecimal('5000')
    )

    private HttpServletRequest reqFor(Long uid) {
        def session = Mock(HttpSession)
        session.getAttribute('steamUserId') >> uid
        def req = Mock(HttpServletRequest)
        req.session >> session
        req
    }

    private SteamUser bannedUser(Long id = 10L) {
        new SteamUser(
            id:            id,
            steamId64:     '111',
            displayName:   'Bad Actor',
            email:         'bad@example.com',
            emailVerified: true,
            totpSecret:    null,
            banned:        true,
            banReason:     'chargeback fraud'
        )
    }

    private SteamUser cleanUser(Long id = 10L) {
        new SteamUser(
            id:            id,
            steamId64:     '111',
            displayName:   'Alice',
            email:         'alice@example.com',
            emailVerified: true,
            totpSecret:    null,
            banned:        false
        )
    }

    private Wallet walletFor(BigDecimal balance = new BigDecimal('4000')) {
        new Wallet(id: 500L, username: 'steam_111', balance: balance, currency: 'USD')
    }

    private WithdrawRequest withdrawReq(BigDecimal amount, String dest = 'acct_x') {
        def r = new WithdrawRequest()
        r.amount = amount
        r.destination = dest
        r
    }

    // ─── /api/wallet/deposit ───────────────────────────────────────────

    def "deposit() banned user: ForbiddenException — never reaches StripeService.createDepositSession"() {
        given: 'a banned user with a live session'
        def user = bannedUser()
        steamUserRepository.findById(10L) >> Optional.of(user)
        // The throw is what the real BanGuard does for user.banned=true.
        1 * banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("Your account is banned: chargeback fraud") }
        def body = new DepositRequest(amount: new BigDecimal('50'))

        when:
        controller.deposit(body, reqFor(10L))

        then: 'rejected at the gate — no Stripe call, no wallet lookup'
        thrown(ForbiddenException)
        0 * stripeService.createDepositSession(*_)
        // The wallet lookup chain (findByUsername / findById) must not
        // even fire — the guard short-circuits before currentWallet().
        0 * walletRepository.findByUsername(_)
        0 * walletRepository.findById(_)
    }

    def "deposit() non-banned user still passes the guard and reaches StripeService (regression guard)"() {
        given: 'a clean user — banGuard is a no-op'
        def user = cleanUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * banGuard.assertNotBanned(10L) >> { /* not banned — return cleanly */ }
        def body = new DepositRequest(amount: new BigDecimal('75'))

        when:
        controller.deposit(body, reqFor(10L))

        then: 'the guard ran but allowed the call to proceed to Stripe'
        1 * stripeService.createDepositSession(500L, new BigDecimal('75')) >>
            [checkoutUrl: 'https://checkout.stripe.com/x', sessionId: 'cs_abc']
    }

    // ─── /api/wallet/withdraw ──────────────────────────────────────────

    def "withdraw() banned user: ForbiddenException — never reaches balance/dispute/cap/totp checks or StripeService"() {
        given: 'a banned user with a live session and a TOTP secret'
        def user = bannedUser()
        user.totpSecret = 'JBSWY3DPEHPK3PXP'  // would normally be required and burned
        def wallet = walletFor(new BigDecimal('1000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        // Pre-fix, this body would have debited the wallet $50 and emitted
        // a PENDING WITHDRAW row before staff noticed the ban.
        1 * banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("Your account is banned: chargeback fraud") }
        def r = withdrawReq(new BigDecimal('50'))
        r.totpCode = '123456'

        when:
        controller.withdraw(r, reqFor(10L))

        then: 'ForbiddenException short-circuits the entire handler'
        thrown(ForbiddenException)
        // Critical: the downstream gate ladder must not run at all —
        // no balance check (no wallet lookup), no dispute count, no
        // 24h cap sum, no TOTP burn, no Stripe withdrawal request.
        0 * walletRepository.findByUsername(_)
        0 * walletRepository.findById(_)
        0 * transactionRepository.countActiveDisputedDeposits(_)
        0 * transactionRepository.sumWithdrawalsSince(*_)
        0 * totpService.verify(*_)
        0 * steamUserRepository.save(_)
        0 * stripeService.requestWithdrawal(*_)
    }

    def "withdraw() non-banned user still passes the guard and reaches StripeService.requestWithdrawal (regression guard)"() {
        given: 'a clean user with a verified email and no TOTP'
        def user = cleanUser()
        def wallet = walletFor(new BigDecimal('1000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.sumWithdrawalsSince(_, _) >> BigDecimal.ZERO
        1 * banGuard.assertNotBanned(10L) >> { /* not banned */ }

        when:
        def resp = controller.withdraw(withdrawReq(new BigDecimal('50')), reqFor(10L))

        then: 'the guard ran but allowed the withdraw to proceed end-to-end'
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('50'), _) >>
            new com.sboxmarket.model.Transaction(id: 7L, status: 'PENDING')
        resp.statusCode.value() == 200
    }

    // ─── /api/wallet/withdraw/{id}/cancel ──────────────────────────────

    def "cancelWithdraw() banned user: ForbiddenException — never reaches StripeService.cancelPendingWithdrawal"() {
        given: 'a banned user with a live session'
        def user = bannedUser()
        steamUserRepository.findById(10L) >> Optional.of(user)
        1 * banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("Your account is banned: chargeback fraud") }

        when:
        controller.cancelWithdraw(9L, reqFor(10L))

        then: 'the cancel never runs — no wallet credit, no tx flip'
        thrown(ForbiddenException)
        0 * walletRepository.findByUsername(_)
        0 * walletRepository.findById(_)
        0 * stripeService.cancelPendingWithdrawal(*_)
    }

    def "cancelWithdraw() non-banned user still passes the guard and reaches StripeService (regression guard)"() {
        given:
        def user = cleanUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        1 * banGuard.assertNotBanned(10L) >> { /* not banned */ }

        when:
        def resp = controller.cancelWithdraw(9L, reqFor(10L))

        then:
        1 * stripeService.cancelPendingWithdrawal(500L, 9L) >>
            [status: 'CANCELLED', refunded: new BigDecimal('25')]
        resp.statusCode.value() == 200
    }

    // ─── /api/wallet/confirm-deposit ───────────────────────────────────

    def "confirmDeposit() banned user: ForbiddenException — never reaches StripeService.completeDeposit"() {
        given: 'a banned user with a live session attempting the synchronous confirm path'
        def user = bannedUser()
        steamUserRepository.findById(10L) >> Optional.of(user)
        1 * banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("Your account is banned: chargeback fraud") }

        when:
        controller.confirmDeposit('cs_abc', reqFor(10L))

        then: 'the synchronous credit path never runs (webhook still owns the eventual credit)'
        thrown(ForbiddenException)
        0 * stripeService.completeDeposit(_)
        0 * walletRepository.findByUsername(_)
        0 * walletRepository.findById(_)
    }

    def "confirmDeposit() non-banned user still passes the guard and reaches StripeService (regression guard)"() {
        given:
        def user = cleanUser()
        def wallet = walletFor(new BigDecimal('175.00'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        1 * banGuard.assertNotBanned(10L) >> { /* not banned */ }
        1 * stripeService.completeDeposit('cs_abc')

        when:
        def resp = controller.confirmDeposit('cs_abc', reqFor(10L))

        then:
        resp.statusCode.value() == 200
        resp.body.newBalance == new BigDecimal('175.00')
    }

    // ─── Cross-endpoint contract pin ───────────────────────────────────

    def "banGuard is injected required=false so older test wiring without it still compiles + runs"() {
        given: 'a controller constructed WITHOUT banGuard (mirrors the legacy WalletControllerSpec wiring)'
        def unguarded = new WalletController(
            walletRepository:      walletRepository,
            transactionRepository: transactionRepository,
            steamUserRepository:   steamUserRepository,
            stripeService:         stripeService,
            totpService:           totpService,
            textSanitizer:         textSanitizer,
            dailyWithdrawalCap:    new BigDecimal('5000')
            // no banGuard — field defaults to null
        )
        def user = cleanUser()
        def wallet = walletFor()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet

        when: 'a deposit fires against a controller with null banGuard'
        unguarded.deposit(new DepositRequest(amount: new BigDecimal('25')), reqFor(10L))

        then: 'the ?. short-circuit makes the missing collaborator a no-op — Stripe is reached'
        // The Mock banGuard on `this` is NOT touched — `unguarded` has none.
        0 * banGuard.assertNotBanned(_)
        1 * stripeService.createDepositSession(500L, new BigDecimal('25')) >> [sessionId: 'cs_x']
    }
}
