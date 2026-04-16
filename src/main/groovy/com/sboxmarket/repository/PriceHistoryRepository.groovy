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

    /** Most-recent row for an item. Used by PriceHistoryService to decide
     *  whether to coalesce today's update into an existing row or append a
     *  new one. Optional<> because newly indexed items have no history. */
    @Query("SELECT p FROM PriceHistory p WHERE p.item.id = :itemId ORDER BY p.recordedAt DESC LIMIT 1")
    java.util.Optional<PriceHistory> findLatestByItem(@Param("itemId") Long itemId)
}
