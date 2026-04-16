package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Aggregated profile view — bundles the user entity with every piece of data
 * the expanded Profile modal needs (balance, stats, counts) so the frontend
 * makes one fetch instead of six.
 */
@Service
@Slf4j
class ProfileService {

    @Autowired SteamUserRepository steamUserRepository
    @Autowired WalletRepository walletRepository
    @Autowired ListingRepository listingRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired OfferRepository offerRepository
    @Autowired BuyOrderRepository buyOrderRepository
    @Autowired BidRepository bidRepository

    @Transactional(readOnly = true)
    Map buildProfile(Long userId) {
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) return null

        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        def balance = wallet?.balance ?: BigDecimal.ZERO
        def walletId = wallet?.id ?: -1L

        // Aggregate each transaction bucket in SQL instead of loading every
        // row and filtering in Groovy. This endpoint fires on every profile
        // fetch (post-login + /me refresh) so the old N-rows-per-view pull
        // scales badly — a user with years of trading history would walk
        // thousands of rows just to render the totals line.
        def totalPurchased = transactionRepository.sumByWalletAndType(walletId, 'PURCHASE', false) ?: BigDecimal.ZERO
        def totalSold      = transactionRepository.sumByWalletAndType(walletId, 'SALE',     false) ?: BigDecimal.ZERO
        def totalDeposited = transactionRepository.sumByWalletAndType(walletId, 'DEPOSIT',  true)  ?: BigDecimal.ZERO
        def purchaseCount  = transactionRepository.countByWalletAndType(walletId, 'PURCHASE')
        def saleCount      = transactionRepository.countByWalletAndType(walletId, 'SALE')
        def withdrawalCount = transactionRepository.countCompletedByWalletAndType(walletId, 'WITHDRAWAL')

        def net = (totalSold as BigDecimal) - (totalPurchased as BigDecimal)

        def activeListings  = listingRepository.findActiveBySeller(userId).size()
        def ownedInventory  = listingRepository.findOwnedBy(userId).size()
        def openBuyOrders   = buyOrderRepository.countActiveByBuyer(userId)
        def activeAutoBids  = bidRepository.findActiveAutoBidsForUser(userId).size()
        def openOffers      = offerRepository.countPendingByBuyer(userId)

        [
            user: user,
            // Derived safe boolean so the frontend can render the 2FA UI
            // state without us having to serialize the secret itself.
            // Before bug #19 the client checked `user.totpSecret` directly
            // which meant the raw base32 secret shipped in every profile
            // response.
            twoFactorEnabled: user.totpSecret != null,
            wallet: [
                balance:  balance,
                currency: wallet?.currency ?: 'USD',
                username: wallet?.username
            ],
            stats: [
                totalPurchased:  totalPurchased,
                totalSold:       totalSold,
                totalDeposited:  totalDeposited,
                net:             net,
                purchaseCount:   purchaseCount,
                saleCount:       saleCount,
                withdrawalCount: withdrawalCount
            ],
            counts: [
                activeListings: activeListings,
                inventory:      ownedInventory,
                openBuyOrders:  openBuyOrders,
                openOffers:     openOffers,
                activeAutoBids: activeAutoBids
            ]
        ]
    }
}
