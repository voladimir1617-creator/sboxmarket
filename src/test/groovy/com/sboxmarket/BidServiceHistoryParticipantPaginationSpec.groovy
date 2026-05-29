package com.sboxmarket

import com.sboxmarket.model.Bid
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BidService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pins the contract that {@link BidService#historyFor} classifies a viewer
 * as a participant whenever they have at least one bid on the listing —
 * regardless of whether that bid landed inside the
 * {@link BidService#HISTORY_PAGE_SIZE}-row page returned to the caller.
 *
 * The old `all.any { it.bidderUserId == viewerUserId }` gate scanned only
 * the page, which is sorted {@code amount DESC, createdAt ASC}. On a hot
 * auction whose bid count exceeds the page cap, the lowest-amount rows
 * get pushed off the page; if all of a bidder's bids happen to live in
 * that tail they were misclassified as a third party — their own
 * identity scrubbed to "Bidder #N" and their own auto-cap stripped
 * inside their OWN history view. The fix probes {@code BidRepository
 * .countByListingAndBidder} as a fallback so a participant whose bid is
 * outside the page is still recognised.
 */
class BidServiceHistoryParticipantPaginationSpec extends Specification {

    ListingRepository     listingRepository     = Mock()
    BidRepository         bidRepository         = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    TextSanitizer         textSanitizer         = Mock() {
        cleanShort(_) >> { String s -> s }
    }

    @Subject
    BidService service = new BidService(
        listingRepository    : listingRepository,
        bidRepository        : bidRepository,
        walletRepository     : walletRepository,
        transactionRepository: transactionRepository,
        steamUserRepository  : steamUserRepository,
        notificationService  : notificationService,
        banGuard             : banGuard,
        textSanitizer        : textSanitizer
    )

    private Listing auctionListing(Long sellerId = 99L) {
        new Listing(
            id:           100L,
            item:         new Item(id: 1L, name: 'Wizard Hat'),
            price:        new BigDecimal('10'),
            sellerUserId: sellerId,
            status:       'ACTIVE',
            listingType:  'AUCTION',
            expiresAt:    System.currentTimeMillis() + 3_600_000L
        )
    }

    private Bid bid(Long id, Long bidderId, String name, BigDecimal amount, BigDecimal max = null) {
        new Bid(
            id:           id,
            listingId:    100L,
            bidderUserId: bidderId,
            bidderName:   name,
            amount:       amount,
            maxAmount:    max,
            kind:         max != null ? 'AUTO' : 'MANUAL',
            status:       'WINNING'
        )
    }

    def "historyFor classifies a bidder as participant even when their bid is outside the returned page (DB count fallback)"() {
        given: 'a hot auction whose returned page does NOT contain Charlie (he bid early and got pushed off the cap)'
        def alice = bid(1L, 10L, 'Alice', new BigDecimal('60'), new BigDecimal('80'))
        def bob   = bid(2L, 20L, 'Bob',   new BigDecimal('55'))
        bidRepository.findByListing(100L, _ as org.springframework.data.domain.Pageable) >> [alice, bob]
        listingRepository.findById(100L) >> Optional.of(auctionListing())
        // Charlie really did bid on this listing — his bid just isn't on
        // the top-N page that historyFor ships back. The DB count gate
        // is the only signal that he is a real participant.
        bidRepository.countByListingAndBidder(100L, 30L) >> 1L

        when: 'Charlie (uid=30) requests the history'
        def out = service.historyFor(100L, 30L)

        then: 'he gets the participant view — bidder identities preserved, OTHER bidders\' maxAmount scrubbed'
        out.size() == 2
        // Before fix: bidderUserId/bidderName were anonymized to null / "Bidder #N"
        // because Charlie missed the `all.any` gate; alice/bob would have come
        // back as bidderUserId=null. Post-fix: Charlie is recognised as a
        // participant and sees real bidder ids.
        out[0].bidderUserId == 10L
        out[0].bidderName == 'Alice'
        out[1].bidderUserId == 20L
        out[1].bidderName == 'Bob'
        // Strategic-secret protection still holds: Charlie sees the FACT a
        // competitor is auto-bidding (kind=AUTO) but never the cap value.
        out[0].maxAmount == null
        out[1].maxAmount == null
        // Original Hibernate-managed entities are not mutated.
        alice.maxAmount == new BigDecimal('80')
    }

    def "historyFor still scrubs identities for a true third party whose count is zero"() {
        given:
        def alice = bid(1L, 10L, 'Alice', new BigDecimal('60'), new BigDecimal('80'))
        def bob   = bid(2L, 20L, 'Bob',   new BigDecimal('55'))
        bidRepository.findByListing(100L, _ as org.springframework.data.domain.Pageable) >> [alice, bob]
        listingRepository.findById(100L) >> Optional.of(auctionListing())
        // Dave never bid — DB count is zero so the fallback gate must NOT
        // promote him to participant. Otherwise the redaction layer
        // collapses and any third party can read every bidder's id.
        bidRepository.countByListingAndBidder(100L, 40L) >> 0L

        when:
        def out = service.historyFor(100L, 40L)

        then: 'Dave gets the handle-aliased view — no real ids, no maxAmounts'
        out.size() == 2
        out*.bidderUserId == [null, null]
        out*.bidderName == ['Bidder #1', 'Bidder #2']
        out*.maxAmount == [null, null]
    }

    def "historyFor short-circuits the count probe when the viewer's bid is on the returned page"() {
        given: 'Bob\'s bid IS on the page, so `all.any` matches and the DB count is never hit'
        def alice = bid(1L, 10L, 'Alice', new BigDecimal('60'), new BigDecimal('80'))
        def bob   = bid(2L, 20L, 'Bob',   new BigDecimal('55'), new BigDecimal('70'))
        bidRepository.findByListing(100L, _ as org.springframework.data.domain.Pageable) >> [alice, bob]
        listingRepository.findById(100L) >> Optional.of(auctionListing())

        when:
        def out = service.historyFor(100L, 20L)

        then: 'the DB count fallback is NOT invoked — `all.any` already proved participation'
        0 * bidRepository.countByListingAndBidder(_, _)
        out.size() == 2
        // Bob sees his own row's max…
        out[1].maxAmount == new BigDecimal('70')
        // …and Alice's max stays scrubbed.
        out[0].maxAmount == null
    }
}
