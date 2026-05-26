package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
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
    /** Batch 692 — active-key ceiling per user. A compromised session
     *  otherwise could mint unlimited long-lived keys in a tight loop
     *  (faster than the per-surface rate limit closes the window).
     *  20 is generous for a real power user running multiple bots +
     *  environments; anyone needing more should revoke stale keys
     *  first, which is the correct hygiene. */
    private static final int MAX_ACTIVE_PER_USER = 20

    @Autowired ApiKeyRepository apiKeyRepository
    @Autowired(required = false) AuditService auditService
    @Autowired TextSanitizer textSanitizer
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository
    @Autowired(required = false) EmailService emailService

    List<ApiKey> listForUser(Long userId) {
        apiKeyRepository.findByUser(userId)
    }

    /**
     * Returns a map containing the persisted ApiKey (without token) AND the raw
     * plaintext token for one-time display. Callers MUST render the token then
     * discard it — it cannot be retrieved again.
     *
     * Batch 670 — optional `scope` lets the issuer mint a read-only key
     * (`RO`) for a price-watcher bot without granting it the authority to
     * buy, sell, or move funds. Unknown / blank scope falls through to
     * `RW` (full access) to preserve the pre-scope contract.
     */
    @Transactional
    Map create(Long userId, String label, String scope = null) {
        // Batch 692 — reject the mint if the user is already at the
        // active-key ceiling. Revoke-first is the intended escape
        // hatch; revoked keys don't count so a user can always churn
        // without hitting the wall. Thrown BEFORE any DB write so a
        // crafted loop can't even burn serial ids on failed mints.
        def active = apiKeyRepository.countActiveByUser(userId)
        if (active >= MAX_ACTIVE_PER_USER) {
            throw new com.sboxmarket.exception.BadRequestException(
                "API_KEY_LIMIT",
                "You already have ${active} active API keys (cap: ${MAX_ACTIVE_PER_USER}). Revoke one from Profile → Developers before minting another.")
        }
        def cleanLabel = textSanitizer.cleanShort(label) ?: 'Untitled'
        def raw = PREFIX + randomToken(32)
        def hash = sha256(raw)
        def prefix = raw.substring(0, Math.min(14, raw.length()))
        def scp = 'RO'.equalsIgnoreCase(scope) ? 'RO' : 'RW'
        def key = new ApiKey(
            userId:       userId,
            publicPrefix: prefix,
            tokenHash:    hash,
            label:        cleanLabel,
            scope:        scp
        )
        apiKeyRepository.save(key)
        try {
            auditService?.log(AuditService.API_KEY_MINTED, userId, userId, key.id,
                "Minted API key ${key.publicPrefix} (${key.label}) scope=${scp}")
        } catch (Exception ignore) {}
        // Batch 691 — security-alert email. Fires on every mint so a
        // compromised-session attacker can't silently provision a long-
        // lived API key. Non-fatal: any failure here (no email on file,
        // SMTP outage, DB blip) is logged but doesn't abort the mint
        // flow — the raw token is still returned to the caller.
        try {
            if (emailService != null && steamUserRepository != null) {
                def user = steamUserRepository.findById(userId).orElse(null)
                // Verified-email gate (canSendSecurityTo) rather than the
                // bare truthiness check this used to do — symmetric with
                // the AdminService.forceLogoutUser fix (batch 318). An
                // attacker who registered an unverified third-party
                // address on a victim's account would otherwise receive
                // the victim's displayName + a security-alert email
                // ("Your API key was just minted") — PII leak + a
                // spam-amplification channel. canSendSecurityTo is also
                // what every other security email in the codebase uses
                // (ban, 2FA reset, wallet freeze, force-logout).
                if (user != null && emailService.canSendSecurityTo(user)) {
                    emailService.sendApiKeyMinted(user.email, user.displayName, key.label, key.scope, key.publicPrefix)
                }
            }
        } catch (Exception e) {
            log.warn("API key mint alert email failed for uid=${userId}: ${e.message}")
        }
        [key: key, token: raw]
    }

    /**
     * Revoke every active API key the user owns in one call (batch 705).
     * Security panic-button for compromised-session recovery — pairs
     * with batch 697's Sign-Out-Everywhere. Returns the count of keys
     * actually revoked so the UI can show "Revoked N keys". Idempotent:
     * zero active keys returns 0, doesn't 404. Audit-logged as
     * API_KEY_REVOKED per key so ops can see each row in the audit
     * trail.
     */
    @Transactional
    int revokeAll(Long userId) {
        def live = apiKeyRepository.findByUser(userId).findAll { !Boolean.TRUE.equals(it.revoked) }
        if (live.isEmpty()) return 0
        live.each { k -> k.revoked = true }
        // Persist BEFORE auditing — AuditService convention (see its class
        // comment): "Services call audit.log AT THE END of a successful
        // operation — never before, so failed attempts don't pollute the
        // trail." Previously the loop wrote audit rows before saveAll, so a
        // saveAll rollback would leave phantom API_KEY_REVOKED rows in the
        // audit trail for keys that were never actually revoked (since
        // AuditService persists in its own transaction context).
        apiKeyRepository.saveAll(live)
        live.each { k ->
            try {
                auditService?.log(AuditService.API_KEY_REVOKED, userId, userId, k.id,
                    "Bulk-revoked API key ${k.publicPrefix} (${k.label})")
            } catch (Exception ignore) { /* tolerated */ }
        }
        live.size()
    }

    @Transactional
    ApiKey revoke(Long userId, Long keyId) {
        def key = apiKeyRepository.findById(keyId)
            .orElseThrow { new NotFoundException("ApiKey", keyId) }
        // Ownership check. A non-owner gets the SAME 404 as a wholly
        // missing id — never a 403 — so an authenticated attacker
        // iterating DELETE /api/api-keys/{id} can't distinguish
        // "exists but not yours" from "doesn't exist" and thereby
        // enumerate other users' key ids (IDOR enumeration leak).
        if (key.userId != userId) throw new NotFoundException("ApiKey", keyId)
        key.revoked = true
        apiKeyRepository.save(key)
        try {
            auditService?.log(AuditService.API_KEY_REVOKED, userId, userId, key.id,
                "Revoked API key ${key.publicPrefix}")
        } catch (Exception ignore) {}
        key
    }

    /** Authenticate a raw token — returns the owning user id or null. */
    @Transactional
    Long authenticate(String rawToken) {
        def key = resolveLiveKey(rawToken)
        key == null ? null : key.userId
    }

    /**
     * Resolve the full auth context for a raw token — userId + scope.
     * Callers that need to gate write operations on RO keys use this;
     * callers that only need the owning user id can still use
     * `authenticate(token)`. Returns null when the token is missing,
     * unknown, or revoked.
     */
    @Transactional
    Map authenticateWithScope(String rawToken) {
        def key = resolveLiveKey(rawToken)
        key == null ? null : [userId: key.userId, scope: (key.scope ?: 'RW')]
    }

    /**
     * Shared verify path for both authenticate flavours: hash-lookup the
     * presented token, refuse a missing / revoked key, and stamp
     * `lastUsedAt` — returning the live key or null.
     *
     * Why this is @Transactional and re-reads the row by id before the
     * write: the previous code mutated and `save()`d the (already
     * detached) `findByTokenHash` result directly. A JPA merge of a
     * detached entity rewrites EVERY column from its stale snapshot — so
     * an `authenticate()` racing a `revoke()` / `revokeAll()` could flush
     * a stale `revoked=false` over a just-committed `revoked=true` and
     * silently un-revoke the key, defeating the revocation kill switch.
     * Running inside one transaction and re-loading the row by id keeps
     * the entity managed and lets us bail the instant we observe it is
     * revoked — a key revoked mid-request now correctly fails auth and
     * its `revoked` flag is never overwritten.
     */
    private ApiKey resolveLiveKey(String rawToken) {
        if (!rawToken || !rawToken.startsWith(PREFIX)) return null
        def hash = sha256(rawToken)
        def found = apiKeyRepository.findByTokenHash(hash)
        if (found == null || found.id == null || Boolean.TRUE.equals(found.revoked)) return null
        // Re-load fresh inside this transaction; abort if it was revoked
        // in the meantime so the lastUsedAt write can never resurrect it.
        def key = apiKeyRepository.findById(found.id).orElse(null)
        if (key == null || Boolean.TRUE.equals(key.revoked)) return null
        key.lastUsedAt = System.currentTimeMillis()
        apiKeyRepository.save(key)
        key
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
