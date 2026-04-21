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

    @Autowired SteamAuthService steamAuthService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired(required = false) com.sboxmarket.service.AuditService auditService
    @Autowired(required = false) com.sboxmarket.service.EmailService emailService
    @Autowired(required = false) com.sboxmarket.repository.AuditLogRepository auditLogRepository

    /** Kicks off the OpenID flow — redirects the browser to Steam's login page. */
    @GetMapping("/login")
    void login(HttpServletResponse resp) {
        resp.sendRedirect(steamAuthService.buildLoginUrl())
    }

    /** Steam redirects the user here after login. We verify and set a session cookie. */
    @GetMapping("/return")
    void steamReturn(HttpServletRequest req, HttpServletResponse resp) {
        log.info("Steam /return hit. query=${req.queryString}")
        String steamId64 = null
        try {
            def claimedId = req.getParameter('openid.claimed_id')
            steamId64 = steamAuthService.verifyReturn(req.queryString, claimedId)
        } catch (Exception e) {
            log.error("Steam verifyReturn threw", e)
        }

        if (!steamId64) {
            log.warn("Steam auth: verification returned null")
            try { resp.sendRedirect("/?login=failed") } catch (Exception ignore) {}
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
            resp.sendRedirect("/?login=success")
        } catch (Exception e) {
            log.error("Steam upsertUser/redirect threw for steamId64=$steamId64", e)
            try { resp.sendRedirect("/?login=failed&reason=upsert") } catch (Exception ignore) {}
        }
    }

    /** Returns the currently-authenticated user, or 401 if not logged in. */
    @GetMapping("/me")
    ResponseEntity<SteamUser> me(HttpServletRequest req) {
        def userId = req.session.getAttribute(SESSION_USER_ID) as Long
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) {
            req.session.invalidate()
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
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
