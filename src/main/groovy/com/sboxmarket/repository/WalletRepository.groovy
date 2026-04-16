package com.sboxmarket.repository

import com.sboxmarket.model.Wallet
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface WalletRepository extends JpaRepository<Wallet, Long> {

    Wallet findByUsername(String username)

    /** Single SUM aggregate instead of `findAll().sum { it.balance }`.
     *  Drops the admin-dashboard roundtrip from O(N) to O(1). */
    @Query("SELECT COALESCE(SUM(w.balance), 0) FROM Wallet w")
    BigDecimal sumAllBalances()
}
