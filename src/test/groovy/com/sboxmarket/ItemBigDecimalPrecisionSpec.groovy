package com.sboxmarket

import com.sboxmarket.model.Item
import jakarta.persistence.Column
import spock.lang.Specification

/**
 * Pin spec for Item.lowestPrice / Item.steamPrice precision.
 *
 * Both columns are BigDecimal money fields, but until this fix the
 * @Column annotations omitted precision/scale. Hibernate 6's JPA
 * default for an unannotated BigDecimal is `numeric(p, 0)` — scale
 * zero — so every fractional-cent value written by
 * ListingService.updateItemFloorPrice, ListingFloorRefreshService,
 * SteamMarketPriceService, or SeedService was silently rounded to
 * a whole dollar on persist. The marketplace homepage rails, /db
 * catalogue grid, stall "from $X" chips, and ItemModal demand
 * chip would render `$13.00` for a $12.99 floor and so on.
 *
 * Every other money column in the project (Wallet.balance,
 * Listing.price, Transaction.amount, Offer.amount, Trade.price,
 * Bid.amount, Loadout.totalValue, LoadoutSlot.snapshotPrice,
 * BuyOrder.maxPrice, TradeProtection.feeAmount/coverageAmount,
 * Listing.currentBid / buyNowPrice) carries an explicit
 * `precision = 19, scale = 2` (or 10, 2). Only the two Item.*
 * money columns were missing it.
 *
 * Compile-time reflection assertion — no DB round-trip needed,
 * which keeps the spec fast and free of the H2 schema cache
 * collision Spock specs sometimes hit. A future @Column edit that
 * silently strips scale (or precision) on either field will fail
 * this assertion before it ever lands in the DB DDL.
 */
class ItemBigDecimalPrecisionSpec extends Specification {

    def "Item.#fieldName carries an explicit precision and scale on its @Column"(String fieldName) {
        given:
        def field = Item.getDeclaredField(fieldName)
        def col   = field.getAnnotation(Column)

        expect: "the @Column has the project-standard money precision"
        col != null
        col.precision() == 19
        col.scale()     == 2

        where:
        fieldName << ['lowestPrice', 'steamPrice']
    }
}
