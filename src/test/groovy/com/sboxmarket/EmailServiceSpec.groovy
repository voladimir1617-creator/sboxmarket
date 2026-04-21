package com.sboxmarket

import com.sboxmarket.service.EmailService
import jakarta.mail.Message
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import spock.lang.Specification
import spock.lang.Subject

/**
 * Two-mode email sender.
 *
 *   - log-sink mode: when no SMTP host is configured, writes the full body
 *     to the server log. smtpReady == false.
 *   - SMTP mode: when a host is set and a JavaMailSender bean is present,
 *     sends a real message. smtpReady == true.
 *
 * Behaviour under test: mode selection happens once at init(); sendVerification
 * never throws even if the underlying mailer blows up; short-circuit guards
 * for null inputs.
 */
class EmailServiceSpec extends Specification {

    JavaMailSender mailSender = Mock()

    private EmailService newService(Map args = [:]) {
        // Batch 892 — EmailService.send() now uses MimeMessage so it
        // can attach List-Unsubscribe headers. Stub createMimeMessage()
        // with a real MimeMessage instance; tests then assert on its
        // getters via `fields(msg)` below.
        if (args.mailSender != null) {
            args.mailSender.createMimeMessage() >> {
                new MimeMessage((jakarta.mail.Session) null)
            }
        }
        def svc = new EmailService(
            mailSender:   args.mailSender,
            smtpHost:     args.smtpHost ?: '',
            fromAddress:  args.fromAddress ?: 'no-reply@skinbox.local',
            fromName:     'SkinBox',
            publicUrl:    args.publicUrl ?: 'http://localhost:8080'
        )
        svc.init()
        svc
    }

    /** Extract readable fields from a MimeMessage for Spock matcher
     *  closures. Kept simple — getContent() returns String for text/plain
     *  bodies which is what all current senders produce. */
    private static Map fields(MimeMessage msg) {
        def froms = (msg.getFrom() ?: [] as Object[]).collect { it.toString() }
        [
            to:       (msg.getRecipients(Message.RecipientType.TO) ?: [] as Object[]).collect { it.toString() },
            // `from` exposed as a bare string so tests can compare to a
            // single address without wrapping in a list — SkinBox only
            // ever has one From.
            from:     froms.isEmpty() ? null : froms[0],
            subject:  msg.getSubject() ?: '',
            text:     (msg.getContent() ?: '').toString(),
            listUnsub: (msg.getHeader('List-Unsubscribe') ?: []).join(' ')
        ]
    }

    def "log-sink mode when SMTP_HOST is blank"() {
        when:
        def svc = newService()

        then:
        !svc.smtpReady
    }

    def "log-sink mode when JavaMailSender bean is null (no spring-boot-starter-mail)"() {
        when:
        def svc = newService(mailSender: null, smtpHost: 'smtp.example.com')

        then:
        !svc.smtpReady
    }

    def "SMTP mode activates when host is set AND a mail sender is present"() {
        when:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        then:
        svc.smtpReady
    }

    def "sendVerification in log-sink mode never calls the mail sender"() {
        given:
        def svc = newService()  // log-sink mode

        when:
        svc.sendVerification('user@example.com', 'abc123token')

        then:
        0 * mailSender.send(_)
    }

    def "sendVerification in SMTP mode builds and sends a SimpleMailMessage"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com',
                             publicUrl: 'https://skinbox.example')

        when:
        svc.sendVerification('user@example.com', 'abc123token')
        // Batch 508 moved SMTP send to a dedicated background executor
        // so a slow relay can't block the caller. Tests need to wait
        // for that executor to drain before asserting on the mock.
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['user@example.com'] &&
            f.from == 'no-reply@skinbox.local' &&
            f.subject == 'Confirm your SkinBox email' &&
            f.text.contains('abc123token') &&
            f.text.contains('https://skinbox.example')
        })
    }

    def "sendVerification swallows a failing mailer so the caller isn't 500'd"() {
        given:
        mailSender.send(_) >> { throw new RuntimeException('SMTP relay is down') }
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendVerification('user@example.com', 'token')

        then:
        // No exception propagates
        noExceptionThrown()
    }

    def "sendVerification is a no-op for null email"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendVerification(null, 'token')

        then:
        0 * mailSender.send(_)
    }

    def "sendEmailChanged targets the OLD address with new + old in the body (batch 512)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendEmailChanged('old@example.com', 'Alice', 'new@example.com')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['old@example.com'] &&
            f.subject.contains('email changed') &&
            f.text.contains('old@example.com') &&
            f.text.contains('new@example.com')
        })
    }

    def "send2faDisabled includes the recovery playbook (batch 512)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.send2faDisabled('user@example.com', 'Alice')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['user@example.com'] &&
            f.subject.contains('2FA') &&
            f.text.contains('Steam password') &&
            f.text.contains('Re-enable 2FA')
        })
    }

    def "sendDisputeCleared announces withdrawals re-enabled (batch 520)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendDisputeCleared('user@example.com', 'Alice')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['user@example.com'] &&
            f.subject.toLowerCase().contains('withdrawals') &&
            f.text.contains('hold has been lifted')
        })
    }

    def "sendChargebackOpened gives the two-scenario recovery playbook (batch 521)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendChargebackOpened('user@example.com', 'Alice', new BigDecimal('50.00'))
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['user@example.com'] &&
            f.subject.toLowerCase().contains('disputed') &&
            f.text.contains('$50.00') &&
            f.text.contains("DID NOT file this") &&
            f.text.contains('Steam password')
        })
    }

    def "generic send() is a no-op for null/empty arguments"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.send(null,  'sub',  'body')
        svc.send('to@x', null,   'body')
        svc.send('to@x', 'sub',  null)

        then:
        0 * mailSender.send(_)
    }

    // ── Per-bucket mute preferences ─────────────────────────────────

    def "isBucketMuted returns true when the bucket is in the user's comma-separated set"() {
        given:
        def svc = newService()
        def user = new com.sboxmarket.model.SteamUser(mutedEmailKinds: 'TRADES,WATCHLIST')

        expect:
        svc.isBucketMuted(user, 'TRADES')
        svc.isBucketMuted(user, 'WATCHLIST')
        !svc.isBucketMuted(user, 'AUCTIONS')
        !svc.isBucketMuted(user, 'FOLLOWS')
    }

    def "isBucketMuted tolerates whitespace and empty entries in the stored string"() {
        given:
        def svc = newService()
        def user = new com.sboxmarket.model.SteamUser(mutedEmailKinds: '  AUCTIONS , , FOLLOWS ')

        expect:
        svc.isBucketMuted(user, 'AUCTIONS')
        svc.isBucketMuted(user, 'FOLLOWS')
    }

    def "isBucketMuted returns false for unknown buckets (fails open)"() {
        given:
        def svc = newService()
        // Even if someone crams a nonsense value into the DB somehow, the
        // service should not silently suppress an unknown new bucket — we
        // never want a future email kind to be invisibly dropped.
        def user = new com.sboxmarket.model.SteamUser(mutedEmailKinds: 'TRADES,MYSTERY_BUCKET')

        expect:
        !svc.isBucketMuted(user, 'MYSTERY_BUCKET')
        svc.isBucketMuted(user, 'TRADES')
    }

    def "isBucketMuted short-circuits on null/empty user or bucket"() {
        given:
        def svc = newService()

        expect:
        !svc.isBucketMuted(null, 'TRADES')
        !svc.isBucketMuted(new com.sboxmarket.model.SteamUser(mutedEmailKinds: null), 'TRADES')
        !svc.isBucketMuted(new com.sboxmarket.model.SteamUser(mutedEmailKinds: ''), 'TRADES')
        !svc.isBucketMuted(new com.sboxmarket.model.SteamUser(mutedEmailKinds: 'TRADES'), null)
    }

    def "MUTABLE_EMAIL_BUCKETS contains the documented buckets"() {
        // Pin the contract so adding a new bucket has to go through this
        // spec — otherwise the frontend's label map can drift from what
        // the server actually honours.
        expect:
        EmailService.MUTABLE_EMAIL_BUCKETS == ['TRADES','AUCTIONS','WATCHLIST','FOLLOWS','MATCHES'].toSet()
    }

    // ── canSendTo / canSendSecurityTo (batches 621/622/623) ─────────

    def "canSendTo returns true only for a fully-opted-in user"() {
        given:
        def svc = newService()
        def user = new com.sboxmarket.model.SteamUser(
            email: 'alice@example.com',
            emailVerified: true,
            emailNotificationsEnabled: true,
            mutedEmailKinds: null
        )

        expect:
        svc.canSendTo(user, 'TRADES')
        svc.canSendTo(user, 'AUCTIONS')
    }

    def "canSendTo closes the gate on every unhappy-path attribute"() {
        given:
        def svc = newService()

        expect:
        !svc.canSendTo(null, 'TRADES')
        !svc.canSendTo(new com.sboxmarket.model.SteamUser(email: null,                          emailVerified: true,  emailNotificationsEnabled: true), 'TRADES')
        !svc.canSendTo(new com.sboxmarket.model.SteamUser(email: '',                            emailVerified: true,  emailNotificationsEnabled: true), 'TRADES')
        !svc.canSendTo(new com.sboxmarket.model.SteamUser(email: 'a@x.test', emailVerified: false, emailNotificationsEnabled: true), 'TRADES')
        !svc.canSendTo(new com.sboxmarket.model.SteamUser(email: 'a@x.test', emailVerified: true,  emailNotificationsEnabled: false), 'TRADES')
        !svc.canSendTo(new com.sboxmarket.model.SteamUser(email: 'a@x.test', emailVerified: true,  emailNotificationsEnabled: true, mutedEmailKinds: 'TRADES'), 'TRADES')
    }

    def "canSendSecurityTo ignores emailNotificationsEnabled and bucket mute"() {
        given:
        def svc = newService()
        // User has disabled notifications AND muted every bucket — a
        // security alert must still fire. This is the whole reason the
        // helper exists.
        def user = new com.sboxmarket.model.SteamUser(
            email: 'alice@example.com',
            emailVerified: true,
            emailNotificationsEnabled: false,
            mutedEmailKinds: 'TRADES,AUCTIONS,WATCHLIST,FOLLOWS,MATCHES'
        )

        expect:
        svc.canSendSecurityTo(user)
    }

    def "canSendSecurityTo requires a verified email address"() {
        given:
        def svc = newService()

        expect:
        !svc.canSendSecurityTo(null)
        !svc.canSendSecurityTo(new com.sboxmarket.model.SteamUser(email: null, emailVerified: true))
        !svc.canSendSecurityTo(new com.sboxmarket.model.SteamUser(email: '', emailVerified: true))
        !svc.canSendSecurityTo(new com.sboxmarket.model.SteamUser(email: 'a@x.test', emailVerified: false))
        !svc.canSendSecurityTo(new com.sboxmarket.model.SteamUser(email: 'a@x.test', emailVerified: null))
    }

    // ── backfill coverage for security-alert send methods (batch 629) ──

    def "send2faDisabled flags the 'security alert' subject line"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.send2faDisabled('alice@example.com', 'Alice')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('security alert') &&
            f.subject.contains('2FA') &&
            f.text.contains('Change your Steam password')
        })
    }

    def "sendEmailChanged is sent to the OLD verified address, not the new one"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendEmailChanged('old@example.com', 'Alice', 'new@example.com')
        svc.awaitSmtpForTests()

        then:
        // Critical security property: the alert goes to the previous
        // recipient so an account-takeover attempt (attacker swapped
        // the email) is visible to the real owner.
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['old@example.com'] &&
            f.text.contains('new@example.com')
        })
    }

    def "sendRefundIssued surfaces the amount + new balance"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendRefundIssued('alice@example.com', 'Alice',
            new BigDecimal('25.00'), new BigDecimal('130.00'))
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('refund') &&
            f.text.contains('25.00') &&
            f.text.contains('130.00')
        })
    }

    def "sendSupportReply embeds the ticket number + reply snippet"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendSupportReply('alice@example.com', 'Alice', 42L,
            'Withdrawal stuck', 'We refunded the pending row.')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.contains('#42') &&
            f.subject.contains('Withdrawal stuck') &&
            f.text.contains('We refunded the pending row') &&
            f.text.contains('/support')
        })
    }

    def "sendSupportReply short-circuits when ticketId is null"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendSupportReply('alice@example.com', 'Alice', null, 'Subject', 'Body')
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    // ── recent batches (567/571/572/573/574/575/584) ─────────────────

    def "sendTradeOpened composes the new-sale subject + body (batch 564)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendTradeOpened('bob@example.com', 'Bob', 'Wizard Hat', 'Alice', new BigDecimal('50.00'), 42L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['bob@example.com'] &&
            f.subject.contains('Wizard Hat') &&
            f.text.contains('Alice') &&
            f.text.contains('50.00')
        })
    }

    def "sendPurchaseReceipt surfaces the price + seller name (batch 571)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendPurchaseReceipt('alice@example.com', 'Alice', 'Wizard Hat', 'Bob', new BigDecimal('50.00'), 7L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('purchase') &&
            f.text.contains('Bob') &&
            f.text.contains('50.00')
        })
    }

    def "sendTradeDisputed surfaces the filer role + reason snippet (batch 567)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendTradeDisputed('bob@example.com', 'Bob', 'Wizard Hat',
            'BUYER', 'Item not received', 42L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['bob@example.com'] &&
            f.subject.toLowerCase().contains('dispute') &&
            f.text.toLowerCase().contains('buyer') &&
            f.text.contains('Item not received')
        })
    }

    def "sendTwoFactorReset is a security-alert subject (batch 575)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendTwoFactorReset('alice@example.com', 'Alice', 'Lost phone')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('security') &&
            f.subject.contains('2FA') &&
            f.text.contains('Lost phone')
        })
    }

    def "sendWalletFrozen flags the security alert with the reason (batch 584)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendWalletFrozen('alice@example.com', 'Alice', 'Suspicious chargeback pattern')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('frozen') &&
            f.text.contains('Suspicious chargeback pattern')
        })
    }

    def "sendWalletUnfrozen fires the good-news email (batch 584)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendWalletUnfrozen('alice@example.com', 'Alice')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.contains('unfrozen')
        })
    }

    def "sendOfferReceived surfaces buyer name + offer amount + asking price (batch 590)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendOfferReceived('sally@example.com', 'Sally', 'Alice',
            'Wizard Hat', new BigDecimal('42.50'), new BigDecimal('50.00'),
            'fast pay, ready now')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['sally@example.com'] &&
            f.subject.contains('42.50') &&
            f.subject.contains('Wizard Hat') &&
            f.text.contains('Alice') &&
            f.text.contains('42.50') &&
            f.text.contains('50.00') &&
            f.text.contains('fast pay, ready now')
        })
    }

    def "sendOfferReceived omits the note block when buyer supplied no message (batch 590)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendOfferReceived('sally@example.com', 'Sally', 'Alice',
            'Wizard Hat', new BigDecimal('42.50'), new BigDecimal('50.00'), null)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['sally@example.com'] &&
            !f.text.toLowerCase().contains("buyer's note")
        })
    }

    def "sendOfferCountered surfaces the buyer's + seller's amounts (batch 591)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendOfferCountered('alice@example.com', 'Alice', 'Wizard Hat',
            new BigDecimal('25'), new BigDecimal('40'), 'final')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.contains('40') &&
            f.subject.contains('Wizard Hat') &&
            f.text.contains('25') &&
            f.text.contains('40') &&
            f.text.contains('final')
        })
    }

    def "sendOfferRejected flags the decline and includes the seller reply (batch 591)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendOfferRejected('alice@example.com', 'Alice', 'Wizard Hat',
            new BigDecimal('30'), 'Firm at asking price')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('declined') &&
            f.text.contains('30') &&
            f.text.contains('Firm at asking price')
        })
    }

    def "sendAuctionExpired surfaces the reason + relist CTA (batch 607)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendAuctionExpired('alice@example.com', 'Alice', 'Wizard Hat',
            'No bids were placed before the timer ran out.', 42L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('ended without a sale') &&
            f.subject.contains('Wizard Hat') &&
            f.text.contains('No bids were placed') &&
            f.text.contains('/sell')
        })
    }

    def "sendNewSignIn surfaces the IP + device + revoke CTA (batch 606)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendNewSignIn('alice@example.com', 'Alice', '203.0.113.42',
            'Mozilla/5.0 (Windows NT 10.0)')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('security') &&
            f.subject.contains('sign-in') &&
            f.text.contains('203.0.113.42') &&
            f.text.contains('Mozilla/5.0') &&
            f.text.contains('Sign out everywhere')
        })
    }

    def "sendTradeAutoReleased surfaces item name + dispute CTA (batch 601)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendTradeAutoReleased('alice@example.com', 'Alice', 'Wizard Hat', 42L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('auto-verified') &&
            f.subject.contains('Wizard Hat') &&
            f.text.contains('8 days') &&
            f.text.contains('/profile?tab=trades')
        })
    }

    def "sendBuyOrderExpired surfaces item name + cap (batch 596)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendBuyOrderExpired('alice@example.com', 'Alice', 'Wizard Hat',
            new BigDecimal('50'), '/item/42')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to ==['alice@example.com'] &&
            f.subject.toLowerCase().contains('expired') &&
            f.subject.contains('Wizard Hat') &&
            f.text.contains('30 days') &&
            f.text.contains('50') &&
            f.text.contains('/item/42')
        })
    }
}
