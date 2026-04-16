package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.SteamAuthService
import spock.lang.Specification
import spock.lang.Subject

/**
 * buildLoginUrl correctness + upsertUser find-or-create.
 *
 * The Steam round-trip network calls (verifyReturn, fetchViaWebApi,
 * fetchViaPublicXml) are not tested here — those either succeed
 * against a real Steam server or fail gracefully to null, both of which
 * are fine.
 */
class SteamAuthServiceSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()
    WalletRepository    walletRepository    = Mock()
    AdminService        adminService        = Mock()

    @Subject
    SteamAuthService service = new SteamAuthService(
        steamUserRepository: steamUserRepository,
        walletRepository:    walletRepository,
        adminService:        adminService,
        steamApiKey:         '',
        realm:               'http://localhost:8080/',
        returnUrl:           'http://localhost:8080/api/auth/steam/return'
    )

    def setup() {
        // Bypass network calls by overriding both profile lookups
        service.metaClass.fetchViaWebApi    = { String id -> null }
        service.metaClass.fetchViaPublicXml = { String id -> null }
    }

    // ── buildLoginUrl ─────────────────────────────────────────────

    def "buildLoginUrl targets Steam OpenID endpoint with encoded realm and return URL"() {
        when:
        def url = service.buildLoginUrl()

        then:
        url.startsWith('https://steamcommunity.com/openid/login?')
        url.contains('openid.mode=checkid_setup')
        url.contains('openid.ns=' + URLEncoder.encode('http://specs.openid.net/auth/2.0', 'UTF-8'))
        url.contains('openid.return_to=' + URLEncoder.encode('http://localhost:8080/api/auth/steam/return', 'UTF-8'))
    }

    // ── verifyReturn guards ───────────────────────────────────────

    def "verifyReturn returns null for an empty query string"() {
        expect:
        service.verifyReturn(null, 'whatever') == null
        service.verifyReturn('', 'whatever') == null
    }

    // ── upsertUser: find-or-create ────────────────────────────────

    def "upsertUser creates a new SteamUser AND a matching wallet on first login"() {
        given:
        steamUserRepository.findBySteamId64('111') >> null
        steamUserRepository.save(_) >> { args -> def u = args[0]; u.id = u.id ?: 1L; u }
        walletRepository.save(_) >> { args -> args[0] }

        when:
        def user = service.upsertUser('111')

        then:
        user.steamId64 == '111'
        user.displayName == 'Player_111'
        1 * walletRepository.save({ Wallet w -> w.username == 'steam_111' && w.balance == BigDecimal.ZERO })
        1 * adminService.promoteBootstrapAdmin(_)
    }

    def "upsertUser does NOT create a duplicate wallet on subsequent logins"() {
        given:
        def existing = new SteamUser(id: 10L, steamId64: '111', displayName: 'Alice')
        steamUserRepository.findBySteamId64('111') >> existing
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.upsertUser('111')

        then:
        0 * walletRepository.save(_)
    }

    def "upsertUser bumps lastLoginAt on existing users"() {
        given:
        def existing = new SteamUser(id: 10L, steamId64: '111', displayName: 'Alice', lastLoginAt: 0L)
        steamUserRepository.findBySteamId64('111') >> existing
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def user = service.upsertUser('111')

        then:
        user.lastLoginAt > 0L
    }

    def "upsertUser calls promoteBootstrapAdmin even when admin service throws"() {
        given:
        steamUserRepository.findBySteamId64('111') >> null
        steamUserRepository.save(_) >> { args -> def u = args[0]; u.id = 1L; u }
        walletRepository.save(_) >> { args -> args[0] }
        adminService.promoteBootstrapAdmin(_) >> { throw new RuntimeException('bootstrap failed') }

        when:
        def user = service.upsertUser('111')

        then:
        // Exception is swallowed so login still succeeds
        noExceptionThrown()
        user != null
    }

    def "upsertUser falls back gracefully when no profile can be fetched"() {
        given:
        steamUserRepository.findBySteamId64('111') >> null
        steamUserRepository.save(_) >> { args -> def u = args[0]; u.id = 1L; u }
        walletRepository.save(_) >> { args -> args[0] }

        when:
        def user = service.upsertUser('111')

        then:
        // No profile data landed but the user still exists with the fallback name
        user.displayName == 'Player_111'
    }

    // ── JSON serialization (bug #19) ─────────────────────────────

    def "SteamUser JSON never leaks totpSecret, emailVerificationToken, or lastTotpStep"() {
        given:
        def user = new SteamUser(
            id:                     1L,
            steamId64:              '76561197960287930',
            displayName:            'Alice',
            avatarUrl:              'https://avatars.example.com/a.jpg',
            profileUrl:             'https://steamcommunity.com/id/alice',
            email:                  'alice@example.com',
            emailVerified:          true,
            emailVerificationToken: 'SECRET-TOKEN-XYZ',
            totpSecret:             'JBSWY3DPEHPK3PXP',
            lastTotpStep:           99999L,
            role:                   'USER',
            banned:                 false
        )

        when:
        def json = new ObjectMapper().writeValueAsString(user)

        then:
        // Secrets MUST NOT appear in the JSON — bug #19 was that /api/auth/steam/me
        // and /api/admin/users were serializing the raw user entity, including
        // the 2FA secret and the one-time email verification token.
        !json.contains('totpSecret')
        !json.contains('JBSWY3DPEHPK3PXP')
        !json.contains('emailVerificationToken')
        !json.contains('SECRET-TOKEN-XYZ')
        !json.contains('lastTotpStep')
        // Safe/expected fields still render
        json.contains('"displayName":"Alice"')
        json.contains('"steamId64":"76561197960287930"')
        json.contains('"email":"alice@example.com"')
        json.contains('"emailVerified":true')
    }
}
