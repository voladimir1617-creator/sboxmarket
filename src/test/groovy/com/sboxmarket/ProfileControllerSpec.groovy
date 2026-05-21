package com.sboxmarket

import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.TotpService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Direct coverage for the 2FA / email-verification correctness fixes on
 * ProfileController. The `emailVerificationToken` column is overloaded
 * as the 2FA enrollment staging slot ("totp_pending:<secret>"); these
 * specs pin the guards that stop the two paths from corrupting each
 * other.
 *
 * Pinned behaviors:
 *   1. /email/verify rejects a staged "totp_pending:" value — a user
 *      can't flip emailVerified=true by pasting their TOTP secret.
 *   2. /email/resend refuses while 2FA enrollment is staged instead of
 *      silently clobbering the staged secret with a random token.
 *   3. /2fa/enroll refuses when 2FA is already on, and when a real
 *      email-verification token is in flight.
 *   4. /2fa/cancel clears a staged enrollment (and is a no-op when
 *      nothing is staged).
 *   5. /2fa/disable bumps sessionEpoch so the security downgrade kicks
 *      every live session.
 */
class ProfileControllerSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()
    EmailService        emailService        = Mock()
    // Real TotpService — generateSecret / verify are pure, no need to mock.
    TotpService         totpService         = new TotpService()

    @Subject
    ProfileController controller = new ProfileController(
        steamUserRepository: steamUserRepository,
        totpService:         totpService,
        emailService:        emailService
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void authedSession(long uid = 100L) {
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    // ── /email/verify vs. the 2FA staging slot ──────────────────────

    def "verifyEmail rejects a staged totp_pending value — no email-verify bypass"() {
        given: 'a user mid-2FA-enroll: the column holds the staged secret'
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            emailVerified: false, emailVerificationToken: 'totp_pending:JBSWY3DPEHPK3PXP')
        steamUserRepository.findById(100L) >> Optional.of(user)

        when: 'the user submits the literal staged string as a "token"'
        controller.verifyEmail([token: 'totp_pending:JBSWY3DPEHPK3PXP'], req)

        then: 'rejected — emailVerified stays false, secret untouched'
        def e = thrown(BadRequestException)
        e.code == 'INVALID_TOKEN'
        user.emailVerified == false
        user.emailVerificationToken == 'totp_pending:JBSWY3DPEHPK3PXP'
        0 * steamUserRepository.save(_)
    }

    def "verifyEmail still accepts a genuine hex token"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            emailVerified: false, emailVerificationToken: 'deadbeefdeadbeefdeadbeefdeadbeef',
            emailVerificationTokenExpiresAt: System.currentTimeMillis() + 60_000L)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def resp = controller.verifyEmail([token: 'deadbeefdeadbeefdeadbeefdeadbeef'], req)

        then:
        resp.statusCode.value() == 200
        user.emailVerified == true
        user.emailVerificationToken == null
        1 * steamUserRepository.save(user)
    }

    // ── /email/resend vs. the 2FA staging slot ──────────────────────

    def "resendVerification refuses while a 2FA enrollment is staged"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            emailVerified: false, emailVerificationToken: 'totp_pending:JBSWY3DPEHPK3PXP')
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        controller.resendVerification(req)

        then: 'refused — the staged secret is NOT clobbered, no email sent'
        def e = thrown(BadRequestException)
        e.code == 'TWOFA_IN_PROGRESS'
        user.emailVerificationToken == 'totp_pending:JBSWY3DPEHPK3PXP'
        0 * emailService.sendVerification(_, _)
    }

    // ── /2fa/enroll guards ──────────────────────────────────────────

    def "enroll2fa refuses when 2FA is already enabled"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', totpSecret: 'EXISTINGSECRET22')
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        controller.enroll2fa(req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'ALREADY_ENABLED'
        0 * steamUserRepository.save(_)
    }

    def "enroll2fa refuses while a real email-verification token is pending"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1',
            emailVerificationToken: 'deadbeefdeadbeefdeadbeefdeadbeef')
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        controller.enroll2fa(req)

        then: 'the in-flight email token would be destroyed — refuse instead'
        def e = thrown(BadRequestException)
        e.code == 'EMAIL_VERIFICATION_PENDING'
        user.emailVerificationToken == 'deadbeefdeadbeefdeadbeefdeadbeef'
        0 * steamUserRepository.save(_)
    }

    def "enroll2fa stages a fresh secret on a clean account"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com')
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def resp = controller.enroll2fa(req)

        then:
        resp.statusCode.value() == 200
        resp.body.secret != null
        resp.body.otpauthUrl.startsWith('otpauth://totp/')
        user.emailVerificationToken == "totp_pending:${resp.body.secret}"
        user.totpSecret == null   // not active until /2fa/confirm
        1 * steamUserRepository.save(user)
    }

    // ── /2fa/cancel ─────────────────────────────────────────────────

    def "cancel2faEnrollment clears a staged enrollment"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1',
            emailVerificationToken: 'totp_pending:JBSWY3DPEHPK3PXP')
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def resp = controller.cancel2faEnrollment(req)

        then:
        resp.body.cancelled == true
        resp.body.enabled == false
        user.emailVerificationToken == null
        1 * steamUserRepository.save(user)
    }

    def "cancel2faEnrollment is a no-op when nothing is staged"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailVerificationToken: null)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def resp = controller.cancel2faEnrollment(req)

        then:
        resp.body.cancelled == false
        0 * steamUserRepository.save(_)
    }

    def "cancel2faEnrollment leaves an active totpSecret untouched"() {
        given: 'a confirmed 2FA secret, no staged enrollment'
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1',
            totpSecret: 'LIVESECRET234567', emailVerificationToken: null)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def resp = controller.cancel2faEnrollment(req)

        then: 'cancel only touches the staging slot — disable is a separate, code-gated path'
        resp.body.cancelled == false
        resp.body.enabled == true
        user.totpSecret == 'LIVESECRET234567'
    }

    // ── /2fa/disable bumps sessionEpoch ─────────────────────────────

    def "disable2fa with a recovery code wipes 2FA and bumps sessionEpoch"() {
        given: 'a 2FA-enabled user with a known recovery code'
        authedSession(100L)
        def codes = totpService.generateBackupCodes()
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            emailVerified: true, totpSecret: 'JBSWY3DPEHPK3PXP',
            totpRecoveryCodes: codes.hashed as String, sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)
        emailService.canSendSecurityTo(user) >> true

        when: 'the user disables 2FA by burning a recovery code'
        def resp = controller.disable2fa([code: codes.plaintext[0] as String], req)

        then: '2FA off AND every other live session invalidated via the epoch bump'
        resp.body.enabled == false
        user.totpSecret == null
        user.sessionEpoch > 0L
        1 * steamUserRepository.save(user)
    }

    def "disable2fa is a no-op when 2FA was never enabled"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', totpSecret: null, sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def resp = controller.disable2fa([code: '000000'], req)

        then: 'no security downgrade happened — epoch is left alone'
        resp.body.enabled == false
        user.sessionEpoch == 0L
        0 * steamUserRepository.save(_)
    }

    // ── auth gate sanity ────────────────────────────────────────────

    def "2fa/cancel requires sign-in"() {
        given:
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        controller.cancel2faEnrollment(req)

        then:
        thrown(UnauthorizedException)
    }
}
