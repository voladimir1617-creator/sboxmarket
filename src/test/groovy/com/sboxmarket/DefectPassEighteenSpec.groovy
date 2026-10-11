package com.sboxmarket

import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.CsrService
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TotpService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 18: the 2FA lockout shared between withdraw and the 2FA
 * settings, backup codes typed without hyphens, the CSR lookup's lifetime
 * chargeback count, old active buy orders on the Buy Orders tab, and the
 * admin Users search honouring the role / banned chips.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassEighteenSpec extends Specification {

    @Autowired CsrService csrService
    @Autowired AdminService adminService
    @Autowired BuyOrderService buyOrderService
    @Autowired SteamUserRepository userRepo
    @Autowired WalletRepository walletRepo
    @Autowired TransactionRepository txRepo
    @Autowired BuyOrderRepository buyOrderRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(Map extra = [:]) {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser([steamId64: '7656118' + tail.padLeft(10, '0'),
            displayName: "Pass18-${seq}-${uniq}", role: 'USER'] + extra))
    }

    // ── 2FA lockout is shared ──────────────────────────────────────

    private Map twoFaHarness(long uid) {
        def totp = new TotpService()
        SteamUserRepository repo = Mock()
        def secret = totp.generateSecret()
        def u = new SteamUser(id: uid, steamId64: '1', email: 'a@b.com', totpSecret: secret,
            totpRecoveryCodes: totp.generateBackupCodes().hashed as String, sessionEpoch: 0L)
        repo.findById(uid) >> Optional.of(u)
        def controller = new ProfileController(steamUserRepository: repo, totpService: totp,
            emailService: Mock(EmailService), textSanitizer: new TextSanitizer())
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req.session = session
        [totp: totp, controller: controller, req: req, user: u]
    }

    def "a user locked out on withdraw can't keep guessing on 2FA disable"() {
        given:
        long uid = 9_180_000_000L + (System.nanoTime() % 1_000_000L)
        def h = twoFaHarness(uid)
        5.times { h.totp.recordFail(uid) }

        when:
        h.controller.disable2fa([code: 'nope'], h.req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'TWOFA_LOCKED'
        h.user.totpSecret != null
    }

    def "failed 2FA disables count toward the withdraw lockout"() {
        given:
        long uid = 9_181_000_000L + (System.nanoTime() % 1_000_000L)
        def h = twoFaHarness(uid)

        when:
        5.times {
            try { h.controller.disable2fa([code: 'nope'], h.req) } catch (BadRequestException ignored) {}
        }

        then:
        h.totp.lockoutRemainingMs(uid) > 0L
    }

    // ── Backup codes ───────────────────────────────────────────────

    def "a backup code typed without hyphens or with spaces still works"() {
        given:
        def totp = new TotpService()
        def codes = totp.generateBackupCodes()
        def plain = codes.plaintext[0] as String

        expect:
        totp.consumeRecoveryCode(codes.hashed as String, plain.replace('-', '')) != null
        totp.consumeRecoveryCode(codes.hashed as String, plain.replace('-', ' ').toUpperCase()) != null
        totp.consumeRecoveryCode(codes.hashed as String, plain) != null
        totp.consumeRecoveryCode(codes.hashed as String, 'zzzzzzzzzzzz') == null
    }

    // ── CSR lookup ─────────────────────────────────────────────────

    def "the CSR lookup's lifetime chargeback count sees past the last 10 transactions"() {
        given:
        def u = user()
        def w = walletRepo.save(new Wallet(username: "steam_${u.steamId64}", balance: BigDecimal.ZERO))
        long base = System.currentTimeMillis() - 1_000_000L
        txRepo.save(new Transaction(walletId: w.id, type: 'DEPOSIT', status: 'COMPLETED',
            amount: new BigDecimal('50.00'), description: 'Card deposit DISPUTE_CLEARED', createdAt: base))
        12.times { i ->
            txRepo.save(new Transaction(walletId: w.id, type: 'DEPOSIT', status: 'COMPLETED',
                amount: new BigDecimal('1.00'), createdAt: base + 1000L * (i + 1)))
        }

        when:
        def match = csrService.lookupUser(u.displayName).matches.find { it.id == u.id }

        then:
        match.lifetimeDisputes == 1L
    }

    // ── Buy orders tab ─────────────────────────────────────────────

    def "an old active buy order stays on the Buy Orders tab behind 300 newer finished ones"() {
        given:
        def u = user()
        long base = System.currentTimeMillis() - 10_000_000L
        def old = buyOrderRepo.save(new BuyOrder(buyerUserId: u.id, itemName: 'Old active', category: 'Hat',
            maxPrice: new BigDecimal('1.00'), status: 'ACTIVE', createdAt: base))
        buyOrderRepo.saveAll((1..BuyOrderService.BUY_ORDER_LIST_CAP).collect { i ->
            new BuyOrder(buyerUserId: u.id, itemName: "Done ${i}", category: 'Hat',
                maxPrice: new BigDecimal('1.00'), status: 'CANCELLED', createdAt: base + i)
        })

        when:
        def rows = buyOrderService.listForBuyer(u.id)

        then:
        rows.size() == BuyOrderService.BUY_ORDER_LIST_CAP
        rows[0].id == old.id
    }

    // ── Admin Users search ─────────────────────────────────────────

    def "admin user search honours the Banned chip"() {
        given:
        def tag = "p18s${uniq}"
        def banned = user(displayName: "${tag}-banned", banned: true)
        user(displayName: "${tag}-ok")

        expect:
        adminService.listUsers(tag, null, true)*.id == [banned.id]
        adminService.listUsers(tag, null, null).size() == 2
    }
}
