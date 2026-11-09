package com.sboxmarket

import com.sboxmarket.controller.UnsubscribeController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.javamail.JavaMailSender
import spock.lang.Specification

/**
 * End to end: the one-click URL a bucketed email carries, fed back to
 * UnsubscribeController exactly as Gmail's one-click POST would, mutes
 * only that bucket. Before this, the List-Unsubscribe URL had no
 * `kind`, so one "Unsubscribe" tap on a price-drop alert switched off
 * receipts, sale and offer emails too.
 */
class UnsubscribeBucketLinkSpec extends Specification {

    JavaMailSender mailSender = Mock()
    SteamUserRepository steamUserRepository = Mock()

    private EmailService newService() {
        mailSender.createMimeMessage() >> { new MimeMessage((jakarta.mail.Session) null) }
        def svc = new EmailService(
            mailSender: mailSender,
            smtpHost: 'smtp.example.com',
            fromAddress: 'no-reply@skinbox.market',
            fromName: 'SkinBox',
            replyToAddress: 'support@skinbox.market',
            unsubscribeSecret: 'test-unsubscribe-secret',
            publicUrl: 'https://skinbox.example'
        )
        svc.init()
        svc
    }

    private static Map<String, String> query(String url) {
        def q = url.substring(url.indexOf('?') + 1)
        q.split('&').collectEntries { kv ->
            def i = kv.indexOf('=')
            [(kv.substring(0, i)): URLDecoder.decode(kv.substring(i + 1), 'UTF-8')]
        }
    }

    def "a POST to a WATCHLIST email's List-Unsubscribe URL mutes only WATCHLIST"() {
        given:
        def svc = newService()
        def controller = new UnsubscribeController(emailService: svc, steamUserRepository: steamUserRepository)
        def user = new SteamUser(id: 9L, email: 'alice@example.com', emailVerified: true,
                                 emailNotificationsEnabled: true, mutedEmailKinds: '')
        String header = null
        String footerUrl = null
        mailSender.send(_ as MimeMessage) >> { MimeMessage m ->
            header = (m.getHeader('List-Unsubscribe') as List)[0]
            def text = m.getContent().toString()
            def line = text.readLines().find { it.startsWith('One-click unsubscribe: ') }
            footerUrl = line?.substring('One-click unsubscribe: '.length())
        }
        steamUserRepository.findByEmailIgnoreCase('alice@example.com') >> [user]

        when: 'a price-drop alert goes out'
        svc.sendPriceDrop('alice@example.com', 'Alice', 'Wizard Hat',
            new BigDecimal('20.00'), new BigDecimal('25.00'), '/item/1')
        svc.awaitSmtpForTests()

        then: 'header and footer carry the same bucketed URL'
        header != null
        header.startsWith('<') && header.endsWith('>')
        def url = header[1..-2]
        url == footerUrl
        query(url).kind == 'WATCHLIST'

        when: "the mail client's RFC 8058 one-click POST hits that URL"
        def p = query(url)
        def resp = controller.unsubscribePost(p.email, p.t, p.kind, 'One-Click')

        then: 'only the WATCHLIST bucket is muted'
        resp.statusCode.value() == 200
        resp.body == 'OK'
        user.mutedEmailKinds == 'WATCHLIST'
        user.emailNotificationsEnabled
        !svc.canSendTo(user, 'WATCHLIST')
        svc.canSendTo(user, 'TRADES')
        svc.canSendTo(user, 'AUCTIONS')
    }
}
