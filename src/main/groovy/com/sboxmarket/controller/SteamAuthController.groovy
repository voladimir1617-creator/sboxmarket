package com.sboxmarket.controller

import com.sboxmarket.config.DevLoginGate
import com.sboxmarket.config.LiveMoneyGuard
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

    /** The body the dev-login GUARD returns when the deployment can move real
     *  money, so a shut door is provable rather than merely quiet. The other
     *  404s on this endpoint carry {@link #DEV_LOGIN_NOT_AUTHORIZED} (also the
     *  guard) and {@link #DEV_LOGIN_NO_SEED_USERS} (the guard was PASSED) —
     *  all three are indistinguishable by status code, which is exactly how a
     *  live, wide-open endpoint was once read as closed. Verification scripts
     *  and specs must assert on these strings, never on the 404 alone.
     *
     *  Aliased from {@link DevLoginGate} rather than re-declared, so the guard
     *  and the body it emits cannot drift apart. */
    static final String DEV_LOGIN_DISABLED = DevLoginGate.REASON_REAL_MONEY

    /** The body the dev-login guard returns on a deployment that handles no
     *  real money but where <b>nobody asked for the door</b> — the DEFAULT
     *  answer on a fresh checkout with no special environment. Distinct from
     *  {@link #DEV_LOGIN_DISABLED} so the two refusals stay tellable apart,
     *  and both start with {@link DevLoginGate#REFUSAL_PREFIX}. */
    static final String DEV_LOGIN_NOT_AUTHORIZED = DevLoginGate.REASON_NOT_AUTHORIZED

    /** The body of the OTHER 404 — reached only AFTER the guard has been
     *  passed, when no user carries the requested id. Its presence is proof the
     *  door is OPEN. */
    static final String DEV_LOGIN_NO_SEED_USERS = 'no seed users'

    @Autowired(required = false) com.sboxmarket.config.ClientIpResolver clientIpResolver
    @Autowired SteamAuthService steamAuthService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired(required = false) com.sboxmarket.service.AuditService auditService
    @Autowired(required = false) com.sboxmarket.service.EmailService emailService
    @Autowired(required = false) com.sboxmarket.repository.AuditLogRepository auditLogRepository
    @Autowired org.springframework.core.env.Environment env

    /** DEV-ONLY local login — establishes a real session for a seed user so the
     *  auth-gated UI can be QA'd on localhost without a live Steam round-trip.
     *  Scaffolding for visual QA — not a shipped feature. Mirrors the /return
     *  session establishment exactly.
     *
     *  This endpoint takes a user id and NOTHING ELSE and hands back a valid
     *  session for that user — no password, no OpenID assertion, no token. It
     *  is total account takeover of every account on the platform, including
     *  every admin, for anyone who can reach the URL. Its gate is therefore
     *  the most safety-critical branch in the controller.
     *
     *  Guarded by {@link DevLoginGate}, which requires BOTH that no real money
     *  can move here ({@link LiveMoneyGuard} — the `prod` profile or a live
     *  Stripe key shuts it, as before) AND that someone has affirmatively
     *  asked for the door via the {@code SBOX_DEV_LOGIN_ENABLED} process
     *  environment variable.
     *
     *  The money half alone was never enough. It answers "is this obviously
     *  production?", and the answer on the operator's own box is correctly NO —
     *  no live Stripe key, so the deployment is SIMULATED and this door opened
     *  by design, its only remaining control being that Tomcat binds
     *  127.0.0.1. A Cloudflare tunnel connects FROM loopback, so that bind
     *  does not stop it, and DNS already points at the tunnel. Being
     *  un-forbidden is not the same as being asked for; see {@link DevLoginGate}
     *  for the full argument. */
    @GetMapping("/dev-login")
    def devLogin(@RequestParam(required = false) Long userId,
                 @RequestParam(required = false, defaultValue = "/profile") String next,
                 HttpServletRequest req, HttpServletResponse resp) {
        String refusal = DevLoginGate.refusalReason(env)
        if (refusal != null) {
            log.warn("dev-login refused — {} (profiles={})", refusal, env?.activeProfiles)
            // The body is the POINT, not decoration. This branch used to return
            // `.build()` — an empty 404, indistinguishable from Spring's own
            // "no such route" and from the OTHER 404 below. On 2026-09-01 the
            // live endpoint answered 404 through that other branch
            // (`{"error":"no seed users"}`) and the empty-vs-present body was
            // the only thing separating "the guard held" from "the guard was
            // passed and nothing happened to match the id I asked for".
            //
            // Proving a door is shut requires a signal FROM the door. An
            // absence is not a proof — it is the same absence a missing route,
            // a typo'd path, or a dead server produces. The reason string comes
            // from the gate itself, so the decision and the explanation are the
            // same computation and cannot disagree.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body([error: refusal])
        }
        SteamUser user = userId != null ? steamUserRepository.findById(userId).orElse(null)
                                        : steamUserRepository.findAll().find { it != null }
        if (user == null) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body([error: DEV_LOGIN_NO_SEED_USERS]) }
        try { req.session?.invalidate() } catch (Exception ignore) {}
        def fresh = req.getSession(true)
        fresh.setAttribute(SESSION_USER_ID, user.id)
        fresh.setAttribute(SESSION_EPOCH, user.sessionEpoch ?: 0L)
        log.warn("DEV-LOGIN as ${user.displayName} (#${user.id}) — credential-free session minted because " +
                 "${DevLoginGate.OPT_IN_ENV_VAR}=${DevLoginGate.OPT_IN_VALUE} is set in this process's environment")
        try { resp.sendRedirect(sanitizeNext(next)) } catch (Exception ignore) {}
        return null
    }

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
        // Any other control character too: browsers strip TAB from URLs, so
        // `/<TAB>/evil.com` passed the `//` check below and then resolved
        // to the protocol-relative `//evil.com`.
        if (s.find(/[\x00-\x1F\x7F]/) != null) return '/'
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
        // Security QA P1: do NOT log the raw query string here. It
        // contains the full signed OpenID assertion (openid.sig +
        // openid.response_nonce); anything that can read this log line
        // can replay the assertion. Log a short non-sensitive marker
        // instead — the verification outcome is logged downstream.
        log.info("Steam /return hit")
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
            // verifyReturn extracts the SteamID from the claimed_id in the
            // raw, signature-verified query string. The servlet-decoded
            // param is passed only as a defence-in-depth cross-check —
            // verifyReturn rejects the login if the two disagree.
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
            // A deleted account gets no new session.
            if (com.sboxmarket.service.SteamAuthService.isDeletedAccount(user)) {
                log.info("Steam login refused: account ${user.id} was deleted at the user's request")
                try { resp.sendRedirect(appendLoginParam(nextPath, 'failed')) } catch (Exception ignore) {}
                return
            }
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
     *
     * Both `state` and `reason` are URL-encoded before being written
     * into the query string. Today the only call sites pass URL-safe
     * literals (`success`, `failed`, `upsert`) so the wire bytes don't
     * change — but defence-in-depth: a future caller passing a value
     * with `&`, `=`, `#`, or CRLF would otherwise smuggle extra params
     * (or a header) into the Location response. The same goes for any
     * exotic Unicode that needs percent-escaping.
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
        sb.append(pathPart).append(sep).append('login=')
            .append(URLEncoder.encode(state ?: '', 'UTF-8'))
        if (reason != null) {
            sb.append('&reason=').append(URLEncoder.encode(reason, 'UTF-8'))
        }
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
     *
     * `Cache-Control: no-store` is critical: without it a browser (or any
     * intermediate proxy / CDN) is free to cache the response and serve
     * it across auth-state transitions. Two real-world breakages this
     * prevents — (1) an anon visitor's cached `{signedIn:false}` keeps
     * the SPA's header rendering "Sign in" for several minutes AFTER the
     * user actually completes a login round-trip, and (2) a user who
     * logs out (or hits `/logout-all` on another device) sees their
     * stale signed-in identity rehydrated on the next page load until
     * the cache entry ages out. `private` keeps shared caches from ever
     * holding user-identifying responses; `must-revalidate` + `max-age=0`
     * close the bfcache / back-forward stale-while-revalidate gap on
     * Safari. The Pragma header is the HTTP/1.0 belt-and-braces twin for
     * any legacy proxy in front of the app.
     */
    @GetMapping("/me")
    ResponseEntity me(HttpServletRequest req) {
        def userId = req.session.getAttribute(SESSION_USER_ID) as Long
        if (userId == null) {
            return noStore(ResponseEntity.ok([signedIn: false] as Map))
        }
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) {
            req.session.invalidate()
            return noStore(ResponseEntity.ok([signedIn: false] as Map))
        }
        // Enforce the "log out everywhere" invariant on /me itself.
        // SessionEpochFilter SKIPS /api/auth/steam/me to avoid chicken-
        // and-egg issues — but without an inline check here, a session
        // that was killed via POST /logout-all on another device keeps
        // returning its full identity from /me indefinitely (until the
        // SPA hits a non-skipped endpoint), leaving the navbar rendered
        // as "signed in" on the abandoned device. Compare the stashed
        // login-time epoch to the live one, and on mismatch invalidate
        // the cookie + report signed-out — same shape SessionEpochFilter
        // uses elsewhere so the kill switch is end-to-end consistent.
        def stashedEpoch = req.session.getAttribute(SESSION_EPOCH) as Long
        Long liveEpoch = user.sessionEpoch
        if (liveEpoch != null && (stashedEpoch ?: 0L) < liveEpoch) {
            try { req.session.invalidate() } catch (Exception ignore) {}
            return noStore(ResponseEntity.ok([signedIn: false] as Map))
        }
        noStore(ResponseEntity.ok(user))
    }

    /** Stamp the standard never-cache header set onto a response. Returns
     *  a NEW ResponseEntity (Spring's builder is immutable) carrying the
     *  same status + body. Pulled out as a helper so every /me return
     *  path uses the same belt-and-braces directive without copy-paste
     *  drift — and so a future endpoint that handles auth identity can
     *  reuse it. See the /me javadoc for the rationale on each token. */
    private static <T> ResponseEntity<T> noStore(ResponseEntity<T> r) {
        ResponseEntity.status(r.statusCode)
            .header('Cache-Control', 'no-store, no-cache, must-revalidate, private, max-age=0')
            .header('Pragma', 'no-cache')
            .header('Expires', '0')
            .body(r.body)
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

    // The audit row this alert counts against stores the IP from
    // ClientIpResolver, which only trusts forwarding headers from known
    // proxies. Reading CF-Connecting-IP / X-Forwarded-For raw let a client
    // name the victim's usual IP to silence the alert, or a random one to
    // fire it on every login.
    private String clientIp(HttpServletRequest req) {
        if (clientIpResolver != null) {
            try { return (clientIpResolver.resolve(req) ?: '').take(64) } catch (Exception ignored) { }
        }
        (req.remoteAddr ?: '').take(64)
    }
}
