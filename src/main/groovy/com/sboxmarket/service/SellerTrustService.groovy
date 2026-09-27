package com.sboxmarket.service

import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * A seller's public trade record: what a buyer needs at the moment of
 * deciding whether to send money to a stranger.
 *
 * csfloat shows it on every item view, next to the seller's avatar: how fast
 * they send and what share of their trades completed, with the count. Here the
 * item page showed nothing about the seller of the listing being bought, and
 * the stall's "lifetime sales" counted listings marked SOLD at the moment of
 * purchase, before anyone knew whether the item would ever be delivered.
 *
 * Every figure is derived from trades, never from listings:
 *   completedTrades  VERIFIED trades (the buyer confirmed, or the release
 *                    timer ran out after the seller marked it sent)
 *   failedTrades     trades the SELLER cancelled or let time out; a buyer
 *                    backing out, a staff ruling and rows cancelled before
 *                    `cancelled_by` existed are not counted either way
 *   completionRate   completed / (completed + failed), whole percent,
 *                    FLOORED so a seller with one failure in two hundred
 *                    trades reads 99%, not 100%; null when there is nothing
 *                    to divide by, so "no record" never reads as "100%"
 *   medianShipMs     median time from sale to "marked sent" over the last
 *                    90 days, null under three samples (TradeService's floor)
 *
 * Banned accounts are left out entirely: their listings are already hidden,
 * and a record next to nothing would only invite questions a banner answers.
 */
@Service
@Slf4j
class SellerTrustService {

    @Autowired TradeRepository tradeRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired(required = false) TradeService tradeService
    @Autowired(required = false) ReviewService reviewService

    /** Upper bound on one bulk call — the item page asks for at most the
     *  sellers of the listings it shows. */
    static final int MAX_IDS = 50

    Map<Long, Map> recordFor(Collection<Long> sellerIds) {
        Map<Long, Map> out = [:]
        List<Long> ids = (sellerIds ?: []).findAll { it != null && it > 0L }.unique().take(MAX_IDS) as List<Long>
        if (ids.isEmpty()) return out
        def users = steamUserRepository.findAllById(ids).findAll { !Boolean.TRUE.equals(it.banned) }
        if (users.isEmpty()) return out
        List<Long> userIds = users.collect { it.id }

        Map<Long, List<Long>> record = [:]
        try {
            (tradeRepository.tradeRecordForSellers(userIds) ?: []).each { Object[] row ->
                if (row == null || row[0] == null) return
                record[(row[0] as Long)] = [
                    ((row[1] ?: 0) as Number).longValue(),
                    ((row[2] ?: 0) as Number).longValue()
                ]
            }
        } catch (Exception e) {
            // A failed aggregate must not render as a spotless record: leave
            // these sellers OUT of the answer so the page shows nothing
            // rather than "0 trades" or "100%".
            log.warn("Seller trade record lookup failed for ${userIds}: ${e.message}")
            return out
        }

        Map<Long, Long> ship = [:]
        try {
            ship = tradeService?.typicalShipMsBulk(userIds) ?: [:]
        } catch (Exception e) {
            log.debug("typicalShipMsBulk failed: ${e.message}")
        }
        Map<Long, Map> ratings = [:]
        try {
            ratings = reviewService?.summariesForUsers(userIds) ?: [:]
        } catch (Exception e) {
            log.debug("summariesForUsers failed: ${e.message}")
        }

        users.each { u ->
            long completed = record[u.id] ? record[u.id][0] : 0L
            long failed    = record[u.id] ? record[u.id][1] : 0L
            long decided   = completed + failed
            def rating     = ratings[u.id]
            out[u.id] = [
                sellerUserId:    u.id,
                completedTrades: completed,
                failedTrades:    failed,
                completionRate:  decided > 0L ? (int) Math.floor(completed * 100.0d / decided) : null,
                medianShipMs:    ship[u.id],
                memberSince:     u.createdAt,
                lastSeenAt:      u.lastSeenAt,
                rating:          rating?.average == null ? null : (rating.average as Double),
                reviewCount:     ((rating?.count ?: 0) as Number).intValue(),
            ] as Map
        }
        out
    }
}
