package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@Entity
@Table(name = "loadout_favorites",
       uniqueConstraints = [@UniqueConstraint(name = "uq_loadout_fav_user_loadout",
                                              columnNames = ["user_id", "loadout_id"])])
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class LoadoutFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = "user_id", nullable = false)
    Long userId

    @Column(name = "loadout_id", nullable = false)
    Long loadoutId

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
