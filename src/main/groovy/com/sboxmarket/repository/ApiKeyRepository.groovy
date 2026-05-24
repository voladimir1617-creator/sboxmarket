package com.sboxmarket.repository

import com.sboxmarket.model.ApiKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    @Query("SELECT k FROM ApiKey k WHERE k.userId = :uid ORDER BY k.createdAt DESC")
    List<ApiKey> findByUser(@Param("uid") Long uid)

    ApiKey findByTokenHash(String tokenHash)

    /** Fraud-triage helper (batch 700). Staff pastes a prefix fragment
     *  (`sbx_live_abc…`) from a suspicious log line and gets back
     *  every matching key so they can identify the owner, see the
     *  label + scope + last-used timestamp, and revoke if warranted.
     *  LIKE rather than equals because log lines sometimes only
     *  capture the first 12-14 chars. Admin-only caller-side. */
    @Query("SELECT k FROM ApiKey k WHERE k.publicPrefix LIKE CONCAT(:prefix, '%') ESCAPE '\\' ORDER BY k.createdAt DESC")
    List<ApiKey> findByPublicPrefixStartsWith(@Param("prefix") String prefix)

    /** Paged companion — a short prefix matches many keys; admin triage
     *  UI should cap the response. */
    @Query("SELECT k FROM ApiKey k WHERE k.publicPrefix LIKE CONCAT(:prefix, '%') ESCAPE '\\' ORDER BY k.createdAt DESC")
    List<ApiKey> findByPublicPrefixStartsWith(@Param("prefix") String prefix,
                                              org.springframework.data.domain.Pageable pageable)

    /** Non-revoked key count per user (batch 692). Powers the per-user
     *  mint-ceiling check — a compromised session otherwise could
     *  burn through the RateLimitFilter bucket and still mint dozens
     *  of long-lived keys before the window resets. Active-only
     *  (revoked keys don't count against the cap — users should
     *  always be able to revoke + reissue without hitting the wall). */
    @Query("SELECT COUNT(k) FROM ApiKey k WHERE k.userId = :uid AND (k.revoked IS NULL OR k.revoked = false)")
    long countActiveByUser(@Param("uid") Long uid)
}
