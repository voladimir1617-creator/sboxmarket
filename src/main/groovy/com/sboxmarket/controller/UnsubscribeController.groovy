package com.sboxmarket.controller

import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * One-click unsubscribe endpoint (batch 891). Linked from the footer
 * of every non-security email. Verifies a signed token against the
 * recipient email + HMAC secret, then flips the user's
 * `emailNotificationsEnabled` flag to false.
 *
 * Deliberately NOT under /api/ auth — unsubscribe must work without a
 * session (user reads the email on their phone but doesn't have login
 * cookies, or wants to opt out of an old account they no longer
 * remember the password for). The HMAC token is the auth proof.
 *
 * Idempotent: re-clicking the link when already unsubscribed returns
 * the same success page. No state to corrupt.
 */
@RestController
@RequestMapping('/api/unsubscribe')
@Slf4j
class UnsubscribeController {

    @Autowired EmailService emailService
    @Autowired SteamUserRepository steamUserRepository

    /**
     * POST handler for RFC 8058 "List-Unsubscribe-Post: One-Click"
     * (batch 892). Gmail / Outlook / Fastmail click the
     * List-Unsubscribe URL and POST with
     * `List-Unsubscribe=One-Click` in the body. Returns a plain 200
     * response — these clients don't render the HTML body, they just
     * check the status code. Delegates to the same validation + flip
     * logic as GET.
     */
    @PostMapping
    ResponseEntity<String> unsubscribePost(@RequestParam(required = false) String email,
                                           @RequestParam(required = false, name = 't') String token) {
        def r = unsubscribe(email, token)
        // Mail clients expect 2xx + empty body for the one-click handshake.
        return ResponseEntity.status(r.statusCode).body('OK')
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> unsubscribe(@RequestParam(required = false) String email,
                                       @RequestParam(required = false, name = 't') String token) {
        def lower = (email ?: '').trim().toLowerCase()
        if (!lower || !token) {
            return page('Missing email or token — open the link from your email again.', false)
        }
        if (!emailService.verifyUnsubscribeToken(lower, token)) {
            log.warn("Unsubscribe: invalid token for email={}", lower)
            return page('That unsubscribe link has expired or is malformed. Sign in and toggle email preferences from /settings.', false)
        }
        // Lookup is case-insensitive on email to avoid false misses from
        // address variants (Gmail treats case as insignificant).
        // Batch 1017 — use the indexed `findByEmailIgnoreCase` query
        // instead of hydrating every SteamUser row just to filter one
        // address. Important because the unsubscribe endpoint is
        // token-gated but otherwise anonymous, and a scanner repeatedly
        // hitting it would have OOM'd the server on a full-table scan.
        def users = steamUserRepository.findByEmailIgnoreCase(lower)
        if (users.isEmpty()) {
            // No user matches — still succeed idempotently so we don't
            // leak whether an email is registered (enumeration guard).
            log.info("Unsubscribe: no account matches email={} (returning success)", lower)
            return page('Preferences saved. You\'re unsubscribed from email notifications.', true)
        }
        // Flip the flag on every matched row (should be 1 in practice).
        users.each { u ->
            try {
                u.emailNotificationsEnabled = false
                steamUserRepository.save(u)
                log.info("Unsubscribe: flipped emailNotificationsEnabled=false for uid={} email={}", u.id, lower)
            } catch (Exception e) {
                log.warn("Unsubscribe: save failed for uid={}: {}", u?.id, e.message)
            }
        }
        return page('Preferences saved. You\'re unsubscribed from email notifications.', true)
    }

    private static ResponseEntity<String> page(String message, boolean ok) {
        // Tiny self-contained HTML response — no SPA assets, no fonts,
        // just a styled card. The CSP on the main site doesn't cover
        // an inline <style> + <script>, so we keep this page dead
        // simple. Links back to the SPA for anyone who wants to revert.
        def escaped = message
            .replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            .replace('"', '&quot;')
        def icon = ok ? '✓' : '⚠'
        def color = ok ? '#22c55e' : '#fbbf24'
        def body = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Unsubscribe · SkinBox</title>
  <meta name="robots" content="noindex, nofollow">
  <style>
    body { margin: 0; min-height: 100vh; font-family: -apple-system, "Segoe UI", system-ui, sans-serif;
           background: #0d1320; color: #e8edf5; display: flex; align-items: center; justify-content: center; padding: 24px; }
    .card { max-width: 480px; width: 100%; padding: 36px 28px; border-radius: 14px;
            background: #151d2e; border: 1px solid rgba(148,163,184,0.2);
            text-align: center; box-shadow: 0 8px 40px rgba(0,0,0,0.4); }
    .icon { font-size: 48px; color: ${color}; margin-bottom: 12px; }
    h1 { font-size: 20px; margin: 0 0 12px; font-weight: 800; }
    p { font-size: 14px; line-height: 1.6; color: #b9c3d5; margin: 0 0 20px; }
    a { color: #1ea5ff; text-decoration: none; font-weight: 600; }
    .row { display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; margin-top: 18px; }
    .btn { padding: 10px 18px; border-radius: 8px; font-size: 13px; font-weight: 700;
           text-decoration: none; border: 1px solid rgba(148,163,184,0.25); color: #e8edf5;
           display: inline-block; }
    .btn.primary { background: #1ea5ff; color: #0d1320; border-color: transparent; }
  </style>
</head>
<body>
  <div class="card">
    <div class="icon">${icon}</div>
    <h1>${ok ? "Unsubscribed" : "Couldn't process"}</h1>
    <p>${escaped}</p>
    <div class="row">
      <a class="btn primary" href="/">Back to SkinBox</a>
      <a class="btn" href="/settings">Manage preferences</a>
    </div>
  </div>
</body>
</html>"""
        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header('Cache-Control', 'no-store, no-cache, must-revalidate, private')
            .body(body)
    }
}
