package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.SavedSearch
import com.sboxmarket.repository.SavedSearchRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SavedSearchService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pure-logic coverage for cross-device saved-search persistence
 * (batch 264 / V32).
 */
class SavedSearchServiceSpec extends Specification {

    SavedSearchRepository repository = Mock()
    BanGuard banGuard = Mock()
    // Real sanitizer — it's a dependency-free pure @Component, so a plain
    // `new` gives the genuine HTML-strip / whitespace-collapse / 80-char
    // behaviour the service relies on (no need to stub each method).
    TextSanitizer textSanitizer = new TextSanitizer()

    @Subject
    SavedSearchService service = new SavedSearchService(
        repository: repository, banGuard: banGuard, textSanitizer: textSanitizer)

    def "upsert creates a new row when none with the same name exists"() {
        given:
        repository.findByUserAndName(10L, 'cheap hats') >> null
        repository.countByUser(10L) >> 3L

        when:
        def saved = service.upsert(10L, [name: 'cheap hats', category: 'Hats', maxPrice: '20'])

        then:
        1 * repository.save({
            it.userId == 10L && it.name == 'cheap hats' &&
                it.category == 'Hats' && it.maxPrice == '20' &&
                it.sort == 'price_desc'    // default
        }) >> { SavedSearch s -> s.id = 1L; s }
        saved.name == 'cheap hats'
    }

    def "upsert overwrites the existing row when the name already exists"() {
        given:
        def existing = new SavedSearch(id: 7L, userId: 10L, name: 'cheap hats',
            category: 'Hats', sort: 'newest')
        repository.findByUserAndName(10L, 'cheap hats') >> existing

        when:
        def out = service.upsert(10L, [name: 'cheap hats', category: 'Boots', sort: 'price_asc'])

        then:
        1 * repository.save({
            it.id == 7L && it.category == 'Boots' && it.sort == 'price_asc'
        }) >> { SavedSearch s -> s }
        // Cap is NOT consulted on upsert — overwriting an existing row
        // never grows the user's row count.
        0 * repository.countByUser(_)
        out.id == 7L
    }

    def "upsert rejects when the user is at MAX_PER_USER"() {
        given:
        repository.findByUserAndName(_, _) >> null
        // Explicit Long literal so the stub matches `userId` (Long) — the
        // `as long` form was being parsed in a way that left the stub
        // returning 0 by default, letting save() through.
        repository.countByUser(_) >> 10L

        when:
        service.upsert(10L, [name: 'fresh preset'])

        then:
        thrown(BadRequestException)
        0 * repository.save(_)
    }

    def "upsert rejects empty/missing name"() {
        when:
        service.upsert(10L, [name: ''])

        then:
        thrown(BadRequestException)
    }

    def "upsert sanitises unknown enum values to defaults"() {
        given:
        repository.findByUserAndName(10L, 'mystery') >> null
        repository.countByUser(10L) >> 0L

        when:
        service.upsert(10L, [name: 'mystery',
            category: 'NopeNotReal', rarity: 'BogusRarity', sort: 'WeirdSort'])

        then:
        1 * repository.save({
            it.category == 'All' && it.rarity == 'All' && it.sort == 'price_desc'
        }) >> { SavedSearch s -> s }
    }

    def "upsert drops non-numeric price bounds so the LISTING_MATCH matcher can't choke (audit P3)"() {
        given:
        repository.findByUserAndName(10L, 'junkprice') >> null
        repository.countByUser(10L) >> 0L

        when:
        // "abc" / "-5" would throw or mean nothing in the matcher, so both
        // normalise to ''. ("1,000" is a thousands separator and now saves
        // as 1000 — see DefectPassSevenSpec.)
        service.upsert(10L, [name: 'junkprice', minPrice: '-5', maxPrice: 'abc'])

        then:
        1 * repository.save({ it.minPrice == '' && it.maxPrice == '' }) >> { SavedSearch s -> s }
    }

    def "upsert keeps a price typed the way the market filter accepts it"() {
        given:
        repository.findByUserAndName(10L, 'under20') >> null
        repository.countByUser(10L) >> 0L

        when: '"$20" and "12,50" filter the grid fine, so saving that view must keep them'
        service.upsert(10L, [name: 'under20', minPrice: '12,50', maxPrice: '$20'])

        then:
        1 * repository.save({ it.minPrice == '12.50' && it.maxPrice == '20' }) >> { SavedSearch s -> s }
    }

    def "upsert keeps the toolbar's Most traded and Most viewed sorts"() {
        given:
        repository.findByUserAndName(10L, _) >> null
        repository.countByUser(10L) >> 0L

        when:
        service.upsert(10L, [name: 'traded', sort: 'popularity'])
        service.upsert(10L, [name: 'viewed', sort: 'views'])

        then:
        1 * repository.save({ it.sort == 'popularity' }) >> { SavedSearch x -> x }
        1 * repository.save({ it.sort == 'views' }) >> { SavedSearch x -> x }
    }

    def "upsert on an existing preset leaves extended filters alone when they aren't sent"() {
        given:
        def existing = new SavedSearch(id: 7L, userId: 10L, name: 'auction deals', category: 'Hats',
            minDiscountPct: 20, dealsOnly: true, newOnly: true, affordableOnly: true, listingType: 'AUCTION')
        repository.findByUserAndName(10L, 'auction deals') >> existing

        when: 'an older client re-saves with only the basic fields'
        service.upsert(10L, [name: 'auction deals', category: 'Hats', sort: 'newest'])

        then:
        1 * repository.save({
            it.minDiscountPct == 20 && it.dealsOnly && it.newOnly && it.affordableOnly && it.listingType == 'AUCTION'
        }) >> { SavedSearch x -> x }
    }

    def "upsert keeps valid price bounds in plain decimal form (no scientific notation)"() {
        given:
        repository.findByUserAndName(10L, 'okprice') >> null
        repository.countByUser(10L) >> 0L

        when:
        service.upsert(10L, [name: 'okprice', minPrice: '  5.50 ', maxPrice: '1e3'])

        then:
        // trimmed; "1e3" normalised to plain "1000" so the matcher parses it.
        1 * repository.save({ it.minPrice == '5.50' && it.maxPrice == '1000' }) >> { SavedSearch s -> s }
    }

    // ── Ban guard ───────────────────────────────────────────────────

    def "upsert rejects a banned user before any repo work"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException('Your account is banned: x') }

        when:
        service.upsert(10L, [name: 'preset'])

        then:
        thrown(ForbiddenException)
        // The ban check fires first — no name lookup, no cap query, no save.
        0 * repository.findByUserAndName(_, _)
        0 * repository.countByUser(_)
        0 * repository.save(_)
    }

    def "upsert consults the ban guard exactly once on the happy path"() {
        given:
        repository.findByUserAndName(10L, 'ok') >> null
        repository.countByUser(10L) >> 0L

        when:
        service.upsert(10L, [name: 'ok'])

        then:
        1 * banGuard.assertNotBanned(10L)
        1 * repository.save(_) >> { SavedSearch s -> s }
    }

    def "bulkMerge skips every row for a banned user (per-row ban rejection)"() {
        given:
        // upsert() runs the ban guard per row; a banned user throws every
        // time, and bulkMerge swallows the per-row exception. Net effect:
        // nothing is persisted.
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException('banned') }
        repository.countByUser(10L) >> 0L
        repository.findByUser(10L) >> []

        when:
        def out = service.bulkMerge(10L, [[name: 'A'], [name: 'B']])

        then:
        0 * repository.save(_)
        out == []
    }

    // ── Stored-query sanitization ───────────────────────────────────

    def "upsert strips HTML tags from the stored q / search text"() {
        given:
        repository.findByUserAndName(10L, 'xss') >> null
        repository.countByUser(10L) >> 0L

        when:
        service.upsert(10L, [name: 'xss', q: '<script>alert(1)</script>hat'])

        then:
        1 * repository.save({
            // Tag stripped, plain text survives — no '<' or '>' persisted.
            it.q == 'alert(1)hat' && !it.q.contains('<') && !it.q.contains('>')
        }) >> { SavedSearch s -> s }
    }

    def "upsert sanitises q supplied under the legacy 'search' key too"() {
        given:
        repository.findByUserAndName(10L, 'legacy') >> null
        repository.countByUser(10L) >> 0L

        when:
        service.upsert(10L, [name: 'legacy', search: '<b>boots</b>'])

        then:
        1 * repository.save({ it.q == 'boots' }) >> { SavedSearch s -> s }
    }

    def "upsert sanitises HTML out of the preset name"() {
        given:
        repository.findByUserAndName(10L, _) >> null
        repository.countByUser(10L) >> 0L

        when:
        service.upsert(10L, [name: '<img src=x onerror=alert(1)>cheap hats'])

        then:
        1 * repository.save({
            // Tag + the onerror= handler are both gone; readable text stays.
            !it.name.contains('<') && !it.name.contains('onerror') &&
                it.name.contains('cheap hats')
        }) >> { SavedSearch s -> s }
    }

    def "upsert rejects a name that sanitises down to empty"() {
        when:
        // A name that is nothing but a tag collapses to '' post-sanitize.
        service.upsert(10L, [name: '<br/>'])

        then:
        thrown(BadRequestException)
        0 * repository.save(_)
    }

    def "upsert caps an over-long q at the 80-char column width"() {
        given:
        repository.findByUserAndName(10L, 'long') >> null
        repository.countByUser(10L) >> 0L

        when:
        service.upsert(10L, [name: 'long', q: 'x' * 500])

        then:
        1 * repository.save({ it.q.length() == 80 }) >> { SavedSearch s -> s }
    }

    def "upsert overwrite path also sanitises q on an existing row"() {
        given:
        def existing = new SavedSearch(id: 7L, userId: 10L, name: 'reuse',
            category: 'All', rarity: 'All', q: 'old')
        repository.findByUserAndName(10L, 'reuse') >> existing

        when:
        service.upsert(10L, [name: 'reuse', q: '<i>new</i>'])

        then:
        1 * repository.save({ it.id == 7L && it.q == 'new' }) >> { SavedSearch s -> s }
    }

    def "upsert canonicalises lowercase category / rarity to the stored case (batch 660)"() {
        given:
        repository.findByUserAndName(10L, 'from-share-url') >> null
        repository.countByUser(10L) >> 0L

        when:
        // A saved search created from a shared `/?category=hats&rarity=off-market`
        // URL carries lowercase values — the save should normalise to
        // the canonical case so the `matches(preset, listing)` predicate
        // (which uses case-sensitive string equals against `item.category`)
        // fires on actual listings.
        service.upsert(10L, [name: 'from-share-url',
            category: 'hats', rarity: 'off-market'])

        then:
        1 * repository.save({
            it.category == 'Hats' && it.rarity == 'Off-Market'
        }) >> { SavedSearch s -> s }
    }

    // ── Extended filters (batch 957) ────────────────────────────────

    def "upsert persists every extended filter field"() {
        given:
        repository.findByUserAndName(10L, 'rich') >> null
        repository.countByUser(10L) >> 0L

        when:
        service.upsert(10L, [
            name: 'rich', category: 'Hats',
            minDiscountPct: 25, dealsOnly: true, newOnly: true,
            affordableOnly: false, listingType: 'AUCTION'
        ])

        then:
        1 * repository.save({
            it.minDiscountPct == 25 &&
                it.dealsOnly == true &&
                it.newOnly == true &&
                it.affordableOnly == false &&
                it.listingType == 'AUCTION'
        }) >> { SavedSearch s -> s }
    }

    def "upsert clamps minDiscountPct into [0, 99] and whitelists listingType"() {
        given:
        repository.findByUserAndName(_, _) >> null
        repository.countByUser(_) >> 0L

        when: "out-of-range + bogus enum"
        service.upsert(10L, [
            name: 'x', minDiscountPct: 500, listingType: 'BOGUS'
        ])

        then:
        1 * repository.save({
            it.minDiscountPct == 99 && it.listingType == 'ALL'
        }) >> { SavedSearch s -> s }
    }

    def "matches rejects a listing whose listingType differs from the preset"() {
        given:
        def auctionOnly = new SavedSearch(name: 'a', category: 'All', rarity: 'All',
            listingType: 'AUCTION')
        def buyNow = listingFor()            // default listingType is null → treated as BUY_NOW

        expect:
        !SavedSearchService.matches(auctionOnly, buyNow)
    }

    def "matches rejects listings older than 24h when newOnly=true"() {
        given:
        def freshOnly = new SavedSearch(name: 'n', category: 'All', rarity: 'All',
            newOnly: true)
        // Listed 2 days ago:
        def stale = listingFor()
        stale.listedAt = System.currentTimeMillis() - (2L * 24L * 60L * 60L * 1000L)

        expect:
        !SavedSearchService.matches(freshOnly, stale)
    }

    def "matches respects minDiscountPct against item.steamPrice"() {
        given:
        def twentyOff = new SavedSearch(name: 'd', category: 'All', rarity: 'All',
            minDiscountPct: 20)
        // item at $10, listed at $7 → 30% off (passes)
        def bigDeal = listingFor(price: new BigDecimal('7'))
        bigDeal.item.steamPrice = new BigDecimal('10')
        // item at $10, listed at $9 → 10% off (fails)
        def smallDeal = listingFor(price: new BigDecimal('9'))
        smallDeal.item.steamPrice = new BigDecimal('10')

        expect:
        SavedSearchService.matches(twentyOff, bigDeal)
        !SavedSearchService.matches(twentyOff, smallDeal)
    }

    def "matches dealsOnly gates on any positive discount (>= 1%)"() {
        given:
        def dealsOnly = new SavedSearch(name: 'd', category: 'All', rarity: 'All',
            dealsOnly: true)
        def atSteam = listingFor(price: new BigDecimal('10'))
        atSteam.item.steamPrice = new BigDecimal('10')   // 0% off → fails
        def oneCentOff = listingFor(price: new BigDecimal('9.90'))
        oneCentOff.item.steamPrice = new BigDecimal('10')   // 1% off → passes

        expect:
        !SavedSearchService.matches(dealsOnly, atSteam)
        SavedSearchService.matches(dealsOnly, oneCentOff)
    }

    def "delete returns true on hit / false on miss"() {
        when:
        def hit  = service.delete(10L, 1L)
        def miss = service.delete(10L, 999L)

        then:
        1 * repository.deleteByUserAndId(10L, 1L) >> 1
        1 * repository.deleteByUserAndId(10L, 999L) >> 0
        hit == true
        miss == false
    }

    def "delete is scoped to the caller — a non-owned id is a no-op false, never a cross-user delete"() {
        // The repo DELETE is keyed on (userId, id); a row owned by user
        // 20 is invisible to user 10's delete. The service must thread
        // the caller's id through unchanged so ownership is enforced at
        // the query — no separate read-then-check that could be skipped.
        when:
        def removed = service.delete(10L, 555L)

        then: 'caller id 10 reaches the repo verbatim; foreign row reports 0 rows'
        1 * repository.deleteByUserAndId(10L, 555L) >> 0
        removed == false
    }

    def "delete short-circuits on null user id or null search id without touching the repo"() {
        when:
        def nullUser = service.delete(null, 1L)
        def nullId = service.delete(10L, null)

        then:
        !nullUser
        !nullId
        0 * repository.deleteByUserAndId(_, _)
    }

    def "deleteAllForUser forwards to the bulk delete and returns the row count (batch 353)"() {
        given:
        repository.deleteByUser(10L) >> 7

        when:
        def n = service.deleteAllForUser(10L)

        then:
        n == 7
    }

    def "deleteAllForUser short-circuits on null user id without hitting the repo"() {
        when:
        def n = service.deleteAllForUser(null)

        then:
        n == 0
        0 * repository.deleteByUser(_)
    }

    def "bulkMerge collapses input duplicates by name and respects headroom"() {
        given:
        repository.countByUser(10L) >>> [3L, 4L, 5L]   // initial + after each save
        repository.findByUserAndName(10L, _) >> null
        repository.findByUser(10L) >> []

        when:
        // 5 entries, two collapse to the same name; cap headroom is
        // MAX_PER_USER - 3 = 7, so all 4 distinct should be saved.
        service.bulkMerge(10L, [
            [name: 'A'], [name: 'B'], [name: 'A'],   // 'A' dedups
            [name: 'C'], [name: 'D']
        ])

        then:
        4 * repository.save(_) >> { SavedSearch s -> s }
    }

    def "bulkMerge truncates a giant input to the available headroom"() {
        given:
        // User already has MAX_PER_USER - 1 rows; only one new can fit.
        repository.countByUser(10L) >> (SavedSearchService.MAX_PER_USER - 1L)
        repository.findByUserAndName(10L, _) >> null
        repository.findByUser(10L) >> []

        when:
        service.bulkMerge(10L, (1..50).collect { [name: "preset-${it}"] })

        then:
        // Headroom = 1 → only one save call.
        1 * repository.save(_) >> { SavedSearch s -> s }
    }

    def "bulkMerge lets an at-cap user update existing presets (headroom only applies to net-new names)"() {
        // Regression: an at-cap user (10/10) could not sync any filter
        // edits via bulkMerge because the headroom guard truncated the
        // whole input including pure updates. The split must classify
        // matching-name rows as updates and pass them through.
        given:
        def existing = (1..10).collect { i ->
            new SavedSearch(id: i as Long, userId: 10L, name: "preset-${i}",
                category: 'All', rarity: 'All', sort: 'newest')
        }
        repository.countByUser(10L) >> 10L
        repository.findByUser(10L) >> existing
        // Each upsert hits the overwrite path — findByUserAndName returns
        // the matching row, so save() touches existing.id, not a new row.
        existing.each { e ->
            repository.findByUserAndName(10L, e.name) >> e
        }

        when:
        // Same 10 names with edited filters — these are all updates.
        service.bulkMerge(10L,
            existing.collect { [name: it.name, sort: 'price_asc', category: 'Hats'] })

        then: 'all 10 updates land even at cap — cap only blocks net-new presets'
        10 * repository.save({ SavedSearch s -> s.id != null }) >> { SavedSearch s -> s }
    }

    def "bulkMerge mixes updates and creates correctly under partial headroom"() {
        // User has 9/10 presets. Incoming has 2 updates (existing names)
        // + 3 creates. Headroom = 1, so only 1 of the 3 creates fits, but
        // BOTH updates land regardless because updates don't consume cap.
        given:
        def existing = [
            new SavedSearch(id: 1L, userId: 10L, name: 'A', category: 'All', rarity: 'All'),
            new SavedSearch(id: 2L, userId: 10L, name: 'B', category: 'All', rarity: 'All')
        ]
        repository.countByUser(10L) >> 9L
        repository.findByUser(10L) >> existing
        repository.findByUserAndName(10L, 'A') >> existing[0]
        repository.findByUserAndName(10L, 'B') >> existing[1]
        repository.findByUserAndName(10L, 'NEW1') >> null
        repository.findByUserAndName(10L, 'NEW2') >> null
        repository.findByUserAndName(10L, 'NEW3') >> null

        when:
        service.bulkMerge(10L, [
            [name: 'A',    sort: 'price_asc'],   // update
            [name: 'B',    sort: 'price_asc'],   // update
            [name: 'NEW1'],                       // create (fits)
            [name: 'NEW2'],                       // create (cap-blocked)
            [name: 'NEW3']                        // create (cap-blocked)
        ])

        then: 'both updates + one create fire — 3 saves total'
        3 * repository.save(_) >> { SavedSearch s -> s }
    }

    // ── Matcher truth table (batch 266) ─────────────────────────────

    private com.sboxmarket.model.Listing listingFor(Map args = [:]) {
        def item = new com.sboxmarket.model.Item(
            id:       args.itemId ?: 1L,
            name:     args.name ?: 'Wizard Hat',
            category: args.category ?: 'Hats',
            rarity:   args.rarity ?: 'Limited'
        )
        new com.sboxmarket.model.Listing(
            id:           args.id ?: 100L,
            item:         item,
            price:        args.price ?: new BigDecimal('15.00'),
            sellerUserId: args.seller ?: 99L
        )
    }

    def "matches returns true on the trivial 'All / All / no bounds / no q' preset"() {
        given:
        def preset = new SavedSearch(name: 'all', category: 'All', rarity: 'All')

        expect:
        SavedSearchService.matches(preset, listingFor())
    }

    def "matches enforces category equality when not 'All'"() {
        given:
        def hatsOnly = new SavedSearch(name: 'h', category: 'Hats', rarity: 'All')
        def bootsOnly = new SavedSearch(name: 'b', category: 'Boots', rarity: 'All')

        expect:
        SavedSearchService.matches(hatsOnly, listingFor(category: 'Hats'))
        !SavedSearchService.matches(bootsOnly, listingFor(category: 'Hats'))
    }

    def "matches enforces rarity equality when not 'All'"() {
        given:
        def limited  = new SavedSearch(name: 'l', category: 'All', rarity: 'Limited')
        def standard = new SavedSearch(name: 's', category: 'All', rarity: 'Standard')

        expect:
        SavedSearchService.matches(limited,  listingFor(rarity: 'Limited'))
        !SavedSearchService.matches(standard, listingFor(rarity: 'Limited'))
    }

    def "matches text query is case-insensitive substring against item name"() {
        given:
        def preset = new SavedSearch(name: 'wizard', category: 'All', rarity: 'All', q: 'WIZARD')

        expect:
        SavedSearchService.matches(preset, listingFor(name: 'Glowing Wizard Hat'))
        !SavedSearchService.matches(preset, listingFor(name: 'Cap'))
    }

    def "matches respects min/max price bounds inclusively"() {
        given:
        def floor10ceil20 = new SavedSearch(name: 'p', category: 'All', rarity: 'All',
            minPrice: '10', maxPrice: '20')

        expect:
        SavedSearchService.matches(floor10ceil20, listingFor(price: new BigDecimal('15')))
        SavedSearchService.matches(floor10ceil20, listingFor(price: new BigDecimal('10')))   // inclusive low
        SavedSearchService.matches(floor10ceil20, listingFor(price: new BigDecimal('20')))   // inclusive high
        !SavedSearchService.matches(floor10ceil20, listingFor(price: new BigDecimal('9.99')))
        !SavedSearchService.matches(floor10ceil20, listingFor(price: new BigDecimal('20.01')))
    }

    def "matches returns false on null inputs"() {
        expect:
        !SavedSearchService.matches(null, listingFor())
        !SavedSearchService.matches(new SavedSearch(name: 'x', category: 'All', rarity: 'All'), null)
    }

    def "matches returns false when preset bounds are unparseable"() {
        given:
        def garbage = new SavedSearch(name: 'g', category: 'All', rarity: 'All',
            minPrice: 'not-a-number')

        expect:
        !SavedSearchService.matches(garbage, listingFor())
    }

    def "matches returns false when the listing has no item attached"() {
        given:
        def preset = new SavedSearch(name: 'x', category: 'All', rarity: 'All')
        def itemless = new com.sboxmarket.model.Listing(id: 1L, price: new BigDecimal('5'))

        expect: 'no item → cannot evaluate category/rarity/q → conservative false, not an NPE'
        !SavedSearchService.matches(preset, itemless)
    }

    def "matches returns false when the listing has no price"() {
        given:
        def preset = new SavedSearch(name: 'x', category: 'All', rarity: 'All')
        def priceless = listingFor()
        priceless.price = null

        expect:
        !SavedSearchService.matches(preset, priceless)
    }

    def "matches discount gate skips a listing whose item has no steamPrice"() {
        // Can't compute a meaningful percent off a missing/zero base
        // price — the deal filter must reject rather than divide by zero
        // or treat 'unknown' as 'huge discount'.
        given:
        def dealsOnly = new SavedSearch(name: 'd', category: 'All', rarity: 'All',
            dealsOnly: true)
        def noSteam = listingFor(price: new BigDecimal('5'))   // item.steamPrice left null
        def zeroSteam = listingFor(price: new BigDecimal('5'))
        zeroSteam.item.steamPrice = BigDecimal.ZERO

        expect:
        !SavedSearchService.matches(dealsOnly, noSteam)
        !SavedSearchService.matches(dealsOnly, zeroSteam)
    }

    def "matches discount maths survives a non-terminating division (steamPrice that doesn't divide evenly)"() {
        // (steamPrice - price) * 100 / steamPrice with steamPrice=3 is
        // 66.66… — a raw BigDecimal.divide would throw ArithmeticException.
        // Groovy's `/` applies a rounding scale, so the matcher must just
        // compute the percent and compare, never blow up the fanout.
        given:
        def twentyOff = new SavedSearch(name: 'd', category: 'All', rarity: 'All',
            minDiscountPct: 20)
        def deal = listingFor(price: new BigDecimal('1'))   // 1 of 3 → 66.6% off
        deal.item.steamPrice = new BigDecimal('3')

        expect:
        SavedSearchService.matches(twentyOff, deal)
    }

    def "matches affordableOnly is intentionally ignored by the fanout predicate"() {
        // affordableOnly is a wallet-relative filter with no wallet in
        // scope here — a preset that sets it must still match so the
        // 'top up to grab it' notification can fire.
        given:
        def affPreset = new SavedSearch(name: 'a', category: 'All', rarity: 'All',
            affordableOnly: true)

        expect:
        SavedSearchService.matches(affPreset, listingFor())
    }

    // ── notifyMatchingForListing fanout ─────────────────────────────

    def "notifyMatchingForListing pushes LISTING_MATCH to every matching user except the seller"() {
        given:
        def notifications = Mock(NotificationService)
        service.notificationService = notifications
        // Seller user id = 99 (also has a matching preset → must be skipped).
        repository.findCandidatesForListing(_, _) >> [
            new SavedSearch(id: 1L, userId: 11L, name: 'limited hats',  category: 'Hats',  rarity: 'Limited'),
            new SavedSearch(id: 2L, userId: 12L, name: 'cheap any',     category: 'All',   rarity: 'All',     maxPrice: '20'),
            new SavedSearch(id: 3L, userId: 13L, name: 'boots only',    category: 'Boots', rarity: 'All'),       // miss
            new SavedSearch(id: 4L, userId: 99L, name: 'self preset',   category: 'Hats',  rarity: 'Limited')    // self — skip
        ]

        when:
        service.notifyMatchingForListing(listingFor())   // seller = 99, Hats/Limited/$15

        then:
        1 * notifications.push(11L, 'LISTING_MATCH', _, _, 100L, '/item/1')
        1 * notifications.push(12L, 'LISTING_MATCH', _, _, 100L, '/item/1')
        0 * notifications.push(13L, _, _, _, _, _)
        0 * notifications.push(99L, _, _, _, _, _)
    }

    def "notifyMatchingForListing stays quiet for a listing nobody can buy yet"() {
        given: 'escrow holds a new listing as PENDING_ESCROW until the deposit lands'
        def notifications = Mock(NotificationService)
        service.notificationService = notifications
        repository.findCandidatesForListing(_, _) >> [
            new SavedSearch(id: 1L, userId: 11L, name: 'limited hats', category: 'Hats', rarity: 'Limited')
        ]

        when:
        service.notifyMatchingForListing(listingFor().tap { it.status = 'PENDING_ESCROW' })
        service.notifyMatchingForListing(listingFor().tap { it.hidden = true })

        then:
        0 * notifications.push(*_)
    }

    def "notifyMatchingForListing skips banned account owners (batch 315 bug fix)"() {
        // A banned user with a matching saved search used to keep
        // getting LISTING_MATCH pings. Ban guard only covers writes;
        // engagement pings needed their own filter.
        given:
        def notifications = Mock(NotificationService)
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.notificationService = notifications
        service.steamUserRepository = steamUserRepo
        repository.findCandidatesForListing(_, _) >> [
            new SavedSearch(id: 1L, userId: 11L, name: 'clean',  category: 'Hats', rarity: 'Limited'),
            new SavedSearch(id: 2L, userId: 12L, name: 'banned', category: 'Hats', rarity: 'Limited')
        ]
        steamUserRepo.findById(11L) >> Optional.of(new com.sboxmarket.model.SteamUser(id: 11L, banned: false))
        steamUserRepo.findById(12L) >> Optional.of(new com.sboxmarket.model.SteamUser(id: 12L, banned: true))

        when:
        service.notifyMatchingForListing(listingFor())

        then:
        1 * notifications.push(11L, 'LISTING_MATCH', _, _, _, _)
        0 * notifications.push(12L, _, _, _, _, _)
    }

    def "notifyMatchingForListing is a silent no-op when there are no saved searches"() {
        given:
        def notifications = Mock(NotificationService)
        service.notificationService = notifications
        repository.findCandidatesForListing(_, _) >> []

        when:
        service.notifyMatchingForListing(listingFor())

        then:
        0 * notifications.push(_, _, _, _, _, _)
    }

    def "notifyMatchingForListing caps the fanout at 50 notifications for a viral preset"() {
        // 80 distinct users all hold an All/All preset matching the new
        // listing; the fanout must stop at 50 pushes so one accidentally
        // broad preset can't blow up the listing-creation path.
        given:
        def notifications = Mock(NotificationService)
        service.notificationService = notifications
        def many = (1..80).collect { i ->
            new SavedSearch(id: i as Long, userId: (1000L + i),
                name: "p${i}", category: 'All', rarity: 'All')
        }
        repository.findCandidatesForListing(_, _) >> many

        when:
        service.notifyMatchingForListing(listingFor())

        then: 'exactly the 50-cap, no more'
        50 * notifications.push(_, 'LISTING_MATCH', _, _, _, _)
    }

    def "notifyMatchingForListing is a no-op when the listing has no item"() {
        given:
        def notifications = Mock(NotificationService)
        service.notificationService = notifications

        when:
        service.notifyMatchingForListing(new com.sboxmarket.model.Listing(id: 1L))

        then: 'no item → never even queries candidates'
        0 * repository.findCandidatesForListing(_, _)
        0 * notifications.push(_, _, _, _, _, _)
    }

    def "notifyMatchingForListing swallows a candidate-query failure without throwing"() {
        // The fanout is best-effort: a flaky repo query must be logged
        // and absorbed so it never rolls back the listing creation.
        given:
        def notifications = Mock(NotificationService)
        service.notificationService = notifications
        repository.findCandidatesForListing(_, _) >> { throw new RuntimeException('db down') }

        when:
        service.notifyMatchingForListing(listingFor())

        then:
        notThrown(Exception)
        0 * notifications.push(_, _, _, _, _, _)
    }

    // ── Saved-search match emails (batch 267) ───────────────────────

    def "notifyMatchingForListing emails when MATCHES bucket is NOT muted"() {
        given:
        def notifications = Mock(NotificationService)
        def emails        = Mock(com.sboxmarket.service.EmailService)
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.notificationService = notifications
        service.emailService        = emails
        service.steamUserRepository = steamUserRepo

        // Whitelist gate plumbing: the user has emails ON, verified, and
        // the MATCHES bucket is NOT in their muted list.
        repository.findCandidatesForListing(_, _) >> [
            new SavedSearch(id: 1L, userId: 11L, name: 'limited hats',
                category: 'Hats', rarity: 'Limited')
        ]
        def user = new com.sboxmarket.model.SteamUser(
            id: 11L, displayName: 'Alice',
            email: 'alice@x.test', emailVerified: true,
            emailNotificationsEnabled: true,
            mutedEmailKinds: 'AUCTIONS'   // MATCHES not in here
        )
        steamUserRepo.findById(11L) >> Optional.of(user)
        emails.canSendTo(user, 'MATCHES') >> true

        when:
        service.notifyMatchingForListing(listingFor())

        then:
        1 * notifications.push(11L, 'LISTING_MATCH', _, _, _, _)
        1 * emails.sendSavedSearchMatch('alice@x.test', 'Alice',
            'limited hats', 'Wizard Hat', _ as BigDecimal, '/item/1')
    }

    def "notifyMatchingForListing skips email when MATCHES bucket IS muted"() {
        given:
        def notifications = Mock(NotificationService)
        def emails        = Mock(com.sboxmarket.service.EmailService)
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.notificationService = notifications
        service.emailService        = emails
        service.steamUserRepository = steamUserRepo

        repository.findCandidatesForListing(_, _) >> [
            new SavedSearch(id: 1L, userId: 11L, name: 'preset',
                category: 'All', rarity: 'All')
        ]
        def user = new com.sboxmarket.model.SteamUser(
            id: 11L, email: 'alice@x.test', emailVerified: true,
            emailNotificationsEnabled: true,
            mutedEmailKinds: 'MATCHES'
        )
        steamUserRepo.findById(11L) >> Optional.of(user)
        emails.canSendTo(user, 'MATCHES') >> false

        when:
        service.notifyMatchingForListing(listingFor())

        then:
        // Bell push still fires — the email mute is per-bucket *email*
        // suppression, not a full notification mute.
        1 * notifications.push(11L, 'LISTING_MATCH', _, _, _, _)
        0 * emails.sendSavedSearchMatch(_, _, _, _, _, _)
    }

    def "notifyMatchingForListing skips email when global emailNotificationsEnabled = false"() {
        given:
        def notifications = Mock(NotificationService)
        def emails        = Mock(com.sboxmarket.service.EmailService)
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.notificationService = notifications
        service.emailService        = emails
        service.steamUserRepository = steamUserRepo

        repository.findCandidatesForListing(_, _) >> [
            new SavedSearch(id: 1L, userId: 11L, name: 'preset',
                category: 'All', rarity: 'All')
        ]
        def user = new com.sboxmarket.model.SteamUser(
            id: 11L, email: 'alice@x.test', emailVerified: true,
            emailNotificationsEnabled: false       // global kill switch
        )
        steamUserRepo.findById(11L) >> Optional.of(user)

        when:
        service.notifyMatchingForListing(listingFor())

        then:
        1 * notifications.push(11L, 'LISTING_MATCH', _, _, _, _)
        0 * emails.sendSavedSearchMatch(_, _, _, _, _, _)
    }

    def "notifyMatchingForListing skips email when address is unverified"() {
        given:
        def notifications = Mock(NotificationService)
        def emails        = Mock(com.sboxmarket.service.EmailService)
        def steamUserRepo = Mock(com.sboxmarket.repository.SteamUserRepository)
        service.notificationService = notifications
        service.emailService        = emails
        service.steamUserRepository = steamUserRepo

        repository.findCandidatesForListing(_, _) >> [
            new SavedSearch(id: 1L, userId: 11L, name: 'preset',
                category: 'All', rarity: 'All')
        ]
        def user = new com.sboxmarket.model.SteamUser(
            id: 11L, email: 'alice@x.test',
            emailVerified: false,                  // <-- unverified
            emailNotificationsEnabled: true
        )
        steamUserRepo.findById(11L) >> Optional.of(user)

        when:
        service.notifyMatchingForListing(listingFor())

        then:
        1 * notifications.push(11L, 'LISTING_MATCH', _, _, _, _)
        0 * emails.sendSavedSearchMatch(_, _, _, _, _, _)
    }

    def "upsert treats a UNIQUE-constraint race as a graceful no-op, not a 500"() {
        // findByUserAndName() + save() is a non-atomic read-modify-write
        // — same TOCTOU race UserBlockService.block closed in 6418f56
        // and SellerFollowService.follow closed in c4d6596. A user
        // double-tapping "Save preset" (or two devices firing the same
        // payload simultaneously) fires two concurrent requests that
        // both observe existing=null and both INSERT; V32's
        // uq_saved_searches_user_name UNIQUE constraint then rejects
        // the loser with a DataIntegrityViolationException. Before the
        // fix that bubbled to the controller as a 500 INTERNAL_ERROR
        // even though the end-state ("user has a preset named X") was
        // exactly what the user wanted. After the fix the loser
        // swallows the constraint violation and returns the row the
        // winning request committed via a fresh re-read.
        given:
        def winner = new SavedSearch(id: 99L, userId: 10L, name: 'cheap hats',
            category: 'Hats', sort: 'price_desc')
        // First lookup (existence check) — null, so we fall through to insert.
        // Second lookup (post-race re-read) — winner row the racing
        // request already committed. Spock returns successive stub
        // values across calls, mirroring the SellerFollowService recovery
        // path: see test note above for parallel rationale.
        repository.findByUserAndName(10L, 'cheap hats') >>> [null, winner]
        repository.countByUser(10L) >> 4L

        when:
        def out = service.upsert(10L, [name: 'cheap hats', category: 'Hats'])

        then: 'save() is attempted exactly once and throws the constraint violation'
        1 * repository.save({
            it.userId == 10L && it.name == 'cheap hats' && it.category == 'Hats'
        }) >> {
            throw new org.springframework.dao.DataIntegrityViolationException('duplicate key')
        }
        // The constraint violation must NOT escape — the controller
        // would translate it to a 500 otherwise.
        noExceptionThrown()
        // Response carries the committed (winning) row, not the
        // unsaved local instance.
        out.is(winner)
        out.id == 99L
    }

    def "upsert re-raises when the post-race re-read also returns null"() {
        // Defensive — if the UNIQUE-violation re-read can't surface the
        // winner row (read-after-write replica lag, or the row was
        // deleted between the violation and the re-read), the service
        // re-throws the original DataIntegrityViolationException rather
        // than silently returning a half-built unsaved instance to the
        // caller. Parallel to SellerFollowService.follow's recovery
        // behaviour from c4d6596.
        given:
        // Both reads return null — the existence check AND the recovery
        // lookup. The save still throws, simulating the constraint
        // violation having fired between the two reads.
        repository.findByUserAndName(10L, 'lost preset') >> null
        repository.countByUser(10L) >> 4L

        when:
        service.upsert(10L, [name: 'lost preset', category: 'Hats'])

        then:
        1 * repository.save(_) >> {
            throw new org.springframework.dao.DataIntegrityViolationException('duplicate key')
        }
        thrown(org.springframework.dao.DataIntegrityViolationException)
    }
}
