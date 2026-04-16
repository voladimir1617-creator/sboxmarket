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
 * Dedupe rule: **one row per item per calendar day** (keyed off a "MMM dd"
 * label so the chart's x-axis labels are trivially human-readable). If a row
 * already exists for today the price is overwritten in place — so the chart
 * tracks the closing price of the day and the table doesn't explode at the
 * pull cadence of the two writers (SCMM + Steam market sync).
 */
@Service
@Slf4j
class PriceHistoryService {

    @Autowired PriceHistoryRepository priceHistoryRepository

    @Transactional
    void record(Item item, BigDecimal price, Integer volumeDelta = 0) {
        if (item?.id == null || price == null || price <= BigDecimal.ZERO) return
        def today = new SimpleDateFormat('MMM dd').format(new Date())
        def latest = priceHistoryRepository.findLatestByItem(item.id).orElse(null)
        if (latest != null && latest.dayLabel == today) {
            latest.price = price
            if (volumeDelta != null && volumeDelta > 0) {
                latest.volume = (latest.volume ?: 0) + volumeDelta
            }
            priceHistoryRepository.save(latest)
        } else {
            priceHistoryRepository.save(new PriceHistory(
                item:     item,
                price:    price,
                volume:   volumeDelta ?: 0,
                dayLabel: today
            ))
        }
    }
}
