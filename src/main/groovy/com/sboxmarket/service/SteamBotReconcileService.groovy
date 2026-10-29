package com.sboxmarket.service

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Checks what the bot really holds against what the database says it holds.
 *
 * Every skin site that keeps skins in bots runs this kind of check, because
 * the two drift: Steam's Trade Protection lets a buyer reverse a delivery and
 * send the skin back into the bot, a seller can reverse a deposit, an operator
 * can trade from the bot account by hand, and a stolen session can empty it.
 * Before this, nothing ever compared the two, so a listing could stay for sale
 * after its skin had left the bot, and a skin could sit in the bot with no row
 * pointing at it.
 *
 * Each run reads the bot inventory once and reports:
 *   - missing: a live listing whose skin is not in the bot. The listing is
 *     taken off sale (ACTIVE -> PENDING_ESCROW) on the first missing read, so
 *     nobody pays for a skin the bot cannot send, and put back on sale if the
 *     skin turns up again. Custody is never changed or failed here: the row
 *     is the only pointer to the skin, and an operator decides.
 *   - orphans: skins in the bot that no custody row expects. Reported only;
 *     the bot never sends anything on its own.
 *
 * Only listings that are ACTIVE are touched. A listing with a sale in
 * progress is not ACTIVE, so a delivery the buyer just accepted can never be
 * mistaken for a missing skin.
 *
 * Inert unless the bot is configured (STEAM_BOT_BASE_URL), like every other
 * part of the bot escrow.
 */
@Service
@Slf4j
class SteamBotReconcileService {

    @Autowired SteamEscrowService escrowService
    @Autowired EscrowedItemRepository escrowRepository
    @Autowired ListingRepository listingRepository

    @Value('${steam.escrow.reconcile-enabled:true}')
    boolean reconcileEnabled = true

    /** Request timeout for the purchase-time check in {@link #skinGoneFromBot}.
     *  Short on purpose: a slow bot must not stall checkout, and a timeout
     *  lets the sale through exactly as before this check existed. */
    @Value('${steam.escrow.purchase-check-timeout-ms:3000}')
    long purchaseCheckTimeoutMs = 3000L

    /** Runs the take-off-sale write for {@link #skinGoneFromBot} off the
     *  buyer's thread, so it never waits on a lock the purchase transaction
     *  (or its caller's) still holds. Tests swap in a same-thread executor. */
    java.util.concurrent.Executor pullExecutor = { Runnable r -> java.util.concurrent.CompletableFuture.runAsync(r) } as java.util.concurrent.Executor

    /** The most recent report, for the staff endpoint. Null until a run. */
    volatile Map lastReport

    @Scheduled(initialDelayString = '${steam.escrow.reconcile-initial-delay-ms:300000}',
               fixedDelayString = '${steam.escrow.reconcile-interval-ms:3600000}')
    void scheduledReconcile() {
        if (!reconcileEnabled) return
        try { reconcile() } catch (Exception e) { log.warn("SteamBotReconcile: run failed: ${e.message}") }
    }

    /** Run one check now. Never throws; a failed inventory read reports
     *  {@code ok:false} and changes nothing. */
    Map reconcile() {
        long now = System.currentTimeMillis()
        if (!escrowService.escrowEnabled) {
            lastReport = [ok: false, enabled: false, checkedAt: now, reason: 'bot not configured']
            return lastReport
        }
        Map index = escrowService.fetchBotInventoryIndex()
        if (!index.ok) {
            lastReport = [ok: false, enabled: true, checkedAt: now, reason: 'bot inventory unreadable']
            log.warn('SteamBotReconcile: bot inventory unreadable; nothing checked')
            return lastReport
        }
        Map byAsset = (index.byAsset ?: [:]) as Map
        List<Map> items = (index.items ?: []) as List<Map>

        List<EscrowedItem> expected = escrowRepository.findExpectedInBot() ?: []
        Set<String> claimed = expected.collect { it.heldAssetId ?: it.assetId }.findAll { it != null } as Set

        List<Map> missing = []
        int pulled = 0, restored = 0
        for (EscrowedItem e : expected) {
            if (e.custodyState != EscrowedItem.IN_CUSTODY || e.returnRequestedAt != null) continue
            String held = e.heldAssetId ?: e.assetId
            boolean present = held != null && byAsset.containsKey(held)
            try {
                if (!present) {
                    missing << [escrowId: e.id, listingId: e.listingId, sellerUserId: e.sellerUserId,
                                assetId: held, marketHashName: e.marketHashName, missingSince: e.missingSince ?: now]
                    if (e.missingSince == null && pullFromSale(e.listingId)) {
                        e.missingSince = now
                        e.lastError = 'skin not in bot inventory; listing taken off sale pending a check'
                        e.updatedAt = now
                        escrowRepository.save(e)
                        pulled++
                        log.error("SteamBotReconcile: skin MISSING from bot — escrow=${e.id} listing=${e.listingId} " +
                                "seller=${e.sellerUserId} asset=${held}. Listing taken off sale; custody left as is.")
                    }
                } else if (e.missingSince != null) {
                    e.missingSince = null
                    e.lastError = null
                    e.updatedAt = now
                    escrowRepository.save(e)
                    if (putBackOnSale(e.listingId)) restored++
                    log.info("SteamBotReconcile: skin back in bot — escrow=${e.id} listing=${e.listingId}; listing on sale again")
                }
            } catch (Exception ex) {
                log.warn("SteamBotReconcile: row escrow=${e?.id} failed: ${ex.message}")
            }
        }

        List<Map> orphans = items.findAll { !(it.assetId in claimed) }
                .collect { [assetId: it.assetId, marketHashName: it.marketHashName, tradable: it.tradable] }
        if (!orphans.isEmpty()) {
            log.warn("SteamBotReconcile: ${orphans.size()} skin(s) in the bot that no custody row expects: " +
                    orphans.take(10).collect { "${it.assetId} ${it.marketHashName}" }.join(', '))
        }

        lastReport = [ok: true, enabled: true, checkedAt: now,
                      botItems: items.size(), expected: expected.size(),
                      missing: missing, orphans: orphans,
                      listingsPulled: pulled, listingsRestored: restored]
        return lastReport
    }

    /**
     * Purchase-time version of the hourly check, for ONE listing: true only
     * when the bot answered and the skin this listing's custody row holds is
     * not in its inventory. The buy paths refuse the sale on true, before any
     * money moves, and the listing is taken off sale the same way
     * {@link #reconcile} does (so the next run puts it back if the skin
     * turns up again).
     *
     * False, so the sale goes ahead exactly as before, whenever we cannot be
     * sure: escrow off, no custody row (a P2P or house listing), a row that
     * is not plainly IN_CUSTODY, or the bot unreachable / slow. An outage
     * must not stop every bot-held sale. Never throws.
     */
    boolean skinGoneFromBot(Long listingId) {
        try {
            if (listingId == null || !escrowService.escrowEnabled) return false
            EscrowedItem e = escrowRepository.findByListingId(listingId)
            if (e == null || e.custodyState != EscrowedItem.IN_CUSTODY || e.returnRequestedAt != null) return false
            String held = e.heldAssetId ?: e.assetId
            if (held == null) return false
            Map index = escrowService.fetchBotInventoryIndex(purchaseCheckTimeoutMs)
            if (!index?.ok) return false
            if (((index.byAsset ?: [:]) as Map).containsKey(held)) return false
            log.error("SteamBotReconcile: purchase refused, skin MISSING from bot — escrow=${e.id} " +
                    "listing=${listingId} asset=${held}. Taking the listing off sale.")
            final Long escrowId = e.id
            try {
                pullExecutor.execute({ markMissing(escrowId) } as Runnable)
            } catch (Exception ex) {
                log.warn("SteamBotReconcile: could not queue take-off-sale for listing ${listingId}: ${ex.message}")
            }
            return true
        } catch (Exception ex) {
            log.warn("SteamBotReconcile: purchase-time check for listing ${listingId} failed, allowing sale: ${ex.message}")
            return false
        }
    }

    /** The {@link #reconcile} missing-skin write for one custody row:
     *  listing ACTIVE -> PENDING_ESCROW and the row stamped missingSince. */
    private void markMissing(Long escrowId) {
        try {
            EscrowedItem e = escrowRepository.findById(escrowId).orElse(null)
            if (e == null || e.custodyState != EscrowedItem.IN_CUSTODY || e.missingSince != null) return
            if (pullFromSale(e.listingId)) {
                long now = System.currentTimeMillis()
                e.missingSince = now
                e.lastError = 'skin not in bot inventory; listing taken off sale pending a check'
                e.updatedAt = now
                escrowRepository.save(e)
            }
        } catch (Exception ex) {
            log.warn("SteamBotReconcile: take-off-sale for escrow=${escrowId} failed: ${ex.message}")
        }
    }

    /** ACTIVE -> PENDING_ESCROW. True when this call took it off sale. */
    private boolean pullFromSale(Long listingId) {
        def l = listingRepository.findById(listingId).orElse(null)
        if (l == null || l.status != SteamEscrowService.STATUS_ACTIVE) return false
        l.status = SteamEscrowService.STATUS_PENDING_ESCROW
        listingRepository.save(l)
        true
    }

    /** PENDING_ESCROW -> ACTIVE, for a listing this service pulled. */
    private boolean putBackOnSale(Long listingId) {
        def l = listingRepository.findById(listingId).orElse(null)
        if (l == null || l.status != SteamEscrowService.STATUS_PENDING_ESCROW) return false
        l.status = SteamEscrowService.STATUS_ACTIVE
        listingRepository.save(l)
        true
    }
}
