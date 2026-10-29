package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Notification
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.BidService
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.PurchaseService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * One action, one bell per recipient. A purchase rang "Purchased" and
 * "Escrow opened" for the buyer; an offer acceptance or a buy-order fill
 * added a third; an auction close rang "You won" + "Escrow opened" for the
 * winner and "Auction sold" + "New sale" for the seller. Each recipient now
 * gets the trade bell (trade id + amount), carrying the action's wording.
 */
@SpringBootTest
@ActiveProfiles("test")
class OneBellPerActionSpec extends Specification {

    @Autowired PurchaseService purchaseService
    @Autowired OfferService offerService
    @Autowired BuyOrderService buyOrderService
    @Autowired BidService bidService
    @Autowired SteamUserRepository userRepo
    @Autowired WalletRepository walletRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired NotificationRepository notificationRepo

    Map world(String tag) {
        def uniq = "${tag}${System.nanoTime()}"
        def seller = userRepo.save(new SteamUser(steamId64: "s${uniq}", displayName: 'Sally'))
        def buyer = userRepo.save(new SteamUser(steamId64: "b${uniq}", displayName: 'Bob',
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abcd1234'))
        walletRepo.save(new Wallet(username: "steam_${seller.steamId64}", balance: BigDecimal.ZERO, currency: 'USD'))
        def buyerWallet = walletRepo.save(new Wallet(username: "steam_${buyer.steamId64}",
            balance: new BigDecimal('500'), currency: 'USD'))
        def item = itemRepo.save(new Item(name: "BellItem-${uniq}", category: 'Hats', rarity: 'Limited',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('10.00'), iconEmoji: '🎩'))
        def listing = listingRepo.save(new Listing(item: item, price: new BigDecimal('10.00'), status: 'ACTIVE',
            sellerName: 'Sally', sellerUserId: seller.id, rarityScore: BigDecimal.ZERO))
        [seller: seller, buyer: buyer, buyerWallet: buyerWallet, item: item, listing: listing]
    }

    List<Notification> bells(Long uid) {
        notificationRepo.findForUser(uid, PageRequest.of(0, 50))
    }

    def "a Buy Now purchase rings the buyer once and the seller once"() {
        given:
        def w = world('p')

        when:
        purchaseService.buy(w.buyerWallet.id, w.buyer.id, w.listing.id)

        then:
        bells(w.buyer.id)*.kind == ['TRADE_OPENED']
        bells(w.buyer.id)[0].refId != null
        bells(w.seller.id)*.kind == ['TRADE_REQUESTED']
    }

    def "an accepted offer rings the buyer once, with the offer wording and the trade id"() {
        given:
        def w = world('o')
        def offer = offerService.makeOffer(w.buyer.id, 'Bob', w.listing.id, new BigDecimal('9.00'))
        notificationRepo.deleteAll(bells(w.seller.id))   // the OFFER_RECEIVED ping

        when:
        offerService.acceptOffer(w.seller.id, offer.id)

        then:
        def mine = bells(w.buyer.id)
        mine.size() == 1
        mine[0].kind == 'TRADE_OPENED'
        mine[0].title.startsWith('Offer accepted · ')
        mine[0].body.startsWith('Your $9.00 offer was accepted')
        mine[0].body.contains('$9.00 held in escrow')
        mine[0].refId != null
        bells(w.seller.id)*.kind == ['TRADE_REQUESTED']
    }

    def "a buy order filled by an existing listing rings the buyer once"() {
        given:
        def w = world('f')

        when:
        buyOrderService.create(w.buyer.id, 'Bob', w.item.id, null, null, new BigDecimal('12.00'), 1)

        then:
        def mine = bells(w.buyer.id)
        mine.size() == 1
        mine[0].kind == 'TRADE_OPENED'
        mine[0].title.startsWith('Buy order auto-filled · ')
        mine[0].body.startsWith('Paid $10.00 (cap $12.00) · $10.00 held in escrow')
        bells(w.seller.id)*.kind == ['TRADE_REQUESTED']
    }

    def "an auction close rings the winner once and the seller once"() {
        given:
        def w = world('a')
        def l = w.listing
        l.listingType = 'AUCTION'
        l.currentBid = new BigDecimal('10.00')
        l.currentBidderId = w.buyer.id
        l.expiresAt = System.currentTimeMillis() - 1000L
        l = listingRepo.save(l)

        when:
        bidService.settle(l)

        then:
        def won = bells(w.buyer.id)
        won.size() == 1
        won[0].kind == 'TRADE_OPENED'
        won[0].title.startsWith('You won · ')
        def sold = bells(w.seller.id)
        sold.size() == 1
        sold[0].kind == 'TRADE_REQUESTED'
        sold[0].title.startsWith('Auction sold · ')
        sold[0].body.startsWith('Bob bought it for $10.00')
    }
}
