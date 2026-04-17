package com.sboxmarket.service

import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

/**
 * Transport-agnostic email sender for verification links, password-less
 * sign-in tokens, and similar one-off system emails.
 *
 * Two modes, selected automatically by inspecting the Spring mail config:
 *
 *  1) **SMTP** — when `spring.mail.host` (aka `SMTP_HOST`) is set, send via
 *     the standard {@link JavaMailSender}. Works with Postmark, SES SMTP,
 *     Mailgun, Resend (SMTP endpoint), Gmail app passwords — anything that
 *     speaks plain RFC-5321.
 *
 *  2) **Log sink** — otherwise, log the full message body at INFO level.
 *     This keeps local dev, CI, and any prod deploy that hasn't yet been
 *     handed SMTP credentials fully functional: the verification token
 *     ends up in the server log instead of an inbox. Operators can still
 *     complete account setup by reading the log.
 *
 * No Spring profile gymnastics — one bean, one decision at startup, log
 * which mode it landed in so an operator can confirm at a glance.
 */
@Service
@Slf4j
class EmailService {

    @Autowired(required = false)
    JavaMailSender mailSender

    @Value('${spring.mail.host:}')
    String smtpHost

    @Value('${app.email.from:no-reply@skinbox.local}')
    String fromAddress

    @Value('${app.email.from-name:SkinBox}')
    String fromName

    @Value('${app.public-url:http://localhost:8080}')
    String publicUrl

    private boolean smtpReady = false

    @PostConstruct
    void init() {
        smtpReady = mailSender != null && smtpHost && !smtpHost.isBlank()
        if (smtpReady) {
            log.info("EmailService: SMTP enabled via {} — sending real mail from {}", smtpHost, fromAddress)
        } else {
            log.warn("EmailService: no SMTP host configured — running in LOG-SINK mode. " +
                     "Verification tokens will appear in the server log. Set SMTP_HOST to enable delivery.")
        }
    }

    /** Send the verification email for a new/changed address. */
    void sendVerification(String toEmail, String token) {
        if (!toEmail) return
        def link = "${publicUrl}/#/profile?verify=${token}"
        def subject = 'Confirm your SkinBox email'
        def body = """\
Hi there,

Please confirm this address so we can reach you about trade activity, security alerts, and support tickets.

Confirm: ${link}

Or paste this code into the verification box on your profile page: ${token}

If you didn't ask for this, you can safely ignore this message — nothing will change on the account.

— SkinBox
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Account-suspended notice. Fires from AdminService.banUser when the
     *  target has a verified email. Plain-text so the template doesn't
     *  render weirdly in email clients that don't speak multipart. */
    void sendAccountBanned(String toEmail, String displayName, String reason, String appealUrl) {
        if (!toEmail) return
        def subject = 'Your SkinBox account has been suspended'
        def body = """\
Hi ${displayName ?: 'there'},

Your SkinBox account has been suspended by our moderation team.

Reason: ${reason ?: 'Policy violation'}

What this means:
  · Your active listings have been cancelled.
  · Open trades have been rolled back to the other party.
  · You can't sign in to SkinBox while the suspension is in effect.

If you believe this was a mistake, you can appeal by replying to this
email${appealUrl ? ' or by visiting ' + appealUrl : ''}. Appeals are reviewed within 1-2 business days.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Withdrawal approved — funds released to the destination. Fires
     *  from AdminService.approveWithdrawal. Payout processors can take a
     *  business day or two to settle so the email gives the user a
     *  concrete reference to follow up on. */
    void sendWithdrawalApproved(String toEmail, String displayName, BigDecimal amount, String payoutRef) {
        if (!toEmail || amount == null) return
        def subject = "Withdrawal approved · \$${amount.toPlainString()}"
        def body = """\
Hi ${displayName ?: 'there'},

Your \$${amount.toPlainString()} withdrawal has been approved and released.

Payout reference: ${payoutRef ?: '(none)'}

Settlement typically clears within 1-2 business days depending on your
payout destination. You can track the transaction from Wallet →
Transactions at any time.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Withdrawal rejected — the amount was credited back to the wallet.
     *  Fires from AdminService.rejectWithdrawal. */
    void sendWithdrawalRejected(String toEmail, String displayName, BigDecimal amount, String reason) {
        if (!toEmail || amount == null) return
        def subject = "Withdrawal rejected · funds refunded"
        def body = """\
Hi ${displayName ?: 'there'},

Your \$${amount.toPlainString()} withdrawal request was rejected and the
full amount has been credited back to your SkinBox wallet.

Reason: ${reason ?: 'See the Transactions tab for details.'}

You can open a new withdrawal from Wallet → Withdraw once the underlying
issue is resolved. If you need help, reply to this email or open a
support ticket.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Account-reinstated notice. Fires from AdminService.unbanUser. */
    void sendAccountUnbanned(String toEmail, String displayName) {
        if (!toEmail) return
        def subject = 'Your SkinBox account is active again'
        def body = """\
Hi ${displayName ?: 'there'},

Your SkinBox account has been reinstated and you can sign in as normal.
Listings you had cancelled during the suspension were not auto-restored —
you'll need to re-list anything you want back on the market.

Thanks for your patience.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Generic sender — used by sendVerification plus any future one-off. */
    void send(String to, String subject, String body) {
        if (!to || !subject || !body) return
        if (smtpReady) {
            try {
                def msg = new SimpleMailMessage()
                msg.setFrom(fromAddress)
                msg.setTo(to)
                msg.setSubject(subject)
                msg.setText(body)
                mailSender.send(msg)
                log.info("EmailService: sent '{}' to {}", subject, to)
            } catch (Exception e) {
                log.error("EmailService: SMTP send FAILED for {} — {}", to, e.message, e)
                // Never throw from the email path — a broken relay must not
                // 500 the caller (account creation, password reset). The
                // token is still persisted, the user can retry, and ops
                // will see the error in the log.
            }
        } else {
            log.info("[email/log-sink] to={} subject={}\n---\n{}\n---", to, subject, body)
        }
    }

    /** Exposed so ProfileController can decide whether to echo tokens
     *  back in the HTTP response (dev aid) or not (prod). */
    boolean isSmtpReady() { smtpReady }
}
