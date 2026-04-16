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
}
