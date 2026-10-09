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
 *
 * Per-bucket opt-out (batch 1082, GDPR/PECR compliance): when the
 * caller supplies an optional `?kind=<bucket>` parameter matching one
 * of EmailService.MUTABLE_EMAIL_BUCKETS (TRADES, AUCTIONS, WATCHLIST,
 * FOLLOWS, MATCHES), only that bucket is added to the user's
 * `mutedEmailKinds` set — security alerts and other notification
 * types keep delivering. Without `kind`, the legacy behaviour
 * (global flag flip) is preserved so existing email footer URLs keep
 * working unchanged. Future email templates that target a single
 * bucket can append `&kind=AUCTIONS` to the URL so a user who only
 * wants to silence outbid alerts isn't forced into all-or-nothing.
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
                                           @RequestParam(required = false, name = 't') String token,
                                           @RequestParam(required = false) String kind,
                                           @RequestParam(required = false, name = 'List-Unsubscribe') String oneClick) {
        def r = doUnsubscribe(email, token, kind)
        // Mail clients expect 2xx + a bare body for the one-click handshake;
        // the confirm button on the GET page posts without it and gets the
        // HTML result card instead.
        if (oneClick != null && oneClick.trim().equalsIgnoreCase('One-Click')) {
            return ResponseEntity.status(r.statusCode).body('OK')
        }
        r
    }

    /** Test-only convenience overloads. The deployed mapping is on the
     *  4-arg `unsubscribePost` so Spring sees a single @PostMapping.
     *  These behave as a mail client's RFC 8058 one-click POST. */
    ResponseEntity<String> unsubscribePost(String email, String token) {
        unsubscribePost(email, token, (String) null, 'One-Click')
    }

    ResponseEntity<String> unsubscribePost(String email, String token, String kind) {
        unsubscribePost(email, token, kind, 'One-Click')
    }

    /** Test-only 2-arg convenience overload of the GET page. */
    ResponseEntity<String> unsubscribe(String email, String token) {
        unsubscribe(email, token, (String) null)
    }

    /**
     * The emailed link only shows a confirm button. Corporate link
     * scanners and mail prefetchers GET every URL in a message, so a GET
     * that changed state unsubscribed people who never clicked; the
     * change happens on the button's POST (or a mail client's RFC 8058
     * one-click POST) instead.
     */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> unsubscribe(@RequestParam(required = false) String email,
                                       @RequestParam(required = false, name = 't') String token,
                                       @RequestParam(required = false) String kind) {
        def lower = (email ?: '').trim().toLowerCase()
        if (!lower || !token) {
            return page('Missing email or token — open the link from your email again.', false)
        }
        if (!emailService.verifyUnsubscribeToken(lower, token)) {
            log.warn("Unsubscribe: invalid token for email={}", EmailService.maskEmail(lower))
            return page('That unsubscribe link has expired or is malformed. Sign in and toggle email preferences from Profile → Personal Info.', false)
        }
        String bucket = null
        if (kind != null) {
            def candidate = kind.trim().toUpperCase()
            if (candidate && EmailService.MUTABLE_EMAIL_BUCKETS.contains(candidate)) bucket = candidate
        }
        def what = bucket ? "${bucket.toLowerCase()} emails" : 'email notifications'
        def form = """<form method="post" action="/api/unsubscribe" class="row">
      <input type="hidden" name="email" value="${esc(lower)}">
      <input type="hidden" name="t" value="${esc(token)}">${bucket ? """
      <input type="hidden" name="kind" value="${esc(bucket)}">""" : ''}
      <button type="submit" class="btn primary">Unsubscribe</button>
      <a class="btn ghost" href="/profile/personal">Manage preferences</a>
    </form>"""
        page("Stop ${what} to this address? Security notices still arrive.", true, form, 'Confirm unsubscribe')
    }

    private static String esc(String v) {
        (v ?: '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }

    private ResponseEntity<String> doUnsubscribe(String email, String token, String kind) {
        def lower = (email ?: '').trim().toLowerCase()
        if (!lower || !token) {
            return page('Missing email or token — open the link from your email again.', false)
        }
        if (!emailService.verifyUnsubscribeToken(lower, token)) {
            log.warn("Unsubscribe: invalid token for email={}", EmailService.maskEmail(lower))
            return page('That unsubscribe link has expired or is malformed. Sign in and toggle email preferences from Profile → Personal Info.', false)
        }
        // Normalize the optional per-bucket selector. Only honour values
        // that match the canonical MUTABLE_EMAIL_BUCKETS whitelist —
        // anything else falls through to the legacy global-flip
        // behaviour so a malformed/unknown kind doesn't 4xx the link.
        String bucket = null
        if (kind != null) {
            def candidate = kind.trim().toUpperCase()
            if (candidate && EmailService.MUTABLE_EMAIL_BUCKETS.contains(candidate)) {
                bucket = candidate
            } else if (candidate) {
                log.info("Unsubscribe: ignoring unknown kind='{}' (falling back to global opt-out)", candidate)
            }
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
            log.info("Unsubscribe: no account matches email={} (returning success)", EmailService.maskEmail(lower))
            return page(bucket
                ? "Preferences saved. You're unsubscribed from ${bucket.toLowerCase()} emails."
                : 'Preferences saved. You\'re unsubscribed from email notifications.', true)
        }
        // Flip the flag on every matched row (should be 1 in practice).
        users.each { u ->
            try {
                if (bucket != null) {
                    // Per-bucket opt-out: add `bucket` to the user's
                    // mutedEmailKinds CSV without touching the global
                    // flag. Idempotent — re-clicking the same bucket
                    // link doesn't dupe the entry. Other buckets and
                    // unrelated security alerts continue to deliver.
                    def existing = (u.mutedEmailKinds ?: '').split(/,/)
                        .collect { it?.trim() }
                        .findAll { it }
                        .collect { it.toUpperCase() } as Set
                    existing.add(bucket)
                    // Re-filter through the whitelist so a stale value
                    // from a deprecated bucket can't survive a save.
                    u.mutedEmailKinds = existing
                        .findAll { EmailService.MUTABLE_EMAIL_BUCKETS.contains(it) }
                        .toSorted()
                        .join(',')
                } else {
                    u.emailNotificationsEnabled = false
                }
                steamUserRepository.save(u)
                if (bucket != null) {
                    log.info("Unsubscribe: muted bucket={} for uid={} email={} (mutedEmailKinds='{}')",
                        bucket, u.id, EmailService.maskEmail(lower), u.mutedEmailKinds)
                } else {
                    log.info("Unsubscribe: flipped emailNotificationsEnabled=false for uid={} email={}", u.id, EmailService.maskEmail(lower))
                }
            } catch (Exception e) {
                log.warn("Unsubscribe: save failed for uid={}: {}", u?.id, e.message)
            }
        }
        return page(bucket
            ? "Preferences saved. You're unsubscribed from ${bucket.toLowerCase()} emails. Other notifications continue."
            : 'Preferences saved. You\'re unsubscribed from email notifications.', true)
    }

    private static ResponseEntity<String> page(String message, boolean ok, String actionsHtml = null, String heading = null) {
        // Tiny self-contained HTML response — no SPA assets, no fonts,
        // just a styled card. The CSP on the main site doesn't cover
        // an inline <style> + <script>, so we keep this page dead
        // simple. Links back to the SPA for anyone who wants to revert.
        def escaped = message
            .replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            .replace('"', '&quot;')
        def icon = ok ? '✓' : '⚠'
        // Title uses Fraunces serif; body + buttons use Geist to match the
        // editorial system on the main app. Entire page is stand-alone
        // (no design.css import — this endpoint must render even if the
        // SPA static bundle is unavailable) but mirrors the tokens.
        def body = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Unsubscribe · SkinBox</title>
  <meta name="robots" content="noindex, nofollow">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Geist:wght@400;500;600&family=Fraunces:opsz,wght@9..144,360;9..144,500&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg:   oklch(0.17 0.008 260);
      --bg-1: oklch(0.20 0.009 260);
      --bg-2: oklch(0.23 0.010 260);
      --line: oklch(0.30 0.012 260);
      --line-2: oklch(0.36 0.014 260);
      --ink:   oklch(0.97 0.006 90);
      --ink-3: oklch(0.58 0.010 90);
      --ink-4: oklch(0.42 0.010 90);
      --up:    oklch(0.82 0.16 150);
      --warn:  oklch(0.80 0.16 80);
    }
    * { box-sizing: border-box; }
    body {
      margin: 0; min-height: 100vh;
      font-family: 'Geist', ui-sans-serif, system-ui, -apple-system, sans-serif;
      background: var(--bg); color: var(--ink);
      display: flex; align-items: center; justify-content: center;
      padding: 24px;
      font-size: 14px; line-height: 1.5;
    }
    .card {
      max-width: 520px; width: 100%;
      padding: 40px 36px;
      border-radius: 10px;
      background: var(--bg-1);
      border: 1px solid var(--line-2);
      box-shadow: 0 12px 40px rgba(0,0,0,0.45), 0 1px 0 rgba(255,255,255,0.04) inset;
      text-align: center;
    }
    .kicker {
      font-family: 'Geist', ui-monospace, monospace;
      font-size: 10.5px; font-weight: 500;
      letter-spacing: 0.2em; text-transform: uppercase;
      color: var(--ink-4);
      margin-bottom: 14px;
    }
    .icon {
      display: inline-grid; place-items: center;
      width: 44px; height: 44px; border-radius: 50%;
      background: ${ok ? 'color-mix(in oklab, var(--up) 10%, transparent)' : 'color-mix(in oklab, var(--warn) 10%, transparent)'};
      border: 1px solid ${ok ? 'color-mix(in oklab, var(--up) 30%, var(--line))' : 'color-mix(in oklab, var(--warn) 30%, var(--line))'};
      color: ${ok ? 'var(--up)' : 'var(--warn)'};
      font-size: 22px; font-weight: 600;
      margin-bottom: 18px;
    }
    h1 {
      font-family: 'Fraunces', 'Times New Roman', Georgia, serif;
      font-size: 32px; font-weight: 360; letter-spacing: -0.025em;
      color: var(--ink);
      font-variation-settings: "opsz" 144, "SOFT" 20;
      line-height: 1.1;
      margin: 0 0 14px;
    }
    p {
      font-size: 14px; line-height: 1.55;
      color: var(--ink-3);
      margin: 0 auto 24px;
      max-width: 42ch;
    }
    .row {
      display: flex; gap: 10px;
      justify-content: center; flex-wrap: wrap;
    }
    .btn {
      display: inline-flex; align-items: center; justify-content: center;
      height: 38px; padding: 0 18px;
      font-size: 13px; font-weight: 600; letter-spacing: -0.005em;
      border-radius: 6px;
      text-decoration: none;
      transition: filter 140ms ease, background 140ms ease, border-color 140ms ease;
    }
    .btn.primary {
      background: var(--ink); color: var(--bg);
      border: 1px solid var(--ink);
      font-family: inherit; cursor: pointer;
    }
    .btn.primary:hover { filter: brightness(0.95); }
    .btn.ghost {
      background: transparent; color: var(--ink);
      border: 1px solid var(--line-2);
    }
    .btn.ghost:hover { background: var(--bg-2); border-color: var(--ink-4); }
  </style>
</head>
<body>
  <div class="card">
    <div class="kicker">SkinBox · Email preferences</div>
    <div class="icon">${icon}</div>
    <h1>${heading ?: (ok ? "Unsubscribed" : "Couldn't process")}</h1>
    <p>${escaped}</p>
    ${actionsHtml ?: """<div class="row">
      <a class="btn primary" href="/">Back to SkinBox</a>
      <a class="btn ghost" href="/profile/personal">Manage preferences</a>
    </div>"""}
  </div>
</body>
</html>"""
        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header('Cache-Control', 'no-store, no-cache, must-revalidate, private')
            .body(body)
    }
}
