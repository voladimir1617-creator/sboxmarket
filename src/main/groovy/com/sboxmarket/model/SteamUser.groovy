package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Steam-authed user row. Every sensitive field is @JsonIgnore'd so that
 * `/api/auth/steam/me`, `/api/admin/users`, and the profile-service
 * payloads never serialize secrets. Before this was locked down, the
 * 2FA TOTP secret and the single-use email verification token were
 * being emitted in plain JSON for any authenticated user (and, worse,
 * for every user in the admin list) — which is bug #19 in the audit log.
 */
@Entity
@Table(name = "steam_users")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class SteamUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false, unique = true, length = 32)
    String steamId64

    @Column(length = 255)
    String displayName

    @Column(length = 500)
    String avatarUrl

    @Column(length = 500)
    String profileUrl

    @Column(nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(nullable = false)
    Long lastLoginAt = System.currentTimeMillis()

    /** Set by SteamSyncService every ~20 minutes when the inventory is refreshed. */
    @Column
    Long lastSyncedAt

    /** Bumped by PresenceFilter on every authenticated request, throttled
     *  to one DB write per 60 seconds per user. Drives real "Online now"
     *  presence dots on listing cards, stall pages, and rails — replaces
     *  the deterministic-seed fallback shipped before V61. Null on
     *  accounts that haven't loaded a page since the column was added
     *  (backfilled from lastLoginAt during migration). Indexed. */
    @Column(name = 'last_seen_at')
    Long lastSeenAt

    /** Cached count of Steam-inventory items the user owns for the s&box appid. */
    @Column
    Integer steamInventorySize

    /** USER or ADMIN — ADMIN unlocks /api/admin/* and the Admin modal in the UI. */
    @Column
    String role = "USER"

    /** Set by an admin via `/api/admin/users/{id}/ban`. Banned users can still log
     *  in but every state-changing endpoint rejects them. */
    @Column
    Boolean banned = false

    @Column(length = 500)
    String banReason

    /** Optional email for out-of-band notifications (deposit, withdrawal,
     *  trade verified). Added after login via the Profile → Personal Info tab. */
    @Column(length = 255)
    String email

    /**
     * Canonical form of {@link #email} used for uniqueness enforcement
     * — see {@link com.sboxmarket.util.EmailNormalizer#canonicalize}.
     * Persisted alongside the raw address so the UI can keep rendering
     * the user's original casing (e.g. `Voladimir@gmail.com`) while the
     * uniqueness predicate collapses every Google mailbox alias
     * (`v.oladimir+abc@googlemail.com` etc.) onto the same key.
     *
     * Backfilled for legacy rows via V63__steam_user_canonical_email.sql.
     * Computed + written by ProfileController.setEmail on every PUT.
     * Indexed UNIQUE (partial — NULL allowed for Steam-only accounts
     * that never set an email). The UNIQUE constraint at the DB layer
     * is defence-in-depth behind the controller's check — TOCTOU on the
     * uniqueness check can no longer let two concurrent /email writes
     * for the same canonical mailbox slip past.
     */
    @JsonIgnore
    @Column(name = 'canonical_email', length = 255)
    String canonicalEmail

    /** Email confirmation token (one-time) — set when the user changes email,
     *  cleared when they click the confirm link. Never emitted over JSON. */
    @JsonIgnore
    @Column(length = 64)
    String emailVerificationToken

    /**
     * Batch 647 — epoch ms when `emailVerificationToken` expires. Set
     * to `now + 24h` whenever a fresh token is minted via the email
     * change / resend flows. `verifyEmail` rejects tokens whose window
     * has passed. Null = "never expires" for backward-compat with
     * tokens that pre-date the migration AND for the 2FA-staging path
     * (which overloads this column intra-session and doesn't need an
     * expiry).
     */
    @JsonIgnore
    @Column(name = 'email_verification_token_expires_at')
    Long emailVerificationTokenExpiresAt

    @Column
    Boolean emailVerified = false

    /**
     * Base32-encoded TOTP secret. Non-null means 2FA is enabled. Absolutely
     * never serialized — a leak here is game-over for the account's 2FA
     * because anyone with the secret can generate valid codes forever. The
     * client learns *whether* 2FA is enabled via a separate boolean in the
     * ProfileService DTO, it never sees the actual secret.
     */
    @JsonIgnore
    @Column(length = 64)
    String totpSecret

    /** Last TOTP step id used — prevents replay of the same 30-second window. */
    @JsonIgnore
    @Column
    Long lastTotpStep

    /** Steam trade offer URL the user chose to expose for counterparty
     *  contact (https://steamcommunity.com/tradeoffer/new/?partner=...&token=...).
     *  Optional. Shown on the other participant's trade row during the
     *  PENDING_* window so the seller can send — and the buyer can verify —
     *  the real Steam offer. Validated to begin with the canonical prefix. */
    @Column(length = 300)
    String tradeUrl

    /** Staff-only internal notes. NEVER rendered to the user — marked
     *  @JsonIgnore so even admin responses that serialise the whole
     *  entity don't accidentally surface them in a role=USER session.
     *  Editable only via /api/admin/users/{id}/notes. */
    @JsonIgnore
    @Column(name = 'admin_notes', columnDefinition = 'TEXT')
    String adminNotes

    /** Epoch ms of the user's self-service deletion request (GDPR/DSAR).
     *  Soft flag — nothing is actually deleted until an admin reviews
     *  + finalises. Cancelling the request resets this to null. */
    @Column(name = 'deletion_requested_at')
    Long deletionRequestedAt

    /** User-opt-in for non-essential email notifications (outbid, won,
     *  price-drop, etc). Default true. Security + operational emails
     *  (verification, password-reset) ignore this flag. */
    @Column(name = 'email_notifications_enabled', nullable = false)
    Boolean emailNotificationsEnabled = true

    /** Optional self-written seller bio. Rendered on the public
     *  /stall/{id} page under the hero. Sanitised via
     *  TextSanitizer.medium on write — no HTML, capped at 500 chars
     *  (matches the column size). Nullable; empty bios are hidden in
     *  the UI. */
    @Column(name = 'stall_bio', length = 500)
    String stallBio

    /** Monotonically-increasing token for "log out all sessions". On
     *  login the current value is stashed in the HttpSession; every
     *  request compares the two and 401s if they don't match. Bumping
     *  this (via POST /logout-all) instantly invalidates every live
     *  session on every device — useful after a stolen cookie or a
     *  2FA reset. Default 0 — existing sessions pre-feature-launch
     *  keep their stashed 0 and stay valid until explicit logout-all. */
    @JsonIgnore
    @Column(name = 'session_epoch', nullable = false)
    Long sessionEpoch = 0L

    /** SHA-256 hex hashes of the user's unused 2FA backup / recovery
     *  codes, space-separated. Each hash corresponds to a single
     *  one-time code that was shown to the user at 2FA enrollment (or
     *  regeneration) and never stored in the clear. When a user
     *  consumes a code the matching hash is removed from this set.
     *  Never rendered to any client — marked @JsonIgnore so even
     *  admin-side user lookups can't leak the hash list. Null means
     *  "no backup codes on file" (pre-feature-launch accounts). */
    @JsonIgnore
    @Column(name = 'totp_recovery_codes', columnDefinition = 'TEXT')
    String totpRecoveryCodes

    /** Comma-separated set of email-notification buckets the user has
     *  silenced. Layered on top of `emailNotificationsEnabled` — that
     *  is the global kill switch, this is per-bucket fine control.
     *  Buckets: TRADES / AUCTIONS / WATCHLIST / FOLLOWS. Transactional
     *  emails (verification, withdrawal approval, ban) ignore this
     *  field — those are legal-adjacent and cannot be muted. Default
     *  empty string = no buckets muted. */
    @Column(name = 'muted_email_kinds', length = 255, nullable = false)
    String mutedEmailKinds = ''

    /** Epoch-ms at which the user's currently-active "vacation mode"
     *  should auto-resume (un-hide every active listing). Null = not
     *  on a scheduled vacation; either fully active OR indefinitely
     *  hidden via the manual away toggle. The hourly
     *  ListingService.sweepExpiredAwayMode job picks up rows whose
     *  value has passed `now()` and flips the listings + clears this
     *  column atomically. */
    @Column(name = 'away_mode_until')
    Long awayModeUntil
}
