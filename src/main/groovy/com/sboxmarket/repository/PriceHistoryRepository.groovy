package com.sboxmarket.repository

import com.sboxmarket.model.PriceHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    @Query("SELECT p FROM PriceHistory p WHERE p.item.id = :itemId ORDER BY p.recordedAt ASC")
    List<PriceHistory> findByItemIdOrdered(@Param("itemId") Long itemId)

    @Query("SELECT p FROM PriceHistory p WHERE p.item.id = :itemId ORDER BY p.recordedAt DESC LIMIT :days")
    List<PriceHistory> findRecentByItemId(@Param("itemId") Long itemId, @Param("days") int days)
}
