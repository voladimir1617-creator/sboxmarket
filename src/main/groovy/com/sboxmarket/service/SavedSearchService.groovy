package com.sboxmarket.service

import com.sboxmarket.model.SavedSearch
import com.sboxmarket.repository.SavedSearchRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Server-side saved-search persistence (cross-device sync). The
 * frontend keeps a localStorage cache for offline reads; this service
 * is the source of truth for signed-in users.
 *
 * Per-user cap mirrors the existing localStorage limit so re-applying
 * the same UX on the server doesn't surprise existing users.
 */
@Service
@Slf4j
class SavedSearchService {

    static final int MAX_PER_USER = 10
    // Batch 660 — the category/rarity whitelists live in the shared
    // `com.sboxmarket.util.ListingEnums` util (single source of truth
    // across controllers + services). Sort stays here because it's
    // service-local and has no parallel on the listings query path.
    private static final Set<String> ALLOWED_SORTS = ['price_desc','price_asc','newest','rarity','discount','ending_soon'].toSet()
    // Batch 957 — listingType whitelist. Mirrors the frontend chip row
    // (ALL / BUY_NOW / AUCTION) so a malformed share URL falls back to
    // the widest view rather than silently narrowing to an invalid bucket.
    private static final Set<String> ALLOWED_LISTING_TYPES = ['ALL','BUY_NOW','AUCTION'].toSet()

    @Autowired SavedSearchRepository repository
    @Autowired com.sboxmarket.service.security.BanGuard banGuard
    @Autowired TextSanitizer textSanitizer
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository
    @Autowired(required = false) EmailService emailService
    // Optional — when present, the fanout skips presets whose owner has
    // blocked the listing's seller. Symmetric with the SellerFollow fanout
    // (a blocked seller's new listing must not ping the blocker via either
    // a saved search OR a follow). Left optional so the existing unit
    // spec — which doesn't wire a block service — still exercises the
    // happy path unchanged.
    @Autowired(required = false) UserBlockService userBlockService

    /**
     * Upsert a preset. If the same `(userId, name)` already exists, the
     * existing row's filters are overwritten and the existing id is
     * returned — no duplicate row created. Returns the persisted entity.
     *
     * noRollbackFor — same systematic rollback-only leak class as waves
     * 136-139 (UserBlock, SellerFollow, Watchlist, Review, Cart, Loadout).
     * The catch(DIVE) recovery path further down handles the rapid
     * double-save UNIQUE-constraint race, but Spring's DIVE translator
     * marks the @Transactional rollback-only BEFORE the catch fires —
     * the recovery's findByUserAndName re-read + return commit NOTHING,
     * and the controller's 200 is followed by UnexpectedRollbackException
     * at commit time.
     */
    @Transactional(noRollbackFor = [DataIntegrityViolationException])
    SavedSearch upsert(Long userId, Map payload) {
        if (userId == null || payload == null) {
            throw new com.sboxmarket.exception.BadRequestException('MISSING_FIELD',
                'A saved-search payload is required')
        }
        // Ban guard — a saved search is a state-changing write, and a
        // created preset fans LISTING_MATCH bell + email pings out to
        // the owner forever. A banned account must not be able to set
        // new ones (the fanout already skips banned recipients, but the
        // write itself was previously ungated). Mirrors LoadoutService.
        banGuard.assertNotBanned(userId)
        // Name is user-controlled free text echoed back to the client
        // (toMap → `name`). Strip HTML / collapse whitespace through the
        // shared sanitizer — same treatment LoadoutService gives its
        // loadout name — before the trim + 80-char cap.
        def name = (textSanitizer.cleanShort(payload.name as String) ?: '').trim().take(80)
        if (!name) {
            throw new com.sboxmarket.exception.BadRequestException('MISSING_FIELD',
                "'name' is required")
        }
        // Batch 660 — category/rarity normalise through the shared
        // case-insensitive canonEnum helper, mirroring the listings
        // query fix from batches 656-659. A saved search created from
        // a share URL carrying `category=hats` now stores 'Hats' (the
        // canonical form) instead of silently collapsing to 'All'.
        def category = com.sboxmarket.util.ListingEnums.canonEnum(
            payload.category as String, com.sboxmarket.util.ListingEnums.CATEGORIES, 'All')
        def rarity   = com.sboxmarket.util.ListingEnums.canonEnum(
            payload.rarity   as String, com.sboxmarket.util.ListingEnums.RARITIES,   'All')
        // Sort whitelist stays case-sensitive — sort ids are machine-
        // generated (`price_desc` etc.) and never appear in a share URL
        // with mixed case, so the simple Set.contains check is fine.
        def sort     = sanitiseEnum(payload.sort     as String, ALLOWED_SORTS,      'price_desc')
        // `q` is user-controlled free text persisted verbatim and echoed
        // back to the client (toMap → `search`). It was previously only
        // length-capped — run it through the shared sanitizer so stored
        // HTML / script payloads can't survive a round-trip. cleanShort
        // also caps at 80 chars, matching the column width.
        def q        = textSanitizer.cleanShort((payload.q ?: payload.search ?: '') as String) ?: ''
        // minPrice/maxPrice persist as strings, but the LISTING_MATCH matcher
        // (matchesPreset, ~L317) evaluates `new BigDecimal(preset.minPrice)`.
        // A non-numeric value stored here (e.g. "1,000", "abc", "$5") makes that
        // throw, so the saved-search alert silently NEVER fires. Normalise to a
        // clean non-negative decimal string (or '' = "no bound") at write time
        // rather than persisting data that quietly breaks matching. (audit P3)
        def minPrice = normalisePriceBound(payload.minPrice as String)
        def maxPrice = normalisePriceBound(payload.maxPrice as String)
        // Batch 957 — the extended filter set. Clamp numerics, whitelist
        // enums. Booleans pass through with a null-safe default.
        int minDisc = 0
        try { minDisc = Math.max(0, Math.min(99, ((payload.minDiscountPct ?: 0) as Number).intValue())) }
        catch (Exception ignore) { minDisc = 0 }
        boolean dealsOnly      = Boolean.TRUE.equals(payload.dealsOnly)
        boolean newOnly        = Boolean.TRUE.equals(payload.newOnly)
        boolean affordableOnly = Boolean.TRUE.equals(payload.affordableOnly)
        String listingType = sanitiseEnum(payload.listingType as String, ALLOWED_LISTING_TYPES, 'ALL')

        def existing = repository.findByUserAndName(userId, name)
        if (existing != null) {
            existing.q               = q.take(80)
            existing.category        = category
            existing.rarity          = rarity
            existing.sort            = sort
            existing.minPrice        = minPrice
            existing.maxPrice        = maxPrice
            existing.minDiscountPct  = minDisc
            existing.dealsOnly       = dealsOnly
            existing.newOnly         = newOnly
            existing.affordableOnly  = affordableOnly
            existing.listingType     = listingType
            return repository.save(existing)
        }
        if (repository.countByUser(userId) >= MAX_PER_USER) {
            throw new com.sboxmarket.exception.BadRequestException('SAVED_SEARCHES_FULL',
                "You can save at most ${MAX_PER_USER} searches. Delete one before saving a new one.")
        }
        def row = new SavedSearch(
            userId:          userId,
            name:            name,
            q:               q.take(80),
            category:        category,
            rarity:          rarity,
            sort:            sort,
            minPrice:        minPrice,
            maxPrice:        maxPrice,
            minDiscountPct:  minDisc,
            dealsOnly:       dealsOnly,
            newOnly:         newOnly,
            affordableOnly:  affordableOnly,
            listingType:     listingType
        )
        // findByUserAndName + save is a non-atomic read-modify-write —
        // same TOCTOU race UserBlockService.block closed in 6418f56 and
        // SellerFollowService.follow closed in c4d6596. A user double-
        // tapping "Save preset" (or two devices firing the same payload
        // simultaneously) fires two concurrent requests that both
        // observe existing=null, both fall through to INSERT, and V32's
        // uq_saved_searches_user_name UNIQUE constraint then rejects
        // the loser with a DataIntegrityViolationException. Uncaught,
        // that bubbles to the catch-all as a 500 INTERNAL_ERROR — even
        // though the end-state ("user has a preset named X") is exactly
        // what the user wanted. Catch the dup, treat as a benign no-op,
        // and return the row the winning request committed via a fresh
        // lookup so the caller still sees a persisted id back.
        try {
            return repository.save(row)
        } catch (DataIntegrityViolationException dup) {
            log.debug("saved-search race on user=${userId} name=${name} — already saved, treating as no-op")
            def winner = repository.findByUserAndName(userId, name)
            if (winner != null) return winner
            // Vanishingly unlikely: the winner row exists per the
            // UNIQUE-violation we just caught but the re-read couldn't
            // find it (read-after-write replica lag? row deleted between
            // violation and re-read?). Surface the original throw —
            // better than silently returning a half-built unsaved row.
            throw dup
        }
    }

    @Transactional
    boolean delete(Long userId, Long id) {
        if (userId == null || id == null) return false
        repository.deleteByUserAndId(userId, id) > 0
    }

    /** Wipe every saved search the user owns. Powers the Profile →
     *  Saved Searches "Clear all" shortcut — parity with the follow +
     *  watchlist bulk-delete affordances (batch 353). Returns the
     *  row count actually removed. */
    @Transactional
    int deleteAllForUser(Long userId) {
        if (userId == null) return 0
        repository.deleteByUser(userId)
    }

    List<SavedSearch> list(Long userId) {
        if (userId == null) return []
        repository.findByUser(userId)
    }

    /**
     * One-shot bridge for the rollout: client posts its localStorage
     * preset list, server upserts each one (skipping any that put the
     * user over the cap). Returns the post-merge list so the client
     * can replace its cache atomically.
     *
     * Headroom only applies to NEW preset names — incoming rows whose
     * name matches an existing preset are pure updates that overwrite
     * the row in place and never grow the user's row count. Without
     * this split, an at-cap user (10/10 presets) couldn't sync any
     * filter edits from another device: `headroom = 0` would truncate
     * every incoming row including the pure updates, silently dropping
     * all server-side merges.
     */
    @Transactional
    List<SavedSearch> bulkMerge(Long userId, List<Map> incoming) {
        if (userId == null) return []
        def cleaned = (incoming ?: []).findAll { it != null && it.name }
        // Collapse name-duplicates within the input — last write wins.
        // Apply the same sanitization upsert() uses on the lookup key so
        // an incoming `<b>hats</b>` collapses to the same bucket as an
        // existing `hats` and is classified as an update, not a create.
        def byName = [:]
        cleaned.each { byName[mergeKeyFor(it.name as String)] = it }
        def candidates = byName.values() as List
        // Existing names indexed by lowercased merge-key → original
        // stored name. The merge-key view classifies updates vs creates
        // case-insensitively (so "Hats" and "hats" collide), and the
        // stored-name view lets us rewrite an incoming row's name to
        // the existing case before handing off to upsert — whose
        // `findByUserAndName` lookup is case-sensitive at the JPQL
        // layer. Without that rewrite an incoming "hats" against an
        // existing "Hats" would classify as an UPDATE (no headroom
        // consumed) but upsert's case-sensitive lookup would miss,
        // fall to the INSERT branch, and silently push the user one
        // row over MAX_PER_USER (the headroom math thought this was
        // an in-place edit).
        def existingByKey = [:] as Map<String, String>
        (repository.findByUser(userId) ?: []).each { row ->
            def key = mergeKeyFor(row.name as String)
            if (key && !existingByKey.containsKey(key)) {
                existingByKey[key] = row.name as String
            }
        }
        def updates = []
        def creates = []
        candidates.each { row ->
            def key = mergeKeyFor(row.name as String)
            if (key && existingByKey.containsKey(key)) {
                // Rewrite to the existing stored name so upsert's
                // case-sensitive findByUserAndName lookup hits the
                // existing row and overwrites in place.
                if (row instanceof Map) row.name = existingByKey[key]
                updates << row
            } else {
                creates << row
            }
        }
        def headroom = MAX_PER_USER - repository.countByUser(userId)
        if (headroom < creates.size()) creates = creates.take(Math.max(0, (int) headroom))
        // Run updates first so an at-cap user's filter edits land even
        // when there's zero headroom for new presets.
        (updates + creates).each { row ->
            try {
                upsert(userId, row as Map)
            } catch (Exception e) {
                log.debug("saved-search merge skipped '${row?.name}' for user ${userId}: ${e.message}")
            }
        }
        list(userId)
    }

    /** Normalise a preset name into the merge-key form used to classify
     *  incoming rows as updates vs creates. Mirrors the sanitization the
     *  upsert path applies (HTML-strip → trim → 80-char cap) plus a
     *  lowercase fold so the match is case-insensitive — same key shape
     *  used to dedup within the incoming batch. Returns an empty string
     *  when the input sanitises down to nothing so a junk row doesn't
     *  collide with another junk row in the existing-keys set. */
    private String mergeKeyFor(String raw) {
        def cleaned = (textSanitizer.cleanShort(raw) ?: '').trim().take(80)
        cleaned.toLowerCase()
    }

    private static String sanitiseEnum(String value, Set<String> whitelist, String fallback) {
        (value && whitelist.contains(value)) ? value : fallback
    }

    /**
     * Normalise a saved-search price bound to a clean non-negative decimal
     * string, or '' to mean "no bound". A non-numeric value persisted here
     * would make the LISTING_MATCH matcher's `new BigDecimal(preset.minPrice)`
     * throw, silently disabling the alert — so garbage is dropped at write
     * time rather than stored. `toPlainString` avoids scientific notation
     * (a "1e3" input is stored as "1000"). (audit P3)
     */
    private static String normalisePriceBound(String raw) {
        if (raw == null) return ''
        def s = raw.trim().take(16)
        if (s.isEmpty()) return ''
        try {
            def v = new BigDecimal(s)
            return v.signum() < 0 ? '' : v.toPlainString()
        } catch (NumberFormatException ignore) {
            return ''
        }
    }

    /**
     * Pure-function predicate: does this listing match the preset?
     *
     *   - category: 'All' or string match against listing.item.category
     *   - rarity:   'All' or string match against listing.item.rarity
     *   - q:        empty or case-insensitive substring of listing.item.name
     *   - minPrice / maxPrice: empty or numeric bounds inclusive
     *
     * Static + null-safe so the spec can call it directly without
     * standing up a service. Returns false on any unparseable input
     * (rather than throwing) — a malformed preset row should silently
     * skip, not break the listing-creation flow.
     */
    static boolean matches(SavedSearch preset, com.sboxmarket.model.Listing listing) {
        if (preset == null || listing == null) return false
        def item = listing.item
        if (item == null) return false
        // Category
        if (preset.category && preset.category != 'All' &&
            preset.category != item.category) return false
        // Rarity
        if (preset.rarity && preset.rarity != 'All' &&
            preset.rarity != item.rarity) return false
        // Text query — substring match against the catalogue name
        def q = (preset.q ?: '').trim()
        if (q && (item.name == null || !item.name.toLowerCase().contains(q.toLowerCase()))) {
            return false
        }
        // Price bounds — both stored as strings (legacy localStorage shape)
        def price = listing.price
        if (price == null) return false
        try {
            if (preset.minPrice && !preset.minPrice.isEmpty()) {
                def floor = new BigDecimal(preset.minPrice)
                if (price < floor) return false
            }
            if (preset.maxPrice && !preset.maxPrice.isEmpty()) {
                def ceil = new BigDecimal(preset.maxPrice)
                if (price > ceil) return false
            }
        } catch (NumberFormatException ignored) {
            // Bad bounds in the preset → conservative: don't match
            return false
        }
        // Batch 957 — extended filters. Order matters: cheaper checks
        // first, discount maths last.
        if (preset.listingType && preset.listingType != 'ALL') {
            if ((listing.listingType ?: 'BUY_NOW') != preset.listingType) return false
        }
        if (Boolean.TRUE.equals(preset.newOnly)) {
            // "New" == listed within the last 24h. Matches the frontend
            // `newOnly` chip predicate so server + client agree on the
            // bucket boundary.
            def listedAt = listing.listedAt ?: 0L
            def cutoff = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)
            if (listedAt < cutoff) return false
        }
        // Discount gate — skip listings whose `steamPrice` is missing/0
        // (can't compute a meaningful percent). dealsOnly is the 1% gate,
        // minDiscountPct is the user-picked floor; both apply together.
        if (Boolean.TRUE.equals(preset.dealsOnly) || (preset.minDiscountPct ?: 0) > 0) {
            def steamPrice = listing.item?.steamPrice
            if (steamPrice == null || steamPrice <= BigDecimal.ZERO) return false
            if (price >= steamPrice) return false
            def discFloor = Math.max(
                Boolean.TRUE.equals(preset.dealsOnly) ? 1 : 0,
                (preset.minDiscountPct ?: 0) as int
            )
            def actual = (steamPrice - price) * 100G / steamPrice
            if (actual < discFloor) return false
        }
        // affordableOnly is intentionally NOT applied here: it's a wallet-
        // balance-relative filter that belongs on the viewer's render
        // path, not on the fanout predicate (no wallet in scope here, and
        // match notifications fire regardless of the user's current
        // balance — seeing "this matches, top up to grab it" is valuable).
        true
    }

    /**
     * Fan a `LISTING_MATCH` notification out to every user with a
     * saved search that matches this listing. Skips the seller's own
     * presets — they don't need to be told their own listing matches
     * their own filter. Capped at 50 notifications per call to absorb
     * a once-in-blue-moon viral preset without blowing up.
     *
     * Best-effort: per-row push failures are logged but never bubble
     * up so a flaky NotificationService doesn't block the listing
     * creation flow.
     */
    void notifyMatchingForListing(com.sboxmarket.model.Listing listing) {
        if (listing?.item == null || notificationService == null) return
        // Batch 619 — narrowed from `findAll()` to a repo query that
        // pre-filters by the listing's category + rarity. Presets that
        // don't constrain those fields (empty strings) still flow
        // through. At platform scale this cuts the candidate set from
        // "every saved search ever" to "only those targeting this
        // category/rarity", a 10-100x reduction for typical browsing.
        def all
        def cat = listing.item?.category ?: ''
        def rar = listing.item?.rarity ?: ''
        try { all = repository.findCandidatesForListing(cat, rar) }
        catch (Exception e) { log.warn("saved-search scan failed: ${e.message}"); return }
        if (all == null || all.isEmpty()) return

        // Bulk pre-fetch every distinct candidate owner once so the inner
        // loop stays at O(1) per preset instead of issuing one SELECT per
        // matching saved-search. Without this, a listing that hits 50
        // presets fired 50 user lookups during the fanout — back when
        // catalogue-wide presets ("All / All") were common, every new
        // listing creation paid that tax. The bulk load is best-effort:
        // if it returns null/empty (the unit-spec default mock does) we
        // fall back to a per-id lookup inside the loop, so existing
        // per-user findById stubs keep working.
        def candidateOwnerIds = all.collect { it.userId }.findAll { it != null }.unique()
        def usersById = [:] as Map<Long, Object>
        if (steamUserRepository != null && !candidateOwnerIds.isEmpty()) {
            try {
                def bulk = steamUserRepository.findAllById(candidateOwnerIds)
                bulk?.each { if (it?.id != null) usersById[it.id] = it }
            } catch (Exception e) {
                log.warn("saved-search bulk user lookup failed: ${e.message}")
            }
        }
        // Bulk-fetch the listing seller's block set once too — a follower
        // who blocked the seller must not get LISTING_MATCH pings (block
        // trumps preset). Build a Set<Long> of "owner ids that blocked
        // this seller" so the per-preset check is a hash hit, not a SQL
        // round-trip. Fail-open: a block-table outage doesn't drop the
        // fanout, it just degrades to "no block filter applied".
        def blockedSet = [] as Set<Long>
        if (userBlockService != null && listing.sellerUserId != null && !candidateOwnerIds.isEmpty()) {
            candidateOwnerIds.each { ownerId ->
                try {
                    if (userBlockService.isBlocked(ownerId, listing.sellerUserId)) {
                        blockedSet << (ownerId as Long)
                    }
                } catch (Exception e) {
                    // Fail-open: a single owner's block-check failure shouldn't
                    // drop their notification — the parent op already logs.
                }
            }
        }

        int sent = 0
        for (SavedSearch preset : all) {
            if (sent >= 50) break
            if (preset.userId == null) continue
            if (preset.userId == listing.sellerUserId) continue
            if (!matches(preset, listing)) continue
            // Skip if the preset owner has blocked the listing's seller
            // (parity with the SellerFollow fanout's block guard).
            if (blockedSet.contains(preset.userId)) continue
            // Per-preset user lookup with a fall-through: prefer the
            // bulk-fetched row, fall back to a per-id query only when
            // the bulk path returned nothing (e.g. the unit spec mocks
            // `findById` per-user without stubbing `findAllById`). In
            // production the bulk path satisfies every preset, so this
            // is a no-op O(1) map hit.
            def user = usersById[preset.userId]
            if (user == null && steamUserRepository != null) {
                try {
                    user = steamUserRepository.findById(preset.userId).orElse(null)
                } catch (Exception e) {
                    log.warn("saved-search user lookup failed for ${preset.userId}: ${e.message}")
                }
            }
            // Skip banned account — ban guard blocks writes but it
            // doesn't block engagement pings. A banned buyer who'd set
            // up a saved search pre-ban would otherwise keep getting
            // LISTING_MATCH bell + email pings on every matching listing.
            if (user != null && Boolean.TRUE.equals(user.banned)) continue
            try {
                def title = "Match for \"${preset.name}\""
                def priceStr = listing.price != null ? "\$${listing.price.toPlainString()}" : ''
                def body = "${listing.item?.name ?: 'a new item'} · ${priceStr}".trim()
                def itemUrl = listing.item?.id != null ? "/item/${listing.item.id}".toString() : null
                notificationService.push(preset.userId, 'LISTING_MATCH',
                    title.toString(), body.toString(), listing.id, itemUrl)
                // Mirror to email — gated on the global toggle, verified
                // address, and the per-bucket MATCHES mute (shipped in
                // batch 257). Best-effort: failure here doesn't undo
                // the bell push or the sent counter.
                try {
                    if (emailService != null && emailService.canSendTo(user, 'MATCHES')) {
                        emailService.sendSavedSearchMatch(user.email, user.displayName,
                            preset.name, listing.item?.name, listing.price, itemUrl)
                    }
                } catch (Exception emailErr) {
                    log.warn("saved-search match email failed for user ${preset.userId}: ${emailErr.message}")
                }
                sent++
            } catch (Exception e) {
                log.warn("saved-search match push failed for user ${preset.userId}: ${e.message}")
            }
        }
        if (sent > 0) log.info("Saved-search fanout: ${sent} match notification(s) for listing ${listing.id}")
    }
}
