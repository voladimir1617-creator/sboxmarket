package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Background job — walks every registered Steam user every ~20 minutes and:
 *   1) refreshes their display name / avatar in case they changed it on Steam,
 *   2) re-reads their public s&box inventory (appid 590830),
 *   3) caches the inventory size and lastSyncedAt on the SteamUser row so the
 *      Profile modal can show "last synced ago" without another round-trip.
 *
 * Failures per-user are logged and isolated — one 403/429 never halts the
 * sweep. Rate-limited to one user per second to avoid Steam anti-abuse.
 */
@Service
@Slf4j
class SteamSyncService {

    // 20 minutes — explicit in ms so the value isn't hidden behind a unit string.
    static final long SYNC_INTERVAL_MS = 20L * 60L * 1000L

    /** How stale a user's Steam profile can be before the sweeper picks them
     *  up. Combined with the per-tick batch cap below, this is how we
     *  guarantee every user gets refreshed on a rolling schedule. */
    static final long STALE_AFTER_MS = 24L * 60L * 60L * 1000L

    /** Hard cap on how many users a single sweep tick will touch. At 1 req/s
     *  this gives a ~15-minute worst-case tick, comfortably inside the
     *  20-minute interval so ticks don't overlap. */
    static final int BATCH_SIZE = 900

    @Autowired SteamUserRepository steamUserRepository
    @Autowired SteamAuthService steamAuthService
    @Autowired SteamInventoryService steamInventoryService
    @Autowired NotificationService notificationService

    @Scheduled(fixedDelay = SYNC_INTERVAL_MS, initialDelay = 60_000L)
    void syncAllUsers() {
        // Only pull users who are actually stale, and cap the batch so
        // one tick can never balloon into an hours-long walk. Users get
        // processed oldest-lastSyncedAt-first so a newly registered
        // account lands on the front of the queue.
        def cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        def page = org.springframework.data.domain.PageRequest.of(0, BATCH_SIZE)
        def users = steamUserRepository.findStaleForSync(cutoff, page)
        if (users.isEmpty()) return
        log.info("Steam sync tick — ${users.size()} stale users (cutoff ${STALE_AFTER_MS / 3600000}h)")
        users.each { user ->
            try {
                syncOne(user)
                Thread.sleep(1000L)  // 1 req/s ceiling
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
                return
            } catch (Exception e) {
                log.warn("Steam sync failed for ${user.steamId64}: ${e.message}")
            }
        }
    }

    @Transactional
    void syncOne(SteamUser user) {
        // 1) profile refresh — reuse the same code path login uses so display
        // name / avatar stays in lock-step with Steam.
        try {
            steamAuthService.upsertUser(user.steamId64)
        } catch (Exception e) {
            log.debug("Profile refresh skipped for ${user.steamId64}: ${e.message}")
        }

        // 2) inventory snapshot — count items, remember previous count so we
        // can fire a notification when new items appear.
        def inv = steamInventoryService.fetchInventory(user.steamId64)
        def before = user.steamInventorySize ?: 0
        def now = inv.size()
        def fresh = steamUserRepository.findById(user.id).orElse(user)
        fresh.steamInventorySize = now
        fresh.lastSyncedAt = System.currentTimeMillis()
        steamUserRepository.save(fresh)

        if (now > before) {
            notificationService?.push(user.id, 'STEAM_INVENTORY',
                "New Steam inventory items",
                "${now - before} new item(s) ready to list", null)
        }
    }

    /** On-demand sync — wired to POST /api/steam/sync from the Profile modal. */
    @Transactional
    Map syncNow(Long userId) {
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) return [ok: false, error: 'Unknown user']
        try {
            syncOne(user)
            def fresh = steamUserRepository.findById(userId).orElse(user)
            return [
                ok:             true,
                lastSyncedAt:   fresh.lastSyncedAt,
                inventorySize:  fresh.steamInventorySize ?: 0
            ]
        } catch (Exception e) {
            log.warn("On-demand sync failed for ${user.steamId64}: ${e.message}")
            return [ok: false, error: 'Steam sync failed — try again in a few minutes']
        }
    }
}
