package com.sboxmarket.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import spock.lang.Specification

/**
 * Regression spec for the PII leak in EmailService's SMTP-retry and
 * final-failure log lines.
 *
 * The pre-fix code masked the {@code to} recipient via {@code maskEmail()}
 * in both the per-attempt WARN ("EmailService: SMTP send attempt N/M
 * failed for {} — {}") and the give-up ERROR ("SMTP send FAILED for {}
 * after {} attempts — {}"), but then immediately interpolated
 * {@code e.message} / {@code lastError?.message} verbatim. Spring's
 * {@link org.springframework.mail.MailSendException} (and the underlying
 * {@code jakarta.mail.SendFailedException}) embed the failing recipient
 * address in their {@code getMessage()} output — exactly the address the
 * mask was meant to suppress. So the masked-recipient log line was
 * neutralised by the trailing exception-message interpolation, leaking
 * the raw email to whatever logs index (Datadog, Elastic, etc) the
 * deploy ships to.
 *
 * The final-failure ERROR was strictly worse: it also passed the raw
 * {@code lastError} as a trailing SLF4J throwable, which Logback prints
 * the full stack-trace for. {@code SendFailedException.toString()}
 * includes every invalid recipient, so the stack-trace also leaked.
 *
 * Fix: introduce a regex-based {@code scrubEmails()} helper that masks
 * any email-shaped substring inside the exception message, log the
 * exception class name + scrubbed message instead of the raw object,
 * and drop the stack-trace arg.
 *
 * This spec wires a Logback {@link ListAppender} to the EmailService
 * logger and asserts:
 *   1. A retry-then-success WARN never contains the recipient address
 *      (only the masked form);
 *   2. An all-retries-fail ERROR never contains it either.
 *
 * The retry exception is constructed with the exact wording jakarta.mail
 * uses for an invalid recipient: {@code 550 5.1.1 <addr>: Recipient
 * address rejected} — the pre-fix code would emit "leaky@victim.com"
 * verbatim and this spec would fail.
 */
class EmailServicePiiScrubSpec extends Specification {

    JavaMailSender mailSender = Mock()

    /** Sentinel recipient — uniquely identifiable so a substring search
     *  in captured log output can prove the address never made it past
     *  the scrubber. */
    static final String LEAK_RECIPIENT = 'leaky@victim.example.com'

    /** What the message body of a typical SMTPSendFailedException looks
     *  like in the wild — the recipient appears inside angle brackets in
     *  the SMTP response line. */
    static final String FAILURE_MESSAGE =
        "550 5.1.1 <${LEAK_RECIPIENT}>: Recipient address rejected: User unknown"

    Logger emailLogger
    ListAppender<ILoggingEvent> appender

    def setup() {
        // Wire a capturing appender to the EmailService logger so we can
        // assert on what actually hits the log pipeline.
        emailLogger = (Logger) LoggerFactory.getLogger(EmailService)
        appender = new ListAppender<ILoggingEvent>()
        appender.start()
        emailLogger.addAppender(appender)
    }

    def cleanup() {
        emailLogger?.detachAppender(appender)
    }

    private EmailService newService() {
        // Match the createMimeMessage stub the main EmailServiceSpec uses
        // — without it the helper construction throws NPE before we ever
        // exercise the retry path.
        mailSender.createMimeMessage() >> {
            new MimeMessage((jakarta.mail.Session) null)
        }
        def svc = new EmailService(
            mailSender:        mailSender,
            smtpHost:          'smtp.example.com',
            fromAddress:       'no-reply@skinbox.local',
            fromName:          'SkinBox',
            replyToAddress:    'support@skinbox.market',
            unsubscribeSecret: 'test-pii-secret',
            publicUrl:         'https://skinbox.example'
        )
        svc.init()
        svc
    }

    /** Concatenate every captured log line's formatted message. The
     *  scrubber must remove the recipient from EVERY one of them. */
    private String capturedText() {
        appender.list.collect { ILoggingEvent ev ->
            // Include the rendered message AND any throwable-proxy so a
            // regression that re-adds the stack-trace arg also gets
            // caught by this spec.
            def thr = ev.throwableProxy
            def thrText = thr ? "${thr.className}: ${thr.message}" : ''
            "${ev.formattedMessage}${thrText ? '\n' + thrText : ''}"
        }.join('\n')
    }

    def "retry WARN never leaks the raw recipient in the exception message"() {
        given:
        def svc = newService()
        int calls = 0
        // First attempt: throw a MailSendException whose message embeds
        // the recipient address — the realistic jakarta.mail / Spring
        // shape. Second attempt: succeed. The WARN log fires on the
        // first failure.
        mailSender.send(_ as MimeMessage) >> { args ->
            calls++
            if (calls == 1) {
                throw new MailSendException(FAILURE_MESSAGE)
            }
            // success on retry
        }

        when:
        svc.send(LEAK_RECIPIENT, 'Test subject', 'Test body')
        svc.awaitSmtpForTests()

        then:
        // Both attempts were made (the retry happened).
        calls == 2
        def warnEvents = appender.list.findAll { it.level == Level.WARN }
        warnEvents.size() == 1
        def text = capturedText()
        // The bug: the raw recipient leaked through e.message. The fix:
        // it must not appear anywhere in any captured log line.
        !text.contains(LEAK_RECIPIENT)
        // Sanity: the masked form does still appear, so ops still gets
        // a recognisable recipient-shaped breadcrumb.
        text.contains('l***@victim.example.com')
        // And the diagnostic detail (exception class + scrubbed message)
        // is still useful for triage.
        text.contains('MailSendException')
        text.contains('550 5.1.1')
        text.contains('Recipient address rejected')
    }

    def "final-failure ERROR never leaks the raw recipient, nor a stack trace containing it"() {
        given:
        def svc = newService()
        // Every attempt throws the same exception so the retry budget
        // is exhausted and the give-up ERROR fires.
        mailSender.send(_ as MimeMessage) >> {
            throw new MailSendException(FAILURE_MESSAGE)
        }

        when:
        svc.send(LEAK_RECIPIENT, 'Test subject', 'Test body')
        svc.awaitSmtpForTests()

        then:
        def errorEvents = appender.list.findAll { it.level == Level.ERROR }
        errorEvents.size() == 1
        def text = capturedText()
        // Bug: pre-fix code logged lastError.message AND passed
        // lastError as the trailing SLF4J throwable arg, so the
        // recipient leaked via the message AND the stack trace.
        !text.contains(LEAK_RECIPIENT)
        text.contains('l***@victim.example.com')
        text.contains('MailSendException')
        // No throwable was attached to the ERROR event (the stack
        // trace was the secondary leak vector — confirm it's gone).
        errorEvents[0].throwableProxy == null
    }

    def "scrubEmails handles a multi-recipient SMTP response without leaking any of them"() {
        // SMTPSendFailedException can list multiple invalid addresses
        // separated by spaces, commas, or angle brackets. The regex must
        // catch every one of them, not just the first.
        given:
        def svc = newService()
        def message = "Invalid addresses; nested exception is " +
            "<${LEAK_RECIPIENT}>, <secondary@victim.example.com>: rejected"
        mailSender.send(_ as MimeMessage) >> {
            throw new MailSendException(message)
        }

        when:
        svc.send(LEAK_RECIPIENT, 'Test', 'Body')
        svc.awaitSmtpForTests()

        then:
        def text = capturedText()
        !text.contains(LEAK_RECIPIENT)
        !text.contains('secondary@victim.example.com')
        text.contains('l***@victim.example.com')
        text.contains('s***@victim.example.com')
    }
}
