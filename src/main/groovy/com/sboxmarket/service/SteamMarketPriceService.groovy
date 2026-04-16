package com.sboxmarket.service

import com.sboxmarket.repository.ItemRepository
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Fetches live prices directly from the Steam Community Market
 * priceoverview endpoint. Replaces SCMM-sourced prices with the
 * actual lowest_price and median_price from Steam itself.
 *
 * Endpoint: GET https://steamcommunity.com/market/priceoverview/
 *   ?country=US&currency=1&appid=590830&market_hash_name=ITEM_NAME
 *
 * Rate limit: Steam allows ~1 req/s on this endpoint. We throttle
 * to 1.5s between calls so a full 80-item catalogue takes ~2 minutes.
 * Runs every 15 minutes on its own scheduler thread.
 */
@Service
@Slf4j
class SteamMarketPriceService {

    static final String SBOX_APP_ID = '590830'
    static final long SYNC_INTERVAL_MS = 15L * 60L * 1000L

    @Autowired ItemRepository itemRepository

    @Scheduled(fixedDelay = SYNC_INTERVAL_MS, initialDelay = 3L * 60L * 1000L)
    @Transactional
    void syncPricesFromSteam() {
        def items = itemRepository.findAll()
        if (items.isEmpty()) return

        int updated = 0, failed = 0, skipped = 0
        log.info("Steam Market price sync starting — ${items.size()} items")

        for (def item : items) {
            if (!item.name) { skipped++; continue }

            try {
                def prices = fetchSteamPrice(item.name)
                if (prices == null) { skipped++; continue }

                def lowestPrice = prices.lowest
                def medianPrice = prices.median

                // Use lowest_price as the primary, fall back to median
                def bestPrice = lowestPrice ?: medianPrice
                if (bestPrice != null && bestPrice > BigDecimal.ZERO) {
                    def oldPrice = item.lowestPrice
                    item.lowestPrice = bestPrice
                    item.steamPrice = lowestPrice ?: item.steamPrice

                    // Calculate trend from old → new price
                    if (oldPrice != null && oldPrice > BigDecimal.ZERO) {
                        def change = (bestPrice - oldPrice) / oldPrice
                        item.trendPercent = Math.max(-99,
                            Math.min(99, Math.round(change * 100) as int))
                    }

                    itemRepository.save(item)
                    updated++
                } else {
                    skipped++
                }
            } catch (Exception e) {
                log.debug("Steam price fetch failed for '${item.name}': ${e.message}")
                failed++
            }

            // Throttle: 1.5s between requests to stay under Steam's rate limit
            try { Thread.sleep(1500L) }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
                break
            }
        }

        log.info("Steam Market price sync done — updated=$updated skipped=$skipped failed=$failed")
    }

    /**
     * Fetch the current lowest and median price for an item from
     * Steam's public priceoverview endpoint. Returns null if the
     * item isn't listed on the Steam Community Market.
     */
    Map fetchSteamPrice(String marketHashName) {
        def encoded = URLEncoder.encode(marketHashName, 'UTF-8')
        def url = "https://steamcommunity.com/market/priceoverview/?country=US&currency=1&appid=${SBOX_APP_ID}&market_hash_name=${encoded}"

        def conn = (HttpURLConnection) new URL(url).openConnection()
        conn.setRequestProperty('User-Agent', 'SkinBox/1.0 (+https://skinbox.market)')
        conn.setRequestProperty('Accept', 'application/json')
        conn.connectTimeout = 8000
        conn.readTimeout = 10000

        int status = conn.responseCode
        if (status == 429) {
            log.warn("Steam Market rate-limited (429) — backing off")
            Thread.sleep(5000L)
            return null
        }
        if (status != 200) return null

        def body = conn.inputStream.getText('UTF-8')
        def json = new JsonSlurper().parseText(body)
        if (!json || json.success != true) return null

        [
            lowest: parseSteamPrice(json.lowest_price),
            median: parseSteamPrice(json.median_price)
        ]
    }

    private static BigDecimal parseSteamPrice(String raw) {
        if (!raw) return null
        // "$1.23" → 1.23 ; "1,23€" → 1.23
        def cleaned = raw.replaceAll(/[^\d.]/, '')
        if (!cleaned) return null
        try {
            def bd = new BigDecimal(cleaned)
            return bd > BigDecimal.ZERO ? bd : null
        } catch (NumberFormatException ignored) {
            return null
        }
    }
}
