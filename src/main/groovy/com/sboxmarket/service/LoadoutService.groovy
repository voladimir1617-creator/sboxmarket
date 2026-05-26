package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.LoadoutFavorite
import com.sboxmarket.model.LoadoutSlot
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.LoadoutFavoriteRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.LoadoutSlotRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Loadout Lab. Users can curate up to 8 s&box slots, publish publicly for the
 * Discover tab, or keep private. The AI-Generate action picks the cheapest active
 * listing per category within a budget, filling any unlocked slots.
 */
@Service
@Slf4j
class LoadoutService {

    static final List<String> SLOTS = ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories','Wild']

    // Per-user loadout cap. Every loadout seeds 8 LoadoutSlot rows, so 50
    // per user = ~400 slot rows at the ceiling — more than any legitimate
    // curator needs (CSFloat caps public loadouts at 20) but high enough
    // that a prolific power-user isn't squeezed. Enforced on both `create`
    // and `clone` so the cap isn't defeated by spamming duplicates.
    static final long MAX_LOADOUTS_PER_USER = 50L

    @Autowired LoadoutRepository loadoutRepository
    @Autowired LoadoutSlotRepository loadoutSlotRepository
    @Autowired LoadoutFavoriteRepository loadoutFavoriteRepository
    @Autowired ItemRepository itemRepository
    @Autowired TextSanitizer textSanitizer
    @Autowired BanGuard banGuard
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) AuditService auditService

    @Transactional
    Loadout create(Long ownerUserId, String ownerName, String name, String description, String visibility) {
        banGuard.assertNotBanned(ownerUserId)
        if (ownerUserId != null && loadoutRepository.countByOwnerUserId(ownerUserId) >= MAX_LOADOUTS_PER_USER) {
            throw new BadRequestException("LOADOUT_CAP",
                "You've reached the ${MAX_LOADOUTS_PER_USER}-loadout limit. Delete an old one before creating another.")
        }
        def cleanName = textSanitizer.cleanShort(name)
        def cleanDesc = textSanitizer.medium(description)
        def cleanOwner = textSanitizer.cleanShort(ownerName)
        if (!cleanName || cleanName.isEmpty()) throw new BadRequestException("INVALID_NAME", "Loadout name is required")
        def allowedVisibility = (visibility in ['PUBLIC','PRIVATE']) ? visibility : 'PUBLIC'
        def loadout = new Loadout(
            ownerUserId: ownerUserId,
            ownerName:   cleanOwner,
            name:        cleanName,
            description: cleanDesc,
            visibility:  allowedVisibility
        )
        loadoutRepository.save(loadout)
        // Seed empty slots
        SLOTS.each { slotName ->
            loadoutSlotRepository.save(new LoadoutSlot(loadoutId: loadout.id, slot: slotName))
        }
        loadout
    }

    List<Loadout> listPublic(String search) {
        def page = org.springframework.data.domain.PageRequest.of(0, 200)
        search ? loadoutRepository.searchPublic(search, page) : loadoutRepository.findPublic(page)
    }

    List<Loadout> listMine(Long ownerUserId) {
        loadoutRepository.findByOwner(ownerUserId)
    }

    /**
     * Loadouts the user has favorited, newest-favorite first. Pulls the
     * favorite ids from the join table in one query, then the Loadout
     * entities in a second bulk findAllById. Private loadouts the user
     * no longer owns (e.g. the original owner flipped visibility back
     * to PRIVATE) are filtered out server-side so the tab never shows
     * rows the user can't actually open. Null uid → empty list.
     */
    List<Loadout> listFavorites(Long userId) {
        if (userId == null || loadoutFavoriteRepository == null) return []
        def ids = loadoutFavoriteRepository.findLoadoutIdsByUser(userId)
        if (ids == null || ids.isEmpty()) return []
        def loadouts = loadoutRepository.findAllById(ids)
        def byId = [:]
        loadouts.each { byId[it.id] = it }
        // Preserve the favorited-at ordering (ids list is already sorted
        // by `createdAt DESC`). Drop loadouts the user can't access —
        // deleted rows are absent from findAllById; PRIVATE rows owned
        // by a different user are filtered here so a re-privatized
        // loadout doesn't leak through.
        ids.collect { byId[it] }.findAll { it != null }.findAll { l ->
            l.visibility != 'PRIVATE' || l.ownerUserId == userId
        }
    }

    /**
     * Attach a compact item preview to each loadout for the Discover /
     * Mine / Favorites card grid. The raw Loadout entity carries no
     * slots, so the cards rendered as empty blocks — csfloat's loadout
     * overview shows each set's items inline. One bulk slot query plus
     * one bulk item-decor query keeps this O(1) regardless of how many
     * loadouts are on the page. `previewItems` is the filled slots in
     * canonical slot order; empty slots are dropped so the strip only
     * shows what's actually equipped.
     */
    List<Map> decorate(List<Loadout> loadouts) {
        if (!loadouts) return []
        def ids = loadouts.collect { it?.id }.findAll { it != null }
        Map<Long, List<LoadoutSlot>> slotsByLoadout = [:]
        if (!ids.isEmpty()) {
            loadoutSlotRepository.findByLoadoutIdIn(ids).each { LoadoutSlot s ->
                (slotsByLoadout[s.loadoutId] = slotsByLoadout[s.loadoutId] ?: []) << s
            }
        }
        def allItemIds = slotsByLoadout.values().flatten()
            .findAll { it?.itemId != null }
            .collect { it.itemId }
            .unique()
        Map<Long, Map> itemDecor = [:]
        if (!allItemIds.isEmpty()) {
            itemRepository.findAllById(allItemIds).each { Item it ->
                itemDecor[it.id] = [imageUrl: it.imageUrl, accentColor: it.accentColor]
            }
        }
        loadouts.collect { Loadout l ->
            def bySlot = (slotsByLoadout[l.id] ?: []).collectEntries { [(it.slot): it] }
            def filled = SLOTS.collect { bySlot[it] }.findAll { it != null && it.itemId != null }
            def preview = filled.collect { LoadoutSlot s ->
                def deco = itemDecor[s.itemId]
                [
                    slot         : s.slot,
                    itemId       : s.itemId,
                    itemName     : s.itemName,
                    itemEmoji    : s.itemEmoji,
                    imageUrl     : deco?.imageUrl,
                    accentColor  : deco?.accentColor,
                    snapshotPrice: s.snapshotPrice
                ]
            }
            [
                id          : l.id,
                ownerUserId : l.ownerUserId,
                ownerName   : l.ownerName,
                name        : l.name,
                description : l.description,
                visibility  : l.visibility,
                totalValue  : l.totalValue,
                favorites   : l.favorites,
                createdAt   : l.createdAt,
                updatedAt   : l.updatedAt,
                previewItems: preview,
                filledSlots : filled.size(),
                slotCount   : SLOTS.size()
            ]
        }
    }

    /**
     * Fetch a loadout with its slots. PRIVATE loadouts are only visible
     * to their owner — any other viewer (anonymous or otherwise) gets a
     * NotFoundException so we neither confirm nor deny the loadout's
     * existence. A plain 404 prevents loadout-id enumeration from
     * discovering which ids belong to hidden sets.
     */
    Map getWithSlots(Long id, Long viewerUserId = null) {
        def loadout = loadoutRepository.findById(id).orElseThrow { new NotFoundException("Loadout", id) }
        if (loadout.visibility == 'PRIVATE' && loadout.ownerUserId != viewerUserId) {
            throw new NotFoundException("Loadout", id)
        }
        def slots = loadoutSlotRepository.findByLoadout(id)
        // Batch 917 — surface the viewer's favorited state so the UI
        // can render "♥ Favorited" vs "♡ Favorite" without a second
        // round-trip. Anonymous viewers always get `false`. Owners
        // also get `false` — the UI hides the Favorite button on
        // self-owned loadouts anyway, so the flag is only meaningful
        // for signed-in non-owner viewers.
        boolean favorited = false
        if (viewerUserId != null && loadout.ownerUserId != viewerUserId) {
            favorited = loadoutFavoriteRepository.findByUserAndLoadout(viewerUserId, id) != null
        }

        // Enrich each slot with itemImageUrl + accentColor from the Item
        // table — the slot snapshot only carries name/emoji/price, so
        // the loadout detail page was rendering as a grid of bare emoji
        // glyphs (no real product thumbnail). One batched findAllById
        // by itemId keeps it cheap; we fall back to the existing
        // emoji-only render in the frontend when imageUrl is null.
        Map<Long, Map> itemDecor = [:]
        def itemIds = slots.findAll { it?.itemId != null }*.itemId
        if (itemIds && !itemIds.isEmpty()) {
            itemRepository.findAllById(itemIds).each { Item it ->
                itemDecor[it.id] = [imageUrl: it.imageUrl, accentColor: it.accentColor]
            }
        }
        // Canonical slot order — `findByLoadout` returns rows in `id ASC`,
        // which matches the create-time seed order for normal loadouts but
        // drifts as soon as `setSlot` creates a previously-missing slot row
        // (newer id => appended last regardless of slot kind). Re-sort by
        // the canonical `SLOTS` list so the detail page renders Hats first,
        // Wild last, every request — matching `decorate()`'s preview order
        // and CSFloat's loadout layout. Unknown slot names (defensive) sort
        // to the end in stable insertion order.
        Map<String, Integer> slotRank = [:]
        SLOTS.eachWithIndex { String name, int i -> slotRank[name] = i }
        def orderedSlots = slots.toList().sort(false) { LoadoutSlot s ->
            def r = slotRank[s?.slot]
            r != null ? r : SLOTS.size()
        }
        def decoratedSlots = orderedSlots.collect { LoadoutSlot s ->
            def deco = (s?.itemId != null) ? itemDecor[s.itemId] : null
            [
                id            : s.id,
                loadoutId     : s.loadoutId,
                slot          : s.slot,
                itemId        : s.itemId,
                itemName      : s.itemName,
                itemEmoji     : s.itemEmoji,
                itemImageUrl  : deco?.imageUrl,
                itemAccentColor: deco?.accentColor,
                snapshotPrice : s.snapshotPrice,
                locked        : s.locked
            ]
        }
        [loadout: loadout, slots: decoratedSlots, favorited: favorited]
    }

    @Transactional
    LoadoutSlot setSlot(Long ownerUserId, Long loadoutId, String slot, Long itemId) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.ownerUserId != ownerUserId) throw new ForbiddenException("Not your loadout")
        if (!(slot in SLOTS)) throw new BadRequestException("INVALID_SLOT", "Unknown slot")

        def existing = loadoutSlotRepository.findByLoadout(loadoutId).find { it.slot == slot }
        def target = existing ?: new LoadoutSlot(loadoutId: loadoutId, slot: slot)

        if (itemId != null) {
            def item = itemRepository.findById(itemId).orElse(null)
            if (item == null) throw new NotFoundException("Item", itemId)
            target.itemId = item.id
            target.itemName = item.name
            target.itemEmoji = item.iconEmoji
            target.snapshotPrice = item.lowestPrice ?: BigDecimal.ZERO
        } else {
            target.itemId = null
            target.itemName = null
            target.itemEmoji = null
            target.snapshotPrice = BigDecimal.ZERO
        }
        loadoutSlotRepository.save(target)
        recalcTotal(loadout)
        target
    }

    @Transactional
    LoadoutSlot toggleLock(Long ownerUserId, Long loadoutId, String slot) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.ownerUserId != ownerUserId) throw new ForbiddenException("Not your loadout")
        def target = loadoutSlotRepository.findByLoadout(loadoutId).find { it.slot == slot }
        if (target == null) throw new NotFoundException("Slot", 0)
        target.locked = !target.locked
        loadoutSlotRepository.save(target)
    }

    /**
     * Fill every unlocked slot with the cheapest available item in that category
     * without overshooting `budget`. Returns the fresh slots list.
     */
    @Transactional
    List<LoadoutSlot> autoGenerate(Long ownerUserId, Long loadoutId, BigDecimal budget) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.ownerUserId != ownerUserId) throw new ForbiddenException("Not your loadout")

        def slots = loadoutSlotRepository.findByLoadout(loadoutId)
        // Explicit null check, not Elvis — Groovy's `?:` treats
        // `BigDecimal.ZERO` as falsy, so `budget ?: new BigDecimal("10000")`
        // silently swapped a user-requested `$0` ceiling for the $10k
        // catch-all and the auto-fill burned through the catalogue
        // instead of leaving every unlocked slot empty. The controller
        // currently rejects `<= 0` so this was only reachable from
        // tests / future callers, but the service is a public API and
        // must honour a literal zero ceiling.
        def remaining = budget != null ? budget : new BigDecimal("10000")
        // Locked, already-filled slots are kept as-is — but their snapshot
        // price still counts against the budget. CSFloat's budget is the
        // TOTAL set spend; without this, a locked $90 item plus a $100
        // budget would let auto-fill spend another $100 (total $190),
        // overshooting the ceiling the user asked for.
        if (budget != null) {
            slots.each { slot ->
                if (slot.locked && slot.itemId != null) {
                    remaining = remaining - (slot.snapshotPrice ?: BigDecimal.ZERO)
                }
            }
            if (remaining < BigDecimal.ZERO) remaining = BigDecimal.ZERO
        }
        def onePage = org.springframework.data.domain.PageRequest.of(0, 1)

        // Track every item id already placed so the fill never repeats one.
        // The `Wild` slot uses `category = ''` (any category), so without
        // this it would just re-pick whichever item is the globally
        // cheapest — almost always one already sitting in its own category
        // slot. Seed with the locked slots' items too, so an unlocked slot
        // can't duplicate a locked pick. CSFloat's loadout lab never repeats.
        Set<Long> pickedIds = new HashSet<>()
        slots.each { slot ->
            if (slot.locked && slot.itemId != null) pickedIds.add(slot.itemId)
        }

        slots.each { slot ->
            if (slot.locked && slot.itemId != null) return
            def category = slot.slot == 'Wild' ? '' : slot.slot
            // One indexed SELECT per slot — cheapest item in the category
            // that fits the remaining budget AND hasn't been picked yet.
            // The old path loaded every catalogue row into memory and
            // filtered per slot; now we fetch exactly 1 row via
            // `PageRequest.of(0, 1)`. JPQL forbids `NOT IN ()`, so pass a
            // sentinel `[-1L]` when nothing has been picked yet.
            def exclude = pickedIds.isEmpty() ? [-1L] : new ArrayList<Long>(pickedIds)
            def pool = loadoutRepository.findCheapestInBudgetExcluding(category, remaining, exclude, onePage)
            def pick = pool.isEmpty() ? null : pool.first()
            if (pick != null) {
                slot.itemId = pick.id
                slot.itemName = pick.name
                slot.itemEmoji = pick.iconEmoji
                slot.snapshotPrice = pick.lowestPrice
                remaining = remaining - pick.lowestPrice
                pickedIds.add(pick.id)
            } else {
                // Nothing in this category fits the remaining budget. The slot
                // is unlocked (locked+filled slots returned early above), so
                // auto-generate owns it — clear any pre-existing item rather
                // than leaving it. A stale, possibly over-budget pick left
                // here doesn't count against `remaining` (only locked slots
                // do), so keeping it would push the loadout's total past the
                // ceiling the user asked for — the exact overshoot the
                // locked-slot subtraction above exists to prevent.
                slot.itemId = null
                slot.itemName = null
                slot.itemEmoji = null
                slot.snapshotPrice = BigDecimal.ZERO
            }
            loadoutSlotRepository.save(slot)
        }
        recalcTotal(loadout)
        loadoutSlotRepository.findByLoadout(loadoutId)
    }

    /**
     * Owner-only metadata edit. Any subset of { name, description, visibility }
     * may be supplied; null / missing keys are left untouched. Visibility is
     * whitelisted to PUBLIC/PRIVATE so a typo doesn't silently drop the
     * loadout to an unknown state. Name goes through the same short-text
     * sanitizer as create(); description through the medium-text sanitizer.
     * Touches updatedAt so the Discover sort surfaces the recent edit.
     */
    @Transactional
    Loadout update(Long ownerUserId, Long loadoutId, String name, String description, String visibility) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.ownerUserId != ownerUserId) throw new ForbiddenException("Not your loadout")
        if (name != null) {
            def cleanName = textSanitizer.cleanShort(name)
            if (!cleanName || cleanName.isEmpty()) {
                throw new BadRequestException("INVALID_NAME", "Loadout name is required")
            }
            loadout.name = cleanName
        }
        if (description != null) {
            loadout.description = textSanitizer.medium(description)
        }
        if (visibility != null) {
            if (!(visibility in ['PUBLIC', 'PRIVATE'])) {
                throw new BadRequestException("INVALID_VISIBILITY", "visibility must be PUBLIC or PRIVATE")
            }
            loadout.visibility = visibility
        }
        loadout.updatedAt = System.currentTimeMillis()
        loadoutRepository.save(loadout)
    }

    @Transactional
    void delete(Long ownerUserId, Long loadoutId) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.ownerUserId != ownerUserId) throw new ForbiddenException("Not your loadout")
        // Hard-delete semantics: wipe slots + favorites referencing this
        // loadout, then the loadout row itself. The favorites cascade is
        // load-bearing — without it, every starring user keeps a junction
        // row pointing at the now-gone loadout id. listFavorites() silently
        // filters those out (findAllById returns nothing for the dead id),
        // but the table grows without bound, the orphan rows have no FK
        // backstop in the schema, and if a future IDENTITY id happened to
        // collide with the dead one, those stale favorites would suddenly
        // light up against an unrelated loadout. Cleanest fix: cascade
        // here in the service layer where the deletion intent is explicit.
        loadoutFavoriteRepository?.deleteByLoadoutId(loadoutId)
        loadoutSlotRepository.deleteByLoadoutId(loadoutId)
        loadoutRepository.delete(loadout)
    }

    /**
     * Admin-initiated loadout deletion (batch 583). Used for moderation
     * takedowns — TOS-violating names, PII in the description, etc.
     * Bypasses the self-only check. Pushes a notification to the
     * owner with the staff-supplied reason so they know what was
     * removed + why. Audited via {@code LOADOUT_DELETED_STAFF}.
     */
    @Transactional
    void adminDelete(Long adminUserId, Long loadoutId, String reason) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        def cleanReason = textSanitizer?.medium(reason ?: '') ?: (reason ?: '')
        if (!cleanReason || cleanReason.isEmpty()) cleanReason = 'Violates the community guidelines'
        def ownerId = loadout.ownerUserId
        def loadoutName = loadout.name
        // Same cascade as the owner delete() — see note there. Admin
        // takedowns must leave the favorites table consistent too;
        // otherwise the staff-removed loadout's orphan favorite rows
        // pile up identically and the loadout-id-reuse risk above
        // applies just the same.
        loadoutFavoriteRepository?.deleteByLoadoutId(loadoutId)
        loadoutSlotRepository.deleteByLoadoutId(loadoutId)
        loadoutRepository.delete(loadout)
        try {
            notificationService?.push(ownerId, 'LOADOUT_DELETED',
                "Loadout removed · ${loadoutName ?: 'your loadout'}",
                cleanReason, loadoutId, '/loadout')
        } catch (Exception e) {
            log.warn("LOADOUT_DELETED push failed for user ${ownerId}: ${e.message}")
        }
        try {
            auditService?.log('LOADOUT_DELETED_STAFF', adminUserId, ownerId, loadoutId,
                "Removed loadout '${loadoutName}': ${cleanReason}")
        } catch (Exception e) {
            log.warn("LOADOUT_DELETED_STAFF audit failed for loadout ${loadoutId}: ${e.message}")
        }
        log.info("Admin ${adminUserId} force-deleted loadout ${loadoutId} (owner=${ownerId}): ${cleanReason}")
    }

    /**
     * Toggle favorite state for a (user, loadout) pair. Hitting this
     * endpoint with a user who has already favorited removes the star;
     * a fresh user adds one. Requires a logged-in user — the controller
     * enforces auth before we get here. The aggregate `favorites` count
     * on the Loadout row is kept in sync from the junction table so
     * the Discover sort doesn't need to JOIN on every query. Returns a
     * map with the new count and whether the viewer is now favoriting.
     *
     * Private loadouts cannot be favorited by non-owners — same
     * enumeration defense as getWithSlots.
     */
    @Transactional
    Map toggleFavorite(Long viewerUserId, Long loadoutId) {
        if (viewerUserId == null) throw new UnauthorizedException()
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.visibility == 'PRIVATE' && loadout.ownerUserId != viewerUserId) {
            throw new NotFoundException("Loadout", loadoutId)
        }

        def existing = loadoutFavoriteRepository.findByUserAndLoadout(viewerUserId, loadoutId)
        boolean favorited
        if (existing != null) {
            loadoutFavoriteRepository.deleteByUserAndLoadout(viewerUserId, loadoutId)
            favorited = false
        } else {
            // findByUserAndLoadout + save is a non-atomic read-modify-write.
            // A user double-tapping the favorite button (or hitting the
            // endpoint from two devices simultaneously) fires two concurrent
            // requests that both observe existing=null and both INSERT; the
            // `uq_loadout_fav_user_loadout` UNIQUE constraint then rejects
            // the loser with a DataIntegrityViolationException. Treat that as
            // a benign no-op (the favorite already exists from the winning
            // request) — without this catch the loser bubbles a 500, and
            // worse, the favorites-counter increment below would have run
            // twice (one per request) producing a count of 2 for a single
            // favoriting user. Mirrors ReviewService.toggleHelpful which
            // handles the same race on the helpful-vote junction table.
            try {
                loadoutFavoriteRepository.save(new LoadoutFavorite(
                    userId:    viewerUserId,
                    loadoutId: loadoutId
                ))
            } catch (DataIntegrityViolationException dup) {
                log.debug("toggleFavorite race on loadout=${loadoutId} user=${viewerUserId} — already favorited, treating as no-op")
                // Re-read the count so the response reflects the row the
                // winning request committed.
                long raceCount = loadoutFavoriteRepository.countByLoadout(loadoutId)
                loadout.favorites = (int) raceCount
                loadoutRepository.save(loadout)
                return [id: loadoutId, favorites: (int) raceCount, favorited: true]
            }
            favorited = true
        }

        long count = loadoutFavoriteRepository.countByLoadout(loadoutId)
        loadout.favorites = (int) count
        loadoutRepository.save(loadout)

        [id: loadoutId, favorites: (int) count, favorited: favorited]
    }

    /**
     * Clone a PUBLIC loadout (or the viewer's own private one) into a new
     * loadout owned by the viewer. All filled slots carry over with a fresh
     * snapshotPrice so the clone starts in sync with current catalogue pricing.
     * Lock state is intentionally NOT copied — a clone starts with every slot
     * unlocked so the new owner can retune it freely. PRIVATE source loadouts
     * owned by someone else 404 (same enumeration defense as getWithSlots /
     * toggleFavorite). Ban guard applies to the cloner so banned users can't
     * populate an unlimited number of public loadouts.
     */
    @Transactional
    Loadout clone(Long viewerUserId, Long sourceLoadoutId, String ownerName) {
        if (viewerUserId == null) throw new UnauthorizedException()
        banGuard.assertNotBanned(viewerUserId)
        if (loadoutRepository.countByOwnerUserId(viewerUserId) >= MAX_LOADOUTS_PER_USER) {
            throw new BadRequestException("LOADOUT_CAP",
                "You've reached the ${MAX_LOADOUTS_PER_USER}-loadout limit. Delete an old one before cloning another.")
        }
        def source = loadoutRepository.findById(sourceLoadoutId)
            .orElseThrow { new NotFoundException("Loadout", sourceLoadoutId) }
        if (source.visibility == 'PRIVATE' && source.ownerUserId != viewerUserId) {
            throw new NotFoundException("Loadout", sourceLoadoutId)
        }

        def baseName = (source.name ?: 'Loadout').trim()
        def cloneName = baseName.length() > 90 ? baseName.substring(0, 90) : baseName
        cloneName = (cloneName + ' (copy)').take(100)
        def cleanOwner = textSanitizer.cleanShort(ownerName)

        def copy = new Loadout(
            ownerUserId: viewerUserId,
            ownerName:   cleanOwner,
            name:        cloneName,
            // Re-sanitize like name (above) and like create()/update() do —
            // a source description could pre-date a sanitizer-rule change,
            // so copying it verbatim would leak now-disallowed content.
            description: textSanitizer.medium(source.description),
            visibility:  'PRIVATE'  // start private so the cloner can tweak before publishing
        )
        // Re-assign `copy` to the save() return value so the slot-seed loop
        // below dereferences the freshly-managed instance whose id has been
        // populated by the IDENTITY INSERT, rather than relying on Hibernate
        // mutating the input argument in place. Two users cloning the same
        // source loadout simultaneously each get their own row + their own
        // IDENTITY-generated id — distinct ids are guaranteed by the
        // database's IDENTITY sequence, so the only correctness concern at
        // the service layer is that we hand the right id to every
        // subsequent loadoutSlotRepository.save(). The explicit return-value
        // capture makes that contract obvious and survives a future provider
        // swap where in-place mutation isn't guaranteed.
        copy = loadoutRepository.save(copy)

        // Seed slots first, then overlay the source's filled slots. Snapshot
        // prices come from the current catalogue row so stale prices from the
        // source don't leak in.
        def sourceSlots = loadoutSlotRepository.findByLoadout(sourceLoadoutId).collectEntries { [(it.slot): it] }
        SLOTS.each { slotName ->
            def src = sourceSlots[slotName] as LoadoutSlot
            def fresh = new LoadoutSlot(loadoutId: copy.id, slot: slotName)
            if (src?.itemId != null) {
                def item = itemRepository.findById(src.itemId).orElse(null)
                if (item != null) {
                    fresh.itemId        = item.id
                    fresh.itemName      = item.name
                    fresh.itemEmoji     = item.iconEmoji
                    fresh.snapshotPrice = item.lowestPrice ?: BigDecimal.ZERO
                }
            }
            loadoutSlotRepository.save(fresh)
        }
        recalcTotal(copy)
        copy
    }

    private void recalcTotal(Loadout loadout) {
        def slots = loadoutSlotRepository.findByLoadout(loadout.id)
        def total = slots.sum { it.snapshotPrice ?: BigDecimal.ZERO } ?: BigDecimal.ZERO
        loadout.totalValue = total instanceof BigDecimal ? total : new BigDecimal(total.toString())
        loadout.updatedAt = System.currentTimeMillis()
        loadoutRepository.save(loadout)
    }
}
