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

    /** Email confirmation token (one-time) — set when the user changes email,
     *  cleared when they click the confirm link. Never emitted over JSON. */
    @JsonIgnore
    @Column(length = 64)
    String emailVerificationToken

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
}
