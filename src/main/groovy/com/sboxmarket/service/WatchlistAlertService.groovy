package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Server-side watchlist price alerts.
 *
 * Unlike the old localStorage alerts (which only fired when the user
 * opened the Watchlist page), these persist across devices and fire
 * even while the user is offline. The scheduled sweeper polls every
 * 5 minutes, finds ACTIVE alerts whose item's lowestPrice is at or
 * below the target, pushes a WATCHLIST_PRICE_DROP notification, and
 * flips the alert to FIRED. A re-set on the same item replaces the
 * FIRED row with a fresh ACTIVE one.
 */
@Service
@Slf4j
class WatchlistAlertService {

    @Autowired WatchlistAlertRepository repo
    @Autowired ItemRepository itemRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) EmailService emailService
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository

    /** Hard cap per user. Protects the sweeper from one-user-creates-
     *  a-million-alerts abuse and keeps the watchlist UI sane. */
    private static final int PER_USER_LIMIT = 50

    @Transactional
    WatchlistAlert upsertAlert(Long userId, Long itemId, BigDecimal targetPrice) {
        if (targetPrice == null || targetPrice <= BigDecimal.ZERO) {
            throw new BadRequestException('INVALID_TARGET',
                'Target price must be greater than zero')
        }
        if (targetPrice > new BigDecimal('100000')) {
            throw new BadRequestException('TARGET_TOO_HIGH',
                'Target price exceeds the \$100,000 sanity cap')
        }
        // Item must exist — otherwise the sweeper's join silently drops
        // the row forever.
        def item = itemRepository.findById(itemId)
            .orElseThrow { new NotFoundException('Item', itemId) }

        def existing = repo.findActiveFor(userId, itemId)
        if (existing.isPresent()) {
            def a = existing.get()
            a.targetPrice = targetPrice.setScale(2, BigDecimal.ROUND_HALF_UP)
            a.createdAt   = System.currentTimeMillis()
            return repo.save(a)
        }
        // Quota check — only applies when creating a brand new alert, so
        // updating an existing one never hits the cap.
        long activeCount = repo.countByUserIdAndStatus(userId, 'ACTIVE')
        if (activeCount >= PER_USER_LIMIT) {
            throw new BadRequestException('ALERT_LIMIT',
                "Active alert limit reached (${PER_USER_LIMIT}). Delete one before adding more.")
        }
        def alert = new WatchlistAlert(
            userId:      userId,
            itemId:      itemId,
            targetPrice: targetPrice.setScale(2, BigDecimal.ROUND_HALF_UP),
            status:      'ACTIVE',
            createdAt:   System.currentTimeMillis()
        )
        repo.save(alert)
        log.info("User ${userId} set price alert ${item.name ?: itemId} @ \$${alert.targetPrice}")
        alert
    }

    List<WatchlistAlert> listForUser(Long userId) {
        repo.findByUserId(userId)
    }

    /** Public demand-side social proof for an item: how many viewers
     *  currently have an ACTIVE alert. Aggregate only — no watcher
     *  identities are exposed. Null itemId → 0, so the UI never has to
     *  guard the call. */
    long countWatchersForItem(Long itemId) {
        if (itemId == null) return 0L
        repo.countActiveForItem(itemId)
    }

    /** Delete every FIRED alert the user has accumulated — lets users
     *  tidy the Watchlist summary without having to cancel each one. */
    @Transactional
    int clearFired(Long userId) {
        repo.deleteFiredForUser(userId)
    }

    @Transactional
    void cancelAlert(Long userId, Long alertId) {
        def a = repo.findById(alertId)
            .orElseThrow { new NotFoundException('WatchlistAlert', alertId) }
        if (a.userId != userId) {
            throw new BadRequestException('NOT_OWNER', 'Not your alert')
        }
        a.status = 'CANCELLED'
        repo.save(a)
    }

    /**
     * Scheduled sweeper. Every 5 minutes scans for ACTIVE alerts whose
     * item's lowestPrice crossed the target, pushes one notification
     * per triggered row, and flips the alert to FIRED. Single pass, no
     * pagination — the repo query uses two indexes (idx_watchlist_
     * alerts_active_item + the Item PK) so even a large population
     * stays O(matches).
     */
    @Scheduled(fixedDelayString = '${watchlist-alert.sweep-ms:300000}',
               initialDelayString = '${watchlist-alert.initial-delay-ms:60000}')
    @Transactional
    void sweep() {
        def triggered = repo.findTriggered()
        if (triggered.isEmpty()) return
        int fired = 0
        triggered.each { row ->
            try {
                WatchlistAlert a = row[0] as WatchlistAlert
                BigDecimal currentFloor = (row[1] as BigDecimal) ?: BigDecimal.ZERO
                def item = itemRepository.findById(a.itemId).orElse(null)
                def name = item?.name ?: "Item #${a.itemId}"
                notificationService?.push(a.userId, 'WATCHLIST_PRICE_DROP',
                    "Price drop · ${name}",
                    "Floor price reached \$${currentFloor.toPlainString()} (target \$${a.targetPrice.toPlainString()})",
                    a.itemId,
                    "/item/${a.itemId}")
                // Email the user too — price drops are time-sensitive.
                // Gated on the email-notifications preference + verified.
                try {
                    def user = steamUserRepository?.findById(a.userId)?.orElse(null)
                    if (emailService != null && user != null &&
                            Boolean.TRUE.equals(user.emailVerified) && user.email &&
                            Boolean.TRUE.equals(user.emailNotificationsEnabled)) {
                        emailService.sendPriceDrop(user.email, user.displayName,
                            name, currentFloor, a.targetPrice,
                            "/item/${a.itemId}".toString())
                    }
                } catch (Exception inner) {
                    log.warn("Price-drop email failed for user ${a.userId}: ${inner.message}")
                }
                a.status = 'FIRED'
                a.firedAt = System.currentTimeMillis()
                repo.save(a)
                fired++
            } catch (Exception e) {
                log.warn("Watchlist alert sweep failed for one row: ${e.message}")
            }
        }
        log.info("Watchlist alert sweep: fired ${fired} of ${triggered.size()} matches")
    }
}
