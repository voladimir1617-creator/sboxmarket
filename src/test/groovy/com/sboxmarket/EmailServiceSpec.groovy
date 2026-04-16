package com.sboxmarket

import com.sboxmarket.service.EmailService
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

        then:
        1 * mailSender.send({ SimpleMailMessage msg ->
            msg.to == ['user@example.com'] as String[] &&
            msg.from == 'no-reply@skinbox.local' &&
            msg.subject == 'Confirm your SkinBox email' &&
            msg.text.contains('abc123token') &&
            msg.text.contains('https://skinbox.example')
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
}
