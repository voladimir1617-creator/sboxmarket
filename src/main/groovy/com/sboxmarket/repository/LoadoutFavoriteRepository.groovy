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

    @org.springframework.transaction.annotation.Transactional
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

    /** Paged companion — a power-user can favorite hundreds of loadouts
     *  over time; the cap keeps the Favorites tab render bounded. */
    @Query("""
        SELECT f.loadoutId FROM LoadoutFavorite f
        WHERE f.userId = :uid
        ORDER BY f.createdAt DESC
    """)
    List<Long> findLoadoutIdsByUser(@Param("uid") Long userId,
                                     org.springframework.data.domain.Pageable pageable)

    /** Cascade-cleanup helper. Loadout rows are hard-deleted by both
     *  the owner self-delete path and the admin takedown path; without
     *  this, favorite rows pointing at the now-gone loadout linger
     *  indefinitely. listFavorites() silently filters them out (the
     *  loadout id no longer resolves to a Loadout row), but the table
     *  grows without bound and the next favorite-count query for the
     *  re-used IDENTITY id would be wrong. Returns the row count so the
     *  service can log the cleanup. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("DELETE FROM LoadoutFavorite f WHERE f.loadoutId = :lid")
    int deleteByLoadoutId(@Param("lid") Long loadoutId)
}
