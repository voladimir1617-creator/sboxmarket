package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.ApiKey
import com.sboxmarket.repository.ApiKeyRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * API-key lifecycle management. Raw tokens leave this class exactly once — the
 * moment they are created. After that only the SHA-256 hash lives in the DB and
 * authentication happens by re-hashing the presented token and looking it up.
 */
@Service
@Slf4j
class ApiKeyService {

    private static final SecureRandom RNG = new SecureRandom()
    private static final String PREFIX = "sbx_live_"

    @Autowired ApiKeyRepository apiKeyRepository
    @Autowired(required = false) AuditService auditService
    @Autowired TextSanitizer textSanitizer

    List<ApiKey> listForUser(Long userId) {
        apiKeyRepository.findByUser(userId)
    }

    /**
     * Returns a map containing the persisted ApiKey (without token) AND the raw
     * plaintext token for one-time display. Callers MUST render the token then
     * discard it — it cannot be retrieved again.
     */
    @Transactional
    Map create(Long userId, String label) {
        def cleanLabel = textSanitizer.cleanShort(label) ?: 'Untitled'
        def raw = PREFIX + randomToken(32)
        def hash = sha256(raw)
        def prefix = raw.substring(0, Math.min(14, raw.length()))
        def key = new ApiKey(
            userId:       userId,
            publicPrefix: prefix,
            tokenHash:    hash,
            label:        cleanLabel
        )
        apiKeyRepository.save(key)
        try {
            auditService?.log(AuditService.API_KEY_MINTED, userId, userId, key.id,
                "Minted API key ${key.publicPrefix} (${key.label})")
        } catch (Exception ignore) {}
        [key: key, token: raw]
    }

    @Transactional
    ApiKey revoke(Long userId, Long keyId) {
        def key = apiKeyRepository.findById(keyId)
            .orElseThrow { new NotFoundException("ApiKey", keyId) }
        if (key.userId != userId) throw new ForbiddenException("Not your API key")
        key.revoked = true
        apiKeyRepository.save(key)
        try {
            auditService?.log(AuditService.API_KEY_REVOKED, userId, userId, key.id,
                "Revoked API key ${key.publicPrefix}")
        } catch (Exception ignore) {}
        key
    }

    /** Authenticate a raw token — returns the owning user id or null. */
    Long authenticate(String rawToken) {
        if (!rawToken || !rawToken.startsWith(PREFIX)) return null
        def hash = sha256(rawToken)
        def key = apiKeyRepository.findByTokenHash(hash)
        if (key == null || key.revoked) return null
        key.lastUsedAt = System.currentTimeMillis()
        apiKeyRepository.save(key)
        key.userId
    }

    private static String randomToken(int bytes) {
        def buf = new byte[bytes]
        RNG.nextBytes(buf)
        buf.encodeHex().toString()
    }

    private static String sha256(String s) {
        def md = MessageDigest.getInstance("SHA-256")
        md.digest(s.getBytes("UTF-8")).encodeHex().toString()
    }
}
