package com.sboxmarket.repository

import com.sboxmarket.model.Transaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByWalletIdOrderByCreatedAtDesc(Long walletId)

    Transaction findByStripeReference(String stripeReference)

    /** Single SUM aggregate for the admin dashboard 24h volume charts.
     *  Replaces `findAll().findAll { ... }.sum()` which is O(N) over every
     *  transaction the platform has ever recorded. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.type = :type AND t.status = :status AND t.createdAt >= :since")
    BigDecimal sumByTypeSinceCompleted(
        @Param('type')   String type,
        @Param('status') String status,
        @Param('since')  Long since
    )

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.type = :type AND t.status = :status")
    long countByTypeStatus(@Param('type') String type, @Param('status') String status)

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.type = :type AND t.status = :status")
    BigDecimal sumByTypeStatus(@Param('type') String type, @Param('status') String status)

    List<Transaction> findByTypeAndStatus(String type, String status)

    @Query("SELECT t FROM Transaction t WHERE t.type = :type AND t.status = :status ORDER BY t.createdAt DESC")
    List<Transaction> findByTypeAndStatusOrderByCreatedAtDesc(@Param('type') String type, @Param('status') String status)

    /** Per-wallet per-type sum — used by `ProfileService.buildProfile` to
     *  compute purchase / sale / deposit totals without loading every
     *  transaction row for the user into Groovy memory first. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.walletId = :walletId AND t.type = :type AND (:requireCompleted = false OR t.status = 'COMPLETED')")
    BigDecimal sumByWalletAndType(
        @Param('walletId') Long walletId,
        @Param('type') String type,
        @Param('requireCompleted') boolean requireCompleted
    )

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.walletId = :walletId AND t.type = :type")
    long countByWalletAndType(@Param('walletId') Long walletId, @Param('type') String type)

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.walletId = :walletId AND t.type = :type AND t.status = 'COMPLETED'")
    long countCompletedByWalletAndType(@Param('walletId') Long walletId, @Param('type') String type)
}
