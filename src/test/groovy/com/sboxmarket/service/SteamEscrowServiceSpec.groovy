package com.sboxmarket.service

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.data.domain.Pageable
import spock.lang.Specification

/**
 * Unit tests for the seller→bot DEPOSIT/custody orchestrator. The bot service,
 * repositories and notifications are all mocked, so no sidecar / DB is needed.
 *
 * Covers:
 *  - disabled-mode: no deposit requested, listing stays ACTIVE (legacy),
 *    pollers + return are inert
 *  - deposit on list: bot requests the asset, custody PENDING_DEPOSIT,
 *    listing held PENDING_ESCROW
 *  - custody confirm: accepted offer + asset held → IN_CUSTODY + listing ACTIVE
 *  - return-to-seller: IN_CUSTODY item sent back, custody → RETURNED
 *  - delivery asset resolution via heldAssetIdForListing
 */
class SteamEscrowServiceSpec extends Specification {

    SteamEscrowService service
    SteamTradeBotService bot = Mock()
    EscrowedItemRepository escrowRepository = Mock()
    ListingRepository listingRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()

    def setup() {
        service = new SteamEscrowService()
        service.steamTradeBotService = bot
        service.escrowRepository = escrowRepository
        service.listingRepository = listingRepository
        service.steamUserRepository = steamUserRepository
        service.notificationService = notificationService
        service.escrowSweepEnabled = true
        service.offerMessage = 'sboxmarket escrow'
        service.batchSize = 50
    }

    private static Listing listing(Map o = [:]) {
        new Listing(
                id: (Long) (o.id ?: 10L),
                price: new BigDecimal('100.00'),
                sellerName: 'Seller',
                status: (o.status ?: 'ACTIVE') as String,
                sellerUserId: o.containsKey('sellerUserId') ? (o.sellerUserId as Long) : 1L)
    }

    private SteamUser sellerWithUrl(String url = 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=xyz') {
        new SteamUser(id: 1L, steamId64: '76561190000000001', tradeUrl: url)
    }

    // ── disabled-mode ──────────────────────────────────────────────────────

    def "escrow disabled: requestDepositForListing is a no-op (listing stays ACTIVE)"() {
        given:
        bot.enabled >> false
        def l = listing()

        expect:
        !service.escrowEnabled

        when:
        def row = service.requestDepositForListing(l, '555', 'AK-47 | Redline')

        then:
        row == null
        0 * bot.requestItems(_, _, _)
        0 * escrowRepository.save(_)
        0 * listingRepository.save(_)
    }

    def "escrow disabled: pollDeposits and returnToSeller are inert"() {
        given:
        bot.enabled >> false

        when:
        service.pollDeposits()
        def returned = service.returnToSeller(10L)

        then:
        // No custody/persistence work and — crucially — no side-effecting bot
        // network calls (deposit request, return offer, status poll, inventory
        // fetch). Reading the bot's `enabled` gate flag is the ONLY bot touch
        // the disabled-mode short-circuit makes, and it must be allowed: the
        // whole point of the gate is to consult it and then do nothing.
        0 * escrowRepository._
        0 * bot.requestItems(_, _, _)
        0 * bot.sendOffer(_, _, _)
        0 * bot.getOfferStatus(_)
        0 * bot.fetchBotInventory()
        0 * bot.acceptIncoming(_)
        !returned
    }

    def "sweep master switch off: pollDeposits does nothing even when bot enabled"() {
        given:
        service.escrowSweepEnabled = false

        when:
        service.pollDeposits()

        then:
        0 * escrowRepository._
        0 * bot._
    }

    // ── deposit on list ──────────────────────────────────────────────────────

    def "deposit: bot requests the asset, persists PENDING_DEPOSIT, holds listing PENDING_ESCROW"() {
        given:
        bot.enabled >> true
        def l = listing(status: 'ACTIVE')

        when:
        def row = service.requestDepositForListing(l, '555', 'AK-47 | Redline')

        then:
        // no existing custody row for this listing
        1 * escrowRepository.findByListingId(10L) >> null
        // seller trade URL resolved
        1 * steamUserRepository.findById(1L) >> Optional.of(sellerWithUrl())
        // listing held PENDING_ESCROW (not buyable) BEFORE the request
        1 * listingRepository.findById(10L) >> Optional.of(l)
        1 * listingRepository.save({ Listing saved -> saved.status == SteamEscrowService.STATUS_PENDING_ESCROW })
        // bot requests the specific asset FROM the seller
        1 * bot.requestItems('https://steamcommunity.com/tradeoffer/new/?partner=1&token=xyz', ['555'], 'sboxmarket escrow') >>
                SteamBotResult.success([ok: true, offerId: '444', status: 'sent'])
        // custody row persisted PENDING_DEPOSIT with the deposit offer id
        1 * escrowRepository.save({ EscrowedItem e ->
            e.listingId == 10L && e.assetId == '555' && e.depositOfferId == '444' &&
            e.custodyState == EscrowedItem.PENDING_DEPOSIT }) >> { EscrowedItem e -> e }
        1 * notificationService.safePush(1L, _, _, _, _, _)
    }

    def "deposit: seller without a trade URL holds the listing and records a clear error"() {
        given:
        bot.enabled >> true
        def l = listing(status: 'ACTIVE')

        when:
        def row = service.requestDepositForListing(l, '555', 'AK-47 | Redline')

        then:
        1 * escrowRepository.findByListingId(10L) >> null
        1 * steamUserRepository.findById(1L) >> Optional.of(sellerWithUrl(null))
        // listing still held (must not be buyable when we can't escrow it)
        1 * listingRepository.findById(10L) >> Optional.of(l)
        1 * listingRepository.save({ Listing saved -> saved.status == SteamEscrowService.STATUS_PENDING_ESCROW })
        // no bot request without a trade URL
        0 * bot.requestItems(_, _, _)
        1 * escrowRepository.save({ EscrowedItem e ->
            e.custodyState == EscrowedItem.PENDING_DEPOSIT && e.lastError?.contains('trade URL') }) >> { EscrowedItem e -> e }
    }

    def "deposit: idempotent — an existing custody row short-circuits"() {
        given:
        bot.enabled >> true
        def l = listing()
        def existing = new EscrowedItem(id: 99L, listingId: 10L, custodyState: EscrowedItem.PENDING_DEPOSIT)

        when:
        def row = service.requestDepositForListing(l, '555', 'AK-47 | Redline')

        then:
        1 * escrowRepository.findByListingId(10L) >> existing
        row.is(existing)
        0 * bot.requestItems(_, _, _)
        0 * listingRepository.save(_)
    }

    def "deposit: transient request failure persists row WITHOUT an offer id (retryable), listing still held"() {
        given:
        bot.enabled >> true
        def l = listing(status: 'ACTIVE')

        when:
        service.requestDepositForListing(l, '555', 'AK-47 | Redline')

        then:
        1 * escrowRepository.findByListingId(10L) >> null
        1 * steamUserRepository.findById(1L) >> Optional.of(sellerWithUrl())
        1 * listingRepository.findById(10L) >> Optional.of(l)
        1 * listingRepository.save({ Listing saved -> saved.status == SteamEscrowService.STATUS_PENDING_ESCROW })
        1 * bot.requestItems(_, _, _) >> SteamBotResult.error('RATE_LIMITED', 'slow down')
        1 * escrowRepository.save({ EscrowedItem e ->
            e.depositOfferId == null && e.custodyState == EscrowedItem.PENDING_DEPOSIT &&
            e.lastError?.contains('RATE_LIMITED') }) >> { EscrowedItem e -> e }
    }

    // ── custody confirm ──────────────────────────────────────────────────────

    def "confirm: accepted offer + asset held flips custody IN_CUSTODY and listing ACTIVE"() {
        given:
        bot.enabled >> true
        def e = new EscrowedItem(id: 5L, listingId: 10L, sellerUserId: 1L, assetId: '555',
                marketHashName: 'AK-47 | Redline', depositOfferId: '444',
                custodyState: EscrowedItem.PENDING_DEPOSIT)
        def held = listing(status: SteamEscrowService.STATUS_PENDING_ESCROW)

        when:
        service.confirmDeposit(5L, [byAsset: ['555': '555'], byName: [:]])

        then:
        1 * escrowRepository.findById(5L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'accepted'])
        // custody promoted with the held asset id
        1 * escrowRepository.save({ EscrowedItem r ->
            r.custodyState == EscrowedItem.IN_CUSTODY && r.heldAssetId == '555' })
        // listing flipped PENDING_ESCROW -> ACTIVE (now buyable)
        1 * listingRepository.findById(10L) >> Optional.of(held)
        1 * listingRepository.save({ Listing l -> l.status == 'ACTIVE' })
        1 * notificationService.safePush(1L, _, _, _, _, _)
    }

    def "confirm: accepted but asset not yet in inventory moves to IN_ESCROW_HOLD (no activation)"() {
        given: "an accepted deposit whose asset has not appeared in the bot inventory"
        // Expectation CHANGED deliberately (Steam trade-hold wave). This row
        // used to stay PENDING_DEPOSIT, which was the orphaning bug: the offer
        // is ACCEPTED, so the item has already left the seller's inventory, and
        // a PENDING_DEPOSIT row is precisely what the 24h timeout sweeper
        // failed and cancelled. Once the item is gone, FAILED does not undo
        // anything — it deletes the only pointer to a real item.
        //
        // IN_ESCROW_HOLD is the honest state ("gone from the seller, not usable
        // by the bot yet") and it is the one the pollers and the escalation
        // sweep both watch. It also stamps depositAcceptedAt, which is what
        // permanently disqualifies the row from the give-up path.
        bot.enabled >> true
        def e = new EscrowedItem(id: 5L, listingId: 10L, assetId: '555', depositOfferId: '444',
                custodyState: EscrowedItem.PENDING_DEPOSIT)

        when:
        service.confirmDeposit(5L, [byAsset: [:], byName: [:]])

        then:
        1 * escrowRepository.findById(5L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'accepted'])
        // parked in the hold state, with the item-has-left-the-seller stamp set
        1 * escrowRepository.save({ EscrowedItem r ->
            r.custodyState == EscrowedItem.IN_ESCROW_HOLD && r.depositAcceptedAt != null })
        // listing is NOT made buyable — the bot cannot deliver what it can't touch
        0 * listingRepository.save(_)
    }

    def "confirm: matches held asset by market_hash_name when Steam reassigned the asset id"() {
        given:
        bot.enabled >> true
        def e = new EscrowedItem(id: 5L, listingId: 10L, sellerUserId: 1L, assetId: '555',
                marketHashName: 'AK-47 | Redline', depositOfferId: '444',
                custodyState: EscrowedItem.PENDING_DEPOSIT)
        def held = listing(status: SteamEscrowService.STATUS_PENDING_ESCROW)

        when:
        // assetId 555 is NOT in the bot inventory, but a same-item asset 999 is
        service.confirmDeposit(5L, [byAsset: ['999': '999'], byName: ['AK-47 | Redline': '999']])

        then:
        1 * escrowRepository.findById(5L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'accepted'])
        1 * escrowRepository.save({ EscrowedItem r ->
            r.custodyState == EscrowedItem.IN_CUSTODY && r.heldAssetId == '999' })
        1 * listingRepository.findById(10L) >> Optional.of(held)
        1 * listingRepository.save({ Listing l -> l.status == 'ACTIVE' })
    }

    def "confirm: declined deposit offer marks custody FAILED, listing not activated"() {
        given:
        bot.enabled >> true
        def e = new EscrowedItem(id: 5L, listingId: 10L, sellerUserId: 1L, assetId: '555',
                depositOfferId: '444', custodyState: EscrowedItem.PENDING_DEPOSIT)

        when:
        service.confirmDeposit(5L, [byAsset: [:], byName: [:]])

        then:
        1 * escrowRepository.findById(5L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'declined'])
        1 * escrowRepository.save({ EscrowedItem r -> r.custodyState == EscrowedItem.FAILED })
        0 * listingRepository.save(_)
        1 * notificationService.safePush(1L, _, _, _, _, _)
    }

    // ── return-to-seller ─────────────────────────────────────────────────────

    def "return: IN_CUSTODY item is sent back to the seller and custody RETURNED"() {
        given:
        bot.enabled >> true
        def e = new EscrowedItem(id: 5L, listingId: 10L, sellerUserId: 1L, assetId: '555',
                heldAssetId: '555', custodyState: EscrowedItem.IN_CUSTODY)

        when:
        def ok = service.returnToSeller(10L, 'listing cancelled')

        then:
        1 * escrowRepository.findByListingId(10L) >> e
        1 * steamUserRepository.findById(1L) >> Optional.of(sellerWithUrl())
        1 * bot.sendOffer('https://steamcommunity.com/tradeoffer/new/?partner=1&token=xyz', ['555'], 'sboxmarket escrow') >>
                SteamBotResult.success([ok: true, offerId: '777', status: 'sent'])
        1 * escrowRepository.save({ EscrowedItem r ->
            r.custodyState == EscrowedItem.RETURNED && r.returnOfferId == '777' })
        ok
        1 * notificationService.safePush(1L, _, _, _, _, _)
    }

    def "return: nothing to return when the item is not IN_CUSTODY (e.g. DELIVERED)"() {
        given:
        bot.enabled >> true
        def e = new EscrowedItem(id: 5L, listingId: 10L, sellerUserId: 1L,
                custodyState: EscrowedItem.DELIVERED)

        when:
        def ok = service.returnToSeller(10L)

        then:
        1 * escrowRepository.findByListingId(10L) >> e
        0 * bot.sendOffer(_, _, _)
        !ok
    }

    def "return: no custody row is a clean no-op"() {
        given:
        bot.enabled >> true

        when:
        def ok = service.returnToSeller(10L)

        then:
        1 * escrowRepository.findByListingId(10L) >> null
        // No return offer is sent when there's no custody row. The escrow gate
        // reads bot.isEnabled() (escrow IS enabled here) before finding no row,
        // so assert specifically that no side-effecting bot call is made rather
        // than the over-broad `0 * bot._`, which would also (wrongly) forbid
        // the gate's enabled-flag read.
        0 * bot.sendOffer(_, _, _)
        0 * bot.requestItems(_, _, _)
        !ok
    }

    // ── delivery asset resolution + markDelivered ─────────────────────────────

    def "heldAssetIdForListing returns the bot-held asset only when IN_CUSTODY"() {
        given:
        bot.enabled >> true

        when:
        def held = service.heldAssetIdForListing(10L)

        then:
        1 * escrowRepository.findByListingId(10L) >> new EscrowedItem(
                listingId: 10L, heldAssetId: '888', custodyState: EscrowedItem.IN_CUSTODY)
        held == '888'
    }

    def "heldAssetIdForListing returns null for a non-custody (e.g. PENDING_DEPOSIT) row"() {
        given:
        bot.enabled >> true

        when:
        def held = service.heldAssetIdForListing(10L)

        then:
        1 * escrowRepository.findByListingId(10L) >> new EscrowedItem(
                listingId: 10L, assetId: '555', custodyState: EscrowedItem.PENDING_DEPOSIT)
        held == null
    }

    def "markDelivered flips IN_CUSTODY to DELIVERED"() {
        given:
        bot.enabled >> true
        def e = new EscrowedItem(id: 5L, listingId: 10L, custodyState: EscrowedItem.IN_CUSTODY)

        when:
        service.markDelivered(10L)

        then:
        1 * escrowRepository.findByListingId(10L) >> e
        1 * escrowRepository.save({ EscrowedItem r -> r.custodyState == EscrowedItem.DELIVERED })
    }

    def "pollDeposits fetches inventory once and confirms each pending row"() {
        given:
        bot.enabled >> true
        def e1 = new EscrowedItem(id: 1L, listingId: 11L, assetId: '111', depositOfferId: 'A',
                custodyState: EscrowedItem.PENDING_DEPOSIT)
        def e2 = new EscrowedItem(id: 2L, listingId: 12L, assetId: '222', depositOfferId: 'B',
                custodyState: EscrowedItem.PENDING_DEPOSIT)

        when:
        service.pollDeposits()

        then:
        1 * escrowRepository.findPendingDeposits(_ as Pageable) >> [e1, e2]
        // inventory fetched exactly once for the whole tick
        1 * bot.fetchBotInventory() >> SteamBotResult.success([ok: true, items: []])
        // each row re-loaded + polled (neither asset held yet → stays pending)
        1 * escrowRepository.findById(1L) >> Optional.of(e1)
        1 * escrowRepository.findById(2L) >> Optional.of(e2)
        1 * bot.getOfferStatus('A') >> SteamBotResult.success([ok: true, status: 'active'])
        1 * bot.getOfferStatus('B') >> SteamBotResult.success([ok: true, status: 'active'])
        noExceptionThrown()
    }

    // ── stale-deposit TIMEOUT sweeper ─────────────────────────────────────────
    //
    // pollDeposits only ever ADVANCES a row (accepted offer + asset held →
    // IN_CUSTODY). A deposit the seller never accepts — or a row whose offer
    // never got created — sits PENDING_DEPOSIT / listing PENDING_ESCROW forever
    // with no auto-resolution. sweepStalePendingDeposits is the missing timeout
    // leg: stale PENDING_DEPOSIT → FAILED (atomic claim) + listing
    // PENDING_ESCROW → CANCELLED. Mirrors StripeService.sweepStalePendingDeposits.

    def "sweep: stale PENDING_DEPOSIT is FAILED via atomic claim and its listing reverted PENDING_ESCROW -> CANCELLED"() {
        given:
        bot.enabled >> true
        service.depositTimeoutHours = 24
        def stale = new EscrowedItem(id: 7L, listingId: 10L, sellerUserId: 1L, assetId: '555',
                custodyState: EscrowedItem.PENDING_DEPOSIT,
                createdAt: System.currentTimeMillis() - (48L * 60L * 60L * 1000L))
        def held = listing(status: SteamEscrowService.STATUS_PENDING_ESCROW)

        when:
        service.sweepStalePendingDeposits()

        then:
        // candidate loaded via the stale finder (cutoff is now - 24h)
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [stale]
        // custody flipped to FAILED through the status-guarded conditional UPDATE,
        // NOT a blind save — this pod wins the claim (returns 1)
        1 * escrowRepository.claimTimeoutPendingDeposit(7L, { String r -> r.contains('24h') }, _ as Long) >> 1
        // listing freed: PENDING_ESCROW -> CANCELLED (NOT re-activated — the
        // bot never received the item, so it must not become buyable)
        1 * listingRepository.findById(10L) >> Optional.of(held)
        1 * listingRepository.save({ Listing l -> l.status == SteamEscrowService.STATUS_CANCELLED })
        // seller told to re-list
        1 * notificationService.safePush(1L, _, _, _, _, _)
        // sweeper does NOT touch any bot network API (timed-out deposit = item
        // never reached the bot; nothing to return/poll)
        0 * bot.requestItems(_, _, _)
        0 * bot.sendOffer(_, _, _)
        0 * bot.getOfferStatus(_)
        0 * bot.fetchBotInventory()
        // and never falls through to a blind escrow save
        0 * escrowRepository.save(_)
    }

    def "sweep: lost claim (sibling pod / last-second deposit) does NOT revert the listing or notify"() {
        given:
        bot.enabled >> true
        service.depositTimeoutHours = 24
        def stale = new EscrowedItem(id: 8L, listingId: 12L, sellerUserId: 2L, assetId: '666',
                custodyState: EscrowedItem.PENDING_DEPOSIT,
                createdAt: System.currentTimeMillis() - (48L * 60L * 60L * 1000L))

        when:
        service.sweepStalePendingDeposits()

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [stale]
        // claim returns 0 — a sibling pod already failed it, OR confirmDeposit
        // promoted it to IN_CUSTODY between the read and the UPDATE
        1 * escrowRepository.claimTimeoutPendingDeposit(8L, _, _ as Long) >> 0
        // losing path bails BEFORE any listing revert or seller notification
        0 * listingRepository.findById(_)
        0 * listingRepository.save(_)
        0 * notificationService.safePush(_, _, _, _, _, _)
    }

    def "sweep: a fresh PENDING_DEPOSIT (under the timeout) is left untouched"() {
        given: "the finder is the threshold gate — a fresh row is simply not returned by the cutoff query"
        bot.enabled >> true
        service.depositTimeoutHours = 24

        when:
        service.sweepStalePendingDeposits()

        then:
        // empty candidate set (the fresh row created minutes ago is newer than
        // the now-24h cutoff, so findStalePendingDeposits excludes it)
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> []
        // no claim attempted, no listing touched, no notification fired
        0 * escrowRepository.claimTimeoutPendingDeposit(_, _, _)
        0 * listingRepository.save(_)
        0 * notificationService.safePush(_, _, _, _, _, _)
    }

    def "sweep: passes a cutoff of (now - depositTimeoutHours) to the finder"() {
        given:
        bot.enabled >> true
        service.depositTimeoutHours = 24
        Long captured = null
        long before = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)

        when:
        service.sweepStalePendingDeposits()
        long after = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> { Long cutoff, p ->
            captured = cutoff
            []
        }
        // the cutoff is a 24h-ago timestamp (within the wall-clock bracket
        // around the call), proving the configurable threshold is applied
        captured != null
        captured >= before
        captured <= after
    }

    def "sweep: disabled mode (bot off) is a complete no-op — no finder, no claim, no bot call"() {
        given:
        bot.enabled >> false

        expect:
        !service.escrowEnabled

        when:
        service.sweepStalePendingDeposits()

        then:
        // gated exactly like pollDeposits: when the bot is unconfigured the
        // whole sweeper short-circuits before touching the repo
        0 * escrowRepository._
        0 * listingRepository._
        0 * notificationService._
        0 * bot.requestItems(_, _, _)
        0 * bot.sendOffer(_, _, _)
        0 * bot.getOfferStatus(_)
        0 * bot.fetchBotInventory()
    }

    def "sweep: master switch off (bot enabled) still no-ops"() {
        given:
        bot.enabled >> true
        service.escrowSweepEnabled = false

        when:
        service.sweepStalePendingDeposits()

        then:
        0 * escrowRepository._
        0 * listingRepository._
        0 * notificationService._
    }

    def "sweep: per-row try/catch — one row whose listing revert throws can't abort the batch"() {
        given:
        bot.enabled >> true
        service.depositTimeoutHours = 24
        def long_ago = System.currentTimeMillis() - (48L * 60L * 60L * 1000L)
        def r1 = new EscrowedItem(id: 21L, listingId: 31L, sellerUserId: 1L,
                custodyState: EscrowedItem.PENDING_DEPOSIT, createdAt: long_ago)
        def r2 = new EscrowedItem(id: 22L, listingId: 32L, sellerUserId: 2L,
                custodyState: EscrowedItem.PENDING_DEPOSIT, createdAt: long_ago)
        def good = listing(id: 32L, status: SteamEscrowService.STATUS_PENDING_ESCROW, sellerUserId: 2L)

        when:
        service.sweepStalePendingDeposits()

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [r1, r2]
        // both rows win their claim
        1 * escrowRepository.claimTimeoutPendingDeposit(21L, _, _ as Long) >> 1
        1 * escrowRepository.claimTimeoutPendingDeposit(22L, _, _ as Long) >> 1
        // first row's listing lookup BLOWS UP — must be swallowed, not abort the loop
        1 * listingRepository.findById(31L) >> { throw new RuntimeException("db blip") }
        // second row still processed: listing reverted + seller notified
        1 * listingRepository.findById(32L) >> Optional.of(good)
        1 * listingRepository.save({ Listing l -> l.status == SteamEscrowService.STATUS_CANCELLED })
        noExceptionThrown()
    }
}
