package com.sboxmarket

import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
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
 * Per-user brute-force lockout on the 2FA verification endpoints.
 *
 * The global /api/profile IP-level rate limit allows 20 writes / 10s, which
 * lets a stolen-session attacker submit ~172k TOTP guesses per day. With
 * 3 valid 6-digit codes in any ±1-step window (3 / 1,000,000), that is a
 * ~50% per-48h brute-force success probability per stolen session. This
 * lockout closes the gap by tracking failed verifications per user id and
 * refusing further attempts after 5 failures inside a 15-minute window.
 *
 * Pinned behaviours:
 *   1. /2fa/disable locks the user out after 5 wrong codes — the 6th
 *      attempt is rejected with TWOFA_LOCKED *before* any verification
 *      runs (no signal, no side-effect against the stored secret /
 *      recovery hashes).
 *   2. A successful code resets the failure counter — a user who fumbles
 *      4 codes then enters the right one is not punished on a later
 *      legit attempt.
 *   3. /2fa/regenerate-codes shares the same per-user counter — an
 *      attacker who pivots from /2fa/disable to /2fa/regenerate-codes
 *      cannot reset the lockout by swapping endpoints.
 *   4. A successful recovery-code disable also clears the counter so
 *      the legitimate "lost my phone, burned a backup code" path is
 *      never gated by a stale counter from earlier mistypes.
 */
class ProfileController2faBruteForceSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()
    EmailService        emailService        = Mock()
    TotpService         totpService         = new TotpService()
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

    /** Clear the static lockout map between specs so a counter accumulated
     *  by an earlier test in the same JVM run can't bleed into the next.
     *  The map is static on the controller and survives @Subject reload. */
    def setup() {
        def f = ProfileController.getDeclaredField('TWOFA_FAILS')
        f.setAccessible(true)
        (f.get(null) as java.util.concurrent.ConcurrentHashMap).clear()
    }

    /** Pure helper — find any 6-digit string guaranteed NOT to verify
     *  against the given Base32 secret right now. Walks ±1 step window
     *  and returns the first non-collision so the spec can never flake
     *  on a 1-in-a-million coincidental match. */
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
        '000000'
    }

    /** Compute the current live TOTP code for a Base32 secret. */
    private String currentTotpCode(String secretBase32) {
        def m = TotpService.getDeclaredMethod('unbase32', String)
        m.setAccessible(true)
        byte[] decoded = m.invoke(null, secretBase32) as byte[]
        long step = ((System.currentTimeMillis() / 1000L) / 30L) as long
        totpService.codeFor(decoded, step)
    }

    // ── 1. /2fa/disable locks out after 5 failed codes ──────────────

    def "disable2fa locks the user after 5 wrong TOTP codes — 6th attempt rejected pre-verify"() {
        given: 'a 2FA-enabled user'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def user = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            totpSecret: secret, lastTotpStep: null,
            totpRecoveryCodes: totpService.generateBackupCodes().hashed as String,
            sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)
        def wrong = wrongTotpCode(secret)

        when: 'the attacker submits 5 wrong codes back-to-back'
        5.times {
            try { controller.disable2fa([code: wrong], req) } catch (BadRequestException ignored) {}
        }
        // The 6th attempt must be refused BEFORE verify runs.
        controller.disable2fa([code: wrong], req)

        then: 'lockout fires with a clean TWOFA_LOCKED code'
        def e = thrown(BadRequestException)
        e.code == 'TWOFA_LOCKED'
        e.message.contains('Try again')
        // Secret is intact — no side effect, no save on the locked attempt.
        user.totpSecret == secret
        user.sessionEpoch == 0L
    }

    def "the locked-out attempt does NOT consume a recovery code"() {
        given: 'a 2FA-enabled user already at the lockout threshold'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def codes  = totpService.generateBackupCodes()
        def user   = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            totpSecret: secret, lastTotpStep: null,
            totpRecoveryCodes: codes.hashed as String, sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)
        def wrong = wrongTotpCode(secret)
        5.times {
            try { controller.disable2fa([code: wrong], req) } catch (BadRequestException ignored) {}
        }
        def hashesBefore = user.totpRecoveryCodes

        when: 'the 6th attempt uses a VALID recovery code'
        controller.disable2fa([code: codes.plaintext[0] as String], req)

        then: 'rejected pre-verify — the recovery code is NOT burnt'
        def e = thrown(BadRequestException)
        e.code == 'TWOFA_LOCKED'
        // Critical: the lockout fires BEFORE consumeRecoveryCode would
        // run, so the user's recovery hashes are intact.
        user.totpRecoveryCodes == hashesBefore
        user.totpSecret == secret
    }

    // ── 2. Success clears the counter ───────────────────────────────

    def "a valid recovery code at attempt 5 succeeds AND clears the counter"() {
        given: '4 failed attempts already on the counter'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def codes  = totpService.generateBackupCodes()
        def user   = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            totpSecret: secret, lastTotpStep: null,
            totpRecoveryCodes: codes.hashed as String, sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)
        emailService.canSendSecurityTo(user) >> true
        def wrong = wrongTotpCode(secret)
        4.times {
            try { controller.disable2fa([code: wrong], req) } catch (BadRequestException ignored) {}
        }

        when: 'the user finally enters a valid recovery code'
        def resp = controller.disable2fa([code: codes.plaintext[0] as String], req)

        then: '2FA actually disabled — counter cleared'
        resp.body.enabled == false
        user.totpSecret == null
        user.sessionEpoch > 0L
    }

    def "after a successful disable the per-user counter is wiped for the next enrollment cycle"() {
        given: '4 failed attempts then a successful recovery-code disable'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def codes  = totpService.generateBackupCodes()
        def user   = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            totpSecret: secret, lastTotpStep: null,
            totpRecoveryCodes: codes.hashed as String, sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)
        emailService.canSendSecurityTo(user) >> true
        def wrong = wrongTotpCode(secret)
        4.times {
            try { controller.disable2fa([code: wrong], req) } catch (BadRequestException ignored) {}
        }
        controller.disable2fa([code: codes.plaintext[0] as String], req)

        when: 'the user re-enrolls and fumbles 5 fresh codes against the NEW secret'
        def secret2 = totpService.generateSecret()
        user.totpSecret = secret2
        user.totpRecoveryCodes = totpService.generateBackupCodes().hashed as String
        user.sessionEpoch = 0L
        def wrong2 = wrongTotpCode(secret2)
        // Five fresh failures should be allowed before the lockout fires
        // — proves the success above wiped the counter.
        5.times {
            try { controller.disable2fa([code: wrong2], req) } catch (BadRequestException ignored) {}
        }
        controller.disable2fa([code: wrong2], req)

        then: 'the 6th attempt — not the 2nd — is what trips the lockout'
        def e = thrown(BadRequestException)
        e.code == 'TWOFA_LOCKED'
    }

    // ── 3. /2fa/regenerate-codes shares the counter ─────────────────

    def "regenerateRecoveryCodes shares the lockout counter with disable2fa"() {
        given: 'a 2FA-enabled user'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def codes  = totpService.generateBackupCodes()
        def user   = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            totpSecret: secret, lastTotpStep: null,
            totpRecoveryCodes: codes.hashed as String, sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)
        def wrong = wrongTotpCode(secret)

        when: 'an attacker uses 3 failed disable2fa attempts and 2 failed regenerate-codes attempts'
        3.times {
            try { controller.disable2fa([code: wrong], req) } catch (BadRequestException ignored) {}
        }
        2.times {
            try { controller.regenerateRecoveryCodes([code: wrong], req) } catch (BadRequestException ignored) {}
        }
        // Sixth attempt on EITHER endpoint must be locked.
        controller.regenerateRecoveryCodes([code: wrong], req)

        then: 'the regenerate-codes path enforces the same lockout — attacker cannot pivot endpoints'
        def e = thrown(BadRequestException)
        e.code == 'TWOFA_LOCKED'
        user.totpSecret == secret
    }

    def "a valid TOTP code on regenerate-codes also clears the lockout counter"() {
        given: '4 failed attempts across mixed endpoints'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def codes  = totpService.generateBackupCodes()
        def user   = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            totpSecret: secret, lastTotpStep: null,
            totpRecoveryCodes: codes.hashed as String, sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)
        def wrong = wrongTotpCode(secret)
        2.times {
            try { controller.disable2fa([code: wrong], req) } catch (BadRequestException ignored) {}
        }
        2.times {
            try { controller.regenerateRecoveryCodes([code: wrong], req) } catch (BadRequestException ignored) {}
        }

        when: 'a real live TOTP code finally arrives via regenerate-codes'
        def resp = controller.regenerateRecoveryCodes([code: currentTotpCode(secret)], req)

        then: 'the new code set is minted and the counter is wiped'
        resp.statusCode.value() == 200
        (resp.body.recoveryCodes as List).size() == 10
        user.totpRecoveryCodes != (codes.hashed as String)
    }

    def "after regenerate-codes success the next bad attempt is INVALID_CODE not TWOFA_LOCKED"() {
        given: '4 failed attempts across mixed endpoints followed by a successful regenerate'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def codes  = totpService.generateBackupCodes()
        def user   = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            totpSecret: secret, lastTotpStep: null,
            totpRecoveryCodes: codes.hashed as String, sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)
        def wrong = wrongTotpCode(secret)
        2.times {
            try { controller.disable2fa([code: wrong], req) } catch (BadRequestException ignored) {}
        }
        2.times {
            try { controller.regenerateRecoveryCodes([code: wrong], req) } catch (BadRequestException ignored) {}
        }
        controller.regenerateRecoveryCodes([code: currentTotpCode(secret)], req)

        when: 'the user fumbles ONE more code right after'
        controller.disable2fa([code: wrong], req)

        then: 'rejected as INVALID_CODE — not TWOFA_LOCKED, proving the counter reset'
        def e = thrown(BadRequestException)
        e.code == 'INVALID_CODE'
    }

    // ── 4. The recovery-code disable path runs through clear2faFails ─

    def "successful recovery-code disable clears the counter"() {
        given: '4 failed attempts on a 2FA-enabled user'
        authedSession(100L)
        def secret = totpService.generateSecret()
        def codes  = totpService.generateBackupCodes()
        def user   = new SteamUser(id: 100L, steamId64: '1', email: 'a@b.com',
            totpSecret: secret, lastTotpStep: null,
            totpRecoveryCodes: codes.hashed as String, sessionEpoch: 0L)
        steamUserRepository.findById(100L) >> Optional.of(user)
        emailService.canSendSecurityTo(user) >> true
        def wrong = wrongTotpCode(secret)
        4.times {
            try { controller.disable2fa([code: wrong], req) } catch (BadRequestException ignored) {}
        }

        when: 'the 5th attempt is a valid recovery code'
        def resp = controller.disable2fa([code: codes.plaintext[2] as String], req)

        then: '2FA disabled cleanly — and the static counter is now empty'
        resp.body.enabled == false
        user.totpSecret == null
        def f = ProfileController.getDeclaredField('TWOFA_FAILS')
        f.setAccessible(true)
        (f.get(null) as java.util.concurrent.ConcurrentHashMap).get(100L) == null
    }
}
