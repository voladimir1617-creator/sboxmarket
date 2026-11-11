package com.sboxmarket.service

import com.sboxmarket.repository.TradeRepository

/**
 * The one rule behind the ✓ Verified seller badge, used by every surface
 * that shows it: marketplace cards, the stall hero, the top-sellers rail,
 * seller search, the bulk /api/sellers/verified lookup and the seller's own
 * verification-progress card.
 *
 * A seller is verified with {@link #MIN_DELIVERED_TRADES}+ delivered trades
 * as the seller AND either no reviews yet or a raw (unrounded) average of
 * {@link #MIN_RATING}+.
 *
 * "Delivered" means a Trade in state VERIFIED: the buyer confirmed receipt,
 * or the release timer ran out after the seller marked it sent. It used to
 * count listings marked SOLD, which flip at the moment of purchase, so a
 * sale that was later cancelled or refunded still counted toward the badge.
 * Self-trades never count.
 */
final class SellerVerifiedRule {

    static final long   MIN_DELIVERED_TRADES = 10L
    static final double MIN_RATING           = 4.0d

    private SellerVerifiedRule() {}

    /** @param reviewCount number of reviews the seller has received
     *  @param ratingAverage the RAW average (not the display-rounded one);
     *         ignored when reviewCount is 0 */
    static boolean isVerified(long deliveredTrades, long reviewCount, Double ratingAverage) {
        deliveredTrades >= MIN_DELIVERED_TRADES && ratingOk(reviewCount, ratingAverage)
    }

    static boolean ratingOk(long reviewCount, Double ratingAverage) {
        reviewCount <= 0L || (ratingAverage != null && ratingAverage >= MIN_RATING)
    }

    static long tradesNeeded(long deliveredTrades) {
        Math.max(0L, MIN_DELIVERED_TRADES - deliveredTrades)
    }

    /** Delivered-trade count for one seller; 0 when the repository is absent. */
    static long deliveredTrades(TradeRepository repo, Long sellerUserId) {
        if (repo == null || sellerUserId == null) return 0L
        (repo.countVerifiedBySeller(sellerUserId) ?: 0L) as long
    }

    /** Delivered-trade counts for many sellers in one GROUP BY. Sellers with
     *  none are absent from the map; read missing as 0. */
    static Map<Long, Long> deliveredTradesBySeller(TradeRepository repo, Collection<Long> sellerUserIds) {
        Map<Long, Long> out = [:]
        def ids = (sellerUserIds ?: []).findAll { it != null } as List<Long>
        if (repo == null || ids.isEmpty()) return out
        (repo.countVerifiedBySellerIds(ids) ?: []).each { Object[] row ->
            if (row == null || row[0] == null) return
            out[(row[0] as Long)] = ((row[1] ?: 0L) as Number).longValue()
        }
        out
    }
}
