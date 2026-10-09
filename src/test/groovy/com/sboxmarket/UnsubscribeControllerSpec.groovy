package com.sboxmarket

import com.sboxmarket.controller.UnsubscribeController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import spock.lang.Specification
import spock.lang.Subject

/**
 * Direct coverage for the one-click email unsubscribe endpoint. The
 * rules we pin down here are load-bearing for the email / anti-spam
 * story:
 *
 *   - Missing email or token → error page (no user lookup attempted).
 *   - Invalid HMAC token → error page, no save.
 *   - Valid token + matching user(s) → flip `emailNotificationsEnabled`
 *     on every matching row (lowercase lookup) and save.
 *   - Valid token + NO matching user → still return a success page
 *     (idempotent, enumeration-safe — we never reveal whether an
 *     address has an account).
 *   - POST handler (RFC 8058 one-click) delegates to the GET logic
 *     and returns a 2xx body of 'OK' that mail clients check for
 *     successful unsubscribe.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class UnsubscribeControllerSpec extends Specification {

    EmailService emailService = Mock()
    SteamUserRepository steamUserRepository = Mock()

    @Subject
    UnsubscribeController controller = new UnsubscribeController(
        emailService       : emailService,
        steamUserRepository: steamUserRepository
    )

    def "GET with no email or token returns the error card"() {
        when:
        def resp = controller.unsubscribe(null, null)

        then: 'never verify, never look up users — short-circuit at input check'
        0 * emailService.verifyUnsubscribeToken(_, _)
        0 * steamUserRepository.findByEmailIgnoreCase(_)
        resp.statusCode == HttpStatus.OK
        // Content-Type ends up in the header map regardless of whether
        // HttpHeaders.contentType resolves on this Spring version.
        resp.headers.getFirst('Content-Type')?.startsWith('text/html')
        resp.body.contains("Missing email or token")
    }

    def "GET with empty-whitespace email returns the error card"() {
        when:
        def resp = controller.unsubscribe('   ', 'sometoken')

        then:
        0 * emailService.verifyUnsubscribeToken(_, _)
        resp.body.contains("Missing email or token")
    }

    def "GET with invalid HMAC token returns the expired/malformed card"() {
        when:
        def resp = controller.unsubscribe('Alice@Example.com', 'bad-token')

        then: 'email normalised to lowercase before verify'
        1 * emailService.verifyUnsubscribeToken('alice@example.com', 'bad-token') >> false
        0 * steamUserRepository.findByEmailIgnoreCase(_)
        resp.body.contains("expired or is malformed")
    }

    def "GET with a valid token only shows a confirm form and changes nothing"() {
        when: 'a mail scanner or prefetcher opens the emailed link'
        def resp = controller.unsubscribe('ALICE@example.com', 'good-token')

        then: 'no lookup, no save; the page posts the confirmation back'
        1 * emailService.verifyUnsubscribeToken('alice@example.com', 'good-token') >> true
        0 * steamUserRepository.findByEmailIgnoreCase(_)
        0 * steamUserRepository.save(_)
        resp.body.contains('Confirm unsubscribe')
        resp.body.contains('<form method="post" action="/api/unsubscribe"')
        resp.body.contains('value="alice@example.com"')
        resp.body.contains('value="good-token"')
    }

    def "the confirm button's POST flips the flag and shows the result card"() {
        given:
        def u = new SteamUser(id: 42L, email: 'alice@example.com', emailNotificationsEnabled: true)

        when: 'browser form POST: no List-Unsubscribe=One-Click field'
        def resp = controller.unsubscribePost('ALICE@example.com', 'good-token', null, null)

        then:
        1 * emailService.verifyUnsubscribeToken('alice@example.com', 'good-token') >> true
        1 * steamUserRepository.findByEmailIgnoreCase('alice@example.com') >> [u]
        1 * steamUserRepository.save({ SteamUser s -> !s.emailNotificationsEnabled && s.id == 42L })
        resp.body.contains("Unsubscribed")
        resp.body.contains("unsubscribed from email notifications")
    }

    def "POST with valid token + NO matching user still returns success (enumeration guard)"() {
        when:
        def resp = controller.unsubscribePost('ghost@example.com', 'good-token', null, null)

        then: 'idempotent success — never reveal whether an address is registered'
        1 * emailService.verifyUnsubscribeToken('ghost@example.com', 'good-token') >> true
        1 * steamUserRepository.findByEmailIgnoreCase('ghost@example.com') >> []
        0 * steamUserRepository.save(_)
        resp.body.contains("Unsubscribed")
    }

    def "POST flips every matching row when email is shared (defensive)"() {
        given: 'two rows match — save both, never short-circuit'
        def u1 = new SteamUser(id: 1L, email: 'dup@example.com', emailNotificationsEnabled: true)
        def u2 = new SteamUser(id: 2L, email: 'dup@example.com', emailNotificationsEnabled: true)

        when:
        controller.unsubscribePost('dup@example.com', 'good-token', null, null)

        then:
        1 * emailService.verifyUnsubscribeToken('dup@example.com', 'good-token') >> true
        1 * steamUserRepository.findByEmailIgnoreCase('dup@example.com') >> [u1, u2]
        1 * steamUserRepository.save({ SteamUser s -> s.id == 1L && !s.emailNotificationsEnabled })
        1 * steamUserRepository.save({ SteamUser s -> s.id == 2L && !s.emailNotificationsEnabled })
    }

    def "POST one-click handshake returns 2xx body 'OK' and flips the flag"() {
        given:
        def u = new SteamUser(id: 7L, email: 'click@example.com', emailNotificationsEnabled: true)

        when: 'Gmail / Outlook RFC 8058 one-click POST'
        def resp = controller.unsubscribePost('click@example.com', 'good-token')

        then: 'mail clients only check status + empty-ish body'
        1 * emailService.verifyUnsubscribeToken('click@example.com', 'good-token') >> true
        1 * steamUserRepository.findByEmailIgnoreCase('click@example.com') >> [u]
        1 * steamUserRepository.save(_)
        resp.statusCode == HttpStatus.OK
        resp.body == 'OK'
    }

    def "POST one-click with invalid token still returns 2xx 'OK' (idempotent)"() {
        when:
        def resp = controller.unsubscribePost('x@example.com', 'bad')

        then: 'the underlying page() always returns 200, POST never surfaces 4xx'
        1 * emailService.verifyUnsubscribeToken('x@example.com', 'bad') >> false
        resp.statusCode == HttpStatus.OK
        resp.body == 'OK'
    }
}
