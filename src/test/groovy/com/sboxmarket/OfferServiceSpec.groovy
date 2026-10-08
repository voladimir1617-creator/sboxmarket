package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.OfferNotPendingException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the offer / counter-offer lifecycle.
 *
 * Branches covered: makeOffer validation (zero/above asking/self-listing),
 * counterOffer guard rails (must be higher, must not exceed asking, seller-
 * only), accept path (price swap, insufficient balance rollback, expire
 * competing offers), reject/cancel ownership checks. Purchase is mocked —
 * the monetary transaction itself is the job of PurchaseServiceSpec.
 */
class OfferServiceSpec extends Specification {

    OfferRepository     offerRepository     = Mock()
    ListingRepository   listingRepository   = Mock()
    WalletRepository    walletRepository    = Mock()
    SteamUserRepository steamUserRepository = Mock()
    PurchaseService     purchaseService     = Mock()
    BanGuard            banGuard            = Mock()
    TextSanitizer       textSanitizer       = Mock() {
        cleanShort(_) >> { String s -> s }
        // The message-sanitisation path on makeOffer + buyerRaise was
        // bumped from cleanShort (80-char cap) to clean(message, 280)
        // so a buyer note up to MESSAGE_MAX_LEN survives intact instead
        // of getting silently chopped to 80.
        clean(_, _) >> { String s, int n -> s }
    }
    NotificationService notificationService = Mock()

    @Subject
    OfferService service = new OfferService(
        offerRepository     : offerRepository,
        listingRepository   : listingRepository,
        walletRepository    : walletRepository,
        steamUserRepository : steamUserRepository,
        purchaseService     : purchaseService,
        banGuard            : banGuard,
        textSanitizer       : textSanitizer,
        notificationService : notificationService
    )

    private Listing activeListing(Map args = [:]) {
        def item = new Item(id: 1L, name: 'Wizard Hat', imageUrl: 'https://example.com/x.png')
        new Listing(
            id:           args.id ?: 100L,
            item:         item,
            price:        args.price ?: new BigDecimal("50.00"),
            sellerUserId: args.seller ?: 99L,
            status:       args.status ?: 'ACTIVE',
            maxDiscount:  args.maxDiscount
        )
    }

    // ── makeOffer ─────────────────────────────────────────────────

    def "makeOffer creates a PENDING row for a valid buyer"() {
        given:
        listingRepository.findById(100L) >> Optional.of(activeListing())
        offerRepository.save(_) >> { Offer o -> o.id = 1L; o }

        when:
        def offer = service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40"))

        then:
        1 * banGuard.assertNotBanned(10L)
        offer.status == 'PENDING'
        offer.amount == new BigDecimal("40")
        offer.buyerName == 'Alice'
        offer.askingPrice == new BigDecimal("50.00")
    }

    def "makeOffer normalizes a sub-cent amount to whole cents (HALF_UP)"() {
        // The CreateOfferRequest DTO caps magnitude but not scale, so a
        // {"amount": 40.999} body reaches the service at 3dp. It must round
        // to whole cents before persist (the value becomes listing.price on
        // accept) so the stored/charged amount is 2dp-clean and every floor/
        // below-ask guard agrees with what persists — mirrors the deposit/
        // withdraw boundary-normalization pinned in StripeServiceSpec.
        given:
        listingRepository.findById(100L) >> Optional.of(activeListing())
        offerRepository.save(_) >> { Offer o -> o.id = 1L; o }

        when:
        def offer = service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40.999"))

        then: 'HALF_UP → 41.00 at scale 2'
        offer.amount == new BigDecimal("41.00")
        offer.amount.scale() == 2
    }

    def "makeOffer refuses a buyer whose wallet is frozen (batch 510)"() {
        given:
        def buyer = new SteamUser(id: 10L, steamId64: '7656117', displayName: 'Alice')
        def frozenWallet = new Wallet(
            id: 500L,
            username: 'steam_7656117',
            balance: new BigDecimal('100.00'),
            frozen: true,
            frozenReason: 'Staff-initiated freeze during fraud review'
        )
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_7656117') >> frozenWallet
        // listing lookup shouldn't even be reached if the check fires first
        listingRepository.findById(_) >> Optional.of(activeListing())

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal('40'))

        then:
        def e = thrown(BadRequestException)
        e.code == 'WALLET_FROZEN'
    }

    def "makeOffer rejects zero/negative/null amounts"() {
        when:
        service.makeOffer(10L, 'Alice', 100L, amount)

        then:
        thrown(BadRequestException)

        where:
        amount << [null, BigDecimal.ZERO, new BigDecimal("-1")]
    }

    def "makeOffer 404s for unknown listing"() {
        given:
        listingRepository.findById(_) >> Optional.empty()

        when:
        service.makeOffer(10L, 'Alice', 999L, new BigDecimal("40"))

        then:
        thrown(NotFoundException)
    }

    def "makeOffer refuses sold listings"() {
        given:
        listingRepository.findById(_) >> Optional.of(activeListing(status: 'SOLD'))

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40"))

        then:
        thrown(ListingNotAvailableException)
    }

    def "makeOffer blocks self-offers (buyer == seller)"() {
        given:
        listingRepository.findById(_) >> Optional.of(activeListing(seller: 10L))

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40"))

        then:
        thrown(ForbiddenException)
    }

    def "makeOffer refuses offers at or above the asking price"() {
        given:
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal("50")))

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("55"))

        then:
        thrown(BadRequestException)
    }

    def "makeOffer refuses hidden listings (batch 308 bug fix)"() {
        // A stale client could POST /api/offers with a listing id
        // that's been hidden since the modal rendered. Must 404-style
        // reject so the seller doesn't get pings for items they pulled.
        given:
        def hidden = activeListing()
        hidden.hidden = true
        listingRepository.findById(_) >> Optional.of(hidden)

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40"))

        then:
        thrown(ListingNotAvailableException)
    }

    def "makeOffer refuses AUCTION listings (bug #54)"() {
        given:
        def auction = activeListing()
        auction.listingType = 'AUCTION'
        listingRepository.findById(_) >> Optional.of(auction)

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("40"))

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NOT_BUY_NOW'
        0 * offerRepository.save(_)
    }

    // ── counterOffer ──────────────────────────────────────────────

    private Offer pendingOffer(Map args = [:]) {
        new Offer(
            id:            args.id ?: 1L,
            listingId:     args.listing ?: 100L,
            buyerUserId:   args.buyer ?: 10L,
            sellerUserId:  args.seller ?: 99L,
            amount:        args.amount ?: new BigDecimal("30"),
            askingPrice:   new BigDecimal("50"),
            status:        args.status ?: 'PENDING',
            author:        'USER'
        )
    }

    def "counterOffer creates a linked counter from the seller"() {
        given:
        def original = pendingOffer()
        offerRepository.findById(1L) >> Optional.of(original)
        listingRepository.findById(100L) >> Optional.of(activeListing())
        offerRepository.save(_) >> { Offer o -> o }

        when:
        def counter = service.counterOffer(99L, 1L, new BigDecimal("40"))

        then:
        counter.parentOfferId == 1L
        counter.author == 'SELLER'
        counter.amount == new BigDecimal("40")
        counter.status == 'PENDING'
    }

    def "counterOffer refuses counters lower than or equal to the buyer's offer"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(amount: new BigDecimal("30")))
        listingRepository.findById(_) >> Optional.of(activeListing())

        when:
        service.counterOffer(99L, 1L, new BigDecimal("30"))

        then:
        thrown(BadRequestException)
    }

    def "counterOffer refuses counters higher than the asking price"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal("50")))

        when:
        service.counterOffer(99L, 1L, new BigDecimal("60"))

        then:
        thrown(BadRequestException)
    }

    def "counterOffer forbids non-seller authors"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())

        when:
        service.counterOffer(77L, 1L, new BigDecimal("40"))

        then:
        thrown(ForbiddenException)
    }

    def "counterOffer refuses offers that aren't PENDING"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(status: 'ACCEPTED'))

        when:
        service.counterOffer(99L, 1L, new BigDecimal("40"))

        then:
        thrown(OfferNotPendingException)
    }

    def "counterOffer preserves a 100-char message instead of silently chopping to 80 (4cc7288 sibling)"() {
        // Pre-fix used textSanitizer.cleanShort which caps at LIMIT_SHORT=80;
        // a 100-char message survived the length() > MESSAGE_MAX_LEN (280)
        // guard but was silently truncated to 80. Counter must persist the
        // full message intact (sanitiser called with 280 cap, not 80).
        given:
        def original = pendingOffer()
        offerRepository.findById(1L) >> Optional.of(original)
        listingRepository.findById(100L) >> Optional.of(activeListing())
        offerRepository.save(_) >> { Offer o -> o }
        String msg = 'x' * 100

        when:
        def counter = service.counterOffer(99L, 1L, new BigDecimal('40'), msg)

        then:
        1 * textSanitizer.clean(msg, 280) >> msg
        0 * textSanitizer.cleanShort(_)
        counter.message == msg
        counter.message.length() == 100
    }

    def "counterOffer refuses counters on a listing that's no longer ACTIVE"() {
        // Listing sold via direct Buy Now after the offer landed — the
        // seller shouldn't be able to mint a dead PENDING counter the
        // buyer is then invited to accept.
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())
        listingRepository.findById(_) >> Optional.of(activeListing(status: 'SOLD'))

        when:
        service.counterOffer(99L, 1L, new BigDecimal("40"))

        then:
        thrown(ListingNotAvailableException)
        0 * offerRepository.save(_)
    }

    // ── buyerRaise ────────────────────────────────────────────────

    def "buyerRaise cancels the original and opens a new PENDING at the higher amount"() {
        given:
        def original = pendingOffer()  // buyer 10L at $30, ask $50
        offerRepository.findById(1L) >> Optional.of(original)
        listingRepository.findById(100L) >> Optional.of(activeListing(price: new BigDecimal("50")))
        offerRepository.save(_) >> { Offer o -> o }

        when:
        def raised = service.buyerRaise(10L, 1L, new BigDecimal("40"))

        then:
        1 * banGuard.assertNotBanned(10L)
        original.status == 'CANCELLED'
        raised.parentOfferId == 1L
        raised.author == 'USER'
        raised.amount == new BigDecimal("40")
        raised.status == 'PENDING'
        raised.buyerUserId == 10L
        raised.sellerUserId == 99L
    }

    def "buyerRaise refuses amounts less than or equal to the original"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(amount: new BigDecimal("30")))

        when:
        service.buyerRaise(10L, 1L, new BigDecimal("30"))

        then:
        thrown(BadRequestException)
    }

    def "buyerRaise refuses amounts at or above the asking price"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal("50")))

        when:
        service.buyerRaise(10L, 1L, new BigDecimal("50"))

        then:
        thrown(BadRequestException)
    }

    def "buyerRaise forbids raises from someone other than the buyer"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())

        when:
        service.buyerRaise(77L, 1L, new BigDecimal("40"))

        then:
        thrown(ForbiddenException)
    }

    def "buyerRaise refuses offers that aren't PENDING"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(status: 'ACCEPTED'))

        when:
        service.buyerRaise(10L, 1L, new BigDecimal("40"))

        then:
        thrown(OfferNotPendingException)
    }

    def "buyerRaise refuses a raise on a listing that's no longer ACTIVE"() {
        // Raising on a SOLD listing would cancel the buyer's existing
        // PENDING offer and open a fresh one that can never close —
        // leaving the buyer worse off. Reject before any state mutation.
        given:
        def original = pendingOffer(amount: new BigDecimal("30"))
        offerRepository.findById(_) >> Optional.of(original)
        listingRepository.findById(_) >> Optional.of(activeListing(status: 'SOLD'))

        when:
        service.buyerRaise(10L, 1L, new BigDecimal("40"))

        then:
        thrown(ListingNotAvailableException)
        original.status == 'PENDING'   // original left intact
        0 * offerRepository.save(_)
    }

    def "buyerRaise notifies the seller of the new amount"() {
        given:
        def original = pendingOffer(amount: new BigDecimal("25"))
        offerRepository.findById(_) >> Optional.of(original)
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal("50")))
        offerRepository.save(_) >> { Offer o -> o.id = o.id ?: 2L; o }

        when:
        service.buyerRaise(10L, 1L, new BigDecimal("40"))

        then:
        1 * notificationService.push(99L, 'OFFER_RECEIVED', _, _, _, '/offers')
    }

    def "buyerRaise auto-accepts when the raise crosses listing.maxDiscount"() {
        // Regression: makeOffer honoured listing.maxDiscount (auto-accept
        // any offer >= price * (1 - maxDiscount)), but buyerRaise did
        // not — a raise that crossed the threshold sat PENDING waiting
        // for manual seller action, contradicting the seller's stated
        // intent. Fix: tryAutoAccept is now called on both code paths.
        given:
        def original = pendingOffer(amount: new BigDecimal("30"))
        // Threshold = 50 - 50*0.10 = 45.00. Raise of $46 crosses it.
        def listing = activeListing(price: new BigDecimal("50"),
                                    maxDiscount: new BigDecimal("0.10"))
        // The raise stores a fresh PENDING row via save; we capture it
        // so acceptOffer (called from tryAutoAccept) finds it on its
        // own findById(2L) lookup. Same instance also satisfies the
        // post-purchase reload at the end of tryAutoAccept.
        Offer raisedRow = null
        offerRepository.save(_) >> { Offer o ->
            o.id = o.id ?: 2L
            if (o.parentOfferId != null) raisedRow = o
            o
        }
        offerRepository.findById(1L) >> Optional.of(original)
        offerRepository.findById(2L) >> { Optional.ofNullable(raisedRow) }
        listingRepository.findById(100L) >> Optional.of(listing)
        // acceptOffer wiring — buyer wallet must cover the raise.
        def buyer = new SteamUser(id: 10L, steamId64: '7656117', displayName: 'Alice')
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_7656117') >> new Wallet(
            id: 500L, username: 'steam_7656117',
            balance: new BigDecimal('100.00'))

        when:
        def raised = service.buyerRaise(10L, 1L, new BigDecimal("46"))

        then:
        // Auto-accept path fired — the raise row was flipped to
        // ACCEPTED by acceptOffer, and the OFFER_RECEIVED ping is
        // suppressed (the purchase flow does its own seller
        // notification + receipt).
        raised.status == 'ACCEPTED'
        0 * notificationService.push(_, 'OFFER_RECEIVED', _, _, _, _)
        1 * purchaseService.buy(500L, 10L, 100L)
    }

    // ── sweepStaleOffers ──────────────────────────────────────────

    def "sweepStaleOffers flips stale offers to EXPIRED, not CANCELLED (batch 129 bug)"() {
        given:
        def stale = pendingOffer(id: 1L, amount: new BigDecimal("20"))
        offerRepository.findStalePending(_) >> [stale]

        when:
        service.sweepStaleOffers()

        then:
        // EXPIRED is the seller-side "didn't respond" status — counts
        // in countSellerEngagedTotal's denominator. CANCELLED would
        // hide this from the seller's response-rate stat and silently
        // inflate it. The PENDING→EXPIRED flip is now persisted atomically
        // by the claimAutoExpire conditional UPDATE (wave 128), not an
        // entity save — winning the claim (returns 1) gates the fan-out.
        1 * offerRepository.claimAutoExpire(1L, _) >> 1
        1 * notificationService.push(10L, 'OFFER_REJECTED', _, _, _, '/offers')
    }

    def "sweepStaleOffers is a no-op when nothing is stale"() {
        given:
        offerRepository.findStalePending(_) >> []

        when:
        service.sweepStaleOffers()

        then:
        0 * offerRepository.save(_)
        0 * notificationService.push(*_)
    }

    def "sweepStaleOffers isolates per-row save failures — one bad offer never poisons sibling EXPIRED flips + pushes"() {
        // Sixth instance of the rollback-only-leak family (commits 443f910,
        // 526a5f4, d3a3df7, ebc1b45, fe48e76). Before the fix, sweepStaleOffers
        // was @Transactional. A mid-batch offer save() that threw (e.g. an
        // OptimisticLockingFailureException because a concurrent buyer cancel
        // or seller accept landed on row #1) marked the SHARED outer tx as
        // rollback-only via Spring Data's save proxy. The per-row try/catch
        // swallowed the throw, sweepStaleOffers returned normally — but the
        // outer @Transactional commit then raised UnexpectedRollbackException
        // and every prior EXPIRED stamp + OFFER_REJECTED push silently
        // reverted. The next 6h tick re-found the same offers and re-fired
        // duplicate auto-decline notifications to buyers we'd already pinged.
        // Per-row auto-commit (no outer @Transactional) must isolate row #1
        // so row #2's flip + push survives.
        given:
        def a = pendingOffer(id: 1L, amount: new BigDecimal("20"))
        def b = pendingOffer(id: 2L, amount: new BigDecimal("30"))
        offerRepository.findStalePending(_) >> [a, b]
        // Row #1's atomic auto-expire claim throws (concurrent buyer cancel
        // / seller accept landing on it, or a DB blip); row #2's claim wins.
        // The sweep no longer calls offerRepository.save(offer) — the
        // claimAutoExpire conditional UPDATE is the persistence point, so
        // the per-row catch around the claim is what isolates the failure.
        offerRepository.claimAutoExpire(1L, _) >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                'Offer', 1L)
        }

        when:
        service.sweepStaleOffers()

        then: "sibling row #2 still wins its claim — not blocked by row #1's failure"
        1 * offerRepository.claimAutoExpire(2L, _) >> 1
        and: "sibling row's buyer push still fires — sweep didn't abort (only row #2 reaches the push)"
        1 * notificationService.push(10L, 'OFFER_REJECTED', _, _, 100L, '/offers')
        and: "no exception bubbles out — bad row was swallowed in the per-row catch"
        noExceptionThrown()
    }

    def "sweepOffersDueForNudge isolates per-row save failures — one bad nudge stamp never poisons sibling stamps + pings"() {
        // Same bug class as sweepStaleOffers above — the half-life nudge sweeper
        // ALSO carried the outer @Transactional + per-row save antipattern. A
        // mid-batch offer save() throw (e.g. concurrent seller acceptance
        // landing on row #1 during the nudge) marked the shared outer tx
        // rollback-only, and on commit every prior sellerNudgedAt stamp +
        // OFFER_RECEIVED ping reverted → the next 3h tick re-finds the same
        // offers and re-fires duplicate nudges to sellers we'd already pinged
        // (defeating the whole point of the sellerNudgedAt idempotency field).
        given:
        service.autoDeclineDays = 14L
        // Distinct seller ids so the per-row push assertions are
        // unambiguous (the default pendingOffer helper uses sellerUserId=99
        // for every row, which would collapse two pushes into one
        // cardinality bucket).
        def a = pendingOffer(id: 1L, listing: 100L, seller: 91L, amount: new BigDecimal("20"))
        def b = pendingOffer(id: 2L, listing: 101L, seller: 92L, amount: new BigDecimal("30"))
        a.updatedAt = System.currentTimeMillis() - (8L * 24L * 60L * 60L * 1000L)
        b.updatedAt = System.currentTimeMillis() - (8L * 24L * 60L * 60L * 1000L)
        offerRepository.findPendingDueForNudge(_, _) >> [a, b]
        // Row #1's atomic nudge claim throws (concurrent seller acceptance
        // landing on it, or a DB blip); row #2's claim wins. The sweep no
        // longer calls offerRepository.save(offer) — claimNudge stamps
        // sellerNudgedAt via a conditional UPDATE, so the per-row catch
        // around the claim is what isolates the failure.
        offerRepository.claimNudge(1L, _) >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                'Offer', 1L)
        }

        when:
        service.sweepOffersDueForNudge()

        then: "sibling row #2 still wins its nudge claim — not blocked by row #1's failure"
        1 * offerRepository.claimNudge(2L, _) >> 1
        and: "sibling row's seller ping still fires — sweep didn't abort"
        1 * notificationService.push(92L, 'OFFER_RECEIVED', _, _, 101L, _)
        and: "row #1's failed claim fired no seller ping — swallowed in the per-row catch"
        0 * notificationService.push(91L, 'OFFER_RECEIVED', _, _, 100L, _)
        and: "no exception bubbles out — bad row was swallowed in the per-row catch"
        noExceptionThrown()
    }

    def "counterOffer blocks any user from countering on a system listing (bug #53)"() {
        given:
        def original = pendingOffer(seller: null)
        offerRepository.findById(1L) >> Optional.of(original)

        when:
        service.counterOffer(77L, 1L, new BigDecimal("40"))

        then:
        thrown(ForbiddenException)
        0 * offerRepository.save(_)
    }

    def "rejectOffer blocks any user from rejecting on a system listing (bug #53)"() {
        given:
        def offer = pendingOffer(seller: null)
        offerRepository.findById(1L) >> Optional.of(offer)

        when:
        service.rejectOffer(77L, 1L)

        then:
        thrown(ForbiddenException)
        0 * offerRepository.save(_)
    }

    // ── acceptOffer ───────────────────────────────────────────────

    def "acceptOffer runs a purchase at the offer price and marks ACCEPTED"() {
        given:
        def offer   = pendingOffer(amount: new BigDecimal("40"))
        def listing = activeListing(price: new BigDecimal("50"))
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("500"))
        offerRepository.findById(1L) >> Optional.of(offer)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        offerRepository.findPendingForListing(100L) >> []
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.acceptOffer(99L, 1L)

        then:
        1 * purchaseService.buy(500L, 10L, 100L)
        result.accepted == true
        result.finalPrice == new BigDecimal("40")
        offer.status == 'ACCEPTED'
    }

    def "acceptOffer flips offer to EXPIRED when buyer's wallet is short"() {
        given:
        def offer   = pendingOffer(amount: new BigDecimal("40"))
        def listing = activeListing()
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("10"))
        offerRepository.findById(1L) >> Optional.of(offer)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet

        when:
        service.acceptOffer(99L, 1L)

        then:
        thrown(InsufficientBalanceException)
        1 * offerRepository.save({ Offer o -> o.status == 'EXPIRED' })
        0 * purchaseService.buy(*_)
        // Buyer is pinged so their Offers tab doesn't show a silent
        // EXPIRED label (batch 328). Title mentions the item; body
        // guides them to top up + re-offer.
        1 * notificationService.push(10L, 'OFFER_REJECTED',
            { String title -> title.contains("couldn't close") },
            { String body -> body.contains('Top up') },
            1L, '/offers')
    }

    def "acceptOffer flips offer to EXPIRED when the listing went SOLD between offer + accept"() {
        // Regression for the second-pass noRollbackFor entry on acceptOffer
        // (ListingNotAvailableException). Without it, the `offer.status =
        // 'EXPIRED'` save above the throw gets rolled back by Spring's
        // transactional proxy — the offer stays stuck PENDING against a
        // dead listing, so every subsequent seller retry hits the same
        // dead branch and never clears the row. This spec pins the
        // contract: the EXPIRED save must reach the repository even
        // though the method exits via ListingNotAvailableException.
        given:
        def offer   = pendingOffer(amount: new BigDecimal("40"))
        // Listing flipped to SOLD via direct Buy-Now after the offer
        // landed but before the seller clicked Accept.
        def listing = activeListing(status: 'SOLD')
        offerRepository.findById(1L) >> Optional.of(offer)
        listingRepository.findById(100L) >> Optional.of(listing)

        when:
        service.acceptOffer(99L, 1L)

        then:
        thrown(ListingNotAvailableException)
        // The EXPIRED transition must persist despite the throw — exactly
        // what the `noRollbackFor = [..., ListingNotAvailableException]`
        // annotation buys us. Without it, the offer is silently stuck
        // PENDING forever.
        1 * offerRepository.save({ Offer o -> o.status == 'EXPIRED' })
        0 * purchaseService.buy(*_)
    }

    def "acceptOffer flips offer to EXPIRED when buyer got banned between offer + accept"() {
        // P1 bug — pre-fix, if a buyer was banned after their offer
        // landed but before the seller clicked Accept, the seller's
        // accept would invoke purchaseService.buy which calls
        // banGuard.assertNotBanned(buyerUserId) → ForbiddenException.
        // Because acceptOffer's `noRollbackFor` only covered
        // InsufficientBalanceException + ListingNotAvailableException,
        // ForbiddenException rolled the whole tx back — the offer never
        // stamped EXPIRED, stayed PENDING forever in the seller's
        // incoming queue, every retry hit the same ban, and the buyer
        // was never told their offer was lost to moderation action.
        // Post-fix: the buyer-ban check runs upfront, EXPIRED persists,
        // the buyer is notified, and the seller's queue drains.
        given:
        def offer   = pendingOffer(amount: new BigDecimal("40"))
        def listing = activeListing()
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("500"))
        offerRepository.findById(1L) >> Optional.of(offer)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        // Seller is callerUserId — assertNotBanned at the top of
        // acceptOffer isn't called (that's a makeOffer guard, not an
        // accept guard) so banGuard.isBanned(10L) is the relevant probe.
        banGuard.isBanned(10L) >> true

        when:
        service.acceptOffer(99L, 1L)

        then:
        thrown(ForbiddenException)
        // The EXPIRED transition must persist despite the throw — the
        // matching `noRollbackFor = [..., ForbiddenException]` on
        // acceptOffer is what buys us that durability.
        1 * offerRepository.save({ Offer o -> o.status == 'EXPIRED' })
        // PurchaseService never runs — we caught the ban before paying.
        0 * purchaseService.buy(*_)
        // Buyer is pinged so they learn the offer was lost to the ban
        // (otherwise their Offers tab just shows a silent EXPIRED row).
        1 * notificationService.push(10L, 'OFFER_REJECTED',
            { String title -> title.contains("couldn't close") },
            { String body -> body.contains('restricted') },
            1L, '/offers')
    }

    def "acceptOffer expires other pending offers on the same listing after a successful sale"() {
        given:
        def offer   = pendingOffer(amount: new BigDecimal("40"))
        def competing = pendingOffer(id: 2L, amount: new BigDecimal("35"))
        def listing = activeListing(price: new BigDecimal("50"))
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("500"))
        offerRepository.findById(1L) >> Optional.of(offer)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        offerRepository.findPendingForListing(100L) >> [competing]
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        service.acceptOffer(99L, 1L)

        then:
        1 * purchaseService.buy(*_)
        1 * offerRepository.save({ Offer o -> o.id == 2L && o.status == 'EXPIRED' })
    }

    def "acceptOffer 404s on unknown offer id"() {
        given:
        offerRepository.findById(_) >> Optional.empty()

        when:
        service.acceptOffer(99L, 999L)

        then:
        thrown(NotFoundException)
    }

    def "acceptOffer refuses offers that aren't PENDING"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(status: 'CANCELLED'))

        when:
        service.acceptOffer(99L, 1L)

        then:
        thrown(OfferNotPendingException)
    }

    def "acceptOffer forbids the seller accepting their own SELLER-authored counter"() {
        // A SELLER counter carries sellerUserId = the seller. Without the
        // author='USER' guard on the seller-accept path, the seller could
        // POST /accept on their own counter and force-charge the buyer's
        // wallet for a price the buyer never agreed to. The counter must
        // only ever be accepted by the buyer.
        given:
        def counter = new Offer(
            id: 5L, listingId: 100L, buyerUserId: 10L, sellerUserId: 99L,
            amount: new BigDecimal("45"), askingPrice: new BigDecimal("50"),
            status: 'PENDING', author: 'SELLER')
        offerRepository.findById(5L) >> Optional.of(counter)

        when: 'the seller (99L) tries to accept their own counter'
        service.acceptOffer(99L, 5L)

        then:
        thrown(ForbiddenException)
        0 * purchaseService.buy(*_)
    }

    def "acceptOffer lets the buyer accept a SELLER-authored counter"() {
        given:
        def counter = new Offer(
            id: 5L, listingId: 100L, buyerUserId: 10L, sellerUserId: 99L,
            amount: new BigDecimal("45"), askingPrice: new BigDecimal("50"),
            status: 'PENDING', author: 'SELLER')
        def listing = activeListing(price: new BigDecimal("50"))
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("500"))
        offerRepository.findById(5L) >> Optional.of(counter)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        offerRepository.findPendingForListing(100L) >> []
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }

        when: 'the buyer (10L) accepts the seller counter'
        def result = service.acceptOffer(10L, 5L)

        then:
        1 * purchaseService.buy(500L, 10L, 100L)
        result.accepted == true
        counter.status == 'ACCEPTED'
    }

    def "acceptOffer on a SELLER counter closes the buyer's COUNTERED original (P1 bug fix)"() {
        // Accepting the counter is the counter's HAPPY-PATH terminal
        // state — yet it was the one terminal transition that never ran
        // closeCounteredParent (reject / cancel / sweep / buyer-raise
        // all did). Left uncleaned, the buyer's original stays COUNTERED,
        // which findLiveByBuyerAndListing still treats as live — so the
        // ItemModal "You offered $X" chip shows a live offer on a listing
        // the buyer has already bought.
        given:
        def original = pendingOffer(id: 1L, status: 'COUNTERED')
        def counter  = new Offer(
            id: 5L, listingId: 100L, buyerUserId: 10L, sellerUserId: 99L,
            amount: new BigDecimal("45"), askingPrice: new BigDecimal("50"),
            status: 'PENDING', author: 'SELLER', parentOfferId: 1L)
        def listing = activeListing(price: new BigDecimal("50"))
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("500"))
        offerRepository.findById(5L) >> Optional.of(counter)
        offerRepository.findById(1L) >> Optional.of(original)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        offerRepository.findPendingForListing(100L) >> []
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }

        when: 'the buyer accepts the seller counter'
        def result = service.acceptOffer(10L, 5L)

        then:
        result.accepted == true
        counter.status == 'ACCEPTED'
        // The buyer's COUNTERED original is closed — CLOSED is inert to
        // findLiveByBuyerAndListing, so no phantom live offer survives.
        original.status == 'CLOSED'
    }

    def "acceptOffer expiring a competing SELLER counter also closes that counter's COUNTERED parent (P1 bug fix)"() {
        // Seller countered buyer A (A's original → COUNTERED, counter C
        // PENDING), then accepts buyer B's separate offer. The competing-
        // offer sweep expires C — but expiring C without closing A's
        // original strands A in COUNTERED forever.
        given:
        def acceptedB = pendingOffer(id: 7L, buyer: 20L, amount: new BigDecimal("40"))
        def originalA = pendingOffer(id: 1L, buyer: 10L, status: 'COUNTERED')
        def counterC  = sellerCounter(id: 2L, buyer: 10L, parent: 1L, amount: new BigDecimal("35"))
        def listing = activeListing(price: new BigDecimal("50"))
        def buyerB  = new SteamUser(id: 20L, steamId64: '222')
        def wallet  = new Wallet(id: 600L, balance: new BigDecimal("500"))
        offerRepository.findById(7L) >> Optional.of(acceptedB)
        offerRepository.findById(1L) >> Optional.of(originalA)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(20L) >> Optional.of(buyerB)
        walletRepository.findByUsername('steam_222') >> wallet
        // The competing-offer sweep finds the seller's still-PENDING
        // counter C hanging off buyer A's COUNTERED original.
        offerRepository.findPendingForListing(100L) >> [counterC]
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }

        when: "the seller accepts buyer B's offer"
        service.acceptOffer(99L, 7L)

        then:
        acceptedB.status == 'ACCEPTED'
        counterC.status == 'EXPIRED'        // competing counter swept
        originalA.status == 'CLOSED'        // its COUNTERED parent freed
    }

    def "acceptOffer EXPIRING a SELLER counter on a now-dead listing closes the COUNTERED parent (P1 bug fix)"() {
        // Race: seller counters buyer (original → COUNTERED, counter C
        // PENDING). Before the buyer accepts C, the listing is sold via
        // direct Buy Now (status → SOLD). The buyer's accept hits the
        // listing-not-ACTIVE branch which flips C to EXPIRED — but
        // pre-fix never closed C's COUNTERED parent. The original was
        // stuck COUNTERED forever, still matched findLiveByBuyerAndListing,
        // and the dup-guard locked the buyer out of every future offer on
        // that listing (or any relist). Mirrors the same dangling-node bug
        // every other terminal counter transition already handles.
        given:
        def original = pendingOffer(id: 1L, status: 'COUNTERED')
        def counter  = sellerCounter(id: 5L, parent: 1L, amount: new BigDecimal("45"))
        def sold     = activeListing(status: 'SOLD')
        offerRepository.findById(5L) >> Optional.of(counter)
        offerRepository.findById(1L) >> Optional.of(original)
        listingRepository.findById(100L) >> Optional.of(sold)
        offerRepository.save(_) >> { Offer o -> o }

        when: 'the buyer accepts the counter on a now-dead listing'
        service.acceptOffer(10L, 5L)

        then:
        thrown(ListingNotAvailableException)
        counter.status == 'EXPIRED'
        // Pre-fix: original sat in COUNTERED forever, locking the buyer
        // out via the makeOffer duplicate guard. Post-fix: CLOSED frees it.
        original.status == 'CLOSED'
        0 * purchaseService.buy(*_)
    }

    def "acceptOffer on a plain USER offer leaves the seller-accept happy path unchanged (closeCounteredParent no-op)"() {
        // Regression guard: the seller-accept happy path is unaffected by
        // the closeCounteredParent call — a root USER offer has a null
        // parentOfferId, so closeCounteredParent short-circuits before it
        // queries (or mutates) anything.
        given:
        def offer   = pendingOffer(amount: new BigDecimal("40"))  // parentOfferId null
        def listing = activeListing(price: new BigDecimal("50"))
        def buyer   = new SteamUser(id: 10L, steamId64: '111')
        def wallet  = new Wallet(id: 500L, balance: new BigDecimal("500"))
        offerRepository.findById(1L) >> Optional.of(offer)
        listingRepository.findById(100L) >> Optional.of(listing)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        walletRepository.findByUsername('steam_111') >> wallet
        offerRepository.findPendingForListing(100L) >> []
        offerRepository.save(_) >> { Offer o -> o }
        listingRepository.save(_) >> { Listing l -> l }

        when:
        def result = service.acceptOffer(99L, 1L)

        then:
        1 * purchaseService.buy(500L, 10L, 100L)
        result.accepted == true
        result.finalPrice == new BigDecimal("40")
        offer.status == 'ACCEPTED'
    }

    // ── rejectOffer / cancelOffer ─────────────────────────────────

    def "rejectOffer flips status to REJECTED for the seller"() {
        given:
        def offer = pendingOffer()
        offerRepository.findById(1L) >> Optional.of(offer)
        offerRepository.save(_) >> { Offer o -> o }

        when:
        def result = service.rejectOffer(99L, 1L)

        then:
        result.status == 'REJECTED'
    }

    def "rejectOffer forbids non-seller callers"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())

        when:
        service.rejectOffer(77L, 1L)

        then:
        thrown(ForbiddenException)
    }

    def "cancelOffer flips status to CANCELLED for the buyer"() {
        given:
        def offer = pendingOffer()
        offerRepository.findById(1L) >> Optional.of(offer)
        offerRepository.save(_) >> { Offer o -> o }

        when:
        def result = service.cancelOffer(10L, 1L)

        then:
        result.status == 'CANCELLED'
    }

    def "cancelOffer forbids non-buyer callers"() {
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer())

        when:
        service.cancelOffer(77L, 1L)

        then:
        thrown(ForbiddenException)
    }

    // ── COUNTERED-thread cleanup (P1/P2 bug fix) ──────────────────
    //
    // counterOffer flips the buyer's original to COUNTERED but nothing
    // ever transitioned it out — so once the seller's counter died
    // (reject / expire / sweep / buyer-raise), the buyer's COUNTERED
    // original stayed live to makeOffer's duplicate guard
    // (findLiveByBuyerAndListing matches PENDING *or* COUNTERED) and
    // OFFER_ALREADY_PENDING locked them out of the listing forever.
    // The fix closes the COUNTERED parent (status CLOSED) whenever its
    // SELLER counter reaches a terminal state.

    /** A SELLER-authored counter threaded under `parentId`. Mirrors the
     *  row counterOffer() mints: buyerUserId = the buyer, sellerUserId =
     *  the seller, author = 'SELLER', status PENDING. */
    private Offer sellerCounter(Map args = [:]) {
        new Offer(
            id:            args.id ?: 2L,
            listingId:     args.listing ?: 100L,
            buyerUserId:   args.buyer ?: 10L,
            sellerUserId:  args.seller ?: 99L,
            amount:        args.amount ?: new BigDecimal("40"),
            askingPrice:   new BigDecimal("50"),
            status:        args.status ?: 'PENDING',
            author:        'SELLER',
            parentOfferId: args.parent ?: 1L
        )
    }

    def "rejectOffer on a SELLER counter closes the buyer's COUNTERED original"() {
        given:
        def original = pendingOffer(id: 1L, status: 'COUNTERED')
        def counter  = sellerCounter(id: 2L, parent: 1L)
        offerRepository.findById(2L) >> Optional.of(counter)
        offerRepository.findById(1L) >> Optional.of(original)
        offerRepository.save(_) >> { Offer o -> o }

        when: 'the seller rejects their own counter'
        service.rejectOffer(99L, 2L)

        then:
        counter.status == 'REJECTED'
        // The dead negotiation node is closed — CLOSED is not matched by
        // findLiveByBuyerAndListing, so the dup-guard frees up.
        original.status == 'CLOSED'
    }

    def "sweepStaleOffers closes the COUNTERED original when a SELLER counter is swept"() {
        given:
        def original = pendingOffer(id: 1L, status: 'COUNTERED')
        def staleCounter = sellerCounter(id: 2L, parent: 1L, amount: new BigDecimal("40"))
        offerRepository.findStalePending(_) >> [staleCounter]
        offerRepository.findById(1L) >> Optional.of(original)
        // Win the atomic auto-expire claim (wave 128) so the sweep proceeds
        // to closeCounteredParent. The claim's conditional UPDATE persists
        // the counter's PENDING→EXPIRED flip; closeCounteredParent still
        // entity-saves the COUNTERED parent to flip it CLOSED.
        offerRepository.claimAutoExpire(2L, _) >> 1
        offerRepository.save(_) >> { Offer o -> o }

        when:
        service.sweepStaleOffers()

        then:
        // The COUNTERED parent is closed in the same pass so the buyer
        // isn't permanently blocked by the dup-guard once the counter
        // negotiation times out unanswered. (The counter's own EXPIRED
        // flip is persisted by claimAutoExpire's UPDATE, not in-memory.)
        original.status == 'CLOSED'
    }

    def "buyer can make a fresh offer after a counter negotiation was rejected"() {
        // End-to-end: seller rejects the counter (closing the COUNTERED
        // original), then the buyer makes a brand-new offer on the same
        // listing. The dup-guard query no longer returns the now-CLOSED
        // original, so makeOffer succeeds instead of OFFER_ALREADY_PENDING.
        given:
        def original = pendingOffer(id: 1L, status: 'COUNTERED')
        def counter  = sellerCounter(id: 2L, parent: 1L)
        offerRepository.findById(2L) >> Optional.of(counter)
        offerRepository.findById(1L) >> Optional.of(original)
        offerRepository.save(_) >> { Offer o -> o.id = o.id ?: 7L; o }
        listingRepository.findById(100L) >> Optional.of(activeListing())
        // The dup-guard query reflects real repo semantics — only
        // PENDING/COUNTERED rows. After the reject closes the original,
        // nothing live remains for (buyer 10, listing 100).
        offerRepository.findLiveByBuyerAndListing(10L, 100L) >> []

        when: 'the counter is rejected'
        service.rejectOffer(99L, 2L)

        then:
        original.status == 'CLOSED'

        when: 'the buyer makes a fresh offer on the same listing'
        def fresh = service.makeOffer(10L, 'Alice', 100L, new BigDecimal("35"))

        then: 'no OFFER_ALREADY_PENDING — the buyer is unblocked'
        fresh.status == 'PENDING'
        fresh.amount == new BigDecimal("35")
    }

    def "buyer can make a fresh offer after a counter negotiation expired via the sweeper"() {
        given:
        def original = pendingOffer(id: 1L, status: 'COUNTERED')
        def staleCounter = sellerCounter(id: 2L, parent: 1L)
        offerRepository.findStalePending(_) >> [staleCounter]
        offerRepository.findById(1L) >> Optional.of(original)
        // Win the atomic auto-expire claim (wave 128) so the sweep reaches
        // closeCounteredParent and frees the COUNTERED original.
        offerRepository.claimAutoExpire(2L, _) >> 1
        offerRepository.save(_) >> { Offer o -> o.id = o.id ?: 8L; o }
        listingRepository.findById(100L) >> Optional.of(activeListing())
        offerRepository.findLiveByBuyerAndListing(10L, 100L) >> []

        when: 'the sweeper expires the stale counter'
        service.sweepStaleOffers()

        then:
        original.status == 'CLOSED'

        when: 'the buyer makes a fresh offer on the same listing'
        def fresh = service.makeOffer(10L, 'Alice', 100L, new BigDecimal("38"))

        then:
        fresh.status == 'PENDING'
    }

    def "makeOffer still blocks a duplicate while a counter is genuinely still pending"() {
        // Sanity guard: the fix must NOT let a buyer stack a second live
        // offer while a counter is mid-negotiation. The COUNTERED
        // original is still returned by the dup-guard query, so a fresh
        // makeOffer is still rejected.
        given:
        def liveCountered = pendingOffer(id: 1L, status: 'COUNTERED')
        listingRepository.findById(100L) >> Optional.of(activeListing())
        offerRepository.findLiveByBuyerAndListing(10L, 100L) >> [liveCountered]

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal("35"))

        then:
        def e = thrown(BadRequestException)
        e.code == 'OFFER_ALREADY_PENDING'
    }

    def "cancelOffer lets the buyer withdraw a COUNTERED offer"() {
        // P2 — the buyer whose offer the seller countered must be able to
        // walk away. The old PENDING-only guard threw OFFER_NOT_PENDING.
        given:
        def countered = pendingOffer(id: 1L, status: 'COUNTERED')
        offerRepository.findById(1L) >> Optional.of(countered)
        offerRepository.findByParentOfferId(1L) >> []
        offerRepository.save(_) >> { Offer o -> o }

        when:
        def result = service.cancelOffer(10L, 1L)

        then:
        result.status == 'CANCELLED'
    }

    def "cancelOffer on a COUNTERED original also expires the seller's live counter"() {
        // Withdrawing the COUNTERED original must kill the still-PENDING
        // SELLER counter hanging off it — otherwise the buyer has walked
        // away but the counter is still acceptable.
        given:
        def countered   = pendingOffer(id: 1L, status: 'COUNTERED')
        def liveCounter = sellerCounter(id: 2L, parent: 1L)
        offerRepository.findById(1L) >> Optional.of(countered)
        offerRepository.findByParentOfferId(1L) >> [liveCounter]
        offerRepository.save(_) >> { Offer o -> o }

        when:
        service.cancelOffer(10L, 1L)

        then:
        countered.status == 'CANCELLED'
        liveCounter.status == 'EXPIRED'
    }

    def "cancelOffer still rejects terminal-state offers (ACCEPTED/REJECTED/etc.)"() {
        // The COUNTERED allowance must not widen to genuinely terminal
        // rows — a CLOSED/ACCEPTED/REJECTED offer is still un-cancellable.
        given:
        offerRepository.findById(_) >> Optional.of(pendingOffer(id: 1L, status: status))

        when:
        service.cancelOffer(10L, 1L)

        then:
        thrown(OfferNotPendingException)

        where:
        status << ['ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'CLOSED']
    }

    def "buyerRaise on a SELLER counter closes the buyer's COUNTERED grandparent"() {
        // A buyer can raise directly on the seller's counter — that
        // cancels the counter and opens a fresh PENDING offer. The
        // grandparent original (COUNTERED) must be closed too so the
        // thread leaves exactly one live row.
        given:
        def grandparent = pendingOffer(id: 1L, status: 'COUNTERED')
        def counter     = sellerCounter(id: 2L, parent: 1L, amount: new BigDecimal("40"))
        offerRepository.findById(2L) >> Optional.of(counter)
        offerRepository.findById(1L) >> Optional.of(grandparent)
        listingRepository.findById(100L) >> Optional.of(activeListing(price: new BigDecimal("50")))
        offerRepository.save(_) >> { Offer o -> o.id = o.id ?: 3L; o }

        when: 'the buyer raises on the counter to $45'
        def raised = service.buyerRaise(10L, 2L, new BigDecimal("45"))

        then:
        counter.status == 'CANCELLED'        // counter superseded
        grandparent.status == 'CLOSED'       // dead COUNTERED node closed
        raised.status == 'PENDING'           // fresh live offer
        raised.amount == new BigDecimal("45")
    }

    def "closeCounteredParent is a no-op when the counter's parent is not COUNTERED"() {
        // Idempotency / safety: rejecting a SELLER counter whose parent
        // was already resolved (e.g. ACCEPTED) must not clobber it.
        given:
        def resolvedParent = pendingOffer(id: 1L, status: 'ACCEPTED')
        def counter        = sellerCounter(id: 2L, parent: 1L)
        offerRepository.findById(2L) >> Optional.of(counter)
        offerRepository.findById(1L) >> Optional.of(resolvedParent)
        offerRepository.save(_) >> { Offer o -> o }

        when:
        service.rejectOffer(99L, 2L)

        then:
        counter.status == 'REJECTED'
        resolvedParent.status == 'ACCEPTED'  // untouched
    }

    // ── thread (redaction) ───────────────────────────────────────

    private Offer threadOffer(Map args) {
        new Offer(
            id:            args.id,
            listingId:     args.listingId ?: 100L,
            buyerUserId:   args.buyerUserId,
            sellerUserId:  args.sellerUserId ?: 99L,
            amount:        args.amount ?: new BigDecimal("30"),
            askingPrice:   new BigDecimal("50"),
            buyerName:     args.buyerName ?: 'RealName',
            itemName:      'Wizard Hat',
            status:        args.status ?: 'PENDING',
            author:        args.author ?: 'USER',
            parentOfferId: args.parentOfferId
        )
    }

    def "thread returns empty list when no offers exist"() {
        given:
        offerRepository.findByListingId(100L) >> []

        expect:
        service.thread(100L, 10L) == []
    }

    def "thread returns raw offers (no redaction) to the listing seller"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        def b = threadOffer(id: 2L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, 99L)

        then:
        out == [a, b]
        out[0].buyerName == 'Alice'
        out[0].buyerUserId == 10L
        out[1].buyerName == 'Bob'
        out[1].buyerUserId == 20L
    }

    def "a participating buyer sees their own offers in full and every other buyer redacted"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        a.message = 'private note to the seller'
        def b = threadOffer(id: 2L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, 20L)  // Bob is viewing

        then: 'Alice is a handle with no id and no note'
        out[0].buyerName == 'Buyer #1'
        out[0].buyerUserId == null
        out[0].message == null
        and: "Bob's own row is untouched"
        out[1].is(b)
        out[1].buyerName == 'Bob'
    }

    def "thread redacts buyer identities for anonymous (null viewer) callers"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        def b = threadOffer(id: 2L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, null)

        then:
        out.size() == 2
        out*.buyerName == ['Buyer #1', 'Buyer #2']
        out*.buyerUserId == [null, null]
        // Original entities untouched — redaction returns fresh detached copies
        a.buyerName == 'Alice'
        b.buyerName == 'Bob'
    }

    def "thread redacts buyer identities for a logged-in third party"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        def b = threadOffer(id: 2L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [a, b]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, 77L)  // Unrelated user

        then:
        out*.buyerName == ['Buyer #1', 'Buyer #2']
        out*.buyerUserId == [null, null]
    }

    def "thread gives the same handle to the same buyer across a counter chain"() {
        given:
        def original = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        def counter  = threadOffer(id: 2L, buyerUserId: 10L, buyerName: 'Alice',
                                   author: 'SELLER', parentOfferId: 1L, amount: new BigDecimal("40"))
        def thirdParty = threadOffer(id: 3L, buyerUserId: 20L, buyerName: 'Bob')
        offerRepository.findByListingId(100L) >> [original, counter, thirdParty]
        listingRepository.findById(100L) >> Optional.of(activeListing(seller: 99L))

        when:
        def out = service.thread(100L, null)

        then:
        out*.buyerName == ['Buyer #1', 'Buyer #1', 'Buyer #2']
    }

    def "thread redacts when listing has been deleted (listingRepository returns empty)"() {
        given:
        def a = threadOffer(id: 1L, buyerUserId: 10L, buyerName: 'Alice')
        offerRepository.findByListingId(100L) >> [a]
        listingRepository.findById(100L) >> Optional.empty()

        when:
        def out = service.thread(100L, 99L)  // "seller" but no listing to prove it

        then:
        out.size() == 1
        out[0].buyerName == 'Buyer #1'
        out[0].buyerUserId == null
    }

    // ── typicalResponseMs ──────────────────────────────────────────

    private Offer resp(Map a) {
        new Offer(
            id:           a.id ?: 1L,
            sellerUserId: a.sellerUserId ?: 99L,
            author:       a.author ?: 'USER',
            status:       a.status ?: 'ACCEPTED',
            createdAt:    a.createdAt,
            updatedAt:    a.updatedAt
        )
    }

    def "typicalResponseMs returns null for a null seller id"() {
        expect:
        service.typicalResponseMs(null) == null
    }

    def "typicalResponseMs returns null when fewer than three resolved responses exist"() {
        given:
        offerRepository.findRecentSellerResponses(99L, _) >> [
            resp(id: 1L, createdAt: 0L,   updatedAt: 60_000L),
            resp(id: 2L, createdAt: 100L, updatedAt: 120_000L)
        ]

        expect:
        service.typicalResponseMs(99L) == null
    }

    def "typicalResponseMs returns the median delta when at least three responses exist"() {
        given:
        // Deltas: 60s, 300s, 900s → median is 300s = 300_000ms
        offerRepository.findRecentSellerResponses(99L, _) >> [
            resp(id: 1L, createdAt: 0L, updatedAt: 60_000L),
            resp(id: 2L, createdAt: 0L, updatedAt: 300_000L),
            resp(id: 3L, createdAt: 0L, updatedAt: 900_000L)
        ]

        expect:
        service.typicalResponseMs(99L) == 300_000L
    }

    def "typicalResponseMs ignores rows whose delta is non-positive (clock skew / same ms)"() {
        given:
        offerRepository.findRecentSellerResponses(99L, _) >> [
            resp(id: 1L, createdAt: 1_000L, updatedAt: 1_000L),   // delta=0, dropped
            resp(id: 2L, createdAt: 2_000L, updatedAt: 1_500L),   // delta=-500, dropped
            resp(id: 3L, createdAt: 0L,     updatedAt: 60_000L),
            resp(id: 4L, createdAt: 0L,     updatedAt: 120_000L),
            resp(id: 5L, createdAt: 0L,     updatedAt: 180_000L)
        ]

        expect:
        // After filtering, deltas = [60_000, 120_000, 180_000] → median 120_000
        service.typicalResponseMs(99L) == 120_000L
    }

    // ── responseRatePct ────────────────────────────────────────────

    def "responseRatePct returns null for a null seller id"() {
        expect:
        service.responseRatePct(null) == null
    }

    def "responseRatePct returns null below the five-offer noise floor without hitting the numerator query"() {
        given:
        offerRepository.countSellerEngagedTotal(99L) >> 4L

        when:
        def result = service.responseRatePct(99L)

        then:
        result == null
        0 * offerRepository.countSellerResponded(_)
    }

    def "responseRatePct returns 100 when every engaged offer was resolved"() {
        given:
        offerRepository.countSellerEngagedTotal(99L) >> 10L
        offerRepository.countSellerResponded(99L)    >> 10L

        expect:
        service.responseRatePct(99L) == 100.0d
    }

    def "responseRatePct returns the responded-over-total fraction as a percentage"() {
        given:
        offerRepository.countSellerEngagedTotal(99L) >> 20L
        offerRepository.countSellerResponded(99L)    >> 15L

        expect:
        service.responseRatePct(99L) == 75.0d
    }

    // ── pendingOfferSummaryForSeller (MyStall best-offer chip) ─────

    def "pendingOfferSummaryForSeller returns an empty map for a null id without hitting the repo"() {
        when:
        def out = service.pendingOfferSummaryForSeller(null)

        then:
        out == [:]
        0 * offerRepository.aggregatePendingBySeller(_)
    }

    def "pendingOfferSummaryForSeller unpacks repo rows into a per-listing summary"() {
        given:
        offerRepository.aggregatePendingBySeller(99L) >> [
            [100L as Long, new BigDecimal("45.00"), 3L as Long, 1700000000000L as Long] as Object[],
            [200L as Long, new BigDecimal("10.00"), 1L as Long, 1700000001000L as Long] as Object[]
        ]

        when:
        def out = service.pendingOfferSummaryForSeller(99L)

        then:
        out.size() == 2
        out[100L].bestAmount == new BigDecimal("45.00")
        out[100L].count      == 3L
        out[100L].newestAt   == 1700000000000L
        out[200L].bestAmount == new BigDecimal("10.00")
        out[200L].count      == 1L
    }

    // ── computeExpiresAt + DTO maps (batch 269) ─────────────────────

    def "computeExpiresAt returns updatedAt + autoDeclineDays for PENDING offers"() {
        given:
        // OfferService default autoDeclineDays = 7. Override via the
        // injected field so the calculation is fully deterministic.
        service.autoDeclineDays = 7L
        def t0 = 1_700_000_000_000L
        def offer = new Offer(id: 1L, status: 'PENDING', updatedAt: t0)

        expect:
        service.computeExpiresAt(offer) == t0 + (7L * 24L * 60L * 60L * 1000L)
    }

    def "computeExpiresAt returns null for terminal status"() {
        given:
        service.autoDeclineDays = 7L
        def now = System.currentTimeMillis()

        expect:
        ['ACCEPTED','REJECTED','CANCELLED','EXPIRED'].each { st ->
            assert service.computeExpiresAt(new Offer(status: st, updatedAt: now)) == null
        }
    }

    def "computeExpiresAt is null on a missing updatedAt"() {
        given:
        service.autoDeclineDays = 7L

        expect:
        service.computeExpiresAt(new Offer(status: 'PENDING', updatedAt: null)) == null
        service.computeExpiresAt(null) == null
    }

    def "incomingWithExpiry decorates each row with expiresAt"() {
        given:
        service.autoDeclineDays = 3L
        def t0 = 1_700_000_000_000L
        offerRepository.findBySellerPaged(99L, _) >> [
            new Offer(id: 1L, listingId: 100L, status: 'PENDING',  updatedAt: t0),
            new Offer(id: 2L, listingId: 100L, status: 'ACCEPTED', updatedAt: t0)
        ]

        when:
        def rows = service.incomingWithExpiry(99L)

        then:
        rows.size() == 2
        rows[0].id == 1L
        rows[0].expiresAt == t0 + (3L * 24L * 60L * 60L * 1000L)
        rows[1].id == 2L
        rows[1].expiresAt == null
    }

    // ── cancelAllForUser (batch 291) ────────────────────────────────

    def "cancelAllForUser flips every PENDING outgoing to CANCELLED and pushes the seller"() {
        given:
        def a = new Offer(id: 1L, buyerUserId: 10L, sellerUserId: 50L, listingId: 100L,
            itemName: 'Hat', amount: new BigDecimal("5"), status: 'PENDING')
        def b = new Offer(id: 2L, buyerUserId: 10L, sellerUserId: 51L, listingId: 101L,
            itemName: 'Boots', amount: new BigDecimal("8"), status: 'PENDING')
        def c = new Offer(id: 3L, buyerUserId: 10L, sellerUserId: 50L, listingId: 102L,
            itemName: 'Shirt', amount: new BigDecimal("3"), status: 'ACCEPTED')
        // Batch 1030 — service now queries PENDING-only. Non-PENDING row
        // `c` is excluded at the repo level, so the stub shape changed.
        offerRepository.findPendingByBuyer(10L) >> [a, b]
        offerRepository.save(_) >> { Offer o -> o }

        when:
        int n = service.cancelAllForUser(10L)

        then:
        n == 2
        a.status == 'CANCELLED'
        b.status == 'CANCELLED'
        c.status == 'ACCEPTED'  // non-PENDING row never fetched
        1 * notificationService.push(50L, 'OFFER_REJECTED', _, _, 1L, '/offers')
        1 * notificationService.push(51L, 'OFFER_REJECTED', _, _, 2L, '/offers')
    }

    def "cancelAllForUser closes the buyer's COUNTERED original when it declines a seller counter"() {
        given: 'the buyer offered (id 1), the seller countered it (id 2, PENDING, author SELLER)'
        def original = new Offer(id: 1L, buyerUserId: 10L, sellerUserId: 50L, listingId: 100L,
            itemName: 'Hat', amount: new BigDecimal("5"), status: 'COUNTERED', author: 'USER')
        def counter = new Offer(id: 2L, buyerUserId: 10L, sellerUserId: 50L, listingId: 100L,
            itemName: 'Hat', amount: new BigDecimal("7"), status: 'PENDING', author: 'SELLER',
            parentOfferId: 1L)
        offerRepository.findPendingByBuyer(10L) >> [counter]
        offerRepository.findById(1L) >> Optional.of(original)
        offerRepository.save(_) >> { Offer o -> o }

        when:
        service.cancelAllForUser(10L)

        then: 'the original leaves COUNTERED so the buyer can offer on that listing again'
        counter.status == 'CANCELLED'
        original.status == 'CLOSED'
        1 * notificationService.push(50L, 'OFFER_REJECTED', { it.startsWith('Buyer declined your counter') }, _, 2L, '/offers')
    }

    def "a seller cannot counter their own counter"() {
        given:
        def own = pendingOffer(id: 2L).tap { it.author = 'SELLER' }
        offerRepository.findById(2L) >> Optional.of(own)

        when:
        service.counterOffer(99L, 2L, new BigDecimal("45"))

        then:
        def e = thrown(BadRequestException)
        e.code == 'OWN_COUNTER'
        own.status == 'PENDING'
    }

    def "withdrawing your own counter tells the buyer it was withdrawn, not that their offer was declined"() {
        given:
        def own = pendingOffer(id: 2L, amount: new BigDecimal("45")).tap { it.author = 'SELLER'; it.itemName = 'Hat' }
        offerRepository.findById(2L) >> Optional.of(own)
        offerRepository.save(_) >> { Offer o -> o }

        when:
        service.rejectOffer(99L, 2L)

        then:
        own.status == 'REJECTED'
        1 * notificationService.push(10L, 'OFFER_REJECTED', 'Counter withdrawn · Hat',
            { it.contains('withdrew their $45') }, 2L, '/offers')
        0 * notificationService.push(10L, _, { it.startsWith('Offer rejected') }, _, _, _)
    }

    def "incomingWithExpiry carries the buyer's note and the seller's reply"() {
        given:
        offerRepository.findBySellerPaged(99L, _) >> [
            new Offer(id: 1L, listingId: 100L, status: 'REJECTED', updatedAt: 1L,
                message: 'fast pay', sellerReply: 'too low')
        ]

        when:
        def rows = service.incomingWithExpiry(99L)

        then:
        rows[0].message == 'fast pay'
        rows[0].sellerReply == 'too low'
    }

    def "cancelAllForUser returns 0 when the user has no pending outgoing offers"() {
        given:
        // Batch 1030 — indexed PENDING-only query returns [] when user
        // has only terminal-state offers.
        offerRepository.findPendingByBuyer(10L) >> []

        when:
        int n = service.cancelAllForUser(10L)

        then:
        n == 0
        0 * offerRepository.save(_)
        0 * notificationService.push(_, _, _, _, _, _)
    }

    def "cancelAllForUser short-circuits on a null user id"() {
        when:
        int n = service.cancelAllForUser(null)

        then:
        n == 0
        0 * offerRepository.findPendingByBuyer(_)
    }

    def "cancelAllForUser swallows per-row push exceptions so the batch still lands"() {
        given:
        def a = new Offer(id: 1L, buyerUserId: 10L, sellerUserId: 50L,
            itemName: 'Hat', amount: new BigDecimal("5"), status: 'PENDING')
        def b = new Offer(id: 2L, buyerUserId: 10L, sellerUserId: 51L,
            itemName: 'Boots', amount: new BigDecimal("8"), status: 'PENDING')
        offerRepository.findPendingByBuyer(10L) >> [a, b]
        offerRepository.save(_) >> { Offer o -> o }
        notificationService.push(50L, _, _, _, _, _) >> { throw new RuntimeException('push down') }

        when:
        int n = service.cancelAllForUser(10L)

        then:
        // Both rows still flipped — the push failure is quiet.
        n == 2
        a.status == 'CANCELLED'
        b.status == 'CANCELLED'
    }

    def "cancelAllForUser isolates per-row save failures — one bad offer never poisons a sibling flip + push"() {
        // Bug bar: the outer cancelAllForUser used to be @Transactional. A
        // mid-batch offerRepository.save() failure on offer #1 (e.g. an
        // OptimisticLockingFailureException because a concurrent
        // counter-offer or seller-accept landed on the same row) marked
        // the SHARED outer tx rollback-only — the per-row try/catch
        // swallowed the throw, but on method return the commit threw
        // UnexpectedRollbackException and EVERY "successfully" cancelled
        // sibling offer (and its seller push, if it had already fired)
        // silently rolled back. Buyer sees `n=1` returned from the API
        // but reloads the Offers tab and ALL N offers are still PENDING.
        // Per-row auto-commit must isolate the bad row from sibling work.
        // Mirrors the BuyOrderService.cancelAllForUser fix from 443f910.
        given:
        def a = new Offer(id: 1L, buyerUserId: 10L, sellerUserId: 50L,
            itemName: 'Hat', amount: new BigDecimal("5"), status: 'PENDING')
        def b = new Offer(id: 2L, buyerUserId: 10L, sellerUserId: 51L,
            itemName: 'Boots', amount: new BigDecimal("8"), status: 'PENDING')
        offerRepository.findPendingByBuyer(10L) >> [a, b]
        // Row #1 save blows up (concurrent counter-offer → optimistic
        // lock). Row #2 save succeeds. Without per-row isolation the
        // sibling flip + push would silently revert under an outer
        // @Transactional commit.
        offerRepository.save({ Offer o -> o.id == 1L }) >> {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                'Offer', 1L)
        }
        offerRepository.save({ Offer o -> o.id == 2L }) >> { Offer o -> o }

        when:
        int n = service.cancelAllForUser(10L)

        then: "only the surviving row counts toward n"
        n == 1
        and: "sibling row's flip persists — not rolled back by the bad save"
        b.status == 'CANCELLED'
        and: "sibling row's seller push still fires — sweep didn't abort"
        1 * notificationService.push(51L, 'OFFER_REJECTED', _, _, 2L, '/offers')
        and: "no exception bubbles out — bad row was swallowed in the per-row catch"
        noExceptionThrown()
    }

    def "outgoingWithExpiry mirrors the incoming decoration shape"() {
        given:
        service.autoDeclineDays = 5L
        def t0 = 1_700_000_000_000L
        offerRepository.findByBuyerPaged(10L, _) >> [
            new Offer(id: 1L, listingId: 100L, status: 'PENDING', updatedAt: t0)
        ]

        when:
        def rows = service.outgoingWithExpiry(10L)

        then:
        rows[0].expiresAt == t0 + (5L * 24L * 60L * 60L * 1000L)
        // Fields the frontend reads should all be present.
        rows[0].listingId == 100L
        rows[0].status    == 'PENDING'
    }

    // ── OFFER_RECEIVED seller notify + email on makeOffer (batch 590) ────

    def "makeOffer pushes OFFER_RECEIVED to the seller for a PENDING offer"() {
        given:
        listingRepository.findById(100L) >> Optional.of(activeListing())
        offerRepository.save(_) >> { Offer o -> o.id = 7L; o }

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal('40'))

        then:
        // Batch 625: migrated to safePush (swallows + logs on failure).
        1 * notificationService.safePush(99L, 'OFFER_RECEIVED', _, _, 7L, '/offers')
    }

    def "makeOffer emails the seller when verified + not muted"() {
        given:
        def seller = new SteamUser(
            id: 99L, steamId64: '7656', displayName: 'Sally',
            email: 'sally@example.com', emailVerified: true,
            emailNotificationsEnabled: true, mutedEmailKinds: null
        )
        def emailService = Mock(com.sboxmarket.service.EmailService)
        emailService.canSendTo(seller, 'TRADES') >> true
        service.emailService = emailService
        listingRepository.findById(100L) >> Optional.of(activeListing())
        steamUserRepository.findById(99L) >> Optional.of(seller)
        offerRepository.save(_) >> { Offer o -> o.id = 8L; o }

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal('42.50'), 'fast pay, ready now')

        then:
        1 * emailService.sendOfferReceived('sally@example.com', 'Sally', 'Alice',
            'Wizard Hat', new BigDecimal('42.50'), new BigDecimal('50.00'), 'fast pay, ready now')
    }

    def "makeOffer skips seller email when the seller muted the TRADES bucket"() {
        given:
        def seller = new SteamUser(
            id: 99L, steamId64: '7656', displayName: 'Sally',
            email: 'sally@example.com', emailVerified: true,
            emailNotificationsEnabled: true, mutedEmailKinds: 'TRADES'
        )
        def emailService = Mock(com.sboxmarket.service.EmailService)
        // Real bucket-mute behavior — we want to prove the gate actually fires.
        emailService.canSendTo(seller, 'TRADES') >> false
        service.emailService = emailService
        listingRepository.findById(100L) >> Optional.of(activeListing())
        steamUserRepository.findById(99L) >> Optional.of(seller)
        offerRepository.save(_) >> { Offer o -> o.id = 9L; o }

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal('40'))

        then:
        0 * emailService.sendOfferReceived(*_)
    }

    def "makeOffer does not push OFFER_RECEIVED for system listings (no seller)"() {
        given:
        def item = new Item(id: 1L, name: 'Wizard Hat', imageUrl: 'https://example.com/x.png')
        def systemListing = new Listing(
            id: 100L, item: item, price: new BigDecimal('50.00'),
            sellerUserId: null, status: 'ACTIVE'
        )
        listingRepository.findById(100L) >> Optional.of(systemListing)
        offerRepository.save(_) >> { Offer o -> o.id = 12L; o }

        when:
        service.makeOffer(10L, 'Alice', 100L, new BigDecimal('40'))

        then:
        // Batch 625: migrated to safePush.
        0 * notificationService.safePush(_, 'OFFER_RECEIVED', _, _, _, _)
    }

    // ── OFFER_COUNTERED + OFFER_REJECTED buyer emails (batch 591) ────────

    def "counterOffer emails the buyer with the seller's counter amount"() {
        given:
        def buyer = new SteamUser(
            id: 10L, steamId64: '7656', displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true
        )
        def emailService = Mock(com.sboxmarket.service.EmailService)
        emailService.canSendTo(buyer, 'TRADES') >> true
        service.emailService = emailService

        def original = pendingOffer(amount: new BigDecimal('25'))
        offerRepository.findById(_) >> Optional.of(original)
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal('50')))
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        offerRepository.save(_) >> { Offer o -> o.id = o.id ?: 2L; o }

        when:
        service.counterOffer(99L, 1L, new BigDecimal('40'), 'final offer')

        then:
        1 * emailService.sendOfferCountered('alice@example.com', 'Alice',
            'Wizard Hat', new BigDecimal('25'), new BigDecimal('40'), 'final offer')
    }

    def "counterOffer skips buyer email when TRADES bucket is muted"() {
        given:
        def buyer = new SteamUser(
            id: 10L, steamId64: '7656', displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true, mutedEmailKinds: 'TRADES'
        )
        def emailService = Mock(com.sboxmarket.service.EmailService)
        emailService.canSendTo(buyer, 'TRADES') >> false
        service.emailService = emailService

        def original = pendingOffer(amount: new BigDecimal('25'))
        offerRepository.findById(_) >> Optional.of(original)
        listingRepository.findById(_) >> Optional.of(activeListing(price: new BigDecimal('50')))
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        offerRepository.save(_) >> { Offer o -> o.id = o.id ?: 2L; o }

        when:
        service.counterOffer(99L, 1L, new BigDecimal('40'))

        then:
        0 * emailService.sendOfferCountered(*_)
    }

    def "rejectOffer emails the buyer with the seller's reply note"() {
        given:
        def buyer = new SteamUser(
            id: 10L, steamId64: '7656', displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true
        )
        def emailService = Mock(com.sboxmarket.service.EmailService)
        emailService.canSendTo(buyer, 'TRADES') >> true
        service.emailService = emailService

        def offer = pendingOffer(id: 5L, amount: new BigDecimal('30'))
        offerRepository.findById(5L) >> Optional.of(offer)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        offerRepository.save(_) >> { Offer o -> o }

        when:
        service.rejectOffer(99L, 5L, 'Firm at asking price — thanks')

        then:
        1 * emailService.sendOfferRejected('alice@example.com', 'Alice',
            null, new BigDecimal('30'), 'Firm at asking price — thanks')
    }

    def "rejectOffer emails the buyer even when the seller supplied no reply"() {
        given:
        def buyer = new SteamUser(
            id: 10L, steamId64: '7656', displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true
        )
        def emailService = Mock(com.sboxmarket.service.EmailService)
        emailService.canSendTo(buyer, 'TRADES') >> true
        service.emailService = emailService

        def offer = pendingOffer(id: 6L, amount: new BigDecimal('30'))
        offerRepository.findById(6L) >> Optional.of(offer)
        steamUserRepository.findById(10L) >> Optional.of(buyer)
        offerRepository.save(_) >> { Offer o -> o }

        when:
        service.rejectOffer(99L, 6L, null)

        then:
        1 * emailService.sendOfferRejected('alice@example.com', 'Alice',
            null, new BigDecimal('30'), null)
    }

    // ── Batch 699 — PRICE_DROPPED fan-out to offer-holders ────────

    def "notifyOfferHoldersOfPriceDrop pushes only to buyers whose offer meets or exceeds the new ask"() {
        given:
        def offers = [
            new Offer(id: 1L, buyerUserId: 100L, amount: new BigDecimal('45'),  status: 'PENDING'),
            new Offer(id: 2L, buyerUserId: 200L, amount: new BigDecimal('40'),  status: 'PENDING'),
            new Offer(id: 3L, buyerUserId: 300L, amount: new BigDecimal('20'),  status: 'PENDING'),
        ]
        offerRepository.findPendingForListing(555L) >> offers
        // Banned-recipient filter (batch 316/317): default to "everyone is
        // active" so the price-drop fan-out reaches every uid the test
        // builds. Without this stub the filter returns null, the
        // activeUids.collect path NPEs into the outer catch, and zero
        // pushes happen — which masks the actual targeting assertion.
        notificationService.filterActiveRecipients(_) >> { args -> args[0] }

        when:
        // Seller dropped from $50 to $40. Buyer #1 ($45) and #2 ($40)
        // now have offers at or above the new ask — they should ping.
        // Buyer #3 ($20) is still below — no ping.
        service.notifyOfferHoldersOfPriceDrop(555L, new BigDecimal('50'), new BigDecimal('40'), 'Lucky Hat', 77L)

        then:
        1 * notificationService.push(100L, 'PRICE_DROPPED', _, _, 555L, '/item/77')
        1 * notificationService.push(200L, 'PRICE_DROPPED', _, _, 555L, '/item/77')
        0 * notificationService.push(300L, _, _, _, _, _)
    }

    def "notifyOfferHoldersOfPriceDrop is a no-op when the new price is not a drop"() {
        when:
        service.notifyOfferHoldersOfPriceDrop(555L, new BigDecimal('40'), new BigDecimal('40'), 'X', 1L)

        then:
        // Price unchanged — nothing to broadcast, nothing to fetch.
        0 * offerRepository.findPendingForListing(_)
        0 * notificationService.push(_, _, _, _, _, _)
    }

    def "notifyOfferHoldersOfPriceDrop deduplicates by buyer id (buyer with 2 offers only pinged once)"() {
        given:
        def offers = [
            new Offer(id: 1L, buyerUserId: 100L, amount: new BigDecimal('45'), status: 'PENDING'),
            new Offer(id: 2L, buyerUserId: 100L, amount: new BigDecimal('50'), status: 'PENDING'),
        ]
        offerRepository.findPendingForListing(555L) >> offers
        // Banned-recipient filter (batch 316/317): no banned buyers in
        // this scenario, so pass every uid through.
        notificationService.filterActiveRecipients(_) >> { args -> args[0] }

        when:
        service.notifyOfferHoldersOfPriceDrop(555L, new BigDecimal('60'), new BigDecimal('40'), 'Item', 1L)

        then:
        1 * notificationService.push(100L, 'PRICE_DROPPED', _, _, 555L, '/item/1')
    }
}
