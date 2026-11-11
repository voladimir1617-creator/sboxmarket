package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import spock.lang.Specification

/**
 * Staff ops alert fan-out: bells to active admins, and for urgent alerts an
 * email to the admins' OWN verified addresses only, throttled per key, and
 * switchable off.
 */
class OpsAlertServiceSpec extends Specification {

    SteamUserRepository users = Mock()
    NotificationService notifications = Mock()
    EmailService email = Mock()
    long clock = 5_000_000L
    OpsAlertService ops = new OpsAlertService() {
        @Override protected long now() { clock }
    }

    SteamUser verified = new SteamUser(id: 1L, role: 'ADMIN', email: 'admin1@example.test', emailVerified: true)
    SteamUser unverified = new SteamUser(id: 2L, role: 'ADMIN', email: 'admin2@example.test', emailVerified: false)
    SteamUser noEmail = new SteamUser(id: 3L, role: 'ADMIN')
    SteamUser banned = new SteamUser(id: 4L, role: 'ADMIN', email: 'banned@example.test', emailVerified: true, banned: true)

    def setup() {
        ops.steamUserRepository = users
        ops.notificationService = notifications
        ops.emailService = email
        ops.emailEnabled = true
        ops.emailThrottleMs = 3_600_000L
        users.findByRole('ADMIN') >> [verified, unverified, noEmail, banned]
        email.canSendSecurityTo(_) >> { SteamUser u -> u.email && Boolean.TRUE.equals(u.emailVerified) }
    }

    def "bells every active admin and skips the banned one"() {
        when:
        int n = ops.alertAdmins('k', 'OPS_X', 'Title', 'Body', '/admin?tab=health', false)

        then:
        1 * notifications.safePush(1L, 'OPS_X', 'Title', 'Body', null, '/admin?tab=health') >> new com.sboxmarket.model.Notification()
        1 * notifications.safePush(2L, *_) >> new com.sboxmarket.model.Notification()
        1 * notifications.safePush(3L, *_) >> new com.sboxmarket.model.Notification()
        0 * notifications.safePush(4L, *_)
        n == 3
    }

    def "non-urgent alerts send no email"() {
        when:
        ops.alertAdmins('k', 'OPS_X', 'T', 'B', null, false)

        then:
        0 * email.send(*_)
    }

    def "urgent alerts email only the verified admin address, never anyone else"() {
        given:
        List<String> to = []

        when:
        ops.alertAdmins('k', 'OPS_X', 'Steam bot offline', 'It is down.', null, true)

        then:
        (1.._) * email.send(_, _, _, EmailService.OPS_ALERT_KIND) >> { String t, String s, String b, String k -> to << t }
        to == ['admin1@example.test']
    }

    def "email is throttled per key for an hour, and keys are independent"() {
        when:
        ops.alertAdmins('a', 'OPS_X', 'T', 'B', null, true)
        ops.alertAdmins('a', 'OPS_X', 'T', 'B', null, true)
        ops.alertAdmins('b', 'OPS_X', 'T', 'B', null, true)

        then:
        2 * email.send('admin1@example.test', _, _, EmailService.OPS_ALERT_KIND)

        when:
        clock += 3_600_000L
        ops.alertAdmins('a', 'OPS_X', 'T', 'B', null, true)

        then:
        1 * email.send('admin1@example.test', _, _, EmailService.OPS_ALERT_KIND)
    }

    def "the on/off flag stops email but not bells"() {
        given:
        ops.emailEnabled = false

        when:
        ops.alertAdmins('k', 'OPS_X', 'T', 'B', null, true)
        ops.emailAdmins('FRAUD_SIGNAL_HIGH', 'T', 'B')

        then:
        0 * email.send(*_)
        3 * notifications.safePush(*_)
    }

    def "emailAdmins (fraud path) uses the same recipients and throttle, with no bell"() {
        when:
        int first = ops.emailAdmins('FRAUD_SIGNAL_HIGH', 'Fraud signal: X', 'summary')
        int second = ops.emailAdmins('FRAUD_SIGNAL_HIGH', 'Fraud signal: Y', 'summary')

        then:
        1 * email.send('admin1@example.test', { it.contains('Fraud signal: X') }, _, EmailService.OPS_ALERT_KIND)
        0 * notifications._
        first == 1
        second == 0
    }

    def "no admins: nothing sent, returns 0, does not throw"() {
        given:
        def lonely = new OpsAlertService(steamUserRepository: Mock(SteamUserRepository) { findByRole(_) >> [] },
                notificationService: notifications, emailService: email)

        expect:
        lonely.alertAdmins('k', 'OPS_X', 'T', 'B', null, true) == 0
    }

    def "an admin lookup failure is swallowed"() {
        given:
        def broken = new OpsAlertService(steamUserRepository: Mock(SteamUserRepository) { findByRole(_) >> { throw new RuntimeException('db') } },
                notificationService: notifications, emailService: email)

        expect:
        broken.alertAdmins('k', 'OPS_X', 'T', 'B', null, true) == 0
    }
}
