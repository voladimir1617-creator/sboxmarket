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

    /** Loadout ids a user has favorited, newest-favorite first. Drives
     *  the Profile → "Favorite loadouts" tab so a user can find the
     *  builds they starred earlier without having to browse Discover
     *  again. */
    @Query("""
        SELECT f.loadoutId FROM LoadoutFavorite f
        WHERE f.userId = :uid
        ORDER BY f.createdAt DESC
    """)
    List<Long> findLoadoutIdsByUser(@Param("uid") Long userId)
}
