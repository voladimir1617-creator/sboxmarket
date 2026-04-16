package com.sboxmarket.repository

import com.sboxmarket.model.BuyOrder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface BuyOrderRepository extends JpaRepository<BuyOrder, Long> {

    @Query("SELECT b FROM BuyOrder b WHERE b.buyerUserId = :uid ORDER BY b.createdAt DESC")
    List<BuyOrder> findByBuyer(@Param("uid") Long uid)

    @Query("SELECT COUNT(b) FROM BuyOrder b WHERE b.buyerUserId = :uid AND b.status = 'ACTIVE'")
    long countActiveByBuyer(@Param("uid") Long uid)

    @Query("SELECT b FROM BuyOrder b WHERE b.status = 'ACTIVE' ORDER BY b.maxPrice DESC")
    List<BuyOrder> findAllActive()

    @Query("""
        SELECT b FROM BuyOrder b
        WHERE b.status = 'ACTIVE'
          AND b.quantity > 0
          AND b.maxPrice >= :price
          AND (b.itemId   IS NULL OR b.itemId   = :itemId)
          AND (b.category IS NULL OR b.category = :category)
          AND (b.rarity   IS NULL OR b.rarity   = :rarity)
        ORDER BY b.maxPrice DESC, b.createdAt ASC
    """)
    List<BuyOrder> findMatching(
        @Param("itemId")   Long itemId,
        @Param("category") String category,
        @Param("rarity")   String rarity,
        @Param("price")    BigDecimal price
    )
}
