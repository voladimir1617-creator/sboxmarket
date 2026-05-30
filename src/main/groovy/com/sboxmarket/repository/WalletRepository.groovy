package com.sboxmarket.repository

import com.sboxmarket.model.Wallet
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface WalletRepository extends JpaRepository<Wallet, Long> {

    Wallet findByUsername(String username)

    /** Single SUM aggregate instead of `findAll().sum { it.balance }`.
     *  Drops the admin-dashboard roundtrip from O(N) to O(1). */
    @Query("SELECT COALESCE(SUM(w.balance), 0) FROM Wallet w")
    BigDecimal sumAllBalances()

    /** Pessimistic-write lock on one wallet row — SERIALIZES the rolling-24h
     *  deposit-cap check in StripeService.createDepositSession. Without it, N
     *  concurrent POST /api/wallet/deposit each read the same sumDepositsSince
     *  (the deposit path only READS the wallet — it never saves it, so the
     *  @Version guarding withdrawals/purchases never fires) and every request
     *  passes the cap, letting ~20 parallel Checkout sessions blow a $5k/24h
     *  cap to ~$190k — the card-testing / stolen-card drain the cap exists to
     *  stop. With this lock, concurrent callers block until the prior session's
     *  PENDING row is committed and visible to their sumDepositsSince. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param('id') Long id)
}
