package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Opaque API keys users can issue for third-party bots / extensions. We store
 * only a SHA-256 hash of the plaintext token — the raw value is returned *once*
 * when the key is created and never again, mirroring how CSFloat's dev portal works.
 */
@Entity
@Table(name = "api_keys")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false)
    Long userId

    /** A short public identifier shown in the UI (e.g. "sbx_live_a1b2…"). Safe to display. */
    @Column(nullable = false, unique = true, length = 32)
    String publicPrefix

    /**
     * SHA-256 of the plaintext token. Raw token is never persisted, and the
     * hash is explicitly excluded from JSON — even though SHA-256 of a 256-bit
     * random payload isn't directly reversible, leaking the hash lets an
     * attacker verify offline guesses without hitting the rate limiter, so
     * `/api/api-keys` now serializes every other field *except* this one.
     */
    @JsonIgnore
    @Column(nullable = false, length = 64)
    String tokenHash

    /** User-provided label — "my-bot", "trade-helper", etc. */
    @Column(length = 80)
    String label

    @Column(nullable = false)
    Boolean revoked = false

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column
    Long lastUsedAt
}
