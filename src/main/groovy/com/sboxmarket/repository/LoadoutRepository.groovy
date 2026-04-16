package com.sboxmarket.repository

import com.sboxmarket.model.Loadout
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface LoadoutRepository extends JpaRepository<Loadout, Long> {

    @Query("SELECT l FROM Loadout l WHERE l.ownerUserId = :uid ORDER BY l.updatedAt DESC")
    List<Loadout> findByOwner(@Param("uid") Long uid)

    @Query("SELECT l FROM Loadout l WHERE l.visibility = 'PUBLIC' ORDER BY l.favorites DESC, l.updatedAt DESC")
    List<Loadout> findPublic(Pageable page)

    @Query("SELECT l FROM Loadout l WHERE l.visibility = 'PUBLIC' AND LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY l.favorites DESC")
    List<Loadout> searchPublic(@Param("q") String q, Pageable page)
}
