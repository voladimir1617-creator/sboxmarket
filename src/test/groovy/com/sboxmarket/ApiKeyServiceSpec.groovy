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

    @Subject
    ApiKeyService service = new ApiKeyService(
        apiKeyRepository: apiKeyRepository,
        textSanitizer:    textSanitizer
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
