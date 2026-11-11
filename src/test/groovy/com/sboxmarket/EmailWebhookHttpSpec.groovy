package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

/** The bounce webhook is reachable with no session or CSRF token, and only a signed call changes anything. */
@SpringBootTest(properties = ['app.email.webhook-secret=whsec_dGVzdC1zaWduaW5nLWtleS0wMTIzNDU2Nzg5YWJjZGVm'])
@AutoConfigureMockMvc
@ActiveProfiles('test')
class EmailWebhookHttpSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired SteamUserRepository userRepo

    static String sign(String id, String ts, String body) {
        def mac = Mac.getInstance('HmacSHA256')
        mac.init(new SecretKeySpec('test-signing-key-0123456789abcdef'.getBytes(StandardCharsets.UTF_8), 'HmacSHA256'))
        'v1,' + Base64.encoder.encodeToString(mac.doFinal("${id}.${ts}.${body}".toString().getBytes(StandardCharsets.UTF_8)))
    }

    def post(String body, String sig, String ts = "${System.currentTimeMillis().intdiv(1000)}") {
        def req = MockMvcRequestBuilders.post('/api/email/webhook')
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header('svix-id', 'msg_http').header('svix-timestamp', ts)
        if (sig) req.header('svix-signature', sig)
        mockMvc.perform(req).andReturn().response
    }

    def "a signed bounce marks the account; an unsigned one is refused and changes nothing"() {
        given:
        def uniq = UUID.randomUUID().toString().replace('-', '').take(10)
        def addr = "bounce-${uniq}@example.com".toString()
        def u = userRepo.save(new SteamUser(steamId64: '76561195' + uniq.replaceAll(/[^0-9]/, '7').take(10).padRight(10, '7'),
                                            displayName: "Bounce-${uniq}", email: addr, emailVerified: true))
        def body = """{"type":"email.bounced","data":{"to":["${addr}"],"bounce":{"type":"Permanent"}}}""".toString()
        def ts = "${System.currentTimeMillis().intdiv(1000)}".toString()

        when:
        def refused = post(body, 'v1,AAAA', ts)

        then:
        refused.status == 401
        userRepo.findById(u.id).get().emailSuppressed == null

        when:
        def ok = post(body, sign('msg_http', ts, body), ts)

        then:
        ok.status == 200
        userRepo.findById(u.id).get().emailSuppressed == 'BOUNCE'
    }
}
