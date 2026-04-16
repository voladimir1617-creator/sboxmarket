package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.ProfileService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TotpService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

import java.security.SecureRandom

/**
 * Profile tab endpoints:
 *   GET  /api/profile/me          — aggregated dashboard snapshot
 *   PUT  /api/profile/email       — set/replace email, generates confirmation token
 *   POST /api/profile/email/verify — confirm with the token
 *   POST /api/profile/2fa/enroll  — generate a fresh secret + otpauth URL
 *   POST /api/profile/2fa/confirm — verify the first 6-digit code and enable 2FA
 *   POST /api/profile/2fa/disable — wipe the secret (requires a fresh code first)
 */
@RestController
@RequestMapping("/api/profile")
@Slf4j
class ProfileController {

    private static final SecureRandom RNG = new SecureRandom()
    private static final java.util.regex.Pattern EMAIL_RE = ~/^[A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9.\-]{1,253}\.[A-Za-z]{2,24}$/

    @Autowired ProfileService profileService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired TotpService totpService
    @Autowired TextSanitizer textSanitizer
    @Autowired EmailService emailService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping("/me")
    ResponseEntity<Map> me(HttpServletRequest req) {
        def data = profileService.buildProfile(requireUser(req))
        if (data == null) throw new UnauthorizedException("Unknown user")
        ResponseEntity.ok(data)
    }

    // ── Email ───────────────────────────────────────────────────────

    @PutMapping("/email")
    @Transactional
    ResponseEntity<Map> setEmail(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def emailRaw = (body?.email as String ?: '').trim().toLowerCase()
        if (!EMAIL_RE.matcher(emailRaw).matches()) {
            throw new BadRequestException("INVALID_EMAIL", "Please enter a valid email address")
        }
        user.email = textSanitizer.cleanShort(emailRaw)
        user.emailVerified = false
        user.emailVerificationToken = randomToken()
        steamUserRepository.save(user)
        // Hand the token off to EmailService — it either sends a real
        // email (SMTP configured) or logs it for local dev / CI. We only
        // echo the token back in the JSON when SMTP is NOT wired up, so
        // a real production instance with mail enabled never leaks it.
        emailService.sendVerification(user.email, user.emailVerificationToken)
        def resp = [email: user.email, verified: false] as Map
        if (!emailService.smtpReady) resp.token = user.emailVerificationToken
        ResponseEntity.ok(resp)
    }

    @PostMapping("/email/verify")
    @Transactional
    ResponseEntity<Map> verifyEmail(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def token = (body?.token as String ?: '').trim()
        if (!user.emailVerificationToken || token != user.emailVerificationToken) {
            throw new BadRequestException("INVALID_TOKEN", "Verification token does not match")
        }
        user.emailVerified = true
        user.emailVerificationToken = null
        steamUserRepository.save(user)
        ResponseEntity.ok([email: user.email, verified: true])
    }

    // ── 2FA ─────────────────────────────────────────────────────────

    /**
     * Step 1: generate a fresh secret and return the otpauth URL so the
     * user can scan a QR code or paste it into their authenticator app.
     * The secret is NOT yet activated — it's staged on the user row and
     * only becomes active after /2fa/confirm succeeds.
     */
    @PostMapping("/2fa/enroll")
    @Transactional
    ResponseEntity<Map> enroll2fa(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def secret = totpService.generateSecret()
        // Store the pending secret but leave totpSecret == null until
        // confirmation succeeds. Use the verificationToken column as a
        // lightweight staging slot so we don't need a new DB column.
        user.emailVerificationToken = "totp_pending:${secret}"
        steamUserRepository.save(user)
        ResponseEntity.ok([
            secret:     secret,
            otpauthUrl: totpService.otpauthUrl(secret, user.email ?: user.steamId64)
        ])
    }

    @PostMapping("/2fa/confirm")
    @Transactional
    ResponseEntity<Map> confirm2fa(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def stage = user.emailVerificationToken
        if (!stage?.startsWith('totp_pending:')) {
            throw new BadRequestException("NOT_ENROLLING", "Call /2fa/enroll first")
        }
        def secret = stage.substring('totp_pending:'.size())
        def code = (body?.code as String ?: '').trim()
        def step = totpService.verify(secret, code, null)
        if (step < 0) {
            throw new BadRequestException("INVALID_CODE", "That code is invalid or already used")
        }
        user.totpSecret = secret
        user.lastTotpStep = step
        // Clear the staging slot — if the user had a pending email token
        // they'll have to re-enter their email, which is acceptable rare UX.
        user.emailVerificationToken = null
        steamUserRepository.save(user)
        ResponseEntity.ok([enabled: true])
    }

    @PostMapping("/2fa/disable")
    @Transactional
    ResponseEntity<Map> disable2fa(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        if (!user.totpSecret) {
            return ResponseEntity.ok([enabled: false])
        }
        def code = (body?.code as String ?: '').trim()
        def step = totpService.verify(user.totpSecret, code, user.lastTotpStep)
        if (step < 0) {
            throw new BadRequestException("INVALID_CODE", "Provide a valid 2FA code to disable 2FA")
        }
        user.totpSecret = null
        user.lastTotpStep = null
        steamUserRepository.save(user)
        ResponseEntity.ok([enabled: false])
    }

    private static String randomToken() {
        def b = new byte[16]
        RNG.nextBytes(b)
        b.encodeHex().toString()
    }
}
