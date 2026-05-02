package com.sboxmarket.controller

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.SteamAuthService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth/steam")
@Slf4j
class SteamAuthController {

    static final String SESSION_USER_ID = "steamUserId"
    /** Session attribute holding the user's sessionEpoch at login time. The
     *  SessionEpochFilter reads this on every request and compares it to
     *  the live steam_users.session_epoch — a mismatch means another login
     *  (or an explicit "log out everywhere") has invalidated this session. */
    static final String SESSION_EPOCH = "steamSessionEpoch"
    /** Session attribute holding the sanitized post-login destination set
     *  by the /login GET. Read once in /return BEFORE the pre-login
     *  session is invalidated, then carried into the redirect URL so the
     *  user lands back on the page they started the auth flow from. */
    static final String SESSION_NEXT = "steamLoginNext"
    /** Cap on the post-login next path. Long enough for `/profile/transactions?range=2026-04` style
     *  deep links, short enough that an attacker can't smuggle anything large. */
    static final int NEXT_MAX_LEN = 200

    @Autowired SteamAuthService steamAuthService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired(required = false) com.sboxmarket.service.AuditService auditService
    @Autowired(required = false) com.sboxmarket.service.EmailService emailService
    @Autowired(required = false) com.sboxmarket.repository.AuditLogRepository auditLogRepository

    /** Kicks off the OpenID flow — redirects the browser to Steam's login page.
     *
     *  Accepts an optional `next` query parameter so the post-login flow can
     *  drop the user back on the page they started auth from (e.g. /sell,
     *  /profile?tab=trades, /watchlist). Pre-fix, every login dumped the
     *  user at "/" regardless of where they began the flow — bad CSFloat-
     *  parity UX (Codex 18:07Z owner finding).
     *
     *  The path is sanitized via {@link #sanitizeNext}: must be a same-
     *  origin internal path, no `//` (protocol-relative URLs), no scheme
     *  or host, no CR/LF (header-injection guard), capped at NEXT_MAX_LEN
     *  characters. Untrusted/malformed → fallback `/`.
     */
    @GetMapping("/login")
    void login(@RequestParam(name = 'next', required = false) String next,
               HttpServletRequest req, HttpServletResponse resp) {
        String safeNext = sanitizeNext(next)
        // Stash on the pre-login session so /return can retrieve it
        // BEFORE invalidating the session on success. We deliberately
        // don't pass it to Steam itself — keeping it server-side avoids
        // exposing the destination in the OpenID return query (where
        // Steam echoes everything back) and prevents an attacker from
        // crafting a Steam URL that bypasses our sanitizer.
        if (safeNext != null && safeNext != '/') {
            try { req.getSession(true).setAttribute(SESSION_NEXT, safeNext) }
            catch (Exception ignore) { /* session unavailable → just lose the hint */ }
        }
        resp.sendRedirect(steamAuthService.buildLoginUrl())
    }

    /**
     * Strict same-origin path sanitizer for the post-login `next` hint.
     * Rejects anything that could redirect the user off-site or smuggle
     * headers into the Location response. Returns "/" for empty / bad input.
     *
     * Allowed shape: starts with "/", never with "//", no CR/LF, no
     * embedded scheme (`http://`, `javascript:`, etc.), <= NEXT_MAX_LEN
     * chars including any query/hash. The browser still receives a
     * relative path, so a successful redirect is bound to the same host
     * regardless of what the underlying servlet container does.
     */
    static String sanitizeNext(String raw) {
        if (raw == null) return '/'
        String s = raw.trim()
        if (s.isEmpty()) return '/'
        // Header-injection guards — newline, carriage-return, NUL.
        if (s.contains('\n') || s.contains('\r') || s.contains('\u0000')) return '/'
        // Length cap — apply BEFORE expensive parsing.
        if (s.length() > NEXT_MAX_LEN) return '/'
        // Must start with a single "/" to lock to same-origin. "//foo"
        // is a protocol-relative URL that browsers resolve to the
        // current scheme + the attacker's host.
        if (!s.startsWith('/')) return '/'
        if (s.startsWith('//')) return '/'
        // Reject anything containing `://` or backslash — guards against
        // exotic encodings like `/\\evil.com` that some browsers normalise
        // to `//evil.com` after the redirect lands.
        if (s.contains('://')) return '/'
        if (s.contains('\\')) return '/'
        return s
    }

    /** Steam redirects the user here after login. We verify and set a session cookie. */
    @GetMapping("/return")
    void steamReturn(HttpServletRequest req, HttpServletResponse resp) {
        log.info("Steam /return hit. query=${req.queryString}")
        // Pull the post-login destination from the pre-login session
        // BEFORE we touch verification — the path is the user's intent
        // regardless of whether OpenID succeeds. Re-sanitize on read
        // (defence-in-depth: another endpoint could in theory have
        // written a value). Default `/` falls through if absent or bad.
        String nextPath = '/'
        try {
            def stash = req.getSession(false)?.getAttribute(SESSION_NEXT) as String
            if (stash) nextPath = sanitizeNext(stash)
        } catch (Exception ignore) { /* session lost → fall back to / */ }

        String steamId64 = null
        try {
            def claimedId = req.getParameter('openid.claimed_id')
            steamId64 = steamAuthService.verifyReturn(req.queryString, claimedId)
        } catch (Exception e) {
            log.error("Steam verifyReturn threw", e)
        }

        if (!steamId64) {
            log.warn("Steam auth: verification returned null")
            // On failure, still respect the user's destination so they
            // land where they tried to act, with `?login=failed` to
            // surface the toast. This avoids dumping them on / after
            // a Steam upstream blip when they were trying to sell.
            try { resp.sendRedirect(appendLoginParam(nextPath, 'failed')) } catch (Exception ignore) {}
            return
        }

        try {
            def user = steamAuthService.upsertUser(steamId64)
            // Rotate the session to prevent session fixation — the old
            // pre-login session ID (which an attacker might have fixated
            // via a crafted link) is invalidated, and a fresh session
            // with a new ID is issued before we write the auth state.
            // Without this, a victim who clicks an attacker's link with
            // a pre-set JSESSIONID cookie logs in ON THAT SESSION ID,
            // and the attacker can then ride it with their copy of the
            // same cookie.
            req.session.invalidate()
            def fresh = req.getSession(true)
            fresh.setAttribute(SESSION_USER_ID, user.id)
            fresh.setAttribute(SESSION_EPOCH, user.sessionEpoch ?: 0L)
            log.info("Steam login OK: ${user.displayName} (${user.steamId64})")
            // Audit every successful sign-in. Fraud review needs this
            // trail — a compromised session attack often shows as a
            // login from an unexpected IP followed by a flurry of
            // withdrawals. Best-effort: audit failure doesn't block
            // the happy path.
            try {
                auditService?.log('USER_SIGN_IN', user.id, user.id, null,
                    "Steam OpenID sign-in")
            } catch (Exception auditErr) {
                log.warn("Sign-in audit log failed for user ${user.id}: ${auditErr.message}")
            }
            // New-device security email (batch 606). Fire when the
            // sign-in comes from an IP that hasn't been seen on this
            // user's audit trail in the last 30 days. Skipped entirely
            // for first-ever sign-in (lifetime count <= 1) because
            // every IP would be "new" on account creation. Best-
            // effort: email failure never blocks the login happy path.
            try {
                fireNewSignInAlertIfNeeded(req, user)
            } catch (Exception ne) {
                log.warn("New-sign-in alert check failed for user ${user.id}: ${ne.message}")
            }
            resp.sendRedirect(appendLoginParam(nextPath, 'success'))
        } catch (Exception e) {
            log.error("Steam upsertUser/redirect threw for steamId64=$steamId64", e)
            try { resp.sendRedirect(appendLoginParam(nextPath, 'failed', 'upsert')) } catch (Exception ignore) {}
        }
    }

    /**
     * Build the post-login redirect URL. Preserves the user's existing
     * query string and hash, appends `login=success` (or `login=failed`)
     * as a parameter the SPA reads to fire the welcome toast. The
     * separator picks `?` vs `&` based on whether the path already has
     * a query string. Hash (everything after `#`) is preserved in place
     * after the query — `/profile?tab=trades#section` becomes
     * `/profile?tab=trades&login=success#section`.
     */
    static String appendLoginParam(String path, String state, String reason = null) {
        String safe = sanitizeNext(path)
        // Split off the hash (browser-only fragment, not sent server-side
        // but if a client-side router pushed one through then preserve it).
        int hashIdx = safe.indexOf('#')
        String hash = hashIdx >= 0 ? safe.substring(hashIdx) : ''
        String pathPart = hashIdx >= 0 ? safe.substring(0, hashIdx) : safe
        String sep = pathPart.contains('?') ? '&' : '?'
        StringBuilder sb = new StringBuilder()
        sb.append(pathPart).append(sep).append('login=').append(state)
        if (reason != null) sb.append('&reason=').append(reason)
        sb.append(hash)
        return sb.toString()
    }

    /**
     * Returns the currently-authenticated user, or `{ signedIn: false }`
     * for anonymous visitors.
     *
     * Was returning 401 for anon, which surfaced as a red "Failed to load
     * resource: 401" line in every visitor's DevTools console on every
     * page load. Cosmetic but ugly — anon /me is the expected case for
     * any unauthenticated session, not an error. Return 200 with an
     * explicit signed-out shape so the browser stops complaining.
     */
    @GetMapping("/me")
    ResponseEntity me(HttpServletRequest req) {
        def userId = req.session.getAttribute(SESSION_USER_ID) as Long
        if (userId == null) {
            return ResponseEntity.ok([signedIn: false] as Map)
        }
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) {
            req.session.invalidate()
            return ResponseEntity.ok([signedIn: false] as Map)
        }
        ResponseEntity.ok(user)
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest req) {
        req.session.invalidate()
        ResponseEntity.noContent().build()
    }

    /**
     * Log out every live session belonging to this user — on this device
     * and on every other browser or phone that's currently signed in.
     *
     * How it works: we bump the user's session_epoch to the current wall
     * time. Every future request (including this user's other sessions)
     * fails the SessionEpochFilter's stashed-epoch == current-epoch check
     * and gets invalidated + 401'd on its next API call. The current
     * session is invalidated inline so the UI falls back to anon.
     *
     * Use cases per CSFloat Visual §23:
     *   - user clicks "Log out all devices" after realising they left
     *     a session open on a shared machine
     *   - staff remediation after a suspected account compromise
     *
     * Any authenticated caller may hit this for *their own* account. No
     * cross-account variant is exposed here — staff already have
     * /api/admin/users/{id}/ban which covers forced logout for abuse.
     */
    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(HttpServletRequest req) {
        def userId = req.session.getAttribute(SESSION_USER_ID) as Long
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) {
            req.session.invalidate()
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        user.sessionEpoch = System.currentTimeMillis()
        steamUserRepository.save(user)
        try {
            auditService?.log(com.sboxmarket.service.AuditService.SESSION_LOGOUT_ALL, user.id, user.id, null,
                "User invalidated every active session")
        } catch (Exception auditErr) {
            log.warn("logout-all audit log failed for user ${user.id}: ${auditErr.message}")
        }
        req.session.invalidate()
        ResponseEntity.noContent().build()
    }

    /** Helper for the /return path — fires a "new sign-in location"
     *  security email when the current IP hasn't been seen on this
     *  user's audit trail in the last 30 days. Silent-fail: email
     *  delivery / DB blip never blocks a login.
     */
    private void fireNewSignInAlertIfNeeded(HttpServletRequest req, SteamUser user) {
        if (user == null || emailService == null || auditLogRepository == null) return
        if (!emailService.canSendSecurityTo(user)) return
        def ip = clientIp(req)
        if (!ip) return
        def since = System.currentTimeMillis() - (30L * 24L * 60L * 60L * 1000L)
        // Exclude the just-written USER_SIGN_IN row by checking whether
        // there are 2+ rows from this IP (the one we just wrote + at
        // least one other prior one). A first-ever sign-in would show
        // the single row we just wrote — still "new", but sending to
        // a user whose very first sign-in just happened is noise. Use
        // a stricter threshold: only skip the alert when there's at
        // least one PRIOR row from this IP. Two rows here = we've seen
        // this IP before; exactly 1 row = this is the first sighting
        // + send the alert.
        long fromIp
        try {
            fromIp = auditLogRepository.countSignInsFromIpSince(user.id, ip, since)
        } catch (Exception e) {
            log.warn("New-sign-in IP count lookup failed for user ${user.id}: ${e.message}")
            return
        }
        if (fromIp > 1) return  // seen before in the 30-day window
        def ua = req.getHeader('User-Agent')
        try {
            emailService.sendNewSignIn(user.email, user.displayName, ip, ua)
        } catch (Exception e) {
            log.warn("New-sign-in email failed for user ${user.id}: ${e.message}")
        }
    }

    private static String clientIp(HttpServletRequest req) {
        def cf = req.getHeader('CF-Connecting-IP')
        if (cf) return cf.trim().take(64)
        def xff = req.getHeader('X-Forwarded-For')
        if (xff) return xff.split(',')[0].trim().take(64)
        (req.remoteAddr ?: '').take(64)
    }
}
