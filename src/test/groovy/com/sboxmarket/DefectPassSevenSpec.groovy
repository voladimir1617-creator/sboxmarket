package com.sboxmarket

import com.sboxmarket.controller.AdminController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.WalletController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Review
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingReportRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.ReviewRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.CsrService
import com.sboxmarket.service.ReviewService
import com.sboxmarket.service.SavedSearchService
import com.sboxmarket.service.security.AdminAuthorization
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 7: wallet activity, saved-search price bounds, staff
 * authorization, CSR / admin user search, listing flags, the audit
 * log filters, and staff moderation of their own stall.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassSevenSpec extends Specification {

    @Autowired WalletController walletController
    @Autowired AdminController adminController
    @Autowired AdminService adminService
    @Autowired AdminAuthorization adminAuthorization
    @Autowired CsrService csrService
    @Autowired ReviewService reviewService
    @Autowired SavedSearchService savedSearchService
    @Autowired AuditService auditService
    @Autowired SteamUserRepository userRepo
    @Autowired WalletRepository walletRepo
    @Autowired TransactionRepository txRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired ListingReportRepository reportRepo
    @Autowired ReviewRepository reviewRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String role = 'USER', Map extra = [:]) {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser([steamId64: '7656117' + tail.padLeft(10, '0'),
            displayName: "Pass7-${role}-${seq}-${uniq}", role: role] + extra))
    }

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req.session = session
        req
    }

    // ── Wallet activity ───────────────────────────────────────────

    def "a deposit refunded to the card is not counted as a purchase refund"() {
        given:
        def u = user()
        def w = walletRepo.save(new Wallet(username: "steam_${u.steamId64}", balance: BigDecimal.ZERO))
        txRepo.save(new Transaction(walletId: w.id, type: 'DEPOSIT', status: 'COMPLETED', amount: new BigDecimal('100.00')))
        txRepo.save(new Transaction(walletId: w.id, type: 'REFUND', status: 'COMPLETED', amount: new BigDecimal('100.00'),
            description: 'Refund of deposit #1'))
        txRepo.save(new Transaction(walletId: w.id, type: 'REFUND', status: 'COMPLETED', amount: new BigDecimal('7.00'),
            listingId: 4242L, description: 'Purchase reversed'))

        when:
        def all = walletController.getActivitySummary(signedIn(u.id)).body.windows['all']

        then:
        all.refunds.count == 1L
        all.refunds.amount == new BigDecimal('7.00')
        all.deposits.amount == new BigDecimal('100.00')
    }

    // ── Saved searches ────────────────────────────────────────────

    def "a saved search keeps thousands separators and swaps reversed bounds"() {
        given:
        def u = user()

        when:
        def thousands = savedSearchService.upsert(u.id, [name: "p7k ${uniq}", minPrice: '5,50', maxPrice: '1,000'])
        def reversed  = savedSearchService.upsert(u.id, [name: "p7r ${uniq}", minPrice: '50', maxPrice: '10'])

        then:
        thousands.minPrice == '5.50'
        thousands.maxPrice == '1000'
        reversed.minPrice == '10'
        reversed.maxPrice == '50'
    }

    // ── Staff authorization ───────────────────────────────────────

    def "a banned CSR or admin loses staff powers"() {
        given:
        def csr = user('CSR', [banned: true])
        def admin = user('ADMIN', [banned: true])

        when:
        csrService.requireCsr(csr.id)

        then:
        thrown(ForbiddenException)
        !csrService.isCsr(csr.id)

        when:
        adminAuthorization.requireAdmin(admin.id)

        then:
        thrown(ForbiddenException)
        !adminAuthorization.isAdmin(admin.id)
    }

    def "revoking admin from a non-admin and unbanning someone not banned are refused"() {
        given:
        def admin = user('ADMIN')
        def csr = user('CSR')
        def plain = user()

        when:
        adminService.revokeAdmin(admin.id, csr.id)

        then:
        def e1 = thrown(BadRequestException)
        e1.code == 'NOT_ADMIN'
        userRepo.findById(csr.id).get().role == 'CSR'

        when:
        adminService.unbanUser(admin.id, plain.id)

        then:
        def e2 = thrown(BadRequestException)
        e2.code == 'NOT_BANNED'
    }

    // ── User search ───────────────────────────────────────────────

    def "staff user search matches _ literally and survives a trailing backslash"() {
        given:
        def admin = user('ADMIN')
        def hit = userRepo.save(new SteamUser(steamId64: '76561160' + uniq.reverse().take(9),
            displayName: "p7${uniq}a_b", role: 'USER'))
        userRepo.save(new SteamUser(steamId64: '76561161' + uniq.reverse().take(9),
            displayName: "p7${uniq}axb", role: 'USER'))

        expect:
        adminService.listUsers("p7${uniq}a_b", null, null)*.id == [hit.id]
        csrService.lookupUser("p7${uniq}a_b").matches*.id == [hit.id]
        adminService.listUsers("p7${uniq}a\\", null, null) == []
    }

    def "CSR lookup by numeric user id puts that user first"() {
        given: "a newer user whose Steam id contains the target's id digits"
        def target = user()
        def idStr = String.valueOf(target.id)
        userRepo.save(new SteamUser(steamId64: ('7656115' + idStr + uniq.reverse()).take(17),
            displayName: "Pass7-decoy-${uniq}", role: 'USER', createdAt: System.currentTimeMillis() + 60_000L))

        when:
        def matches = csrService.lookupUser(idStr).matches

        then:
        matches[0].id == target.id
    }

    // ── Listing flags ─────────────────────────────────────────────

    def "a CSR flag goes to the admin report queue and never touches the public description"() {
        given:
        def csr = user('CSR')
        def item = itemRepo.save(new Item(name: "Pass7 flag ${uniq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('3.00'), isListed: true, iconEmoji: '🎩'))
        def l = listingRepo.save(new Listing(item: item, price: new BigDecimal('3.00'), listingType: 'BUY_NOW',
            status: 'ACTIVE', sellerName: 'p7', description: 'Mint condition', rarityScore: BigDecimal.ZERO))

        when:
        csrService.flagListing(csr.id, l.id, 'Suspected duplicate')
        csrService.flagListing(csr.id, l.id, 'Suspected duplicate, again')
        def after = listingRepo.findById(l.id).get()

        then:
        after.description == 'Mint condition'
        after.reportCount == 1
        reportRepo.findByListingIdAndReporterUserId(l.id, csr.id).get().note.contains('again')
    }

    // ── Audit log ─────────────────────────────────────────────────

    def "the audit log applies event and actor filters together"() {
        given:
        def admin = user('ADMIN')
        def otherActor = user('ADMIN')
        def ev = "P7_EVENT_${uniq}".take(40)
        auditService.log(ev, admin.id, null, null, 'mine')
        auditService.log(ev, otherActor.id, null, null, 'theirs')

        when:
        def rows = adminController.audit(ev, admin.id, null, null, signedIn(admin.id)).body

        then:
        rows*.summary == ['mine']
    }

    // ── Reviews ───────────────────────────────────────────────────

    def "staff cannot remove reviews of their own stall"() {
        given:
        def adminSeller = user('ADMIN')
        def buyer = user()
        def review = reviewRepo.save(new Review(fromUserId: buyer.id, toUserId: adminSeller.id, tradeId: System.nanoTime(),
            rating: 1, comment: 'bad', fromDisplayName: 'b'))

        when:
        reviewService.adminDeleteReview(adminSeller.id, review.id, 'cleanup')

        then:
        def e = thrown(BadRequestException)
        e.code == 'CANT_MODERATE_OWN_STALL'
        reviewRepo.findById(review.id).isPresent()
    }
}
