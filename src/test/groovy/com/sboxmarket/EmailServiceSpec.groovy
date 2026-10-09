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
            // The @Value defaults on replyToAddress / unsubscribeSecret
            // only apply under Spring — the plain Groovy map constructor
            // leaves them null. Supply them here so the reply-to and
            // one-click-unsubscribe (HMAC) paths behave as in prod.
            // `replyToAddress` is only set when the caller passes it, so
            // the blank-reply-to case can still exercise a null/blank.
            replyToAddress: args.containsKey('replyToAddress')
                                ? args.replyToAddress
                                : 'support@skinbox.market',
            unsubscribeSecret: args.unsubscribeSecret ?: 'test-unsubscribe-secret',
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
        def replyTos = (msg.getReplyTo() ?: [] as Object[]).collect { it.toString() }
        [
            to:       (msg.getRecipients(Message.RecipientType.TO) ?: [] as Object[]).collect { it.toString() },
            // `from` exposed as a bare string so tests can compare to a
            // single address without wrapping in a list — SkinBox only
            // ever has one From.
            from:     froms.isEmpty() ? null : froms[0],
            replyTo:  replyTos.isEmpty() ? null : replyTos[0],
            subject:  msg.getSubject() ?: '',
            text:     (msg.getContent() ?: '').toString(),
            // contentType is asserted by the plain-text/XSS tests below:
            // every SkinBox email must stay text/plain so a user-supplied
            // display name / item name / dispute note cannot be parsed as
            // HTML by the recipient's mail client.
            contentType: msg.getContentType() ?: '',
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
            // From: now carries the SkinBox display name + bare mailbox
            // — see "From header includes the configured display name"
            // spec below for the deliberate behaviour.
            f.from == 'SkinBox <no-reply@skinbox.local>' &&
            f.subject == 'Confirm your SkinBox email' &&
            f.text.contains('abc123token') &&
            f.text.contains('https://skinbox.example')
        })
    }

    def "From header includes the configured display name"() {
        // Inboxes (and reputation systems at Gmail/Outlook/Yahoo) penalise
        // bare-mailbox From: addresses — `no-reply@skinbox.local` looks like
        // spam to humans AND scores worse than `SkinBox <no-reply@…>`.
        // EmailService configures `fromName` from `app.email.from-name`
        // but for ~200 batches never passed it to MimeMessageHelper.setFrom;
        // this regression pins the display-name plumbing.
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendVerification('user@example.com', 'tok')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg ->
            fields(msg).from == 'SkinBox <no-reply@skinbox.local>'
        })
    }

    def "From header falls back to bare mailbox when fromName is blank"() {
        // A deploy that intentionally clears the display name (e.g. for a
        // throwaway dev instance or a relay that rejects RFC 5322 group
        // addresses) must still send mail rather than NPE at setFrom.
        given:
        def svc = new EmailService(
            mailSender: mailSender, smtpHost: 'smtp.example.com',
            fromAddress: 'no-reply@skinbox.local', fromName: '',
            replyToAddress: 'support@skinbox.market',
            unsubscribeSecret: 'test-unsubscribe-secret',
            publicUrl: 'http://localhost:8080')
        mailSender.createMimeMessage() >> { new MimeMessage((jakarta.mail.Session) null) }
        svc.init()

        when:
        svc.sendVerification('user@example.com', 'tok')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg ->
            fields(msg).from == 'no-reply@skinbox.local'
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

    // ── plain-text / no-HTML-injection (the email-XSS property) ───────
    //
    // Every SkinBox email is built as plain text — send() calls
    // helper.setText(body, false). That `false` is the load-bearing
    // anti-XSS control: a user-supplied display name / item name /
    // dispute note that contains `<script>` is delivered verbatim and
    // the recipient's mail client renders it as literal text, never as
    // markup. These tests pin that contract so a future change to an
    // HTML body can't silently land without also adding escaping.

    def "emails are sent as text/plain, never text/html"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendVerification('user@example.com', 'tok')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            // MimeMessageHelper.setText(text, false) yields a text/plain
            // part; an accidental switch to setText(text, true) would
            // make this read text/html and the assertion would fail.
            f.contentType.toLowerCase().startsWith('text/plain') &&
            !f.contentType.toLowerCase().contains('text/html')
        })
    }

    def "a script-tag display name is delivered verbatim (no HTML to escape, no corruption)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')
        def evil = '<script>alert(1)</script>'

        when:
        svc.sendAuctionWon('user@example.com', evil, 'Wizard Hat',
            new BigDecimal('12.00'), '/item/1')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            // Body stays text/plain, so the angle brackets are literal
            // characters in the recipient's client — they must NOT be
            // HTML-entity-escaped (that would corrupt a plain-text body
            // into showing `&lt;script&gt;`) and must NOT be stripped.
            f.contentType.toLowerCase().startsWith('text/plain') &&
            f.text.contains(evil) &&
            !f.text.contains('&lt;') &&
            !f.text.contains('&amp;')
        })
    }

    def "user-supplied item name + dispute note pass through unmodified"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')
        def itemName = 'AK-47 | <b>Redline</b>'
        def note = 'seller said "ship it" & never did — <img src=x>'

        when:
        svc.sendTradeDisputed('bob@example.com', 'Bob', itemName,
            'BUYER', note, 7L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.contentType.toLowerCase().startsWith('text/plain') &&
            f.subject.contains(itemName) &&
            f.text.contains(note)
        })
    }

    // ── exception isolation (a mail failure must not break the caller) ─

    def "a security-alert send swallows an SMTP failure"() {
        given:
        mailSender.send(_) >> { throw new RuntimeException('relay timeout') }
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendWalletFrozen('user@example.com', 'Alice', 'fraud review')
        svc.awaitSmtpForTests()

        then:
        // The async worker logs + swallows; nothing propagates to the
        // caller that triggered the security alert.
        noExceptionThrown()
    }

    def "a transactional send swallows an SMTP failure"() {
        given:
        mailSender.send(_) >> { throw new IllegalStateException('mailbox full') }
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendSaleCompleted('seller@example.com', 'Sam', 'Wizard Hat',
            new BigDecimal('48.00'), '/wallet')
        svc.awaitSmtpForTests()

        then:
        noExceptionThrown()
    }

    def "createMimeMessage blowing up does not break the caller"() {
        given:
        // Some JavaMailSender configs touch the session eagerly; if that
        // throws, the failure happens inside the async worker's try block
        // and must still be swallowed.
        def boomSender = Mock(JavaMailSender)
        boomSender.createMimeMessage() >> { throw new RuntimeException('no mail session') }
        def svc = new EmailService(
            mailSender: boomSender, smtpHost: 'smtp.example.com',
            fromAddress: 'no-reply@skinbox.local', fromName: 'SkinBox',
            publicUrl: 'http://localhost:8080')
        svc.init()

        when:
        svc.sendVerification('user@example.com', 'tok')
        svc.awaitSmtpForTests()

        then:
        noExceptionThrown()
    }

    // ── reply-to + footer + unsubscribe wiring ────────────────────────

    def "every email carries the support reply-to address"() {
        given:
        // newService() stubs createMimeMessage() — required now that
        // send() builds a MimeMessage — and supplies a non-Spring
        // unsubscribeSecret so the footer/header HMAC path doesn't abort.
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com',
                             replyToAddress: 'support@skinbox.market')

        when:
        svc.sendVerification('user@example.com', 'tok')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg ->
            // A real Reply-To header is set — and it differs from the
            // no-reply From, which is the whole point of the field.
            (msg.getHeader('Reply-To') ?: []).join(' ').contains('support@skinbox.market') &&
            fields(msg).from == 'SkinBox <no-reply@skinbox.local>'
        })
    }

    def "a blank reply-to config does not break the send and sets no Reply-To header"() {
        given:
        // Pass replyToAddress: '' explicitly — newService() only defaults
        // it when the key is absent, so this exercises the blank-config
        // branch (helper.setReplyTo is skipped, no Reply-To header).
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com',
                             replyToAddress: '')

        when:
        svc.sendVerification('user@example.com', 'tok')
        svc.awaitSmtpForTests()

        then:
        // No explicit Reply-To header written (getReplyTo() would fall
        // back to From per the JavaMail contract, so assert on the raw
        // header instead), but the email still goes out fine.
        1 * mailSender.send({ MimeMessage msg ->
            fields(msg).to == ['user@example.com'] &&
            (msg.getHeader('Reply-To') == null)
        })
    }

    def "the footer + List-Unsubscribe header are attached to every email"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com',
                             publicUrl: 'https://skinbox.example')

        when:
        svc.sendVerification('user@example.com', 'tok')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            // CAN-SPAM footer + RFC-8058 one-click unsubscribe.
            f.text.contains('Manage your email preferences') &&
            // The email switches live on Profile → Personal Info, not /settings.
            f.text.contains('/profile/personal') &&
            f.text.contains('One-click unsubscribe') &&
            f.listUnsub.contains('/api/unsubscribe?email=') &&
            f.listUnsub.contains('user%40example.com')
        })
    }

    // ── currency formatting + null-safety on money methods ────────────

    def "usd() formats to two decimals with the (USD) suffix"() {
        expect:
        EmailService.usd(new BigDecimal('5'))      == '$5.00 (USD)'
        EmailService.usd(new BigDecimal('5.1'))    == '$5.10 (USD)'
        EmailService.usd(new BigDecimal('5.125'))  == '$5.13 (USD)'   // HALF_UP
        EmailService.usd(new BigDecimal('0'))      == '$0.00 (USD)'
    }

    def "usd() treats a null amount as zero rather than throwing"() {
        expect:
        EmailService.usd(null) == '$0.00 (USD)'
    }

    def "sendWithdrawalApproved short-circuits on a null amount"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendWithdrawalApproved('user@example.com', 'Alice', null, 'ref-1')
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    def "sendWithdrawalRejected short-circuits on a null amount"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendWithdrawalRejected('user@example.com', 'Alice', null, 'reason')
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    def "sendWithdrawalApproved surfaces the amount + payout reference"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendWithdrawalApproved('user@example.com', 'Alice',
            new BigDecimal('75.00'), 'PO-9931')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['user@example.com'] &&
            f.subject.toLowerCase().contains('approved') &&
            f.text.contains('75.00') &&
            f.text.contains('PO-9931')
        })
    }

    // ── null displayName / itemName fall back to safe defaults ────────

    def "a null displayName renders as 'there' instead of the literal null"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendAuctionWon('user@example.com', null, 'Wizard Hat',
            new BigDecimal('10.00'), '/item/1')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.text.contains('Hi there,') &&
            !f.text.contains('Hi null,')
        })
    }

    def "a null itemName falls back without printing 'null' in the body"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendAuctionWon('user@example.com', 'Alice', null,
            new BigDecimal('10.00'), null)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['user@example.com'] &&
            !f.text.contains('won the auction for null')
        })
    }

    def "sendPriceDrop switches to restock wording when the target is the sentinel"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendPriceDrop('user@example.com', 'Alice', 'Wizard Hat',
            new BigDecimal('20.00'), new BigDecimal('99999'), '/item/1')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.subject.startsWith('Restock') &&
            f.text.contains('back in stock') &&
            // must NOT leak the sentinel target as a literal price
            !f.text.contains('99999')
        })
    }

    def "sendPriceDrop short-circuits when itemName is null"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendPriceDrop('user@example.com', 'Alice', null,
            new BigDecimal('20.00'), new BigDecimal('10.00'), '/item/1')
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    def "sendAuctionExpired tolerates a null reason"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendAuctionExpired('user@example.com', 'Alice', 'Wizard Hat', null, 9L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['user@example.com'] &&
            f.text.contains('No valid winning bid was recorded')
        })
    }

    // ── unsubscribe HMAC token round-trip ─────────────────────────
    //
    // UnsubscribeControllerSpec mocks EmailService, so the real HMAC
    // token logic is only exercised here. These pin: determinism,
    // verification, tamper-rejection, and that the secret is part of
    // the MAC (rotating it invalidates outstanding links).

    def "unsubscribeToken is deterministic for the same email"() {
        given:
        def svc = newService()

        expect:
        svc.unsubscribeToken('alice@example.com') == svc.unsubscribeToken('alice@example.com')
        svc.unsubscribeToken('alice@example.com') != null
    }

    def "unsubscribeToken differs per email address"() {
        given:
        def svc = newService()

        expect:
        svc.unsubscribeToken('alice@example.com') != svc.unsubscribeToken('bob@example.com')
    }

    def "verifyUnsubscribeToken accepts a freshly minted token and rejects a tampered one"() {
        given:
        def svc = newService()
        def tok = svc.unsubscribeToken('alice@example.com')

        expect:
        svc.verifyUnsubscribeToken('alice@example.com', tok)
        // Wrong email for this token.
        !svc.verifyUnsubscribeToken('mallory@example.com', tok)
        // Mutated token.
        !svc.verifyUnsubscribeToken('alice@example.com', tok + 'x')
        // Null / empty inputs never throw, always false.
        !svc.verifyUnsubscribeToken(null, tok)
        !svc.verifyUnsubscribeToken('alice@example.com', null)
        !svc.verifyUnsubscribeToken('alice@example.com', '')
    }

    def "unsubscribeToken short-circuits to null for a null/empty email"() {
        given:
        def svc = newService()

        expect:
        svc.unsubscribeToken(null) == null
        svc.unsubscribeToken('') == null
    }

    def "a token minted under one secret does not validate under a rotated secret"() {
        given:
        def svcOld = newService(unsubscribeSecret: 'secret-A')
        def svcNew = newService(unsubscribeSecret: 'secret-B')
        def tok = svcOld.unsubscribeToken('alice@example.com')

        expect:
        // Same secret validates.
        svcOld.verifyUnsubscribeToken('alice@example.com', tok)
        // Rotated secret invalidates every outstanding link — the
        // documented revocation mechanism.
        !svcNew.verifyUnsubscribeToken('alice@example.com', tok)
    }

    // ── backfill coverage for untested template methods ───────────

    def "sendTradeUrlChanged flags the security alert and shows the new URL"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendTradeUrlChanged('alice@example.com', 'Alice',
            'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['alice@example.com'] &&
            f.subject.toLowerCase().contains('trade url') &&
            f.text.contains('partner=1') &&
            f.text.contains('Change your Steam password')
        })
    }

    def "sendTradeUrlChanged switches wording when the URL was removed"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendTradeUrlChanged('alice@example.com', 'Alice', null)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['alice@example.com'] &&
            f.text.toLowerCase().contains('removed') &&
            // Must NOT print the literal 'null' for the missing URL.
            !f.text.contains('null')
        })
    }

    def "sendAccountBanned surfaces the reason and an optional appeal URL"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendAccountBanned('alice@example.com', 'Alice',
            'Chargeback fraud', 'https://skinbox.market/appeal')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['alice@example.com'] &&
            f.subject.toLowerCase().contains('suspended') &&
            f.text.contains('Chargeback fraud') &&
            f.text.contains('https://skinbox.market/appeal')
        })
    }

    def "sendAccountBanned falls back to a default reason and omits the appeal URL clause"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendAccountBanned('alice@example.com', 'Alice', null, null)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['alice@example.com'] &&
            f.text.contains('Policy violation') &&
            !f.text.contains('null')
        })
    }

    def "sendApiKeyMinted distinguishes a read-only key from a full-access key"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendApiKeyMinted('alice@example.com', 'Alice', 'CI bot', 'RO', 'sk_live_ab')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['alice@example.com'] &&
            f.subject.contains('read-only') &&
            f.text.contains('CI bot') &&
            f.text.contains('sk_live_ab')
        })
    }

    def "sendApiKeyMinted reads as full-access when scope is RW"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendApiKeyMinted('alice@example.com', 'Alice', 'My key', 'RW', 'sk_live_zz')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.subject.contains('full access') &&
            f.text.contains('full access')
        })
    }

    def "sendChargebackOpened tolerates a null amount (renders a placeholder, no crash)"() {
        given:
        // sendChargebackOpened deliberately does NOT short-circuit on a
        // null amount — it null-coalesces to BigDecimal.ZERO, so the
        // body must render a '$0' placeholder rather than throw or print
        // the literal 'null'.
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendChargebackOpened('alice@example.com', 'Alice', null)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['alice@example.com'] &&
            f.text.contains('$0 SkinBox') &&
            !f.text.contains('null')
        })
    }

    def "sendOfferAccepted surfaces the amount and the trade id"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendOfferAccepted('alice@example.com', 'Alice', 'Wizard Hat',
            new BigDecimal('33.00'), 88L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['alice@example.com'] &&
            f.subject.contains('#88') &&
            f.text.contains('33.00')
        })
    }

    def "sendOfferAccepted short-circuits on a null amount"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendOfferAccepted('alice@example.com', 'Alice', 'Wizard Hat', null, 88L)
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    def "sendSavedSearchMatch embeds the preset name and short-circuits on a null preset"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendSavedSearchMatch('alice@example.com', 'Alice', 'cheap knives',
            'Karambit', new BigDecimal('120.00'), '/item/9')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.subject.contains('cheap knives') &&
            f.text.contains('Karambit')
        })

        when:
        svc.sendSavedSearchMatch('alice@example.com', 'Alice', null,
            'Karambit', new BigDecimal('120.00'), '/item/9')
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    def "sendBuyOrderFilled tolerates null money fields without printing 'null'"() {
        given:
        // fillPrice + maxCap flow through usd(), which null-coalesces to
        // $0.00 — the body must never surface a literal 'null'.
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendBuyOrderFilled('alice@example.com', 'Alice', 'Wizard Hat', null, null, '/item/3')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['alice@example.com'] &&
            f.text.contains('0.00 (USD)') &&
            !f.text.contains('null')
        })
    }

    def "sendAuctionEnding pluralises the minute count correctly"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when: 'exactly one minute left — singular'
        svc.sendAuctionEnding('alice@example.com', 'Alice', 'Wizard Hat',
            new BigDecimal('10.00'), 1L, '/item/1')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.text.contains('1 minute') &&
            !f.text.contains('1 minutes')
        })

        when: 'several minutes left — plural'
        svc.sendAuctionEnding('alice@example.com', 'Alice', 'Wizard Hat',
            new BigDecimal('10.00'), 5L, '/item/1')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.text.contains('5 minutes')
        })
    }

    def "sendTradeCancelled wording differs for buyer vs seller role"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when: 'buyer sees the refund message'
        svc.sendTradeCancelled('buyer@example.com', 'Bob', 'Wizard Hat',
            'Seller went silent', 'buyer', 5L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['buyer@example.com'] &&
            f.text.contains('refunded') &&
            f.text.contains('Seller went silent')
        })

        when: 'seller sees the relist message'
        svc.sendTradeCancelled('seller@example.com', 'Sam', 'Wizard Hat',
            null, 'seller', 5L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['seller@example.com'] &&
            f.text.contains('back in your stall inventory')
        })
    }

    def "sendForceLogout is a non-opt-out security alert with the admin note"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendForceLogout('alice@example.com', 'Alice', 'Reported account')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['alice@example.com'] &&
            f.subject.toLowerCase().contains('security') &&
            f.text.contains('Reported account') &&
            f.text.contains('revoked')
        })
    }

    def "sendDeletionRequested + sendAccountUnbanned send without throwing"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendDeletionRequested('alice@example.com', 'Alice')
        svc.sendAccountUnbanned('alice@example.com', 'Alice')
        svc.awaitSmtpForTests()

        then:
        2 * mailSender.send(_)
        noExceptionThrown()
    }

    def "every backfilled template short-circuits on a null recipient"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendTradeUrlChanged(null, 'Alice', 'url')
        svc.sendAccountBanned(null, 'Alice', 'reason', 'appeal')
        svc.sendApiKeyMinted(null, 'Alice', 'label', 'RO', 'prefix')
        svc.sendChargebackOpened(null, 'Alice', new BigDecimal('5'))
        svc.sendForceLogout(null, 'Alice', 'note')
        svc.sendTradeCancelled(null, 'Alice', 'item', 'reason', 'buyer', 1L)
        svc.sendBuyOrderFilled(null, 'Alice', 'item', new BigDecimal('1'), new BigDecimal('2'), '/item/1')
        svc.sendAuctionEnding(null, 'Alice', 'item', new BigDecimal('1'), 3L, '/item/1')
        svc.sendDeletionRequested(null, 'Alice')
        svc.sendAccountUnbanned(null, 'Alice')
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    // ── Templates previously without coverage (audit gap) ─────────
    //
    // These four senders had no dedicated spec — added so a future
    // template refactor can't silently drop the subject line, the body
    // marker copy, or the null-recipient short-circuit on these
    // engagement / state-machine emails.

    def "sendAuctionOutbid carries the new top bid AND the listing URL in the body"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendAuctionOutbid('user@example.com', 'Alice',
            'AK-47 Redline', new BigDecimal('12.50'), '/item/42')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['user@example.com'] &&
            // Subject names the item so the auction is identifiable from the inbox list.
            f.subject.contains('AK-47 Redline') &&
            // Body must surface the new top bid in plain dollars so the user
            // can decide whether to re-bid without opening the listing.
            f.text.contains('$12.50') &&
            // The link to the listing must be present so the user can act in one click.
            f.text.contains('/item/42') &&
            // Auto-bid mention is the load-bearing reassurance — pin the
            // copy so a template refactor can't drop the "may have re-raised
            // on your behalf" caveat (otherwise users panic-bid manually
            // even when their auto-bid already covered them).
            f.text.contains('auto-bid')
        })
    }

    def "sendAuctionOutbid omits the URL clause when itemUrl is null but still sends"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendAuctionOutbid('user@example.com', 'Alice',
            'Skull Mask', new BigDecimal('5.00'), null)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.text.contains('$5.00') &&
            // The fall-through clause ends the sentence with a period
            // instead of leaking a bare ':' from a null URL.
            !f.text.contains('null') &&
            !f.text.contains(': /')
        })
    }

    def "sendAuctionOutbid short-circuits on null recipient"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendAuctionOutbid(null, 'Alice', 'Item', new BigDecimal('1'), '/item/1')
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    def "sendNewListingFromSeller includes seller name + item name in subject + body"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendNewListingFromSeller('follower@example.com', 'Alice',
            'NeonArc', 'Wizard Hat', new BigDecimal('3.50'), '/item/7')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['follower@example.com'] &&
            // Subject identifies BOTH the seller and the item — otherwise
            // "NeonArc just listed something" reads as spam and gets
            // auto-archived by power followers.
            f.subject.contains('NeonArc') &&
            f.subject.contains('Wizard Hat') &&
            f.text.contains('$3.50') &&
            // The unsubscribe nudge points the user at the right
            // affordance (the seller's stall page) instead of a vague
            // "go to settings" call to action.
            f.text.contains('stall page')
        })
    }

    def "sendNewListingFromSeller skips fanout when sellerName is null (caller guard)"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        // The caller side enforces sellerName non-null — this guard
        // documents that the email layer ALSO short-circuits so a
        // bad fanout invocation can't paste a literal "null just
        // listed Item Name" subject into a follower's inbox.
        svc.sendNewListingFromSeller('follower@example.com', 'Alice',
            null, 'Item', new BigDecimal('1'), '/item/1')
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    def "sendTradeSent walks the buyer through the steamcommunity → confirm receipt flow"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendTradeSent('buyer@example.com', 'Alice', 'Karambit Doppler',
            'BoneTender', 1234L)
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg -> def f = fields(msg)
            f.to == ['buyer@example.com'] &&
            f.subject.contains('Karambit Doppler') &&
            // The instructions must point at steamcommunity AND include
            // the "Confirm receipt" jargon so a confused buyer can pattern-
            // match it against the actual button on the trades page.
            f.text.contains('steamcommunity') &&
            f.text.contains('Confirm receipt') &&
            // The 8-day auto-release window is the load-bearing safety net —
            // dropping this copy means a buyer who delays accepting on
            // Steam thinks they've lost the money.
            f.text.contains('8 days') &&
            f.text.contains('BoneTender')
        })
    }

    def "send2faDisabled short-circuits on null recipient"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        // The two security alerts already covered (sendEmailChanged,
        // sendWalletFrozen) test their body shape; this round-trips the
        // null-recipient guard for the only security alert previously
        // uncovered for that case.
        svc.send2faDisabled(null, 'Alice')
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }

    def "sendTradeSent short-circuits on null recipient"() {
        given:
        def svc = newService(mailSender: mailSender, smtpHost: 'smtp.example.com')

        when:
        svc.sendTradeSent(null, 'Alice', 'Item', 'Seller', 1L)
        svc.awaitSmtpForTests()

        then:
        0 * mailSender.send(_)
    }
}
