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

    @Autowired SteamAuthService steamAuthService
    @Autowired SteamUserRepository steamUserRepository

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
            log.info("Steam login OK: ${user.displayName} (${user.steamId64})")
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
}
