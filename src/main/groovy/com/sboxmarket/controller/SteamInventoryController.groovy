package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.SteamInventoryService
import com.sboxmarket.service.SteamSyncService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Bridges the user's Steam inventory into sboxmarket:
 *
 * - `GET  /api/steam/inventory` — returns the live Steam inventory for the
 *   signed-in user's steamId64, enriched with whether each item already has
 *   a matching catalogue entry (so the frontend can show "new" badges).
 * - `POST /api/steam/sync` — forces an immediate inventory + profile resync
 *   without waiting for the 20-minute scheduler tick.
 * - `POST /api/steam/list` — creates a listing from an item in the user's
 *   Steam inventory. Matches by name against our `Item` catalogue; if no
 *   matching catalogue entry exists, we auto-create one (with category
 *   inferred from Steam tags) so the first person to list a new cosmetic
 *   also adds it to the database.
 */
@RestController
@RequestMapping("/api/steam")
@Slf4j
class SteamInventoryController {

    @Autowired SteamInventoryService steamInventoryService
    @Autowired SteamSyncService steamSyncService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired ItemRepository itemRepository
    @Autowired ListingService listingService
    @Autowired com.sboxmarket.service.TextSanitizer textSanitizer
    // Bot-escrow deposit leg. Optional so existing tests that wire this
    // controller without it still construct; when absent OR the bot is
    // unconfigured (steamEscrowService.escrowEnabled == false), the listing
    // stays ACTIVE/buyable the legacy way and no deposit is requested. When
    // present + enabled, listing a Steam item requests the asset into bot
    // custody and holds the listing in PENDING_ESCROW until it's received.
    @Autowired(required = false) com.sboxmarket.service.SteamEscrowService steamEscrowService
    // SellService.relist gates list-creation behind banGuard, but
    // /api/steam/list + /api/steam/list-bulk bypass SellService and
    // call listingService.createListing() directly — so without an
    // explicit guard here a banned user could keep listing from their
    // Steam inventory. Required=false so older test wiring that only
    // injects the six collaborators above still constructs cleanly;
    // the guard short-circuits to a no-op when the bean is absent
    // (mirrors the same posture every other optional collaborator on
    // this controller uses).
    @Autowired(required = false) com.sboxmarket.service.security.BanGuard banGuard

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping("/inventory")
    ResponseEntity<Map> inventory(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        // Surface the rate-limit / private-inventory state so the empty-
        // inventory UI can render a real reason ("Steam is throttling our
        // requests — retry in ~3 min") instead of "No s&box items in your
        // Steam inventory" — that copy is correct for a genuinely empty
        // inventory, but it's misleading when the empty came from a 429.
        Long blockedUntil = steamInventoryService.blockedUntilMs(user.steamId64)
        def items = steamInventoryService.fetchInventory(user.steamId64)

        // Pull ONLY the catalogue rows whose lowercase name matches an
        // item in the user's inventory. Previously this was
        // `itemRepository.findAll()` on every request, which pulled the
        // entire catalogue per inventory fetch — fine at 80 items but
        // linear in the catalogue size as it grows. The bulk indexed
        // query hits `idx_items_name` directly.
        def lowerNames = items.collect { (it.name ?: '').toString().toLowerCase() }
                              .findAll { it }
                              .unique()
        def catalogue = lowerNames.isEmpty() ? [:] :
            itemRepository.findByNamesLowerIn(lowerNames).collectEntries { [(it.name?.toLowerCase()): it] }

        // Stack-aware grouping. Steam returns one descriptor per
        // (classid, instanceid) and one entry in `assets[]` per physical
        // copy — so 50 Lunar Trousers come back as 50 asset rows that
        // share a descriptor. The Sell Items grid has always shown those
        // 50 copies as 50 distinct rows, which made stacks of identical
        // items eat the entire grid and caused the per-row "tradable"
        // and "est. value" totals to ignore quantity. Group by descriptor
        // here so the frontend renders ONE card per stack with a ×N
        // quantity badge, and so summary totals can multiply by quantity.
        //
        // The representative `assetId` is the first tradable asset in
        // the group (falling back to the first asset overall if the
        // whole stack is locked) so the existing single-item Pick flow
        // (POST /api/steam/list with one assetId) still works without
        // the frontend having to know about the assetIds[] array.
        // /api/steam/list-bulk callers can either expand a stack into
        // its full assetIds[] for "list every copy" or just iterate.
        // /api/steam/sync (steamInventorySize) is unchanged — it still
        // counts at the asset level via SteamInventoryService.fetchInventory.
        def grouped = new java.util.LinkedHashMap<String, Map>()
        items.each { s ->
            // classId + instanceId is the canonical Steam descriptor
            // key. Some workshop assets have null instanceId (rare on
            // s&box but documented for partner contexts) — fall back
            // to the assetId so those rows stay distinct rather than
            // being collapsed into a single mystery group.
            def key = "${s.classId ?: ''}_${s.instanceId ?: s.assetId}".toString()
            def g = grouped.get(key)
            if (g == null) {
                g = [first: s, assetIds: new java.util.ArrayList<String>(), tradableCount: 0]
                grouped.put(key, g)
            }
            (g.assetIds as List).add(s.assetId?.toString())
            if (s.tradable) {
                g.tradableCount = (g.tradableCount as int) + 1
                if (g.tradableAssetId == null) g.tradableAssetId = s.assetId?.toString()
            }
        }
        def enriched = grouped.values().collect { g ->
            def s = g.first as Map
            def existing = catalogue[(s.name ?: '').toString().toLowerCase()]
            def assetIdsList = g.assetIds as List<String>
            [
                // Pick a tradable asset as the representative when one
                // exists — listing flow needs a tradable assetId or it
                // throws NOT_TRADABLE — falling back to the first asset
                // for fully-locked stacks so the row still renders.
                assetId:     g.tradableAssetId ?: assetIdsList[0],
                assetIds:    assetIdsList,
                quantity:    assetIdsList.size(),
                name:        s.name,
                type:        s.type,
                iconUrl:     s.iconUrl,
                tradable:    s.tradable,
                marketable:  s.marketable,
                category:    existing?.category ?: steamInventoryService.inferCategory(s),
                rarity:      existing?.rarity ?: 'Standard',
                catalogueId: existing?.id,
                suggestedPrice: existing?.lowestPrice ?: BigDecimal.ZERO
            ]
        }
        // `count` was historically the number of items the frontend
        // would render. Now that rows are stacked, the asset count
        // (= sum of quantities) is the more useful number for the UI's
        // "total" badge. We expose BOTH so older clients reading
        // `count` keep working AND the new stacked client has an
        // explicit `assetCount` to anchor on.
        int assetCount = (enriched.collect { (it.quantity as Integer) ?: 1 } as List<Integer>).sum() ?: 0
        Map resp = [
            items:         enriched,
            count:         enriched.size(),
            assetCount:    assetCount,
            lastSyncedAt:  user.lastSyncedAt,
            steamId64:     user.steamId64
        ]
        // Re-probe AFTER the fetch — fetchInventory itself may have just
        // tripped the negative cache on this call (first 429 of the window).
        Long after = steamInventoryService.blockedUntilMs(user.steamId64)
        Long signal = (blockedUntil != null) ? blockedUntil : after
        if (signal != null && enriched.isEmpty()) {
            // intdiv() keeps this as long-division — Groovy's `/` on two
            // longs yields a BigDecimal, and Math.max(long, BigDecimal)
            // has no unambiguous overload (it throws GroovyRuntimeException
            // "Ambiguous method overloading for Math#max"). That escape
            // 500s GET /api/steam/inventory the instant a user is genuinely
            // rate-limited — exactly when this branch runs.
            long retryInSec = Math.max(1L, (signal - System.currentTimeMillis()).intdiv(1000L))
            resp.blocked = true
            resp.blockedUntil = signal
            resp.retryInSec = retryInSec
            resp.reason = 'rate_limited'
        }
        ResponseEntity.ok(resp)
    }

    @PostMapping("/sync")
    ResponseEntity<Map> sync(HttpServletRequest req) {
        ResponseEntity.ok(steamSyncService.syncNow(requireUser(req)))
    }

    @PostMapping("/list")
    ResponseEntity<Map> listFromSteam(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        // Symmetric with SellService.relist's banGuard — see the
        // collaborator field comment for why the check has to live here
        // rather than being inherited from a service call.
        banGuard?.assertNotBanned(uid)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def assetId = body?.assetId?.toString()
        def priceRaw = body?.price
        if (!assetId)  throw new BadRequestException("INVALID_ASSET", "assetId is required")
        // Steam asset IDs are numeric strings. Reject arrays, objects, or
        // non-numeric payloads that would bypass the inventory ownership check.
        if (!assetId.matches(/^\d+$/)) throw new BadRequestException("INVALID_ASSET", "assetId must be numeric")
        if (!priceRaw) throw new BadRequestException("INVALID_PRICE", "price is required")
        BigDecimal price
        try {
            price = new BigDecimal(priceRaw.toString())
        } catch (NumberFormatException ignored) {
            throw new BadRequestException("INVALID_PRICE", "price must be a valid number")
        }
        // Floor at $0.01 — the relist path enforces @DecimalMin("0.01") via
        // SellListingRequest; this first-list path validated by hand and only
        // rejected <= 0, so a sub-cent price (e.g. 0.004) rounded to $0.00 in
        // listings.price NUMERIC(10,2), creating a free, instantly-buyable
        // listing. Match the relist floor.
        if (price < new BigDecimal("0.01")) throw new BadRequestException("INVALID_PRICE", "price must be at least \$0.01")
        // Mirror the DTO-layer cap used on /api/listings/sell and the
        // rest of the trading surface so a user can't list a Steam item
        // at $1,000,000,000 by bypassing the frontend form.
        if (price > new BigDecimal("100000")) {
            throw new BadRequestException("PRICE_TOO_HIGH", "price must not exceed \$100,000")
        }
        // Defensive assetId cap — Steam asset ids are short numeric
        // strings (typically 10-20 chars). Anything longer is either a
        // probe or a crafted payload.
        if (assetId.length() > 32) {
            throw new BadRequestException("INVALID_ASSET", "assetId is too long")
        }

        // Re-fetch the live inventory so we can't list something the user
        // doesn't currently own (or has already moved out of Steam).
        def inv = steamInventoryService.fetchInventory(user.steamId64)
        def steamItem = inv.find { (it.assetId as String) == assetId }
        if (steamItem == null) {
            throw new BadRequestException("NOT_IN_INVENTORY", "Asset $assetId was not found in your Steam inventory. Try /api/steam/sync first.")
        }
        if (!steamItem.tradable) {
            throw new BadRequestException("NOT_TRADABLE", "This item is not tradable on Steam right now.")
        }

        def name = (steamItem.name ?: '').toString()
        // Indexed lookup via the `idx_items_name` functional index on
        // `LOWER(name)` — O(log N) instead of the previous full scan.
        def item = itemRepository.findByNameIgnoreCase(name)
        if (item == null) {
            // Auto-create a catalogue entry so brand-new items become listable
            item = itemRepository.save(new Item(
                name:        name.take(255),
                category:    steamInventoryService.inferCategory(steamItem),
                rarity:      'Standard',
                imageUrl:    steamItem.iconUrl as String,
                accentColor: '#13192a',
                lowestPrice: price,
                steamPrice:  price,
                supply:      1,
                totalSold:   0,
                trendPercent: 0
            ))
            log.info("Auto-created catalogue item \"${item.name}\" from Steam inventory of ${user.steamId64}")
        }

        // Optional auction fields — same shape as the /api/listings/sell
        // DTO. Whitelist the type so anything bogus falls back to BUY_NOW;
        // AUCTION must carry a durationHours in [1,168].
        def rawType = (body?.listingType as String ?: 'BUY_NOW').toUpperCase()
        def resolvedType = (rawType in ['BUY_NOW', 'AUCTION']) ? rawType : 'BUY_NOW'
        Long durationHours = null
        if (resolvedType == 'AUCTION') {
            def rawDur = body?.durationHours
            if (rawDur == null) {
                throw new BadRequestException("DURATION_REQUIRED", "durationHours is required for AUCTION listings")
            }
            try {
                durationHours = Long.parseLong(rawDur.toString())
            } catch (NumberFormatException ignored) {
                throw new BadRequestException("INVALID_DURATION", "durationHours must be a valid number")
            }
            if (durationHours < 1L || durationHours > 168L) {
                throw new BadRequestException("INVALID_DURATION", "durationHours must be between 1 and 168")
            }
        }

        // Optional Buy-Now ceiling on an auction (batch 371). Lets a
        // buyer skip the auction entirely at this price. Must exceed
        // the starting bid to be meaningful; equals/below would make
        // Buy-Now cheaper than the first bid, which breaks the price
        // discovery. Rejected outright on BUY_NOW — it's an auction-
        // only concept.
        BigDecimal buyNowPrice = null
        def rawBuyNow = body?.buyNowPrice
        if (rawBuyNow != null && rawBuyNow.toString().trim()) {
            if (resolvedType != 'AUCTION') {
                throw new BadRequestException("BUY_NOW_ON_BUY_NOW",
                    "buyNowPrice only applies to AUCTION listings — the price field already sets the Buy Now amount on BUY_NOW listings.")
            }
            try {
                buyNowPrice = new BigDecimal(rawBuyNow.toString())
            } catch (NumberFormatException ignored) {
                throw new BadRequestException("INVALID_BUY_NOW", "buyNowPrice must be a valid number")
            }
            if (buyNowPrice <= price) {
                throw new BadRequestException("INVALID_BUY_NOW",
                    "buyNowPrice must be greater than the starting bid (\$${price})")
            }
            if (buyNowPrice > new BigDecimal("100000")) {
                throw new BadRequestException("BUY_NOW_TOO_HIGH",
                    "buyNowPrice must not exceed \$100,000")
            }
        }

        // Optional seller note (batch 304). Sanitised server-side;
        // 500-char cap matches the column size and the edit form.
        String cleanDesc = null
        def rawDesc = body?.description as String
        if (rawDesc != null && rawDesc.trim()) {
            if (rawDesc.length() > 500) {
                throw new BadRequestException("DESCRIPTION_TOO_LONG",
                    "description must not exceed 500 characters")
            }
            cleanDesc = textSanitizer.clean(rawDesc, 500)
        }
        // Optional auto-accept threshold (batch 646). Fraction 0..1 —
        // 0.20 means "auto-accept offers >= 80% of ask". Null / 0 =
        // no auto-accept. Mirrors the MyStall edit form + the
        // ListingController /sell DTO. Strict numeric parse; defensive
        // bounds (same as SellService.relist).
        BigDecimal maxDiscount = null
        def rawMaxDisc = body?.maxDiscount
        if (rawMaxDisc != null && rawMaxDisc.toString().trim()) {
            try { maxDiscount = new BigDecimal(rawMaxDisc.toString()) }
            catch (NumberFormatException ignored) {
                throw new BadRequestException("INVALID_DISCOUNT", "maxDiscount must be a valid number")
            }
            if (maxDiscount < BigDecimal.ZERO || maxDiscount >= BigDecimal.ONE) {
                throw new BadRequestException("INVALID_DISCOUNT",
                    "maxDiscount must be between 0 and 1 (exclusive)")
            }
            if (maxDiscount.signum() == 0) maxDiscount = null
        }
        // When bot-escrow is live, the listing is created NOT-yet-buyable
        // (PENDING_ESCROW) so it can never be auto-sold (buy-order tryMatch
        // gates on status='ACTIVE') before the bot actually holds the item.
        // SteamEscrowService flips it to ACTIVE once the deposit is IN_CUSTODY.
        // When the bot is unconfigured, it's plain ACTIVE (legacy behaviour).
        String initialStatus = (steamEscrowService?.escrowEnabled)
            ? com.sboxmarket.service.SteamEscrowService.STATUS_PENDING_ESCROW : 'ACTIVE'
        def listing = new Listing(
            item:         item,
            price:        price,
            sellerName:   user.displayName ?: ("Player_" + user.steamId64.takeRight(6)),
            sellerAvatar: (user.displayName ?: 'US').take(2).toUpperCase(),
            condition:    '',
            rarityScore:  BigDecimal.ZERO,
            status:       initialStatus,
            sellerUserId: uid,
            listingType:  resolvedType,
            description:  cleanDesc,
            buyNowPrice:  buyNowPrice,
            maxDiscount:  maxDiscount
        )
        if (resolvedType == 'AUCTION') {
            listing.expiresAt = System.currentTimeMillis() + (durationHours * 60L * 60L * 1000L)
        }
        def saved = listingService.createListing(listing)
        // Bot-escrow DEPOSIT leg. When the bot is configured, request the
        // seller's specific Steam asset into custody and hold the listing in
        // PENDING_ESCROW until it's received (the listing only becomes buyable
        // once IN_CUSTODY). When the bot is unconfigured, this is a no-op and
        // the listing stays ACTIVE/buyable the legacy way. Best-effort — a
        // deposit-request hiccup must not 500 the list call; the listing is
        // already persisted and the deposit poller / re-list can recover.
        try {
            steamEscrowService?.requestDepositForListing(saved, assetId, name)
        } catch (Exception e) {
            log.warn("Escrow deposit request failed for listing ${saved.id}: ${e.message}")
        }
        // status already reflects the deposit hold (PENDING_ESCROW) when the
        // bot is live, ACTIVE otherwise — so the response tells the seller the
        // truth. escrowPending lets the UI render "waiting for your deposit".
        ResponseEntity.ok([
            listingId:    saved.id,
            itemId:       item.id,
            price:        saved.price,
            status:       saved.status,
            escrowPending: (steamEscrowService?.escrowEnabled ?: false),
            listingType:  saved.listingType,
            expiresAt:    saved.expiresAt,
            buyNowPrice:  saved.buyNowPrice
        ])
    }

    /**
     * Bulk-list every asset in the request body at the same flat price
     * (batch 370). Common power-seller use case: "I've got 8 Wizard Hats,
     * list them all at $10 each" without clicking through the sell form
     * 8 times. Reuses the single-list flow internally — each item gets
     * its own inventory probe, catalogue lookup, and Listing row, and one
     * failing asset doesn't abort the batch.
     *
     * Payload: `{ assetIds: [String], price: Number, listingType?: 'BUY_NOW' }`
     * Response: `{ ok: [{assetId, listingId, itemId, price}], failed: [{assetId, code, message}] }`
     *
     * BUY_NOW only — auction duration semantics on a batch get weird
     * (do all 8 auctions share the same expiry?) so we keep that flow
     * single-item. Capped at 20 assets per call to bound the tx.
     */
    @PostMapping("/list-bulk")
    ResponseEntity<Map> listBulkFromSteam(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        // Symmetric with /list above. Gate fans into createListing()
        // up front so a banned user can't slip 20 new listings through
        // the bulk path in a single call.
        banGuard?.assertNotBanned(uid)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }

        def raw = body?.assetIds
        if (!(raw instanceof List)) {
            throw new BadRequestException("INVALID_BODY", "assetIds must be an array")
        }
        def assetIds = (raw as List)
            .collect { it == null ? null : it.toString().trim() }
            .findAll { it && it.matches(/^\d{1,32}$/) }
            .unique()
        if (assetIds.isEmpty()) {
            throw new BadRequestException("INVALID_BODY", "assetIds must contain at least one numeric id")
        }
        if (assetIds.size() > 20) {
            throw new BadRequestException("TOO_MANY", "bulk-list is capped at 20 assets per call")
        }
        def priceRaw = body?.price
        if (priceRaw == null) throw new BadRequestException("INVALID_PRICE", "price is required")
        BigDecimal price
        try { price = new BigDecimal(priceRaw.toString()) }
        catch (NumberFormatException ignored) {
            throw new BadRequestException("INVALID_PRICE", "price must be a valid number")
        }
        // Floor at $0.01 (matches the single-list path + relist DTO) — a
        // sub-cent bulk price would round to $0.00 per row in NUMERIC(10,2).
        if (price < new BigDecimal("0.01")) throw new BadRequestException("INVALID_PRICE", "price must be at least \$0.01")
        if (price > new BigDecimal("100000")) {
            throw new BadRequestException("PRICE_TOO_HIGH", "price must not exceed \$100,000")
        }

        // Batch 648 — optional auto-accept threshold applied uniformly
        // across every listing in the batch. Same shape + validation as
        // the single-list path. Null / 0 / omitted = no auto-accept.
        BigDecimal bulkMaxDiscount = null
        def rawBulkMaxDisc = body?.maxDiscount
        if (rawBulkMaxDisc != null && rawBulkMaxDisc.toString().trim()) {
            try { bulkMaxDiscount = new BigDecimal(rawBulkMaxDisc.toString()) }
            catch (NumberFormatException ignored) {
                throw new BadRequestException("INVALID_DISCOUNT", "maxDiscount must be a valid number")
            }
            if (bulkMaxDiscount < BigDecimal.ZERO || bulkMaxDiscount >= BigDecimal.ONE) {
                throw new BadRequestException("INVALID_DISCOUNT",
                    "maxDiscount must be between 0 and 1 (exclusive)")
            }
            if (bulkMaxDiscount.signum() == 0) bulkMaxDiscount = null
        }

        // Fetch inventory ONCE; per-asset lookup walks the in-memory list.
        def inv = steamInventoryService.fetchInventory(user.steamId64)
        def byAsset = [:]
        inv.each { byAsset[(it.assetId as String)] = it }

        String sellerName = user.displayName ?: ("Player_" + user.steamId64.takeRight(6))
        String sellerAvatar = (user.displayName ?: 'US').take(2).toUpperCase()

        def results = []
        def failed = []
        assetIds.each { assetId ->
            try {
                def steamItem = byAsset[assetId]
                if (steamItem == null) {
                    failed << [assetId: assetId, code: 'NOT_IN_INVENTORY',
                        message: 'Not in current Steam inventory']
                    return
                }
                if (!steamItem.tradable) {
                    failed << [assetId: assetId, code: 'NOT_TRADABLE',
                        message: 'Not tradable on Steam right now']
                    return
                }
                def name = (steamItem.name ?: '').toString()
                def item = itemRepository.findByNameIgnoreCase(name)
                if (item == null) {
                    item = itemRepository.save(new Item(
                        name:        name.take(255),
                        category:    steamInventoryService.inferCategory(steamItem),
                        rarity:      'Standard',
                        imageUrl:    steamItem.iconUrl as String,
                        accentColor: '#13192a',
                        lowestPrice: price,
                        steamPrice:  price,
                        supply:      1,
                        totalSold:   0,
                        trendPercent: 0
                    ))
                }
                // PENDING_ESCROW when the bot is live (see single-list note),
                // plain ACTIVE otherwise.
                String initialStatus = (steamEscrowService?.escrowEnabled)
                    ? com.sboxmarket.service.SteamEscrowService.STATUS_PENDING_ESCROW : 'ACTIVE'
                def listing = new Listing(
                    item:         item,
                    price:        price,
                    sellerName:   sellerName,
                    sellerAvatar: sellerAvatar,
                    condition:    '',
                    rarityScore:  BigDecimal.ZERO,
                    status:       initialStatus,
                    sellerUserId: uid,
                    listingType:  'BUY_NOW',
                    maxDiscount:  bulkMaxDiscount
                )
                def saved = listingService.createListing(listing)
                // Bot-escrow DEPOSIT leg per item — same contract as the
                // single-list path. No-op when the bot is unconfigured.
                try {
                    steamEscrowService?.requestDepositForListing(saved, assetId, name)
                } catch (Exception e) {
                    log.warn("Escrow deposit request failed for bulk listing ${saved.id}: ${e.message}")
                }
                results << [assetId: assetId, listingId: saved.id, itemId: item.id, price: saved.price,
                            status: saved.status,
                            escrowPending: (steamEscrowService?.escrowEnabled ?: false)]
            } catch (BadRequestException e) {
                failed << [assetId: assetId, code: e.code ?: 'BAD_REQUEST', message: e.message]
            } catch (Exception e) {
                log.warn("bulk-list failed for asset ${assetId}: ${e.message}")
                failed << [assetId: assetId, code: 'INTERNAL_ERROR', message: 'Could not list this item']
            }
        }
        log.info("Bulk-list: user ${uid} · ${results.size()} ok · ${failed.size()} failed")
        ResponseEntity.ok([ok: results, failed: failed])
    }
}
