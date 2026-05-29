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
 * Wave 145 — mailbox-flood / SMTP-reputation guard on the verification
 * email send path.
 *
 * The 60s per-user cooldown (batch 602) was wired ONLY into /email/resend,
 * with an explicit comment that the global 20/10s rate limit is too loose
 * for the outbound-email side. But PUT /email fires the identical
 * {@code emailService.sendVerification} on every successful call, and:
 *   - re-saving your own pending address is explicitly allowed, and
 *   - the uniqueness gate only blocks OTHER users' verified emails.
 * So an attacker (or a buggy UI) could bypass the resend cooldown entirely
 * by hammering PUT /email — flooding an arbitrary mailbox with up to 20
 * "Confirm your SkinBox email" messages per 10s, sustained.
 *
 * These specs pin that:
 *   1. A second PUT /email inside the cooldown window is refused with
 *      RESEND_COOLDOWN and fires NO second verification email.
 *   2. The cooldown bucket is SHARED with /email/resend, so alternating
 *      the two endpoints can't double the flood rate.
 *   3. A cooldown rejection on PUT /email leaves the user row untouched
 *      (no half-applied new token / cleared emailVerified) and does not
 *      persist.
 *
 * Distinct, high uid range per feature keeps these specs immune to the
 * static cooldown map shared across the whole test JVM.
 */
class ProfileControllerEmailFloodCooldownSpec extends Specification {

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

    private void authedSession(long uid) {
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    def setup() {
        // The send cooldown lives in a process-global static map shared
        // across every spec in the test JVM. Reset it before each case so
        // a send primed by a previous test (here or in another spec) can't
        // leak a spurious RESEND_COOLDOWN into this one.
        ProfileController.clearEmailCooldowns()
    }

    def "PUT /email is rate-limited per-user — a rapid second set-email is refused with no second mail"() {
        given: 'a clean account with no email yet'
        long uid = 90101L
        authedSession(uid)
        def user = new SteamUser(id: uid, steamId64: '1', emailVerificationToken: null)
        steamUserRepository.findById(uid) >> Optional.of(user)

        when: 'the first set-email succeeds and sends one verification email'
        def first = controller.setEmail([email: 'first@b.com'], req)

        then:
        first.statusCode.value() == 200
        user.email == 'first@b.com'
        1 * emailService.sendVerification('first@b.com', _)

        when: 'a second set-email arrives within the 60s cooldown window'
        controller.setEmail([email: 'second@b.com'], req)

        then: 'refused — the cooldown blocks the redundant outbound send'
        def e = thrown(BadRequestException)
        e.code == 'RESEND_COOLDOWN'

        and: 'NO second verification email was sent (mailbox-flood blocked)'
        0 * emailService.sendVerification('second@b.com', _)
    }

    def "a cooldown rejection on PUT /email leaves the user row untouched and unpersisted"() {
        given: 'an account with no email yet'
        long uid = 90102L
        authedSession(uid)
        def user = new SteamUser(id: uid, steamId64: '1', emailVerificationToken: null)
        steamUserRepository.findById(uid) >> Optional.of(user)

        when: 'a first set-email primes the cooldown — it persists and sends once'
        controller.setEmail([email: 'orig@b.com'], req)
        def tokenAfterFirst = user.emailVerificationToken

        then: 'the priming write saved the row exactly once and sent one mail'
        // Interactions are scoped to the preceding when:, so the priming
        // call must live in its OWN when/then pair — declaring `1 * save`
        // in the SECOND when's then would only observe the rejected call
        // (which never saves) and spuriously report TooFewInvocations.
        1 * steamUserRepository.save(user)
        1 * emailService.sendVerification('orig@b.com', _)
        tokenAfterFirst != null

        when: 'a second set-email to a different address arrives inside the window'
        controller.setEmail([email: 'attacker-target@b.com'], req)

        then: 'refused before any mutation — original pending email + token survive'
        thrown(BadRequestException)
        user.email == 'orig@b.com'
        user.emailVerificationToken == tokenAfterFirst

        and: 'the rejected call neither saved again nor sent a second mail'
        0 * steamUserRepository.save(_)
        0 * emailService.sendVerification('attacker-target@b.com', _)
    }

    def "the send cooldown bucket is shared between PUT /email and /email/resend"() {
        given: 'an account that just set its email via PUT /email'
        long uid = 90103L
        authedSession(uid)
        def user = new SteamUser(id: uid, steamId64: '1', emailVerificationToken: null)
        steamUserRepository.findById(uid) >> Optional.of(user)

        and: 'PUT /email primes the shared cooldown'
        controller.setEmail([email: 'shared@b.com'], req)

        when: 'the attacker immediately pivots to /email/resend to flood faster'
        controller.resendVerification(req)

        then: 'the shared bucket blocks it — alternating endpoints cannot double the rate'
        def e = thrown(BadRequestException)
        e.code == 'RESEND_COOLDOWN'
        0 * emailService.sendVerification(_, _)
    }

    def "/email/resend still honours the cooldown after a PUT /email primes it (reverse pivot)"() {
        given: 'an account with an unverified email'
        long uid = 90104L
        authedSession(uid)
        def user = new SteamUser(id: uid, steamId64: '1', email: 'rev@b.com',
            emailVerified: false, emailVerificationToken: 'deadbeefdeadbeefdeadbeefdeadbeef')
        steamUserRepository.findById(uid) >> Optional.of(user)

        when: 'one resend primes the shared cooldown bucket'
        def firstResend = controller.resendVerification(req)

        then:
        firstResend.statusCode.value() == 200
        1 * emailService.sendVerification('rev@b.com', _)

        when: 'the attacker pivots to PUT /email (same address) to keep flooding'
        controller.setEmail([email: 'rev@b.com'], req)

        then: 'blocked by the shared bucket — no additional mail'
        def e = thrown(BadRequestException)
        e.code == 'RESEND_COOLDOWN'
        0 * emailService.sendVerification(_, _)
    }
}
