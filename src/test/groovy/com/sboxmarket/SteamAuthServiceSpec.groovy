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
 * buildLoginUrl correctness + upsertUser find-or-create + verifyReturn
 * security guards (OpenID replay protection and return_to validation).
 *
 * The profile-lookup network calls (fetchViaWebApi, fetchViaPublicXml)
 * are not tested here — those either succeed against a real Steam server
 * or fail gracefully to null, both of which are fine. verifyReturn's own
 * check_authentication round-trip is stubbed via the metaClass override
 * of checkAuthentication so the replay / return_to logic can be asserted
 * deterministically.
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

    // ── verifyReturn: replay + return_to (security QA) ─────────────

    /** A SteamID64 that satisfies STEAMID_REGEX. */
    private static final String STEAMID = '76561197960287930'
    private static final String CLAIMED_ID =
        "https://steamcommunity.com/openid/id/${STEAMID}"

    /**
     * Build a Steam-style raw (URL-encoded) /return query string. The
     * `return_to` defaults to the service's configured return URL so the
     * P2 check passes; pass a different value to exercise the mismatch
     * path. `nonce` is the value of openid.response_nonce.
     */
    private static String returnQuery(String nonce,
                                      String returnTo = 'http://localhost:8080/api/auth/steam/return') {
        [
            'openid.ns'            : 'http://specs.openid.net/auth/2.0',
            'openid.mode'          : 'id_res',
            'openid.op_endpoint'   : 'https://steamcommunity.com/openid/login',
            'openid.claimed_id'    : CLAIMED_ID,
            'openid.identity'      : CLAIMED_ID,
            'openid.return_to'     : returnTo,
            'openid.response_nonce': nonce,
            'openid.assoc_handle'  : '1234567890',
            'openid.signed'        : 'signed,op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle',
            'openid.sig'           : 'abcDEF123signaturebase64==',
        ].collect { k, v ->
            "${URLEncoder.encode(k, 'UTF-8')}=${URLEncoder.encode(v, 'UTF-8')}"
        }.join('&')
    }

    def "verifyReturn accepts a first-use nonce and returns the SteamID64"() {
        given: "Steam reports the assertion valid"
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }

        when:
        def steamId = service.verifyReturn(returnQuery('nonce-first-use'), CLAIMED_ID)

        then:
        steamId == STEAMID
    }

    def "verifyReturn rejects a replayed assertion (same nonce used twice)"() {
        given: "Steam keeps reporting is_valid:true — it does NOT enforce one-time use"
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }
        def query = returnQuery('nonce-replay-me')

        when: "the same callback URL is verified twice"
        def first  = service.verifyReturn(query, CLAIMED_ID)
        def second = service.verifyReturn(query, CLAIMED_ID)

        then: "the first use succeeds, the replay is rejected"
        first  == STEAMID
        second == null
    }

    def "verifyReturn rejects an assertion with no response_nonce"() {
        given:
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }

        when: "the callback carries an empty nonce"
        def steamId = service.verifyReturn(returnQuery(''), CLAIMED_ID)

        then:
        steamId == null
    }

    def "verifyReturn rejects a mismatched return_to before contacting Steam"() {
        given: "checkAuthentication would succeed if it were ever called"
        boolean networkCalled = false
        service.metaClass.checkAuthentication = { String body ->
            networkCalled = true
            'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n'
        }

        when: "the signed return_to points at an attacker-controlled host"
        def steamId = service.verifyReturn(
            returnQuery('nonce-bad-return-to', 'http://evil.example.com/api/auth/steam/return'),
            CLAIMED_ID)

        then: "login is rejected and no check_authentication round-trip happened"
        steamId == null
        !networkCalled
    }

    def "verifyReturn does not consume the nonce when Steam reports invalid"() {
        given: "Steam first rejects the assertion, then (hypothetically) accepts it"
        def query = returnQuery('nonce-not-burned')
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:false\n' }

        when: "a verification attempt fails at the is_valid check"
        def rejected = service.verifyReturn(query, CLAIMED_ID)

        and: "Steam now reports the SAME assertion valid and it is retried"
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }
        def accepted = service.verifyReturn(query, CLAIMED_ID)

        then: "the failed attempt did not burn the nonce, so the genuine retry still works"
        rejected == null
        accepted == STEAMID
    }

    // ── verifyReturn: claimed_id must come from the SIGNED query ───
    //
    // The authentication-bypass class. The SteamID we log in as MUST be
    // derived from the claimed_id inside the raw, signature-verified
    // query string — never from a separately-supplied param, and only
    // when claimed_id/identity are actually covered by openid.signed.

    /**
     * Like {@link #returnQuery} but lets a test override openid.signed
     * and openid.claimed_id independently so the spoofing vectors can be
     * exercised. `signed` is the literal openid.signed CSV; `claimedId`
     * is the literal openid.claimed_id value in the raw query.
     */
    private static String craftedQuery(Map opts) {
        def nonce     = opts.nonce     ?: 'nonce-crafted'
        def signed    = opts.containsKey('signed')    ? opts.signed    : 'signed,op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle'
        def claimedId = opts.containsKey('claimedId') ? opts.claimedId : CLAIMED_ID
        [
            'openid.ns'            : 'http://specs.openid.net/auth/2.0',
            'openid.mode'          : 'id_res',
            'openid.op_endpoint'   : 'https://steamcommunity.com/openid/login',
            'openid.claimed_id'    : claimedId,
            'openid.identity'      : claimedId,
            'openid.return_to'     : 'http://localhost:8080/api/auth/steam/return',
            'openid.response_nonce': nonce,
            'openid.assoc_handle'  : '1234567890',
            'openid.signed'        : signed,
            'openid.sig'           : 'abcDEF123signaturebase64==',
        ].collect { k, v ->
            "${URLEncoder.encode(k as String, 'UTF-8')}=${URLEncoder.encode(v as String, 'UTF-8')}"
        }.join('&')
    }

    def "verifyReturn rejects an assertion where claimed_id is NOT in openid.signed"() {
        given: "Steam validly signs a REDUCED field set that omits claimed_id/identity"
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }

        when: "an attacker strips claimed_id/identity from openid.signed and substitutes a victim ID"
        def steamId = service.verifyReturn(
            craftedQuery(signed: 'signed,op_endpoint,return_to,response_nonce,assoc_handle',
                         claimedId: 'https://steamcommunity.com/openid/id/76561197960000000'))

        then: "the unsigned identity is not trusted — login is rejected"
        steamId == null
    }

    def "verifyReturn rejects when identity is signed but claimed_id is not"() {
        given:
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }

        when: "openid.signed lists identity but omits claimed_id"
        def steamId = service.verifyReturn(
            craftedQuery(signed: 'signed,op_endpoint,identity,return_to,response_nonce,assoc_handle'))

        then:
        steamId == null
    }

    def "verifyReturn rejects when openid.signed is absent entirely"() {
        given:
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }
        // Drop openid.signed from the query altogether.
        def query = returnQuery('nonce-no-signed').split('&')
            .findAll { !it.startsWith('openid.signed=') }.join('&')

        when:
        def steamId = service.verifyReturn(query, CLAIMED_ID)

        then:
        steamId == null
    }

    def "verifyReturn derives the SteamID from the SIGNED query, not the passed param"() {
        given: "Steam validly signs the attacker's OWN claimed_id"
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }
        // The raw, signed query carries the attacker's real SteamID...
        def query = craftedQuery(nonce: 'nonce-param-spoof', claimedId: CLAIMED_ID)

        when: "...but the caller passes a DIFFERENT (victim) claimed_id param"
        def steamId = service.verifyReturn(query, 'https://steamcommunity.com/openid/id/76561197960000000')

        then: "the disagreement is detected and the login is rejected — not silently mapped to either ID"
        steamId == null
    }

    def "verifyReturn accepts when the passed claimed_id param matches the signed value"() {
        given:
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }

        when: "the param agrees with the signed claimed_id"
        def steamId = service.verifyReturn(craftedQuery(nonce: 'nonce-param-ok'), CLAIMED_ID)

        then:
        steamId == STEAMID
    }

    def "verifyReturn works with no claimed_id param at all — the signed query is authoritative"() {
        given:
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }

        when: "the controller-supplied param is omitted (defaults to null)"
        def steamId = service.verifyReturn(craftedQuery(nonce: 'nonce-no-param'))

        then: "the SteamID still comes straight from the verified query string"
        steamId == STEAMID
    }

    def "verifyReturn rejects a malformed claimed_id in the signed query"() {
        given:
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }

        when: "the signed claimed_id is not a steamcommunity openid id URL"
        def steamId = service.verifyReturn(
            craftedQuery(claimedId: 'https://evil.example.com/openid/id/76561197960287930'))

        then:
        steamId == null
    }

    def "verifyReturn rejects a non-numeric SteamID smuggled into a steamcommunity URL"() {
        given:
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }

        when:
        def steamId = service.verifyReturn(
            craftedQuery(claimedId: 'https://steamcommunity.com/openid/id/notanumber'))

        then:
        steamId == null
    }

    def "verifyReturn rejects a callback with no claimed_id at all"() {
        given:
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }
        // Strip openid.claimed_id from an otherwise-valid query.
        def query = returnQuery('nonce-no-claimed').split('&')
            .findAll { !it.startsWith('openid.claimed_id=') }.join('&')

        when:
        def steamId = service.verifyReturn(query, null)

        then: "a missing claimed_id is a clean rejection, not an exception"
        noExceptionThrown()
        steamId == null
    }

    def "verifyReturn does not burn the nonce when the claimed_id-not-signed check rejects"() {
        given: "an assertion Steam validly signs over a field set missing claimed_id/identity"
        service.metaClass.checkAuthentication = { String body -> 'ns:http://specs.openid.net/auth/2.0\nis_valid:true\n' }
        def reduced = craftedQuery(nonce: 'nonce-signed-guard',
                                   signed: 'signed,op_endpoint,return_to,response_nonce,assoc_handle')

        when: "the reduced assertion is rejected by the openid.signed guard"
        def rejected = service.verifyReturn(reduced)

        and: "a genuine, fully-signed assertion later arrives carrying the SAME nonce"
        def genuine = craftedQuery(nonce: 'nonce-signed-guard')
        def accepted = service.verifyReturn(genuine)

        then: "the rejected attempt did not consume the nonce — the genuine login still works"
        rejected == null
        accepted == STEAMID
    }

    // ── verifyReturn: is_valid parsing is line-anchored ───────────

    def "verifyReturn does not accept is_valid:true smuggled as a substring"() {
        given: "a response where the literal text is_valid:true appears but not as a real field line"
        service.metaClass.checkAuthentication = { String body ->
            'ns:http://specs.openid.net/auth/2.0\nis_valid:true_butnotreally\n'
        }

        when:
        def steamId = service.verifyReturn(returnQuery('nonce-substring'), CLAIMED_ID)

        then: "only a whole-line is_valid:true counts"
        steamId == null
    }

    def "verifyReturn accepts is_valid:true with trailing whitespace or CRLF"() {
        given: "Steam's real responses use \\r\\n line endings"
        service.metaClass.checkAuthentication = { String body ->
            "ns:http://specs.openid.net/auth/2.0\r\nis_valid:true\r\n"
        }

        when:
        def steamId = service.verifyReturn(returnQuery('nonce-crlf'), CLAIMED_ID)

        then:
        steamId == STEAMID
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
