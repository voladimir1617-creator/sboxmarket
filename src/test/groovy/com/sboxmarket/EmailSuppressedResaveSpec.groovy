package com.sboxmarket

import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/** A bounced or spam-reported address is fixed by saving an address and opening the fresh link. */
@SpringBootTest
@ActiveProfiles("test")
class EmailSuppressedResaveSpec extends Specification {

    @Autowired ProfileController profileController
    @Autowired SteamUserRepository userRepo

    String uniq = String.valueOf(System.nanoTime())

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        req.session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req
    }

    def "re-saving a bounced verified address sends a fresh link instead of keeping it as is"() {
        given:
        def email = "bounced${uniq}@example.com"
        def u = userRepo.save(new SteamUser(steamId64: "76521${uniq}".take(17), displayName: 'b',
                email: email, canonicalEmail: email, emailVerified: true, emailSuppressed: 'BOUNCE'))

        when:
        def body = profileController.setEmail([email: email], signedIn(u.id)).body

        then:
        body.verified == false
        with(userRepo.findById(u.id).get()) {
            emailVerified == false
            emailVerificationToken != null
            emailSuppressed == null
        }
    }

    def "a new address clears the marker"() {
        given:
        def u = userRepo.save(new SteamUser(steamId64: "76522${uniq}".take(17), displayName: 'c',
                email: "old${uniq}@example.com", emailVerified: true, emailSuppressed: 'COMPLAINT'))

        when:
        profileController.setEmail([email: "new${uniq}@example.com"], signedIn(u.id))

        then:
        userRepo.findById(u.id).get().emailSuppressed == null
    }
}
