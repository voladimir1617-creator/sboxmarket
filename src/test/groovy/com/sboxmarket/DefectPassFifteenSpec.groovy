package com.sboxmarket

import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 15: email verification.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassFifteenSpec extends Specification {

    @Autowired ProfileController profileController
    @Autowired SteamUserRepository userRepo

    String uniq = String.valueOf(System.nanoTime())

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        req.session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req
    }

    private SteamUser user(String tag, Map extra = [:]) {
        userRepo.save(new SteamUser([steamId64: "765${tag}${uniq}".take(17), displayName: tag] + extra))
    }

    def "saving the already-verified address again keeps it verified"() {
        given:
        def email = "same${uniq}@example.com"
        def u = user('11', [email: email, canonicalEmail: email, emailVerified: true])

        when:
        def body = profileController.setEmail([email: email.toUpperCase()], signedIn(u.id)).body

        then:
        body.verified == true
        with(userRepo.findById(u.id).get()) {
            emailVerified == true
            emailVerificationToken == null
        }
    }

    def "an address another account typed in and never verified does not block its real owner"() {
        given:
        def email = "victim${uniq}@example.com"
        def squatter = user('12', [email: email, canonicalEmail: email, emailVerified: false,
            emailVerificationToken: 'abc', emailVerificationTokenExpiresAt: System.currentTimeMillis() - 1000L])
        def owner = user('13')

        when:
        def body = profileController.setEmail([email: email], signedIn(owner.id)).body

        then:
        body.email == email
        userRepo.findById(owner.id).get().canonicalEmail == email
        userRepo.findById(squatter.id).get().email == null
    }

    def "a verified address on another account, or one with a live link, still blocks"() {
        given:
        def email = "taken${uniq}@example.com"
        user('14', [email: email, canonicalEmail: email, emailVerified: verified,
            emailVerificationToken: token, emailVerificationTokenExpiresAt: System.currentTimeMillis() + 3_600_000L])
        def other = user('15')

        when:
        profileController.setEmail([email: email], signedIn(other.id))

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'EMAIL_TAKEN'

        where:
        verified | token
        true     | null
        false    | 'live-token'
    }

    def "opening the verification link again after verifying says verified, not 'token does not match'"() {
        given:
        def email = "done${uniq}@example.com"
        def u = user('16', [email: email, canonicalEmail: email, emailVerified: true])

        when:
        def body = profileController.verifyEmail([token: 'whatever-was-in-the-link'], signedIn(u.id)).body

        then:
        body.verified == true
    }
}
