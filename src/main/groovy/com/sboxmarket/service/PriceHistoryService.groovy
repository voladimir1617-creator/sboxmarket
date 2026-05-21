package com.sboxmarket.service

import com.sboxmarket.model.Item
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.PriceHistoryRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.text.SimpleDateFormat

/**
 * Append-or-coalesce writer for the item price-history table that feeds the
 * sparkline on the item detail modal. Without this service the table stayed
 * empty and the chart rendered as a blank strip for every item.
 *
 * Dedupe rule: **one row per item per calendar day** (keyed off a
 * "MMM dd, yyyy" label so the chart's x-axis labels are human-readable AND
 * unambiguous). If a row already exists for today the price is overwritten
 * in place — so the chart tracks the closing price of the day and the table
 * doesn't explode at the pull cadence of the two writers (SCMM + Steam
 * market sync).
 *
 * The label MUST carry the year: `getPriceHistory` hydrates a 400-day
 * window, so a series routinely spans two calendar years. A year-less
 * "MMM dd" label collided same-day rows across years — both the chart
 * tooltip ("Apr 01" with no year) AND, worse, the coalesce key: an item
 * idle for ~365 days whose most-recent row was last year's "Apr 01" would
 * have today's "Apr 01" price overwrite that year-old data point in place
 * instead of appending a fresh row.
 */
@Service
@Slf4j
class PriceHistoryService {

    /** Year-qualified so the dayLabel is a unique key across the 400-day
     *  hydration window — not just "MMM dd", which repeats every year. */
    private static final String DAY_LABEL_PATTERN = 'MMM dd, yyyy'

    @Autowired PriceHistoryRepository priceHistoryRepository

    @Transactional
    void record(Item item, BigDecimal price, Integer volumeDelta = 0) {
        if (item?.id == null || price == null || price <= BigDecimal.ZERO) return
        // A fresh SimpleDateFormat per call — the class is not thread-safe
        // and this writer is hit concurrently by the SCMM + Steam syncs.
        def today = new SimpleDateFormat(DAY_LABEL_PATTERN).format(new Date())
        // Volume is a non-negative trade count; clamp the delta so a
        // negative value can never decrement (update) or seed a negative
        // row (insert). Treat null as zero.
        int bump = Math.max(0, volumeDelta ?: 0)
        def latest = priceHistoryRepository.findLatestByItem(item.id).orElse(null)
        if (latest != null && latest.dayLabel == today) {
            latest.price = price
            if (bump > 0) {
                latest.volume = (latest.volume ?: 0) + bump
            }
            priceHistoryRepository.save(latest)
        } else {
            priceHistoryRepository.save(new PriceHistory(
                item:     item,
                price:    price,
                volume:   bump,
                dayLabel: today
            ))
        }
    }
}
