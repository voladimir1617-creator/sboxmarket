package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
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
        // A persisted row carries a generated id — set one so the
        // race-safety scalar re-check resolves.
        minted.id = 42L
        // Rebind the mock to return the minted key by hash AND report
        // the row as live (count == 1) on the wave-110 race re-check.
        apiKeyRepository.findByTokenHash(minted.tokenHash) >> minted
        apiKeyRepository.countLiveById(42L) >> 1L

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
        def revoked = new ApiKey(id: 3L, userId: 10L, tokenHash: 'abc', revoked: true)
        apiKeyRepository.findByTokenHash(_) >> revoked

        when:
        def userId = service.authenticate('sbx_live_whatever')

        then:
        userId == null
        // A revoked key is refused at the hash-lookup stage — the
        // race-safety re-check + lastUsedAt write must never run.
        0 * apiKeyRepository.countLiveById(_)
        0 * apiKeyRepository.save(_)
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

    def "revoke 404s for a non-owner (NOT 403) so key ids can't be enumerated"() {
        given:
        // Key 7 exists but belongs to user 10. User 99 tries to revoke it.
        def key = new ApiKey(id: 7L, userId: 10L, revoked: false)
        apiKeyRepository.findById(_) >> Optional.of(key)

        when:
        service.revoke(99L, 7L)

        then:
        // Must be a 404 — identical to the missing-id case — so an
        // attacker iterating DELETE /api/api-keys/{id} cannot tell
        // "exists but not yours" apart from "doesn't exist".
        thrown(NotFoundException)
        // The other user's key must NOT be revoked as a side-effect.
        key.revoked == false
        // No write may happen for a non-owner.
        0 * apiKeyRepository.save(_)
    }

    def "revoke 404s for unknown key id"() {
        given:
        apiKeyRepository.findById(_) >> Optional.empty()

        when:
        service.revoke(10L, 999L)

        then:
        thrown(NotFoundException)
    }

    def "revoke audit-logs against the revoking owner, never the victim"() {
        given:
        def audit = Mock(com.sboxmarket.service.AuditService)
        def svc = new ApiKeyService(
            apiKeyRepository: apiKeyRepository,
            textSanitizer:    textSanitizer,
            auditService:     audit)
        def key = new ApiKey(id: 7L, userId: 10L, revoked: false, publicPrefix: 'sbx_live_abc')
        apiKeyRepository.findById(7L) >> Optional.of(key)
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        svc.revoke(10L, 7L)

        then:
        // actorId + subjectId are the owner (10), not some other user.
        1 * audit.log(_, 10L, 10L, 7L, _)
    }

    // ── token storage / hashing invariant ─────────────────────────

    def "the stored hash is the SHA-256 of the raw token (lookup uses the same hash)"() {
        given:
        ApiKey minted
        apiKeyRepository.save(_) >> { args -> minted = args[0]; args[0] }

        when:
        def result = service.create(10L, 'bot')
        // Independently re-hash the raw token the way authenticate() does.
        def md = java.security.MessageDigest.getInstance('SHA-256')
        def expected = md.digest(result.token.getBytes('UTF-8')).encodeHex().toString()

        then:
        // What we persisted equals SHA-256(raw): proves storage is a
        // hash (not plaintext) AND that authenticate() can find it.
        minted.tokenHash == expected
        // The raw token itself is never the stored value.
        minted.tokenHash != result.token
        // Hash is irreversible-width and case-stable lowercase hex.
        minted.tokenHash ==~ /[0-9a-f]{64}/
    }

    def "raw token is 9-char prefix + 64 hex chars (256 bits of entropy)"() {
        given:
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.create(10L, 'bot')

        then:
        result.token.startsWith('sbx_live_')
        // 32 random bytes -> 64 hex chars after the 'sbx_live_' prefix.
        result.token.length() == 'sbx_live_'.length() + 64
        result.token.substring('sbx_live_'.length()) ==~ /[0-9a-f]{64}/
    }

    def "publicPrefix is a safe 14-char fragment that never reveals the secret body"() {
        given:
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.create(10L, 'bot')

        then:
        result.key.publicPrefix.length() == 14
        result.key.publicPrefix == result.token.substring(0, 14)
        // The prefix is only 5 hex chars of the 64-char secret body —
        // far too little to brute-force the remaining 236 bits.
        !result.key.tokenHash.contains(result.key.publicPrefix.substring(9))
    }

    def "authenticate stamps lastUsedAt and persists it"() {
        given:
        def before = System.currentTimeMillis()
        def key = new ApiKey(id: 5L, userId: 10L, revoked: false, tokenHash: 't', lastUsedAt: null)
        apiKeyRepository.findByTokenHash(_) >> key
        apiKeyRepository.countLiveById(5L) >> 1L

        when:
        def uid = service.authenticate('sbx_live_whatever')

        then:
        uid == 10L
        key.lastUsedAt != null
        key.lastUsedAt >= before
        1 * apiKeyRepository.save(key)
    }

    def "authenticate is case-sensitive on the prefix (SBX_LIVE_ is rejected)"() {
        when:
        def uid = service.authenticate('SBX_LIVE_deadbeef')

        then:
        // Prefix check must be exact — an attacker can't slip past the
        // fast-reject with a case variant.
        uid == null
        0 * apiKeyRepository.findByTokenHash(_)
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
        // Production now gates the alert through emailService
        // .canSendSecurityTo(user) — same matrix every other security
        // alert (sign-in, wallet-frozen) goes through. Stub it true so
        // the test exercises the actual `sendApiKeyMinted` send path.
        emailService.canSendSecurityTo(_) >> true

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
        apiKeyRepository.countLiveById(1L) >> 1L
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

    // ── revocation race: authenticate must never resurrect a revoked key ──

    def "authenticate consults the scalar live-probe (not findById) before stamping lastUsedAt"() {
        given:
        // Wave 110 — the race-safety re-check must be a scalar DB
        // probe, NOT a `findById` round-trip. `findById` short-circuits
        // to Hibernate's L1 cache and returns the SAME managed entity
        // that `findByTokenHash` already loaded — making it blind to a
        // concurrent revoke that committed in another transaction.
        def key = new ApiKey(id: 5L, userId: 10L, revoked: false, tokenHash: 't')
        apiKeyRepository.findByTokenHash(_) >> key
        apiKeyRepository.countLiveById(5L) >> 1L

        when:
        def uid = service.authenticate('sbx_live_whatever')

        then:
        uid == 10L
        key.lastUsedAt != null
        1 * apiKeyRepository.save(key)
        // findById must NOT be the race-check vector — its L1-cache
        // short-circuit silently un-protects the auth path against
        // concurrent revoke.
        0 * apiKeyRepository.findById(_)
    }

    def "authenticate aborts (no save) if the key was revoked between hash lookup and re-check"() {
        given:
        // Index lookup still sees the key as live, but by the time we
        // re-check, a concurrent revoke()/revokeAll() has committed.
        // The fresh scalar probe sees zero live rows for that id and
        // we fail closed.
        def atLookup = new ApiKey(id: 5L, userId: 10L, revoked: false, tokenHash: 't')
        apiKeyRepository.findByTokenHash(_) >> atLookup
        apiKeyRepository.countLiveById(5L) >> 0L

        when:
        def uid = service.authenticate('sbx_live_whatever')

        then:
        uid == null
        0 * apiKeyRepository.save(_)
    }

    def "authenticateWithScope aborts (no save) if the key was revoked between hash lookup and re-check"() {
        given:
        def atLookup = new ApiKey(id: 9L, userId: 10L, scope: 'RW', revoked: false, tokenHash: 't')
        apiKeyRepository.findByTokenHash(_) >> atLookup
        apiKeyRepository.countLiveById(9L) >> 0L

        when:
        def ctx = service.authenticateWithScope('sbx_live_whatever')

        then:
        ctx == null
        0 * apiKeyRepository.save(_)
    }

    def "authenticate returns null if the key row is deleted between hash lookup and re-check"() {
        given:
        // count == 0 collapses both "row gone" and "row revoked" into
        // the same fail-closed branch.
        def atLookup = new ApiKey(id: 5L, userId: 10L, revoked: false, tokenHash: 't')
        apiKeyRepository.findByTokenHash(_) >> atLookup
        apiKeyRepository.countLiveById(5L) >> 0L

        when:
        def uid = service.authenticate('sbx_live_whatever')

        then:
        uid == null
        0 * apiKeyRepository.save(_)
    }

    def "authenticate treats a null revoked flag as live (legacy-row safety)"() {
        given:
        // revoked column is NOT NULL in the schema, but a defensive
        // null check must not NPE or mis-classify a legacy row. The
        // countLiveById query whitelists `revoked IS NULL OR = false`
        // so a legacy row still counts as live (count == 1).
        def key = new ApiKey(id: 5L, userId: 10L, revoked: null, tokenHash: 't')
        apiKeyRepository.findByTokenHash(_) >> key
        apiKeyRepository.countLiveById(5L) >> 1L
        apiKeyRepository.save(_) >> { args -> args[0] }

        when:
        def uid = service.authenticate('sbx_live_whatever')

        then:
        noExceptionThrown()
        uid == 10L
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
