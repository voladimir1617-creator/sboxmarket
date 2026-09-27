package com.sboxmarket.repository

import com.sboxmarket.model.LoadoutSlot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface LoadoutSlotRepository extends JpaRepository<LoadoutSlot, Long> {

    @Query("SELECT s FROM LoadoutSlot s WHERE s.loadoutId = :lid ORDER BY s.id ASC")
    List<LoadoutSlot> findByLoadout(@Param("lid") Long loadoutId)

    /** Bulk slot fetch for the Discover / Mine / Favorites card grid —
     *  one query for every loadout on the page instead of N findByLoadout
     *  round-trips. */
    @Query("SELECT s FROM LoadoutSlot s WHERE s.loadoutId IN :ids ORDER BY s.loadoutId ASC, s.id ASC")
    List<LoadoutSlot> findByLoadoutIdIn(@Param("ids") Collection<Long> ids)

    void deleteByLoadoutId(Long loadoutId)
}
