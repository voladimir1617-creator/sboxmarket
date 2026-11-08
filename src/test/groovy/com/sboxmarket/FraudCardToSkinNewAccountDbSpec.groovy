package com.sboxmarket

import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.FraudAnalysisService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * CARD_TO_SKIN_NEW_ACCOUNT against the real audit / user / listing tables:
 * SteamUser.createdAt drives account age and Listing.price drives the total.
 */
@SpringBootTest
@ActiveProfiles("test")
class FraudCardToSkinNewAccountDbSpec extends Specification {

    static final long MIN = 60_000L
    static final long DAY = 24L * 60L * MIN

    @Autowired FraudAnalysisService fraudAnalysisService
    @Autowired SteamUserRepository userRepo
    @Autowired AuditLogRepository auditRepo
    @Autowired ListingRepository listingRepo
    @Autowired ItemRepository itemRepo

    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String tag, long createdAt) {
        def tail = (uniq + tag).reverse().take(10).reverse()
        userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "C2S-${tag}-${uniq}", role: 'USER', createdAt: createdAt))
    }

    private Listing soldListing(Item item, String price) {
        listingRepo.save(new Listing(item: item, sellerUserId: 1L, sellerName: 'seller',
            price: new BigDecimal(price), status: 'SOLD'))
    }

    private void audit(String event, Long uid, Long resourceId, long ts, String summary = 'x') {
        auditRepo.save(new AuditLog(actorUserId: uid, actorName: "u${uid}", subjectUserId: uid,
            eventType: event, resourceId: resourceId, summary: summary, ipAddress: '10.9.9.9', createdAt: ts))
    }

    def "new account card deposit then purchases: HIGH at \$200+, old account quiet"() {
        given:
        def now = System.currentTimeMillis()
        def item = itemRepo.save(new Item(name: "C2S ${uniq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('10'), isListed: true, iconEmoji: '🎩'))
        def fresh = user('a', now - 2 * DAY)
        def veteran = user('b', now - 90 * DAY)
        def l1 = soldListing(item, '180.00')
        def l2 = soldListing(item, '25.00')
        def l3 = soldListing(item, '400.00')

        // Summary says $1 so a pass proves the total came from Listing.price.
        audit(AuditService.DEPOSIT_COMPLETE, fresh.id, null, now - 40 * MIN)
        audit(AuditService.LISTING_PURCHASED, fresh.id, l1.id, now - 30 * MIN, 'Bought C2S for $1')
        audit(AuditService.LISTING_PURCHASED, fresh.id, l2.id, now - 20 * MIN, 'Bought C2S for $1')

        audit(AuditService.DEPOSIT_COMPLETE, veteran.id, null, now - 40 * MIN)
        audit(AuditService.LISTING_PURCHASED, veteran.id, l3.id, now - 30 * MIN, 'Bought C2S for $400.00')

        when:
        def mine = fraudAnalysisService.computeSignals().findAll {
            it.type == 'CARD_TO_SKIN_NEW_ACCOUNT' && it.userId in [fresh.id, veteran.id]
        }

        then:
        mine.size() == 1
        mine[0].userId == fresh.id
        mine[0].severity == 'HIGH'
        mine[0].count == 2
        mine[0].total == new BigDecimal('205.00')

        and: 'the signal changes nothing'
        listingRepo.findById(l1.id).get().status == 'SOLD'
        !userRepo.findById(fresh.id).get().banned
    }
}
