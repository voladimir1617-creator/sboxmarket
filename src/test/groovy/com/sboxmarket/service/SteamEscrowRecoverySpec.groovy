package com.sboxmarket.service

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.data.domain.Pageable
import spock.lang.Specification

/**
 * The two recovery sweeps that stop a routine bot failure from becoming a
 * permanent one.
 *
 * Both gaps closed here have the same shape: a custody row lands in a state no
 * poller was looking for, so the failure is invisible and nothing ever tries
 * again. The difference is the cost. An un-sent DEPOSIT costs the seller a
 * listing. A failed RETURN costs them the ITEM — and the platform is the party
 * holding it.
 *
 * Pinned here:
 *  - returnToSeller records the return INTENT before attempting, so a failure
 *    (or a throw) leaves a durable trace; every call site is best-effort and
 *    discards the boolean, so nothing else would have.
 *  - the retry sweep NEVER touches a live for-sale listing — the thing that
 *    makes this safe is returnRequestedAt, not custody state, because
 *    IN_CUSTODY is the healthy state of every listing on the market.
 *  - a lost multi-pod claim sends no second Steam offer.
 *  - the post-claim re-load really happens: saving the stale pre-claim entity
 *    would roll the attempt counter back and loop forever.
 *  - the deposit re-request recovers the seller who pastes a missing trade URL
 *    after listing — the case that previously always lost the listing.
 */
class SteamEscrowRecoverySpec extends Specification {

    SteamEscrowService service
    SteamTradeBotService bot = Mock()
    EscrowedItemRepository escrowRepository = Mock()
    ListingRepository listingRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()

    static final String URL = 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=xyz'

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
                depositOfferId: o.depositOfferId as String,
                custodyState: (o.custodyState ?: EscrowedItem.IN_CUSTODY) as String,
                returnRequestedAt: o.returnRequestedAt as Long,
                returnAttempts: (Integer) (o.returnAttempts ?: 0),
                updatedAt: (Long) (o.updatedAt ?: 1_000L))
    }

    private void sellerHasUrl(String url = URL) {
        steamUserRepository.findById(1L) >> Optional.of(
                new SteamUser(id: 1L, steamId64: '76561190000000001', tradeUrl: url))
    }

    // ── the intent stamp ───────────────────────────────────────────────────

    def "a return that FAILS still records that the seller is owed their item back"() {
        given: 'the bot is up but rate-limited, the single most ordinary failure'
        bot.enabled >> true
        sellerHasUrl()
        def row = custody()
        escrowRepository.findByListingId(10L) >> row
        bot.sendOffer(URL, ['555'], _) >> SteamBotResult.error('RATE_LIMITED', 'too many requests')

        when:
        boolean sent = service.returnToSeller(10L, 'listing cancelled')

        then: 'the caller is told it did not happen'
        !sent

        and: 'the row stays IN_CUSTODY — the bot really does still hold the item'
        row.custodyState == EscrowedItem.IN_CUSTODY

        and: 'and it now carries the trace that makes the failure recoverable'
        row.returnRequestedAt != null
        row.lastError.contains('RATE_LIMITED')
    }

    def "the intent stamp is set ONCE — a retry must not keep pushing the clock forward"() {
        given:
        bot.enabled >> true
        sellerHasUrl()
        def row = custody(returnRequestedAt: 12_345L, returnAttempts: 2)
        escrowRepository.findByListingId(10L) >> row
        bot.sendOffer(_, _, _) >> SteamBotResult.error('NOT_READY', 'sidecar reconnecting')

        when:
        service.returnToSeller(10L)

        then: 'still the original time — otherwise the age of a stranded item is permanently understated'
        row.returnRequestedAt == 12_345L
    }

    // ── the retry sweep ────────────────────────────────────────────────────

    def "the retry sweep re-sends a failed return and completes it"() {
        given:
        bot.enabled >> true
        sellerHasUrl()
        def row = custody(returnRequestedAt: 500L, returnAttempts: 1, heldAssetId: '999')
        escrowRepository.findPendingReturns(_, _) >> [row]
        escrowRepository.claimReturnRetry(7L, 1, _) >> 1
        escrowRepository.findById(7L) >> Optional.of(row)

        when:
        service.sweepPendingReturns()

        then: 'the held asset id is what goes back, not the original seller-side id'
        1 * bot.sendOffer(URL, ['999'], _) >> SteamBotResult.success([ok: true, offerId: 'R1', status: 'sent'])

        and: 'and the item is no longer owed'
        row.custodyState == EscrowedItem.RETURNED
        row.returnOfferId == 'R1'
        row.lastError == null
    }

    def "a live for-sale listing is NEVER swept — the guard is returnRequestedAt, not custody state"() {
        given: 'the sweep asks the repository for candidates'
        bot.enabled >> true
        Long capturedCutoff = null
        escrowRepository.findPendingReturns(_, _) >> { Long cutoff, Pageable p ->
            capturedCutoff = cutoff
            // The real query filters on returnRequestedAt IS NOT NULL. A healthy
            // marketplace is ALL IN_CUSTODY rows with no return requested, so
            // the candidate set is empty. Model that honestly.
            return []
        }

        when:
        service.sweepPendingReturns()

        then: 'no Steam offer is sent to anybody'
        0 * bot.sendOffer(_, _, _)
        0 * escrowRepository.claimReturnRetry(_, _, _)

        and: 'and the cutoff really is backed off, not "now" (which would retry every tick)'
        capturedCutoff != null
        capturedCutoff <= System.currentTimeMillis() - 600000L + 5000L
    }

    def "losing the multi-pod claim sends NO second return offer"() {
        given:
        bot.enabled >> true
        sellerHasUrl()
        def row = custody(returnRequestedAt: 500L, returnAttempts: 3)
        escrowRepository.findPendingReturns(_, _) >> [row]

        and: 'a sibling pod got there first'
        escrowRepository.claimReturnRetry(7L, 3, _) >> 0

        when:
        service.sweepPendingReturns()

        then: 'we bail before touching Steam — two offers for one asset is a real mess'
        0 * bot.sendOffer(_, _, _)
        0 * escrowRepository.findById(_)
    }

    def "the sweep re-loads after the claim instead of saving the stale pre-claim entity"() {
        given: 'the in-memory row still reads the OLD attempt count'
        bot.enabled >> true
        sellerHasUrl()
        def stale = custody(returnRequestedAt: 500L, returnAttempts: 4)
        // What the bulk UPDATE actually wrote — a separate instance, as JPA
        // would hand back after the modifying query bypassed the context.
        def fresh = custody(returnRequestedAt: 500L, returnAttempts: 5)
        escrowRepository.findPendingReturns(_, _) >> [stale]
        escrowRepository.claimReturnRetry(7L, 4, _) >> 1
        escrowRepository.findById(7L) >> Optional.of(fresh)
        bot.sendOffer(_, _, _) >> SteamBotResult.error('TIMEOUT', 'request timeout')

        when:
        service.sweepPendingReturns()

        then: 'the row persisted is the FRESH one, carrying the advanced counter'
        1 * escrowRepository.save({ EscrowedItem e -> e.returnAttempts == 5 })

        and: 'the stale instance was never written back — that would undo the claim and loop forever'
        0 * escrowRepository.save({ EscrowedItem e -> e.returnAttempts == 4 })
    }

    def "escrow disabled: neither recovery sweep touches the bot"() {
        given:
        bot.enabled >> false

        when:
        service.sweepPendingReturns()
        service.sweepUnsentDeposits()

        then:
        0 * escrowRepository.findPendingReturns(_, _)
        0 * escrowRepository.findUnsentDeposits(_, _)
        0 * bot.sendOffer(_, _, _)
        0 * bot.requestItems(_, _, _)
    }

    // ── the deposit re-request sweep ───────────────────────────────────────

    def "a seller who adds their trade URL AFTER listing gets their deposit offer"() {
        given: 'a row parked with no offer id — findPendingDeposits structurally cannot see it'
        bot.enabled >> true
        def row = custody(custodyState: EscrowedItem.PENDING_DEPOSIT,
                          depositOfferId: null, updatedAt: 1_000L)
        escrowRepository.findUnsentDeposits(_, _) >> [row]

        and: 'the seller has since pasted a trade URL'
        sellerHasUrl()
        escrowRepository.claimDepositRetry(7L, 1_000L, _) >> 1
        escrowRepository.findById(7L) >> Optional.of(row)

        when:
        service.sweepUnsentDeposits()

        then: 'the bot finally asks them for the item'
        1 * bot.requestItems(URL, ['555'], _) >> SteamBotResult.success([ok: true, offerId: 'D9', status: 'sent'])

        and: 'and the row now has an offer id, so the ordinary confirm poller can see it'
        row.depositOfferId == 'D9'
        row.lastError == null
    }

    def "a seller with STILL no trade URL is not re-requested, and the row is touched so it backs off"() {
        given:
        bot.enabled >> true
        def row = custody(custodyState: EscrowedItem.PENDING_DEPOSIT, depositOfferId: null)
        escrowRepository.findUnsentDeposits(_, _) >> [row]
        steamUserRepository.findById(1L) >> Optional.of(
                new SteamUser(id: 1L, steamId64: '76561190000000001', tradeUrl: null))

        when:
        service.sweepUnsentDeposits()

        then: 'there is no address to send to — no offer, no claim'
        0 * bot.requestItems(_, _, _)
        0 * escrowRepository.claimDepositRetry(_, _, _)

        and: 'but the row is stamped so it does not spin on every single tick'
        1 * escrowRepository.save({ EscrowedItem e -> e.lastError?.contains('trade URL') })
    }

    def "losing the deposit claim sends NO duplicate offer to the seller"() {
        given:
        bot.enabled >> true
        sellerHasUrl()
        def row = custody(custodyState: EscrowedItem.PENDING_DEPOSIT,
                          depositOfferId: null, updatedAt: 1_000L)
        escrowRepository.findUnsentDeposits(_, _) >> [row]
        escrowRepository.claimDepositRetry(7L, 1_000L, _) >> 0

        and: '''findById IS stubbed, deliberately. Without this stub the mock
                returns null, `.orElse(null)` throws NPE inside the per-row
                try/catch, and requestItems is skipped because of the crash
                rather than because of the claim — so the test passed even with
                the claim check deleted. Teeth-check M7 caught that false green.
                Stubbed to a usable row, the ONLY thing standing between this
                test and a duplicate offer is the claim check itself.'''
        escrowRepository.findById(7L) >> Optional.of(row)

        when:
        service.sweepUnsentDeposits()

        then: 'one seller must not receive two identical deposit requests for one listing'
        0 * bot.requestItems(_, _, _)

        and: 'and we bail at the claim, before even re-loading the row'
        0 * escrowRepository.findById(_)
    }

    def "a sibling pod that already got an offer id stops the re-request"() {
        given: 'we won the claim, but by re-load time another pod had succeeded'
        bot.enabled >> true
        sellerHasUrl()
        def read = custody(custodyState: EscrowedItem.PENDING_DEPOSIT,
                           depositOfferId: null, updatedAt: 1_000L)
        // Still PENDING_DEPOSIT — so the custody-state half of the re-check does
        // NOT catch this. Only the depositOfferId clause does, which is exactly
        // the clause teeth-check M8 showed nothing was pinning.
        def alreadySent = custody(custodyState: EscrowedItem.PENDING_DEPOSIT,
                                  depositOfferId: 'D-SIBLING')
        escrowRepository.findUnsentDeposits(_, _) >> [read]
        escrowRepository.claimDepositRetry(7L, 1_000L, _) >> 1
        escrowRepository.findById(7L) >> Optional.of(alreadySent)

        when:
        service.sweepUnsentDeposits()

        then: 'an offer already exists — asking Steam for the same asset again is a duplicate'
        0 * bot.requestItems(_, _, _)
    }

    def "a row that raced to IN_CUSTODY between read and claim is not re-requested"() {
        given:
        bot.enabled >> true
        sellerHasUrl()
        def read = custody(custodyState: EscrowedItem.PENDING_DEPOSIT,
                           depositOfferId: null, updatedAt: 1_000L)
        // The seller accepted at the last second; the confirm poller promoted it.
        def promoted = custody(custodyState: EscrowedItem.IN_CUSTODY, depositOfferId: 'D1')
        escrowRepository.findUnsentDeposits(_, _) >> [read]
        escrowRepository.claimDepositRetry(7L, 1_000L, _) >> 1
        escrowRepository.findById(7L) >> Optional.of(promoted)

        when:
        service.sweepUnsentDeposits()

        then: 'the post-claim re-load catches it — no request for an item we already hold'
        0 * bot.requestItems(_, _, _)
    }
}
