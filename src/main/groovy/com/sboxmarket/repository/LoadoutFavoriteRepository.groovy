package com.sboxmarket.repository

import com.sboxmarket.model.LoadoutFavorite
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface LoadoutFavoriteRepository extends JpaRepository<LoadoutFavorite, Long> {

    @Query("SELECT f FROM LoadoutFavorite f WHERE f.userId = :uid AND f.loadoutId = :lid")
    LoadoutFavorite findByUserAndLoadout(@Param("uid") Long userId, @Param("lid") Long loadoutId)

    @Modifying
    @Query("DELETE FROM LoadoutFavorite f WHERE f.userId = :uid AND f.loadoutId = :lid")
    int deleteByUserAndLoadout(@Param("uid") Long userId, @Param("lid") Long loadoutId)

    @Query("SELECT COUNT(f) FROM LoadoutFavorite f WHERE f.loadoutId = :lid")
    long countByLoadout(@Param("lid") Long loadoutId)
}
