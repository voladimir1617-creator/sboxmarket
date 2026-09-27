package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * Wave 128 multi-pod race regression pin for both
 * {@link OfferService#sweepStaleOffers} and
 * {@link OfferService#sweepOffersDueForNudge}.
 *
 * Multi-pod bug: every pod read the same `findStalePending` /
 * `findPendingDueForNudge` rows on the heartbeat. Old code then ran the
 * full fan-out (OFFER_REJECTED for stale; OFFER_RECEIVED for nudge) on
 * BOTH pods before either pod's save() landed — buyer/seller got the
 * notification TWICE. Worse for the stale sweep: a buyer/seller acting
 * on the offer (PENDING → ACCEPTED / REJECTED / CANCELLED) between
 * sweeper read and unconditional save would have been STOMPED back to
 * EXPIRED.
 *
 * Fix: claimAutoExpire + claimNudge conditional UPDATEs return 1 to
 * the winning path / 0 to the losing path (sibling pod or
 * status-changed-out-of-band).
 *
 * Same shape as waves 112, 120, 124, 125, 126, 127.
 */
class OfferServiceSweepClaimSpec extends Specification {

    OfferRepository offerRepository = Mock()
    ListingRepository listingRepository = Mock()
    NotificationService notificationService = Mock()
    BanGuard banGuard = Mock()
    TextSanitizer textSanitizer = Mock()

    OfferService service

    def setup() {
        service = new OfferService(
            offerRepository:     offerRepository,
            listingRepository:   listingRepository,
            notificationService: notificationService,
            banGuard:            banGuard,
            textSanitizer:       textSanitizer,
            autoDeclineDays:     7L
        )
    }

    private Offer makeStale(long id) {
        new Offer(
            id:          id,
            buyerUserId: 42L,
            sellerUserId: 7L,
            listingId:   100L,
            status:      'PENDING',
            amount:      new BigDecimal('25.00'),
            updatedAt:   System.currentTimeMillis() - (10L * 24L * 60L * 60L * 1000L)
        )
    }

    private Offer makeHalfLife(long id) {
        new Offer(
            id:          id,
            buyerUserId: 42L,
            sellerUserId: 7L,
            listingId:   100L,
            status:      'PENDING',
            amount:      new BigDecimal('25.00'),
            sellerNudgedAt: null,
            updatedAt:   System.currentTimeMillis() - (5L * 24L * 60L * 60L * 1000L)
        )
    }

    // ── (1) sweepStaleOffers winning claim → OFFER_REJECTED fires ─────────

    def "sweepStaleOffers fires OFFER_REJECTED when claimAutoExpire returns 1"() {
        given:
        def offer = makeStale(1001L)
        offerRepository.findStalePending(_) >> [offer]
        offerRepository.claimAutoExpire(1001L, _) >> 1
        listingRepository.findAllById(_) >> []

        when:
        service.sweepStaleOffers()

        then:
        1 * notificationService.push(42L, 'OFFER_REJECTED', _, _, 100L, _)
    }

    // ── (2) sweepStaleOffers losing claim → fan-out skipped ───────────────

    def "sweepStaleOffers bails on losing claim — no OFFER_REJECTED, no save"() {
        given: "buyer/seller late-acted on the offer between sweeper read and claim — status flipped PENDING → ACCEPTED"
        def offer = makeStale(2002L)
        offerRepository.findStalePending(_) >> [offer]
        offerRepository.claimAutoExpire(2002L, _) >> 0
        listingRepository.findAllById(_) >> []

        when:
        service.sweepStaleOffers()

        then: "no push — the buyer's/seller's late action stands, NOT overwritten by EXPIRED"
        0 * notificationService.push(_, _, _, _, _, _)

        and: "no redundant entity-save — would have stomped the buyer's/seller's freshly-flipped status"
        0 * offerRepository.save(_)
    }

    // ── (3) sweepStaleOffers does NOT call save() on winning claim ────────

    def "sweepStaleOffers does NOT call save() on winning claim either — the claim UPDATE handles persistence"() {
        given:
        def offer = makeStale(3003L)
        offerRepository.findStalePending(_) >> [offer]
        offerRepository.claimAutoExpire(3003L, _) >> 1
        listingRepository.findAllById(_) >> []

        when:
        service.sweepStaleOffers()

        then: "redundant save() would dirty-flush every column and race a concurrent action"
        0 * offerRepository.save(_)
    }

    // ── (4) sweepOffersDueForNudge winning claim → OFFER_RECEIVED fires ──

    def "sweepOffersDueForNudge fires OFFER_RECEIVED when claimNudge returns 1"() {
        given:
        def offer = makeHalfLife(4004L)
        offerRepository.findPendingDueForNudge(_, _) >> [offer]
        offerRepository.claimNudge(4004L, _) >> 1
        listingRepository.findAllById(_) >> []

        when:
        service.sweepOffersDueForNudge()

        then: "seller (id=7) sees the half-life nudge exactly once"
        1 * notificationService.push(7L, 'OFFER_RECEIVED', _, _, 100L, _)
    }

    // ── (5) sweepOffersDueForNudge losing claim → fan-out skipped ─────────

    def "sweepOffersDueForNudge bails on losing claim — no OFFER_RECEIVED, no save"() {
        given:
        def offer = makeHalfLife(5005L)
        offerRepository.findPendingDueForNudge(_, _) >> [offer]
        offerRepository.claimNudge(5005L, _) >> 0
        listingRepository.findAllById(_) >> []

        when:
        service.sweepOffersDueForNudge()

        then: "sibling pod already nudged this seller — we MUST not double-ping"
        0 * notificationService.push(_, _, _, _, _, _)
        0 * offerRepository.save(_)
    }

    // ── (6) Mixed batch — only won claims fire ────────────────────────────

    def "sweepStaleOffers in a 4-row batch with 2 won claims fires exactly 2 OFFER_REJECTED pushes"() {
        given:
        def offers = (6001L..6004L).collect { makeStale(it) }
        offerRepository.findStalePending(_) >> offers
        offerRepository.claimAutoExpire(6001L, _) >> 1
        offerRepository.claimAutoExpire(6002L, _) >> 0
        offerRepository.claimAutoExpire(6003L, _) >> 1
        offerRepository.claimAutoExpire(6004L, _) >> 0
        listingRepository.findAllById(_) >> []

        when:
        service.sweepStaleOffers()

        then:
        2 * notificationService.push(_, 'OFFER_REJECTED', _, _, _, _)
    }
}
