package com.sboxmarket.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@Entity
@Table(name = "loadout_slots")
@JsonIgnoreProperties(["hibernateLazyInitializer", "handler"])
class LoadoutSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false)
    Long loadoutId

    /** Hats, Jackets, Shirts, Pants, Gloves, Boots, Accessories, Wild */
    @Column(nullable = false)
    String slot

    /** Optional — null means "empty slot". */
    @Column
    Long itemId

    /** Snapshot at save time — survives item renames. */
    @Column
    String itemName

    /** Snapshot of the item icon/emoji for quick rendering. */
    @Column
    String itemEmoji

    /** Snapshot of the lowest price at time of save (display only). */
    @Column(precision = 19, scale = 2)
    BigDecimal snapshotPrice = BigDecimal.ZERO

    /** Locked slots are not changed by the AI "Generate" action. */
    @Column(nullable = false)
    Boolean locked = false
}
