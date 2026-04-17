package com.sboxmarket.model

import jakarta.persistence.*

/**
 * One row per (follower → seller) pair. Enforces the unique pair at the
 * DB level so clicking Follow twice upserts instead of stacking.
 * SellService fires NEW_LISTING_FROM_SELLER notifications to every
 * follower when the subscribed seller posts a new listing.
 */
@Entity
@Table(name = "seller_follows")
class SellerFollow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(name = "follower_user_id", nullable = false)
    Long followerUserId

    @Column(name = "seller_user_id", nullable = false)
    Long sellerUserId

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()
}
