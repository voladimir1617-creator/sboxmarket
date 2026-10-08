package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.ApiKey
import com.sboxmarket.service.ApiKeyService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/api-keys")
@Slf4j
class ApiKeyController {

    @Autowired ApiKeyService apiKeyService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    /** API keys the caller has minted. Per-user mint count is already
     *  capped by ApiKeyService.create (countActiveByUser), but the
     *  response is still bounded here so a long-lived account with a
     *  large revocation history can't ship a multi-MB JSON every time
     *  the settings page loads. Default 50, `?limit` (1..200) overrides.
     *  Repository ordering is `createdAt DESC` (see ApiKeyRepository
     *  #findByUser) so newest-first is stable. `?limit` is read off
     *  the raw request rather than bound via @RequestParam so the
     *  method stays single-arg and Groovy can't auto-generate an
     *  overload that Spring would double-map. */
    @GetMapping
    ResponseEntity<List<ApiKey>> list(HttpServletRequest req) {
        def uid = requireUser(req)
        int cap = parseLimit(req, 50, 200)
        def rows = apiKeyService.listForUser(uid)
        if (rows.size() > cap) {
            // Active keys (capped at 20 per user) always make the page; the
            // cap only trims revoked history. Taking the newest `cap` rows
            // let a run of revoked keys push an older live key, and its
            // Revoke button, off the list.
            def active  = rows.findAll { !Boolean.TRUE.equals(it.revoked) }
            def revoked = rows.findAll { Boolean.TRUE.equals(it.revoked) }
            def keep = new HashSet(active.take(cap)*.id)
            keep.addAll(revoked.take(Math.max(0, cap - keep.size()))*.id)
            rows = rows.findAll { it.id in keep }
        }
        ResponseEntity.ok(rows)
    }

    /** Parse `?limit=N` off the raw request, clamp into [1, max], fall
     *  back to `defaultCap` on missing / blank / non-numeric input. */
    private static int parseLimit(HttpServletRequest req, int defaultCap, int max) {
        def raw = req.getParameter('limit')
        if (raw == null || raw.isBlank()) return defaultCap
        try {
            int n = Integer.parseInt(raw.trim())
            return Math.min(Math.max(n, 1), max)
        } catch (NumberFormatException ignored) {
            return defaultCap
        }
    }

    @PostMapping
    ResponseEntity<Map> create(@RequestBody(required = false) Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        // Upstream length caps. ApiKeyService.create runs label through
        // textSanitizer.cleanShort (80-char truncation). Reject obviously
        // oversized labels at the boundary so a script can't flood the
        // sanitizer with megabytes of text per mint attempt. Scope is a
        // 2-char enum ('RO'/'RW') so we cap aggressively.
        com.sboxmarket.util.InputLimits.requireMax(body, 'label',
            com.sboxmarket.util.InputLimits.SHORT_LABEL,
            'LABEL_TOO_LONG', 'label')
        com.sboxmarket.util.InputLimits.requireMax(body, 'scope',
            16, 'SCOPE_TOO_LONG', 'scope')
        def label = body?.label as String
        // Batch 670 — optional scope ('RO' or 'RW'). Invalid / blank
        // falls through to 'RW' so the pre-scope contract holds.
        def scope = body?.scope as String
        def result = apiKeyService.create(uid, label, scope)
        ResponseEntity.ok([
            id:           result.key.id,
            publicPrefix: result.key.publicPrefix,
            label:        result.key.label,
            scope:        result.key.scope,
            token:        result.token,        // returned ONCE
            createdAt:    result.key.createdAt
        ])
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Map> revoke(@PathVariable Long id, HttpServletRequest req) {
        def key = apiKeyService.revoke(requireUser(req), id)
        ResponseEntity.ok([id: key.id, revoked: key.revoked])
    }

    /**
     * Bulk-revoke every active key on the caller's account (batch 705).
     * Security panic button — pairs with Profile → Sign out everywhere.
     * Idempotent: zero-key callers get `{revoked: 0}`, not a 404.
     */
    @DeleteMapping
    ResponseEntity<Map> revokeAll(HttpServletRequest req) {
        int n = apiKeyService.revokeAll(requireUser(req))
        ResponseEntity.ok([revoked: n])
    }
}
