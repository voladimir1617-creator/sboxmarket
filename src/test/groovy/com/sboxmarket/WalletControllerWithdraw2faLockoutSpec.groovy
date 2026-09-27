package com.sboxmarket

import com.sboxmarket.controller.WalletController
import com.sboxmarket.dto.request.WithdrawRequest
import com.sboxmarket.exception.BadRequestException
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
 * Withdraw-path 2FA brute-force lockout (wave 157).
 *
 * `/api/wallet/withdraw` verifies a fresh 6-digit TOTP before releasing a
 * payout. A 6-digit code is a 1,000,000 space; the per-user /api/wallet
 * rate limit alone permits ~172k guesses/day, so WITHOUT an attempt cap a
 * hijacked session could brute-force the code in ~48h and drain to the
 * daily cap. ProfileController already locks /2fa/disable +
 * /2fa/regenerate-codes (waves 120/149); this pins the SAME per-user
 * lockout — now shared in {@link TotpService} — on the money-out gate.
 *
 * The lockout counter lives in a REAL TotpService instance so across-call
 * state accumulates; the mock's three lockout methods delegate to it and
 * `verify` is stubbed so the "wrong code" outcome is deterministic (no
 * dependency on the wall-clock TOTP window).
 */
class WalletControllerWithdraw2faLockoutSpec extends Specification {

    static final String SECRET = 'JBSWY3DPEHPK3PXP'

    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    StripeService         stripeService         = Mock()
    TextSanitizer         textSanitizer         = new TextSanitizer()  // real impl
    // Real instance backs the shared lockout counter so its state persists
    // across the repeated withdraw() calls within a single feature; the
    // mock below delegates its lockout surface to it.
    TotpService           lockoutState          = new TotpService()
    TotpService           totpService           = Mock()

    @Subject
    WalletController controller = new WalletController(
        walletRepository:      walletRepository,
        transactionRepository: transactionRepository,
        steamUserRepository:   steamUserRepository,
        stripeService:         stripeService,
        totpService:           totpService,
        textSanitizer:         textSanitizer,
        dailyWithdrawalCap:    null   // skip the cap query — irrelevant to the 2FA gate
    )

    def setup() {
        // Wire the mock's lockout surface to the real counter; `verify` is
        // stubbed per-feature so each scenario controls the code outcome.
        totpService.lockoutRemainingMs(_) >> { Long uid -> lockoutState.lockoutRemainingMs(uid) }
        totpService.recordFail(_)         >> { Long uid -> lockoutState.recordFail(uid) }
        totpService.clearFails(_)         >> { Long uid -> lockoutState.clearFails(uid) }
    }

    private HttpServletRequest reqFor(Long uid) {
        def session = Mock(HttpSession)
        session.getAttribute('steamUserId') >> uid
        def req = Mock(HttpServletRequest)
        req.session >> session
        req
    }

    private SteamUser user2fa(Long id = 10L) {
        new SteamUser(id: id, steamId64: '111', displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true, totpSecret: SECRET)
    }

    private Wallet walletFor(BigDecimal balance = new BigDecimal('4000')) {
        new Wallet(id: 500L, username: 'steam_111', balance: balance, currency: 'USD')
    }

    private WithdrawRequest req(String code, BigDecimal amount = new BigDecimal('50')) {
        def r = new WithdrawRequest()
        r.amount = amount
        r.destination = 'acct_x'
        r.totpCode = code
        r
    }

    def "withdraw 2FA gate locks the user out after MAX_2FA_FAILS consecutive bad codes"() {
        given:
        def user = user2fa()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> walletFor()
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        totpService.verify(SECRET, _, _) >> -1L   // every code is wrong

        when: 'exactly MAX_2FA_FAILS wrong codes are submitted'
        def codes = []
        TotpService.MAX_2FA_FAILS.times {
            try { controller.withdraw(req('000000'), reqFor(10L)) }
            catch (BadRequestException e) { codes << e.code }
        }

        then: 'each is rejected as a normal invalid code — no payout'
        codes == ['TOTP_INVALID'] * TotpService.MAX_2FA_FAILS
        0 * stripeService.requestWithdrawal(*_)

        when: 'the next attempt arrives after the cap is reached'
        controller.withdraw(req('000000'), reqFor(10L))

        then: 'the gate is now locked — TOTP_LOCKED, still no payout'
        def e = thrown(BadRequestException)
        e.code == 'TOTP_LOCKED'
        e.message.toLowerCase().contains('too many')
        0 * stripeService.requestWithdrawal(*_)
    }

    def "a locked user is rejected WITHOUT the code being verified (no oracle, no burned code)"() {
        given: 'the per-user lockout is already tripped'
        def user = user2fa()
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> walletFor()
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        TotpService.MAX_2FA_FAILS.times { lockoutState.recordFail(10L) }

        when:
        controller.withdraw(req('123456'), reqFor(10L))

        then: 'rejected before verify runs — a locked attacker gets no verify oracle and burns no code'
        def e = thrown(BadRequestException)
        e.code == 'TOTP_LOCKED'
        0 * totpService.verify(*_)
        0 * steamUserRepository.save(_)
        0 * stripeService.requestWithdrawal(*_)
    }

    def "a correct code after some failures clears the counter and completes the withdrawal"() {
        given: 'one failure short of the lockout, then a valid code'
        def user = user2fa()
        def wallet = walletFor(new BigDecimal('1000'))
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        (TotpService.MAX_2FA_FAILS - 1).times { lockoutState.recordFail(10L) }
        totpService.verify(SECRET, '654321', _) >> 99L

        when:
        def resp = controller.withdraw(req('654321'), reqFor(10L))

        then: 'the payout proceeds'
        1 * stripeService.requestWithdrawal(500L, new BigDecimal('50'), _) >>
            new Transaction(id: 7L, status: 'PENDING')
        resp.statusCode.value() == 200

        and: 'the failure counter truly reset — a single later failure does NOT re-lock'
        lockoutState.lockoutRemainingMs(10L) == 0L
        lockoutState.recordFail(10L)
        lockoutState.lockoutRemainingMs(10L) == 0L
    }
}
