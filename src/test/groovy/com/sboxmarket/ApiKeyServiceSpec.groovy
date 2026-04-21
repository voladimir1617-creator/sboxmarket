package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.ApiKey
import com.sboxmarket.repository.ApiKeyRepository
import com.sboxmarket.service.ApiKeyService
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for API-key minting, revocation, and authentication.
 *
 * The critical property: the raw token is only ever returned once (on
 * create) — never stored. Re-authentication must hash the presented
 * token and compare against the DB hash. A revoked key can never come
 * back. A wrong prefix is rejected without a DB lookup.
 */
class ApiKeyServiceSpec extends Specification {

    ApiKeyRepository apiKeyRepository = Mock()
    TextSanitizer    textSanitizer    = Mock() {
        cleanShort(_) >> { String s -> s }
    }
    // Batch 691 — email + user repo are optional dependencies; most
    // tests don't touch them but the mint-alert tests below do.
    com.sboxmarket.repository.SteamUserRepository steamUserRepository = Mock()
    com.sboxmarket.service.EmailService           emailService        = Mock()

    @Subject
    ApiKeyService service = new ApiKeyService(
        apiKeyRepository:     apiKeyRepository,
        textSanitizer:        textSanitizer,
        steamUserRepository:  steamUserRepository,
        emailService:         emailService
    )

    def "create returns a persisted key + a one-time raw token"() {
        given:
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.create(10L, 'production bot')

        then:
        result.key != null
        result.key.userId == 10L
        result.key.label == 'production bot'
        result.key.tokenHash != null
        result.key.tokenHash.length() == 64  // sha-256 hex
        result.token.startsWith('sbx_live_')
        // publicPrefix is visible and matches the first chars of the raw token
        result.token.startsWith(result.key.publicPrefix)
    }

    def "created token is NOT equal to the stored hash"() {
        given:
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.create(10L, 'bot')

        then:
        result.token != result.key.tokenHash
    }

    def "two minted keys produce different tokens and different hashes"() {
        given:
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def a = service.create(10L, 'one')
        def b = service.create(10L, 'two')

        then:
        a.token != b.token
        a.key.tokenHash != b.key.tokenHash
    }

    // ── authenticate ──────────────────────────────────────────────

    def "authenticate returns the user id when hash matches a live key"() {
        given:
        def minted
        apiKeyRepository.save(_) >> { args -> def k = args[0]; minted = k; k }
        def createResult = service.create(10L, 'bot')
        // Rebind the mock to now return the minted key when queried by hash
        apiKeyRepository.findByTokenHash(minted.tokenHash) >> minted

        when:
        def userId = service.authenticate(createResult.token)

        then:
        userId == 10L
    }

    def "authenticate returns null for a wrong prefix"() {
        when:
        def userId = service.authenticate('notaprefix_garbage')

        then:
        userId == null
        0 * apiKeyRepository.findByTokenHash(_)
    }

    def "authenticate returns null when the hash is not in the DB"() {
        given:
        apiKeyRepository.findByTokenHash(_) >> null

        when:
        def userId = service.authenticate('sbx_live_garbage1234567890abcdef')

        then:
        userId == null
    }

    def "authenticate returns null for a revoked key"() {
        given:
        def revoked = new ApiKey(userId: 10L, tokenHash: 'abc', revoked: true)
        apiKeyRepository.findByTokenHash(_) >> revoked

        when:
        def userId = service.authenticate('sbx_live_whatever')

        then:
        userId == null
    }

    def "authenticate null/empty returns null without a repo call"() {
        when:
        def a = service.authenticate(null)
        def b = service.authenticate('')

        then:
        a == null
        b == null
        0 * apiKeyRepository.findByTokenHash(_)
    }

    // ── revoke ────────────────────────────────────────────────────

    def "revoke flips revoked=true for the owner"() {
        given:
        def key = new ApiKey(id: 7L, userId: 10L, revoked: false, publicPrefix: 'sbx_live_abc')
        apiKeyRepository.findById(7L) >> Optional.of(key)
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.revoke(10L, 7L)

        then:
        result.revoked == true
    }

    def "revoke forbids non-owner"() {
        given:
        def key = new ApiKey(id: 7L, userId: 10L, revoked: false)
        apiKeyRepository.findById(_) >> Optional.of(key)

        when:
        service.revoke(99L, 7L)

        then:
        thrown(ForbiddenException)
    }

    def "revoke 404s for unknown key id"() {
        given:
        apiKeyRepository.findById(_) >> Optional.empty()

        when:
        service.revoke(10L, 999L)

        then:
        thrown(NotFoundException)
    }

    // ── label handling ────────────────────────────────────────────

    def "create falls back to 'Untitled' when label is null"() {
        given:
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.create(10L, null)

        then:
        result.key.label == 'Untitled'
    }

    def "revokeAll flips every non-revoked key for the user and returns the count (batch 705)"() {
        given:
        def keys = [
            new ApiKey(id: 1L, userId: 10L, revoked: false, label: 'a', publicPrefix: 'sbx_live_aa'),
            new ApiKey(id: 2L, userId: 10L, revoked: false, label: 'b', publicPrefix: 'sbx_live_bb'),
            new ApiKey(id: 3L, userId: 10L, revoked: true,  label: 'c', publicPrefix: 'sbx_live_cc'),
        ]
        apiKeyRepository.findByUser(10L) >> keys

        when:
        def n = service.revokeAll(10L)

        then:
        n == 2                          // only the 2 non-revoked rows
        keys[0].revoked == true
        keys[1].revoked == true
        keys[2].revoked == true         // was already revoked
        1 * apiKeyRepository.saveAll({ it.size() == 2 })
    }

    def "revokeAll returns 0 when the user has no active keys (idempotent)"() {
        given:
        apiKeyRepository.findByUser(10L) >> [
            new ApiKey(id: 1L, userId: 10L, revoked: true, label: 'old')
        ]

        when:
        def n = service.revokeAll(10L)

        then:
        n == 0
        0 * apiKeyRepository.saveAll(_)
    }

    def "revokeAll returns 0 on an empty key set (no 404)"() {
        given:
        apiKeyRepository.findByUser(10L) >> []

        when:
        def n = service.revokeAll(10L)

        then:
        n == 0
        0 * apiKeyRepository.saveAll(_)
    }

    def "create rejects once the user hits the active-key ceiling (batch 692)"() {
        given:
        // User is already at exactly 20 active keys — next mint must refuse.
        apiKeyRepository.countActiveByUser(10L) >> 20L
        def user = new com.sboxmarket.model.SteamUser(id: 10L, email: 'user@example.com', displayName: 'Al')
        steamUserRepository.findById(10L) >> Optional.of(user)

        when:
        service.create(10L, 'bot-21')

        then:
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'API_KEY_LIMIT'
        // Mustn't touch save() or fire the alert email — the mint is
        // refused before any side-effect.
        0 * apiKeyRepository.save(_)
        0 * emailService.sendApiKeyMinted(_, _, _, _, _)
    }

    def "create allows the mint right up to the ceiling"() {
        given:
        // 19 active — this call lands at exactly 20 which is still allowed.
        apiKeyRepository.countActiveByUser(10L) >> 19L
        apiKeyRepository.save(_) >> { args -> args[0] }
        def user = new com.sboxmarket.model.SteamUser(id: 10L, email: null, displayName: 'Al')
        steamUserRepository.findById(10L) >> Optional.of(user)

        when:
        def result = service.create(10L, 'bot-20')

        then:
        notThrown(Exception)
        result.token?.startsWith('sbx_live_')
    }

    def "create sends a security-alert email to the user's address (batch 691)"() {
        given:
        def user = new com.sboxmarket.model.SteamUser(id: 10L, email: 'user@example.com', displayName: 'Al')
        steamUserRepository.findById(10L) >> Optional.of(user)
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        service.create(10L, 'my-bot', 'RW')

        then:
        // Security alert email must fire with the user's email, label,
        // scope, and a prefix fragment. Non-opt-out per the security-
        // email matrix (mirrors batch 606 sign-in alert, batch 502
        // wallet-frozen alert).
        1 * emailService.sendApiKeyMinted('user@example.com', 'Al', 'my-bot', 'RW', _)
    }

    def "create silently skips the email when no address is on file"() {
        given:
        def user = new com.sboxmarket.model.SteamUser(id: 10L, email: null, displayName: 'Al')
        steamUserRepository.findById(10L) >> Optional.of(user)
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        service.create(10L, 'my-bot', 'RW')

        then:
        // No email set → nothing to alert — must not throw NPE.
        0 * emailService.sendApiKeyMinted(_, _, _, _, _)
    }

    def "create DOES NOT abort the mint when the alert email fails"() {
        given:
        def user = new com.sboxmarket.model.SteamUser(id: 10L, email: 'user@example.com', displayName: 'Al')
        steamUserRepository.findById(10L) >> Optional.of(user)
        apiKeyRepository.save(_) >> { args -> args[0] }
        emailService.sendApiKeyMinted(_, _, _, _, _) >> { throw new RuntimeException('SMTP down') }

        when:
        def result = service.create(10L, 'my-bot', 'RW')

        then:
        // The raw token must still be returned; a logging/email side-
        // effect failure must not lose the token (which can't be
        // regenerated).
        result.token?.startsWith('sbx_live_')
        result.key != null
    }

    def "create defaults scope to 'RW' when scope arg is blank (batch 670)"() {
        given:
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.create(10L, 'bot')

        then:
        result.key.scope == 'RW'
    }

    def "create honours 'RO' scope (case-insensitive)"() {
        given:
        apiKeyRepository.save(_) >> { args -> args[0] }

        expect:
        ['RO', 'ro', 'Ro', 'rO'].each { s ->
            def result = service.create(10L, 'readonly-bot', s)
            assert result.key.scope == 'RO'
        }
    }

    def "create coerces unknown scope values back to 'RW' (no silent RO)"() {
        given:
        apiKeyRepository.save(_) >> { args -> args[0] }

        expect:
        ['WRITE_ONLY', 'admin', 'RWX', '', ' '].each { s ->
            def result = service.create(10L, 'bot', s)
            // Anything unrecognised falls back to RW — never silently
            // degrade to RO (that would break existing callers) and
            // never upgrade to a fictional "RWX" (prevents SQL
            // constraint violation on the V51 CHECK).
            assert result.key.scope == 'RW'
        }
    }

    def "authenticateWithScope returns {userId, scope} for a valid key"() {
        given:
        def key = new ApiKey(
            id: 1L, userId: 10L, scope: 'RO', revoked: false,
            publicPrefix: 'sbx_live_abc',
            tokenHash: '9b03a45db5b73b96cc16dfda41bf7bef90eaa59ca164f9cb1b1ec45e2c69124a'
        )
        apiKeyRepository.findByTokenHash(_) >> key
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def ctx = service.authenticateWithScope('sbx_live_deadbeef')

        then:
        ctx != null
        ctx.userId == 10L
        ctx.scope == 'RO'
    }

    def "authenticateWithScope returns null for a revoked key"() {
        given:
        def key = new ApiKey(id: 1L, userId: 10L, scope: 'RO', revoked: true, tokenHash: 'h')
        apiKeyRepository.findByTokenHash(_) >> key

        expect:
        service.authenticateWithScope('sbx_live_deadbeef') == null
    }

    // ── JSON serialization (bug #18) ─────────────────────────────

    def "ApiKey JSON serialization omits tokenHash so /api/api-keys never leaks it"() {
        given:
        def key = new ApiKey(
            id:           7L,
            userId:       10L,
            publicPrefix: 'sbx_live_abc',
            tokenHash:    'deadbeef' * 8,   // 64-char fake hash
            label:        'bot',
            revoked:      false,
            createdAt:    1700000000000L
        )

        when:
        def json = new ObjectMapper().writeValueAsString(key)

        then:
        !json.contains('tokenHash')
        !json.contains('deadbeefdeadbeef')
        json.contains('"publicPrefix":"sbx_live_abc"')
        json.contains('"label":"bot"')
        json.contains('"revoked":false')
    }
}
