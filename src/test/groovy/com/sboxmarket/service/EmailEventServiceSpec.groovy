package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import spock.lang.Specification

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

/** Bounce / spam-complaint webhook: signature check and what an event changes. */
class EmailEventServiceSpec extends Specification {

    static final byte[] KEY = 'test-signing-key-0123456789abcdef'.getBytes(StandardCharsets.UTF_8)
    static final String SECRET = 'whsec_' + Base64.encoder.encodeToString(KEY)
    static final long NOW = 1_800_000_000L

    SteamUserRepository repo = Mock()
    NotificationService notifications = Mock()
    EmailEventService service

    def setup() {
        service = new EmailEventService()
        service.clock = java.time.Clock.fixed(java.time.Instant.ofEpochSecond(NOW), java.time.ZoneOffset.UTC)
        service.steamUserRepository = repo
        service.notificationService = notifications
        service.webhookSecret = SECRET
    }

    static String sign(String id, String ts, String body, byte[] key = KEY) {
        def mac = Mac.getInstance('HmacSHA256')
        mac.init(new SecretKeySpec(key, 'HmacSHA256'))
        'v1,' + Base64.encoder.encodeToString(mac.doFinal("${id}.${ts}.${body}".toString().getBytes(StandardCharsets.UTF_8)))
    }

    def "a correctly signed fresh delivery verifies"() {
        expect:
        service.verify('msg_1', "$NOW", sign('msg_1', "$NOW", '{"a":1}'), '{"a":1}')
    }

    def "a rotated-secret header verifies when any v1 entry matches"() {
        given:
        def good = sign('msg_1', "$NOW", '{}')
        def other = sign('msg_1', "$NOW", '{}', 'another-key-another-key-another!!'.bytes)

        expect:
        service.verify('msg_1', "$NOW", "$other $good", '{}')
    }

    def "refused: #why"() {
        expect:
        !service.verify(id, ts, sig, body)

        where:
        why                    | id      | ts             | body      | sig
        'body changed'         | 'msg_1' | "$NOW"         | '{"a":2}' | sign('msg_1', "$NOW", '{"a":1}')
        'other id'             | 'msg_2' | "$NOW"         | '{}'      | sign('msg_1', "$NOW", '{}')
        'older than 5 minutes' | 'msg_1' | "${NOW - 301}" | '{}'      | sign('msg_1', "${NOW - 301}", '{}')
        'from the future'      | 'msg_1' | "${NOW + 301}" | '{}'      | sign('msg_1', "${NOW + 301}", '{}')
        'wrong key'            | 'msg_1' | "$NOW"         | '{}'      | sign('msg_1', "$NOW", '{}', 'not-the-key-not-the-key-not-the!!'.bytes)
        'not a v1 signature'   | 'msg_1' | "$NOW"         | '{}'      | sign('msg_1', "$NOW", '{}').replace('v1,', 'v2,')
        'no signature'         | 'msg_1' | "$NOW"         | '{}'      | null
        'junk timestamp'       | 'msg_1' | 'soon'         | '{}'      | sign('msg_1', 'soon', '{}')
    }

    def "nothing verifies while no secret is set"() {
        given:
        service.webhookSecret = ''

        expect:
        !service.isConfigured()
        !service.verify('msg_1', "$NOW", sign('msg_1', "$NOW", '{}'), '{}')
    }

    def "a bounce stops mail to the address and tells the user once"() {
        given:
        def u = new SteamUser(id: 5L, email: 'Dead@Example.com')
        repo.findByEmailIgnoreCase('dead@example.com') >> [u]

        when:
        def n = service.handle('{"type":"email.bounced","data":{"to":["Dead@Example.com"],"bounce":{"type":"Permanent"}}}')

        then:
        n == 1
        u.emailSuppressed == EmailService.EMAIL_SUPPRESSED_BOUNCE
        1 * repo.save(u)
        1 * notifications.safePush(5L, EmailEventService.NOTIFICATION_KIND, 'Our emails to you are bouncing', _, null, '/profile')

        when: 'the provider repeats it'
        n = service.handle('{"type":"email.bounced","data":{"to":["dead@example.com"]}}')

        then:
        n == 0
        0 * repo.save(_)
        0 * notifications.safePush(*_)
    }

    def "a spam complaint pauses all but security mail, and never downgrades a bounce"() {
        given:
        def fine = new SteamUser(id: 6L, email: 'a@example.com')
        def bounced = new SteamUser(id: 7L, email: 'b@example.com', emailSuppressed: 'BOUNCE')
        repo.findByEmailIgnoreCase('a@example.com') >> [fine]
        repo.findByEmailIgnoreCase('b@example.com') >> [bounced]

        when:
        def n = service.handle('{"type":"email.complained","data":{"to":["a@example.com","b@example.com"]}}')

        then:
        n == 1
        fine.emailSuppressed == EmailService.EMAIL_SUPPRESSED_COMPLAINT
        bounced.emailSuppressed == 'BOUNCE'
        1 * notifications.safePush(6L, EmailEventService.NOTIFICATION_KIND, 'Emails paused after a spam report', _, null, '/profile')
    }

    def "ignored: #why"() {
        when:
        def n = service.handle(body)

        then:
        n == 0
        0 * repo.findByEmailIgnoreCase(_)

        where:
        why                 | body
        'transient bounce'  | '{"type":"email.bounced","data":{"to":["x@example.com"],"bounce":{"type":"Transient"}}}'
        'delivered event'   | '{"type":"email.delivered","data":{"to":["x@example.com"]}}'
        'not json'          | 'oops'
        'json array'        | '[1,2]'
    }

    def "the send gates honour the marker"() {
        given:
        def mail = new EmailService()
        def u = new SteamUser(email: 'x@example.com', emailVerified: true, emailNotificationsEnabled: true,
                              emailSuppressed: mark)

        expect:
        mail.canSendTo(u, 'TRADES') == trades
        mail.canSendSecurityTo(u) == security

        where:
        mark        | trades | security
        null        | true   | true
        'COMPLAINT' | false  | true
        'BOUNCE'    | false  | false
    }
}
