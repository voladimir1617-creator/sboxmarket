package com.sboxmarket

import com.sboxmarket.service.EmailService
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.javamail.JavaMailSender
import spock.lang.Specification

/**
 * Subject-line CRLF / SMTP header-injection regression.
 *
 * Jakarta Mail's `MimeMessage.setSubject(String)` runs the value
 * through `encodeText` + `fold` but does NOT strip raw CR/LF. A
 * user-controlled field (Steam display name, third-party item name,
 * saved-search preset name) spliced into a subject template can
 * therefore become two SMTP headers on relays that don't pre-validate
 * header lines: an attacker who sets their Steam display name to
 * `"Foo\r\nBcc: attacker@evil"` smuggles a `Bcc:` header into every
 * email we send them — silently fanning out the recipient's
 * verification token / wallet alert / dispute reply to a third party.
 *
 * `EmailService.scrubHeaderLine()` collapses CR/LF/C0 controls to a
 * single space before the subject reaches `setSubject`. These specs
 * pin that contract for representative templates that splice
 * user-controlled fields into a subject string.
 */
class EmailServiceSubjectInjectionSpec extends Specification {

    JavaMailSender mailSender = Mock()

    private EmailService newService() {
        mailSender.createMimeMessage() >> {
            new MimeMessage((jakarta.mail.Session) null)
        }
        def svc = new EmailService(
            mailSender:        mailSender,
            smtpHost:          'smtp.example.com',
            fromAddress:       'no-reply@skinbox.local',
            fromName:          'SkinBox',
            replyToAddress:    'support@skinbox.market',
            unsubscribeSecret: 'test-unsubscribe-secret',
            publicUrl:         'http://localhost:8080'
        )
        svc.init()
        svc
    }

    private static String captureSubject(MimeMessage msg) {
        msg.getSubject() ?: ''
    }

    /**
     * Check ONLY the decoded subject value (the attacker-payload
     * shape). Jakarta Mail's wire-form header uses RFC 5322 "folding"
     * (CRLF + whitespace) to wrap long subjects across multiple
     * physical lines — that CRLF is STRUCTURAL and the receiving relay
     * unfolds it back into one logical header. A test that checked the
     * folded form would false-positive on every subject longer than
     * ~76 chars. The real injection signal is raw CR/LF in the DECODED
     * subject — that means scrubHeaderLine missed an attacker-controlled
     * control character before fold/encode normalised it.
     */
    private static boolean noHeaderInjection(MimeMessage msg) {
        def subj = msg.getSubject() ?: ''
        return !subj.contains('\r') && !subj.contains('\n')
    }

    def "an item name containing CR/LF cannot smuggle a Bcc header into the subject"() {
        given:
        def svc = newService()
        // Classic SMTP-header-injection payload: end the subject, open a
        // new header line, hide a Bcc: that some relays will accept.
        def evilItem = "AK-47 | Redline\r\nBcc: attacker@evil.example"

        when:
        svc.sendAuctionWon('buyer@example.com', 'Alice', evilItem,
            new BigDecimal('12.50'), '/item/1')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg ->
            noHeaderInjection(msg) &&
            captureSubject(msg).startsWith('You won')
        })
    }

    def "a Steam display name with embedded LF cannot inject a header via the sale-completed subject"() {
        given:
        def svc = newService()
        def evilName = "Wizard Hat\nCc: leak@example.com"

        when:
        svc.sendSaleCompleted('seller@example.com', 'Sam', evilName,
            new BigDecimal('48.00'), '/wallet')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg ->
            noHeaderInjection(msg)
        })
    }

    def "a saved-search preset name with CRLF cannot smuggle headers"() {
        given:
        def svc = newService()
        def evilPreset = "knives under \$50\r\nReply-To: phish@example.com"

        when:
        svc.sendSavedSearchMatch('user@example.com', 'Alice', evilPreset,
            'Karambit', new BigDecimal('48.00'), '/item/9')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg ->
            noHeaderInjection(msg)
        })
    }

    def "a legitimate subject with no control characters is unchanged"() {
        given:
        def svc = newService()

        when:
        svc.sendAuctionWon('buyer@example.com', 'Alice', 'AK-47 | Redline',
            new BigDecimal('12.50'), '/item/1')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg ->
            captureSubject(msg) == 'You won · AK-47 | Redline'
        })
    }
}
