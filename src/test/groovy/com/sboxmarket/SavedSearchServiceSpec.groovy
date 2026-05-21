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
}
