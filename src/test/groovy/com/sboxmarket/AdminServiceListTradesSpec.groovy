package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import spock.lang.Specification

/**
 * The admin Trades queue read `itemImageUrl` off the Trade entity, which has
 * no such field, so every filter with at least one trade in it answered 500
 * and the tab showed "Couldn't load trades."
 */
class AdminServiceListTradesSpec extends Specification {

    TradeRepository tradeRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    ItemRepository itemRepository = Mock()
    WalletRepository walletRepository = Mock()
    TransactionRepository transactionRepository = Mock()

    AdminService service = new AdminService(
        tradeRepository:       tradeRepository,
        steamUserRepository:   steamUserRepository,
        itemRepository:        itemRepository,
        walletRepository:      walletRepository,
        transactionRepository: transactionRepository
    )

    def "listTrades returns rows for open trades with the item's image"() {
        given:
        def trade = new Trade(id: 5L, listingId: 25L, itemId: 12L, itemName: 'Vintage Design Crop Top',
            buyerUserId: 3L, sellerUserId: 5L, price: new BigDecimal('0.52'), state: 'PENDING_SELLER_ACCEPT')
        tradeRepository.findForAdmin('PENDING_SELLER_ACCEPT') >> [trade]
        steamUserRepository.findAllById(_) >> [
            new SteamUser(id: 3L, steamId64: '1', displayName: 'PixelPusher'),
            new SteamUser(id: 5L, steamId64: '2', displayName: 'EmberWolf')
        ]
        itemRepository.findAllById([12L]) >> [new Item(id: 12L, name: 'Vintage Design Crop Top', imageUrl: 'https://img/x.png')]

        when:
        def rows = service.listTrades('PENDING_SELLER_ACCEPT')

        then:
        rows.size() == 1
        rows[0].id == 5L
        rows[0].itemImageUrl == 'https://img/x.png'
        rows[0].buyerName == 'PixelPusher'
        rows[0].sellerName == 'EmberWolf'
        rows[0].state == 'PENDING_SELLER_ACCEPT'
    }
}
