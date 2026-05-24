package com.sboxmarket.repository

import com.sboxmarket.model.TradeProtection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TradeProtectionRepository extends JpaRepository<TradeProtection, Long> {

    /** The protection record for a trade, if any. One-or-none — the
     *  UNIQUE(trade_id) constraint guarantees at most one row. */
    @Query("SELECT p FROM TradeProtection p WHERE p.tradeId = :tradeId")
    TradeProtection findByTradeId(@Param("tradeId") Long tradeId)

    /** True when a trade already has protection — drives the
     *  double-enable guard so a hot click can't stack two fee charges. */
    boolean existsByTradeId(Long tradeId)

    /** Every protection a buyer has bought, newest-first — powers a
     *  "my protected trades" surface. */
    @Query("SELECT p FROM TradeProtection p WHERE p.buyerUserId = :uid ORDER BY p.createdAt DESC")
    List<TradeProtection> findByBuyer(@Param("uid") Long buyerUserId)

    /** Paged companion — protection rows accumulate one per trade-with-
     *  cover for the buyer's lifetime; cap to avoid hydrating years of
     *  history on every "My protected trades" open. */
    @Query("SELECT p FROM TradeProtection p WHERE p.buyerUserId = :uid ORDER BY p.createdAt DESC")
    List<TradeProtection> findByBuyer(@Param("uid") Long buyerUserId,
                                       org.springframework.data.domain.Pageable pageable)

    /** Count of protections in a given status — feeds admin stat cards
     *  ("active cover", "claims paid"). Backed by idx_trade_protections_status. */
    @Query("SELECT COUNT(p) FROM TradeProtection p WHERE p.status = :status")
    long countByStatus(@Param("status") String status)

    /** Total protection-fee revenue collected since a cutoff — one
     *  indexed SUM for the admin dashboard, mirroring
     *  TradeRepository.sumFeesSince. */
    @Query("""
        SELECT COALESCE(SUM(p.feeAmount), 0) FROM TradeProtection p
        WHERE p.createdAt >= :since
    """)
    BigDecimal sumFeesSince(@Param("since") Long since)
}
