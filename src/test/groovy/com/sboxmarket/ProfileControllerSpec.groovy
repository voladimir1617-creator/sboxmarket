package com.sboxmarket

import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.TextSanitizer
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
 *   3. PUT /email refuses while 2FA enrollment is staged — it is the
 *      fourth writer of the overloaded column and must not clobber the
 *      staged secret either.
 *   4. /2fa/enroll refuses when 2FA is already on, and when a real
 *      email-verification token is in flight; re-enroll before confirm
 *      is allowed.
 *   5. /2fa/cancel clears a staged enrollment (and is a no-op when
 *      nothing is staged).
 *   6. /2fa/confirm transitions the staging slot to a live secret only
 *      on a valid code; a bad code leaves the enrollment recoverable.
 *   7. /2fa/disable bumps sessionEpoch so the security downgrade kicks
 *      every live session.
 *   8. The enrolment state machine has no permanent wedge — /2fa/cancel
 *      always restores the email-verification path.
 */
class ProfileControllerSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()
    EmailService        emailService        = Mock()
    // Real TotpService — generateSecret / verify are pure, no need to mock.
    TotpService         totpService         = new TotpService()
    // Real TextSanitizer — clean() is pure (HTML strip + length cap), so a
    // real instance is simpler than stubbing it for the setEmail specs.
    TextSanitizer       textSanitizer       = new TextSanitizer()

    @Subject
    ProfileController controller = new ProfileController(
        steamUserRepository: steamUserRepository,
        totpService:         totpService,
        emailService:        emailService,
        textSanitizer:       textSanitizer
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void authedSession(long uid = 100L) {
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    /**
     * Compute the TOTP code an authenticator app would currently display
     * for a Base32 secret. Decodes the secret through TotpService's own
     * private {@code unbase32} (via reflection) so the test stays in
     * lockstep with the production codec, then calls the public
     * {@code codeFor}. Used to drive the real /2fa/confirm and
     * /2fa/regenerate-codes paths end-to-end.
     */
    private String currentTotpCode(String secretBase32) {
        def m = TotpService.getDeclaredMethod('unbase32', String)
        m.setAccessible(true)
        byte[] decoded = m.invoke(null, secretBase32) as byte[]
        long step = ((System.currentTimeMillis() / 1000L) / 30L) as long
        totpService.codeFor(decoded, step)
    }

    /**
     * A 6-digit code guaranteed NOT to verify against {@code secretBase32}
     * right now. Derived from the real current code by walking each ±1-step
     * code in the verify window and returning the first value that collides
     * with none of them — so the "reject a bad code" specs can never flake
     * on a 1-in-a-million coincidental match.
     */
    private String wrongTotpCode(String secretBase32) {
        def m = TotpService.getDeclaredMethod('unbase32', String)
        m.setAccessible(true)
        byte[] decoded = m.invoke(null, secretBase32) as byte[]
        long curStep = ((System.currentTimeMillis() / 1000L) / 30L) as long
        def valid = [(curStep - 1L), curStep, (curStep + 1L)]
            .collect { totpService.codeFor(decoded, it as long) } as Set
        for (int n = 0; n < 1_000_000; n++) {
            def candidate = String.format('%06d', n)
            if (!valid.contains(candidate)) return candidate
        }
        // Unreachable — the window holds at most 3 of 1,000,000 codes.
        '000000'
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

    // Adversarial bug hunt — timing-side-channel guard. The token compare
    // must be length-independent so an attacker probing the /email/verify
    // endpoint can't recover a 32-hex token character-by-character within
    // its 24h validity window. Differing-length and same-length-wrong
    // candidates must both be rejected with no observable difference
    // (and no save).
    def "verifyEmail rejects wrong tokens of any length without saving — constant-time guard"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            emailVerified: false, emailVerificationToken: 'deadbeefdeadbeefdeadbeefdeadbeef',
            emailVerificationTokenExpiresAt: System.currentTimeMillis() + 60_000L)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when: 'submit a token that shares a long prefix but differs at the end'
        controller.verifyEmail([token: candidate], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_TOKEN'
        user.emailVerified == false
        user.emailVerificationToken == 'deadbeefdeadbeefdeadbeefdeadbeef'
        0 * steamUserRepository.save(_)

        where:
        candidate << [
            'deadbeefdeadbeefdeadbeefdeadbeeX', // same length, last char wrong
            'deadbeefdeadbeefdeadbeefdeadbe',   // shorter (prefix of real token)
            'deadbeefdeadbeefdeadbeefdeadbeefXX', // longer (real token + suffix)
            '',                                  // empty
        ]
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

    // ── PUT /email vs. the 2FA staging slot ─────────────────────────

    def "setEmail refuses while a 2FA enrollment is staged — staged secret not clobbered"() {
        given: 'a user mid-2FA-enroll changes their email'
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'old@b.com',
            emailVerified: false, emailVerificationToken: 'totp_pending:JBSWY3DPEHPK3PXP')
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        controller.setEmail([email: 'new@b.com'], req)

        then: 'refused — the staged secret survives, nothing persisted, no mail sent'
        def e = thrown(BadRequestException)
        e.code == 'TWOFA_IN_PROGRESS'
        user.email == 'old@b.com'
        user.emailVerificationToken == 'totp_pending:JBSWY3DPEHPK3PXP'
        0 * steamUserRepository.save(_)
        0 * emailService.sendVerification(_, _)
    }

    def "setEmail still succeeds on an account with no 2FA enrollment staged"() {
        given: 'a clean account, no staged 2FA, email being set for the first time'
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailVerificationToken: null)
        steamUserRepository.findById(100L) >> Optional.of(user)
        steamUserRepository.findByEmailIgnoreCase('fresh@b.com') >> []

        when:
        def resp = controller.setEmail([email: 'fresh@b.com'], req)

        then: 'a genuine hex verification token is minted (never the totp_pending form)'
        resp.statusCode.value() == 200
        user.email == 'fresh@b.com'
        user.emailVerified == false
        user.emailVerificationToken != null
        !user.emailVerificationToken.startsWith('totp_pending:')
        user.emailVerificationTokenExpiresAt != null
        1 * steamUserRepository.save(user)
        1 * emailService.sendVerification('fresh@b.com', _)
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

    // ── /2fa/confirm — the staging slot → live secret transition ────

    def "confirm2fa rejects when no enrollment is staged"() {
        given: 'a clean account, /2fa/confirm called without /2fa/enroll first'
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailVerificationToken: null)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        controller.confirm2fa([code: '123456'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'NOT_ENROLLING'
        0 * steamUserRepository.save(_)
    }

    def "confirm2fa rejects when a real email-verification token is in the slot"() {
        given: 'the column holds a genuine hex token, not a staged 2FA secret'
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1',
            emailVerificationToken: 'deadbeefdeadbeefdeadbeefdeadbeef')
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        controller.confirm2fa([code: '123456'], req)

        then: 'the hex token is not a totp_pending: value — treated as not-enrolling'
        def e = thrown(BadRequestException)
        e.code == 'NOT_ENROLLING'
        user.emailVerificationToken == 'deadbeefdeadbeefdeadbeefdeadbeef'
        0 * steamUserRepository.save(_)
    }

    def "confirm2fa rejects a wrong code and leaves the enrollment staged"() {
        given: 'a staged enrollment with a known secret'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def user = new SteamUser(id: 100L, steamId64: '1',
            emailVerificationToken: "totp_pending:${secret}")
        steamUserRepository.findById(100L) >> Optional.of(user)

        when: 'a code guaranteed not to verify is submitted'
        controller.confirm2fa([code: wrongTotpCode(secret)], req)

        then: 'rejected — and crucially the staging slot is intact so the user can retry'
        def e = thrown(BadRequestException)
        e.code == 'INVALID_CODE'
        user.totpSecret == null
        user.emailVerificationToken == "totp_pending:${secret}"
        0 * steamUserRepository.save(_)
    }

    def "full enrollment lifecycle: enroll → confirm flips totpSecret on and clears the staging slot"() {
        given: 'a clean, email-verified account'
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            emailVerified: true, emailVerificationToken: null)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when: 'step 1 — enroll stages a secret'
        def enrollResp = controller.enroll2fa(req)

        then:
        enrollResp.statusCode.value() == 200
        user.emailVerificationToken == "totp_pending:${enrollResp.body.secret}"
        user.totpSecret == null

        when: 'step 2 — confirm with a genuine code derived from the staged secret'
        def staged = user.emailVerificationToken.substring('totp_pending:'.length())
        // Drive verify() once to discover the matching step, then compute
        // the code for it — the real /2fa/confirm path the user follows.
        def code = currentTotpCode(staged)
        def confirmResp = controller.confirm2fa([code: code], req)

        then: '2FA is now live, the staging slot is cleared, recovery codes minted'
        confirmResp.statusCode.value() == 200
        confirmResp.body.enabled == true
        (confirmResp.body.recoveryCodes as List).size() == 10
        user.totpSecret == staged
        user.emailVerificationToken == null
        user.lastTotpStep != null
    }

    def "no permanent wedge: a staged enrollment can always be escaped via /2fa/cancel, restoring email verify"() {
        given: 'a user staged 2FA mid-flow; email is unverified'
        // Distinct uid — the success path below writes the static
        // per-user resend-cooldown map, so an isolated id keeps this
        // spec immune to cross-test cooldown pollution.
        authedSession(7100L)
        def user = new SteamUser(id: 7100L, steamId64: '1', email: 'a@b.com',
            emailVerified: false, emailVerificationToken: 'totp_pending:JBSWY3DPEHPK3PXP')
        steamUserRepository.findById(7100L) >> Optional.of(user)

        and: 'while staged, email verify + resend are both blocked'
        when:
        controller.verifyEmail([token: 'anything'], req)
        then:
        thrown(BadRequestException)

        when:
        controller.resendVerification(req)
        then:
        thrown(BadRequestException)

        when: 'the user cancels the staged enrollment'
        controller.cancel2faEnrollment(req)

        then: 'the staging slot is cleared — the user is no longer wedged'
        user.emailVerificationToken == null

        when: 'a fresh real email-verification token is now mintable via resend'
        // emailService is a Mock — smtpReady defaults to false, so the
        // resend response echoes the freshly-minted token back.
        def resp = controller.resendVerification(req)

        then: 'resend works again — a genuine hex token, not a totp_pending: value'
        resp.statusCode.value() == 200
        resp.body.resent == true
        user.emailVerificationToken != null
        !user.emailVerificationToken.startsWith('totp_pending:')
        1 * emailService.sendVerification('a@b.com', _)
    }

    def "re-enroll before confirm is allowed and replaces the staged secret"() {
        given: 'a staged enrollment that the user wants to restart (e.g. lost the QR)'
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            emailVerificationToken: 'totp_pending:OLDSECRETOLDSEC2')
        steamUserRepository.findById(100L) >> Optional.of(user)

        when: 'enroll is called again on top of the staged value'
        def resp = controller.enroll2fa(req)

        then: 'a totp_pending: value is safe to overwrite — fresh secret staged, no error'
        resp.statusCode.value() == 200
        user.emailVerificationToken == "totp_pending:${resp.body.secret}"
        resp.body.secret != 'OLDSECRETOLDSEC2'
        user.totpSecret == null
        1 * steamUserRepository.save(user)
    }

    // ── /2fa/regenerate-codes ───────────────────────────────────────

    def "regenerateRecoveryCodes rejects when 2FA is not enabled"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', totpSecret: null)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        controller.regenerateRecoveryCodes([code: '123456'], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'NOT_ENROLLED'
        0 * steamUserRepository.save(_)
    }

    def "regenerateRecoveryCodes rejects a bad code without replacing the stored set"() {
        given: 'a 2FA-enabled user with an existing recovery set'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def existing = totpService.generateBackupCodes()
        def user = new SteamUser(id: 100L, steamId64: '1', totpSecret: secret,
            totpRecoveryCodes: existing.hashed as String)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when: 'a code guaranteed not to verify is supplied'
        controller.regenerateRecoveryCodes([code: wrongTotpCode(secret)], req)

        then: 'rejected — the stored hash set is untouched'
        def e = thrown(BadRequestException)
        e.code == 'INVALID_CODE'
        user.totpRecoveryCodes == (existing.hashed as String)
        0 * steamUserRepository.save(_)
    }

    def "regenerateRecoveryCodes with a live code replaces the set and persists lastTotpStep"() {
        given: 'a 2FA-enabled user'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def existing = totpService.generateBackupCodes()
        def user = new SteamUser(id: 100L, steamId64: '1', totpSecret: secret,
            lastTotpStep: null, totpRecoveryCodes: existing.hashed as String)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def resp = controller.regenerateRecoveryCodes([code: currentTotpCode(secret)], req)

        then: 'a fresh set of 10 codes is returned; the step is persisted for replay defence'
        resp.statusCode.value() == 200
        (resp.body.recoveryCodes as List).size() == 10
        user.totpRecoveryCodes != (existing.hashed as String)
        user.lastTotpStep != null
        1 * steamUserRepository.save(user)
    }

    // ── /email-notifications boolean coercion ───────────────────────
    //
    // Regression guard: the `enabled` field arrives in an untyped Map, so
    // Jackson hands it back as a String when the client sends a JSON
    // string. A bare `value as Boolean` makes EVERY non-empty string
    // truthy, so `{"enabled":"false"}` used to silently RE-ENABLE
    // notifications a user was deliberately trying to mute. Fixed via
    // parseEnabledFlag (mirrors SellerFollowController.parseMutedFlag).

    def "updateEmailNotifications honours a real boolean false"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailNotificationsEnabled: true)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def resp = controller.updateEmailNotifications([enabled: false], req)

        then:
        resp.statusCode.value() == 200
        user.emailNotificationsEnabled == false
        resp.body.emailNotificationsEnabled == false
        1 * steamUserRepository.save(user)
    }

    def "updateEmailNotifications honours the string 'false' — does NOT re-enable"() {
        given: 'a client that JSON-encodes the flag as a string'
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailNotificationsEnabled: true)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when: 'the user tries to mute via {"enabled":"false"}'
        def resp = controller.updateEmailNotifications([enabled: 'false'], req)

        then: 'the string is parsed correctly — notifications actually turn OFF'
        resp.statusCode.value() == 200
        user.emailNotificationsEnabled == false
        resp.body.emailNotificationsEnabled == false
        1 * steamUserRepository.save(user)
    }

    def "updateEmailNotifications honours the string 'true'"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailNotificationsEnabled: false)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        def resp = controller.updateEmailNotifications([enabled: 'true'], req)

        then:
        resp.statusCode.value() == 200
        user.emailNotificationsEnabled == true
        1 * steamUserRepository.save(user)
    }

    def "updateEmailNotifications rejects a non-boolean junk value"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailNotificationsEnabled: true)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when: 'a value that is neither a boolean nor a true/false string'
        controller.updateEmailNotifications([enabled: 'yes'], req)

        then: 'rejected — the stored preference is not touched'
        def e = thrown(BadRequestException)
        e.code == 'INVALID_FIELD'
        user.emailNotificationsEnabled == true
        0 * steamUserRepository.save(_)
    }

    def "updateEmailNotifications rejects a missing 'enabled' field"() {
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailNotificationsEnabled: true)
        steamUserRepository.findById(100L) >> Optional.of(user)

        when:
        controller.updateEmailNotifications([:], req)

        then:
        def e = thrown(BadRequestException)
        e.code == 'MISSING_FIELD'
        0 * steamUserRepository.save(_)
    }

    def "updateEmailNotifications requires sign-in"() {
        given:
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> null

        when:
        controller.updateEmailNotifications([enabled: false], req)

        then:
        thrown(UnauthorizedException)
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
