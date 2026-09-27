package com.sboxmarket.service

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import spock.lang.Specification

/**
 * Steam MOBILE-AUTHENTICATOR TRADE HOLDS, and the orphaning they used to cause.
 *
 * ── The incident this pins ────────────────────────────────────────────────
 * {@code deposit-timeout-hours} defaulted to 24, and the timeout sweeper flipped
 * any older PENDING_DEPOSIT row to custody FAILED and its listing to CANCELLED.
 * Steam's trade hold runs 7 to 15 DAYS. The sidecar has always reported the
 * release time as {@code escrowEndsUnix} and no Groovy code read it.
 *
 * So the first real listing went: seller lists, accepts the bot's offer, the
 * item LEAVES HIS INVENTORY into Steam's hold — and at hour 24, with up to a
 * fortnight still to run, the server marked custody FAILED and cancelled the
 * listing. That undid nothing, because the item was already gone; it deleted
 * the only record of where it was. Days later the asset landed in the bot's
 * inventory matching no row, and nothing promoted it, returned it, or mentioned
 * it to anyone.
 *
 * Every test here fails against the pre-fix service. The three that matter most:
 *  - the seconds→millis conversion (a raw seconds value reads as 1970, making
 *    every hold instantly overdue and re-creating the bug behind a populated
 *    column that LOOKS handled),
 *  - the timeout being a function of the hold rather than a constant,
 *  - and the absolute rule that no row whose item has left the seller is ever
 *    marked FAILED, at any age, by any path.
 */
class SteamEscrowHoldSpec extends Specification {

    SteamEscrowService service
    SteamTradeBotService bot = Mock()
    EscrowedItemRepository escrowRepository = Mock()
    ListingRepository listingRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()

    static final long HOUR = 60L * 60L * 1000L
    static final long DAY = 24L * HOUR

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
        service.depositTimeoutHours = 24
        service.holdGraceHours = 48
        service.holdEscalationBackoffMs = 6L * HOUR
        service.returnRetryBackoffMs = 600000L
        service.returnAlertAttempts = 5
        service.depositRetryBackoffMs = 300000L
    }

    private static EscrowedItem custody(Map o = [:]) {
        new EscrowedItem(
                id: (Long) (o.id ?: 7L),
                listingId: (Long) (o.listingId ?: 10L),
                sellerUserId: (Long) (o.sellerUserId ?: 1L),
                assetId: (o.assetId ?: '555') as String,
                heldAssetId: o.heldAssetId as String,
                marketHashName: 'AK-47 | Redline',
                depositOfferId: (o.containsKey('depositOfferId') ? o.depositOfferId : '444') as String,
                custodyState: (o.custodyState ?: EscrowedItem.PENDING_DEPOSIT) as String,
                escrowEndsAt: o.escrowEndsAt as Long,
                depositAcceptedAt: o.depositAcceptedAt as Long,
                returnRequestedAt: o.returnRequestedAt as Long,
                returnAttempts: (Integer) (o.returnAttempts ?: 0),
                createdAt: (Long) (o.createdAt ?: System.currentTimeMillis()),
                updatedAt: (Long) (o.updatedAt ?: 1_000L))
    }

    // ── 1. Reading the hold: the units trap ────────────────────────────────

    def "readEscrowEndsMillis converts Steam's SECONDS to millis"() {
        given: "a hold 10 days out, as the sidecar reports it — epoch SECONDS"
        long releaseSeconds = 1_800_000_000L
        def res = SteamBotResult.success([ok: true, status: 'in_escrow', escrowEndsUnix: releaseSeconds])

        expect: "millis, not the raw seconds value"
        // Without the *1000 this returns 1_800_000_000 ms = 21 Jan 1970, i.e. a
        // hold that expired 56 years ago — so every held deposit would read as
        // instantly overdue and be failed on the first sweep tick. Same outcome
        // as having no hold support at all, but with a populated column making
        // it look handled.
        SteamEscrowService.readEscrowEndsMillis(res) == releaseSeconds * 1000L
    }

    def "readEscrowEndsMillis accepts the value as Integer or String across the JSON boundary"() {
        expect: "the sidecar's JSON shape does not change the answer"
        SteamEscrowService.readEscrowEndsMillis(
                SteamBotResult.success([ok: true, escrowEndsUnix: value])) == expected

        where:
        value                  || expected
        1_800_000_000L         || 1_800_000_000_000L
        1_800_000_000          || 1_800_000_000_000L
        '1800000000'           || 1_800_000_000_000L
    }

    def "readEscrowEndsMillis reads a missing / zero / unparseable hold as NO hold"() {
        expect: "never an epoch at the dawn of 1970"
        SteamEscrowService.readEscrowEndsMillis(res) == null

        where:
        res << [
                null,
                SteamBotResult.success([ok: true, status: 'accepted']),
                SteamBotResult.success([ok: true, escrowEndsUnix: null]),
                SteamBotResult.success([ok: true, escrowEndsUnix: 0]),
                SteamBotResult.success([ok: true, escrowEndsUnix: -1]),
                SteamBotResult.success([ok: true, escrowEndsUnix: 'not-a-number']),
        ]
    }

    // ── 2. The deadline is a function of the hold ──────────────────────────

    def "deadline: an UNHELD deposit keeps the flat 24h timeout"() {
        given:
        long created = System.currentTimeMillis() - (1L * HOUR)
        def e = custody(createdAt: created)

        expect:
        service.depositDeadlineFor(e) == created + 24L * HOUR
        !service.isDepositOverdue(e)
    }

    def "deadline: a HELD deposit is not late until its own release plus grace"() {
        given: "a 15-day Steam hold on a deposit created 3 days ago"
        long now = System.currentTimeMillis()
        long release = now + 12L * DAY
        def e = custody(createdAt: now - 3L * DAY, escrowEndsAt: release,
                depositAcceptedAt: now - 3L * DAY, custodyState: EscrowedItem.IN_ESCROW_HOLD)

        expect: "the deadline tracks Steam's release time, NOT createdAt + 24h"
        service.depositDeadlineFor(e) == release + 48L * HOUR
        // The pre-fix service would have called this overdue 2 days ago.
        !service.isDepositOverdue(e, now)
        // still not late the instant the hold releases — grace absorbs Steam's
        // own inventory propagation
        !service.isDepositOverdue(e, release + 1L * HOUR)
        // late once the grace window is genuinely spent
        service.isDepositOverdue(e, release + 49L * HOUR)
    }

    def "deadline: an ACCEPTED deposit with no known hold is NEVER late"() {
        given: "offer accepted a year ago; Steam never told us a release time"
        long now = System.currentTimeMillis()
        def e = custody(createdAt: now - 365L * DAY, depositAcceptedAt: now - 365L * DAY,
                custodyState: EscrowedItem.IN_ESCROW_HOLD)

        expect: "there is no age at which discarding the pointer to a real item is right"
        service.depositDeadlineFor(e) == Long.MAX_VALUE
        !service.isDepositOverdue(e, now)
    }

    // ── 3. Confirm learns to see a hold ────────────────────────────────────

    def "confirm: an in_escrow offer persists the hold in MILLIS and parks custody IN_ESCROW_HOLD"() {
        given:
        bot.enabled >> true
        long releaseSeconds = 1_800_000_000L
        def e = custody()
        EscrowedItem saved = null

        when:
        service.confirmDeposit(7L, [byAsset: [:], byName: [:]])

        then:
        1 * escrowRepository.findById(7L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success(
                [ok: true, status: 'in_escrow', escrowEndsUnix: releaseSeconds])
        1 * escrowRepository.save(_) >> { EscrowedItem r -> saved = r; r }

        and: "the hold is recorded in the table's units, and the item is marked gone from the seller"
        saved.escrowEndsAt == releaseSeconds * 1000L
        saved.custodyState == EscrowedItem.IN_ESCROW_HOLD
        saved.depositAcceptedAt != null

        and: "the listing is NOT made buyable — the bot cannot deliver a held item"
        0 * listingRepository.save(_)

        and: "the seller is told what is happening, with the real release date"
        1 * notificationService.safePush(1L, _, _, { String body ->
            body.contains('Steam is holding your item') && body.contains('2027')
        }, _, _)
    }

    def "confirm: a held deposit that finally lands flips IN_ESCROW_HOLD to IN_CUSTODY and activates the listing"() {
        given: "the hold expired and the asset is now in the bot's inventory"
        bot.enabled >> true
        def e = custody(custodyState: EscrowedItem.IN_ESCROW_HOLD,
                escrowEndsAt: System.currentTimeMillis() - 1L * DAY,
                depositAcceptedAt: System.currentTimeMillis() - 9L * DAY)
        def held = new Listing(id: 10L, price: new BigDecimal('100.00'), sellerName: 'S',
                status: SteamEscrowService.STATUS_PENDING_ESCROW, sellerUserId: 1L)

        when:
        service.confirmDeposit(7L, [byAsset: ['555': '555'], byName: [:]])

        then:
        // The poller must still be willing to look at an IN_ESCROW_HOLD row —
        // that is the whole point of keeping it in findPendingDeposits.
        1 * escrowRepository.findById(7L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'accepted'])
        1 * escrowRepository.save({ EscrowedItem r ->
            r.custodyState == EscrowedItem.IN_CUSTODY && r.heldAssetId == '555' })
        1 * listingRepository.findById(10L) >> Optional.of(held)
        1 * listingRepository.save({ Listing l -> l.status == SteamEscrowService.STATUS_ACTIVE })
    }

    def "confirm: a terminal offer status AFTER acceptance does NOT mark custody FAILED"() {
        given: "Steam contradicts itself about an offer whose item already left the seller"
        bot.enabled >> true
        def e = custody(custodyState: EscrowedItem.IN_ESCROW_HOLD,
                depositAcceptedAt: System.currentTimeMillis() - 2L * DAY)
        EscrowedItem saved = null

        when:
        service.confirmDeposit(7L, [byAsset: [:], byName: [:]])

        then:
        1 * escrowRepository.findById(7L) >> Optional.of(e)
        1 * bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: 'canceled'])
        1 * escrowRepository.save(_) >> { EscrowedItem r -> saved = r; r }

        and: "the row keeps pointing at the item rather than being thrown away"
        saved.custodyState != EscrowedItem.FAILED
        saved.custodyState == EscrowedItem.IN_ESCROW_HOLD
    }

    // ── 4. The sweeper can no longer orphan an item ────────────────────────

    def "sweep: a deposit inside a live Steam hold is NEVER failed or cancelled"() {
        given: "hour 24 of a 15-day hold — the exact moment the old sweeper fired"
        bot.enabled >> true
        long now = System.currentTimeMillis()
        def heldRow = custody(id: 9L, listingId: 11L,
                custodyState: EscrowedItem.IN_ESCROW_HOLD,
                createdAt: now - 25L * HOUR,
                depositAcceptedAt: now - 24L * HOUR,
                escrowEndsAt: now + 14L * DAY)

        when: "the row somehow reaches the sweeper anyway (stale read, hand-edited row)"
        service.sweepStalePendingDeposits()

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [heldRow]

        and: "no claim, no FAILED, no cancelled listing, no 'we never received it' notification"
        0 * escrowRepository.claimTimeoutPendingDeposit(_, _, _)
        0 * listingRepository.save(_)
        0 * notificationService.safePush(_, _, _, _, _, _)
    }

    def "sweep: a deposit the seller ACCEPTED is never failed, even with no hold recorded"() {
        given: "accepted 30 days ago, no escrowEndsAt ever read"
        bot.enabled >> true
        long now = System.currentTimeMillis()
        def accepted = custody(id: 9L, listingId: 11L,
                createdAt: now - 30L * DAY, depositAcceptedAt: now - 30L * DAY)

        when:
        service.sweepStalePendingDeposits()

        then:
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [accepted]
        // The item has left the seller. Age is irrelevant; FAILED is how the
        // pointer to a real item gets lost.
        0 * escrowRepository.claimTimeoutPendingDeposit(_, _, _)
        0 * listingRepository.save(_)
    }

    def "sweep: an UNACCEPTED deposit past 24h is still failed and its listing cancelled"() {
        given: "the seller never touched the offer — the item is still theirs"
        bot.enabled >> true
        def stale = custody(id: 9L, listingId: 11L,
                createdAt: System.currentTimeMillis() - 48L * HOUR)
        def held = new Listing(id: 11L, price: new BigDecimal('100.00'), sellerName: 'S',
                status: SteamEscrowService.STATUS_PENDING_ESCROW, sellerUserId: 1L)

        when:
        service.sweepStalePendingDeposits()

        then: "the pre-existing, correct behaviour is preserved — giving up here costs nobody an item"
        1 * escrowRepository.findStalePendingDeposits(_ as Long, _) >> [stale]
        1 * escrowRepository.claimTimeoutPendingDeposit(9L, _, _ as Long) >> 1
        1 * listingRepository.findById(11L) >> Optional.of(held)
        1 * listingRepository.save({ Listing l -> l.status == SteamEscrowService.STATUS_CANCELLED })
        1 * notificationService.safePush(1L, _, _, _, _, _)
    }

    // ── 5. Overdue holds escalate; they never give up ──────────────────────

    def "overdue-hold sweep: escalates loudly and leaves the row IN_ESCROW_HOLD"() {
        given: "a hold that ended a week ago with no item in sight"
        bot.enabled >> true
        long now = System.currentTimeMillis()
        def stuck = custody(id: 9L, listingId: 11L,
                custodyState: EscrowedItem.IN_ESCROW_HOLD,
                depositAcceptedAt: now - 20L * DAY,
                escrowEndsAt: now - 7L * DAY,
                updatedAt: now - 10L * DAY)

        when:
        service.sweepOverdueEscrowHolds()

        then:
        1 * escrowRepository.findOverdueEscrowHolds(_ as Long, _ as Long, _) >> [stuck]
        // claim is a CAS on updatedAt: it rate-limits the shouting, it does NOT
        // move the row to a give-up state
        1 * escrowRepository.claimOverdueHoldEscalation(9L, _, (now - 10L * DAY), _ as Long) >> 1
        // the seller is told the truth: still tracked, nothing lost
        1 * notificationService.safePush(1L, _, _, { String b -> b.contains("haven't received it yet") }, _, _)

        and: "nothing is failed, nothing is cancelled, no item is disowned"
        0 * escrowRepository.claimTimeoutPendingDeposit(_, _, _)
        0 * listingRepository.save(_)
    }

    def "overdue-hold sweep: a lost claim escalates nothing twice"() {
        given:
        bot.enabled >> true
        def stuck = custody(id: 9L, custodyState: EscrowedItem.IN_ESCROW_HOLD,
                escrowEndsAt: System.currentTimeMillis() - 7L * DAY)

        when:
        service.sweepOverdueEscrowHolds()

        then:
        1 * escrowRepository.findOverdueEscrowHolds(_ as Long, _ as Long, _) >> [stuck]
        1 * escrowRepository.claimOverdueHoldEscalation(9L, _, _, _ as Long) >> 0
        0 * notificationService.safePush(_, _, _, _, _, _)
    }

    def "overdue-hold sweep: inert when the bot is unconfigured"() {
        given:
        bot.enabled >> false

        when:
        service.sweepOverdueEscrowHolds()

        then:
        0 * escrowRepository.findOverdueEscrowHolds(_, _, _)
        0 * notificationService.safePush(_, _, _, _, _, _)
    }

    // ── 6. Cancelling during a hold does not strand the item ───────────────

    def "return: cancelling during a Steam hold QUEUES the return instead of dropping it"() {
        given: "the seller cancels while Steam still has the item"
        bot.enabled >> true
        long release = System.currentTimeMillis() + 9L * DAY
        def e = custody(custodyState: EscrowedItem.IN_ESCROW_HOLD,
                escrowEndsAt: release,
                depositAcceptedAt: System.currentTimeMillis() - 1L * DAY)
        EscrowedItem saved = null

        when:
        boolean sent = service.returnToSeller(10L, 'seller cancelled')

        then:
        1 * escrowRepository.findByListingId(10L) >> e
        // no offer can possibly be sent — the bot does not hold the item yet
        0 * bot.sendOffer(_, _, _)
        !sent

        and: "the ASK is recorded, which is what makes it happen later"
        1 * escrowRepository.save(_) >> { EscrowedItem r -> saved = r; r }
        saved.returnRequestedAt != null
        // Once the hold clears, confirmDeposit promotes this row to IN_CUSTODY,
        // where findPendingReturns (IN_CUSTODY AND returnRequestedAt NOT NULL)
        // picks it up and sweepPendingReturns mails it back — no new machinery.
        saved.custodyState == EscrowedItem.IN_ESCROW_HOLD

        and: "the seller is told they need do nothing"
        1 * notificationService.safePush(1L, _, _, { String b -> b.contains('as soon as the hold ends') }, _, _)
    }

    // ── 7. What the seller actually SEES ───────────────────────────────────

    def "custody view: a held listing reports the hold, the release date and a plain-English reason"() {
        given:
        bot.enabled >> true
        long release = 1_800_000_000_000L
        def e = custody(listingId: 10L, custodyState: EscrowedItem.IN_ESCROW_HOLD,
                escrowEndsAt: release, depositAcceptedAt: System.currentTimeMillis())

        when:
        def view = service.custodyViewForListings([10L])

        then:
        1 * escrowRepository.findByListingIds([10L]) >> [e]
        view[10L].inSteamHold
        view[10L].custodyState == EscrowedItem.IN_ESCROW_HOLD
        // a machine-readable instant for a countdown AND a human date
        view[10L].holdReleasesAt == release
        view[10L].holdReleasesOn.contains('2027')
        view[10L].itemLeftSeller
        // the sentence names Steam as the cause, so the seller does not read
        // "not live" as "your listing is broken"
        view[10L].message.contains('Steam is holding this item until')
        !view[10L].holdOverdue
    }

    def "custody view: a deposit the seller has not accepted asks THEM to act, not us"() {
        given:
        bot.enabled >> true
        def e = custody(listingId: 10L, custodyState: EscrowedItem.PENDING_DEPOSIT)

        when:
        def view = service.custodyViewForListings([10L])

        then:
        1 * escrowRepository.findByListingIds([10L]) >> [e]
        !view[10L].inSteamHold
        !view[10L].itemLeftSeller
        view[10L].message.contains('Waiting for you to accept')
    }

    def "custody view: never throws — a lookup failure degrades to an empty explanation"() {
        given:
        bot.enabled >> true

        when:
        def view = service.custodyViewForListings([10L])

        then:
        1 * escrowRepository.findByListingIds(_) >> { throw new RuntimeException('db blip') }
        noExceptionThrown()
        view.isEmpty()
    }
}
