package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Orchestrates a purchase: verify funds, debit wallet, mark listing sold,
 * record transactions for both sides, transfer ownership to buyer.
 *
 * Single-Responsibility: this class only knows about the BUY flow.
 * It depends on repositories (interfaces), not concrete DB implementations.
 */
@Service
@Slf4j
class PurchaseService {

    @Autowired ListingRepository listingRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired BanGuard banGuard
    @Autowired(required = false) AuditService auditService
    // Needed to resolve the seller's wallet via their Steam64 — every other
    // service keys wallets on `"steam_${steamId64}"`, not the numeric user id.
    @Autowired(required = false) SteamUserRepository steamUserRepository

    @Transactional
    Map buy(Long buyerWalletId, Long buyerUserId, Long listingId) {
        banGuard.assertNotBanned(buyerUserId)
        def buyerWallet = walletRepository.findById(buyerWalletId)
                .orElseThrow { new NotFoundException("Wallet", buyerWalletId) }
        def listing = listingRepository.findById(listingId)
                .orElseThrow { new NotFoundException("Listing", listingId) }

        if (listing.status != 'ACTIVE') {
            throw new ListingNotAvailableException(listingId)
        }
        // AUCTION listings settle via BidService.settle() when expiresAt
        // passes — not via this BUY_NOW path. Without this guard, anyone
        // could sidestep the bidding mechanism by POSTing /api/listings/
        // {id}/buy on an auction, orphaning the current high bidder and
        // collapsing the whole bid history. Force auction purchases back
        // into the bid surface so the auction winner is whoever actually
        // bid highest.
        if (listing.listingType == 'AUCTION') {
            throw new BadRequestException("NOT_BUY_NOW",
                "This is an auction listing — place a bid instead of buying directly")
        }
        if (listing.buyerUserId != null && listing.buyerUserId == buyerUserId) {
            throw new BadRequestException("ALREADY_OWNED", "You already own this listing")
        }
        if (listing.sellerUserId != null && listing.sellerUserId == buyerUserId) {
            throw new BadRequestException("OWN_LISTING", "You can't buy your own listing")
        }
        if (buyerWallet.balance < listing.price) {
            throw new InsufficientBalanceException(listing.price, buyerWallet.balance)
        }

        // Debit buyer
        buyerWallet.balance = buyerWallet.balance - listing.price
        walletRepository.save(buyerWallet)

        // Mark listing sold + transfer ownership
        listing.status = 'SOLD'
        listing.soldAt = System.currentTimeMillis()
        listing.buyerUserId = buyerUserId
        listingRepository.save(listing)

        // Record transaction on buyer side
        def buyerTx = new Transaction(
            walletId       : buyerWalletId,
            type           : 'PURCHASE',
            status         : 'COMPLETED',
            amount         : listing.price,
            currency       : buyerWallet.currency,
            stripeReference: 'wallet',
            description    : "Bought ${listing.item.name} from ${listing.sellerName}",
            listingId      : listing.id
        )
        transactionRepository.save(buyerTx)

        // If a real seller user exists, credit their wallet (minus 2% platform fee).
        // Wallet usernames are keyed on "steam_${steamId64}" everywhere in the
        // app. The previous version hit "steam_user_${sellerUserId}" which
        // never matched a real wallet, so sellers were silently unpaid on
        // every BuyNow purchase.
        if (listing.sellerUserId != null && steamUserRepository != null) {
            def sellerUser = steamUserRepository.findById(listing.sellerUserId).orElse(null)
            def sellerWallet = sellerUser ? walletRepository.findByUsername("steam_${sellerUser.steamId64}") : null
            if (sellerWallet != null) {
                def fee = (listing.price * new BigDecimal('0.02')).setScale(2, BigDecimal.ROUND_HALF_UP)
                def credit = listing.price - fee
                sellerWallet.balance = sellerWallet.balance + credit
                walletRepository.save(sellerWallet)
                transactionRepository.save(new Transaction(
                    walletId       : sellerWallet.id,
                    type           : 'SALE',
                    status         : 'COMPLETED',
                    amount         : credit,
                    currency       : sellerWallet.currency,
                    stripeReference: 'wallet',
                    description    : "Sold ${listing.item.name} (-\$${fee} fee)",
                    listingId      : listing.id
                ))
            }
        }

        // Append-only audit trail — fires after the transaction is persisted
        // so failed attempts never pollute the log.
        try {
            auditService?.log(AuditService.LISTING_PURCHASED, buyerUserId, listing.sellerUserId, listing.id,
                "Bought ${listing.item?.name} for \$${listing.price}")
        } catch (Exception ignore) {}

        // Persisted notifications (buyer + seller). NotificationService is optional
        // in unit tests that use mocked collaborators — guard the null case.
        if (notificationService != null) {
            try {
                notificationService.push(buyerUserId, 'ITEM_PURCHASED',
                    "Purchased ${listing.item.name}",
                    "Paid \$${listing.price.toPlainString()} from balance",
                    listing.id)
                if (listing.sellerUserId != null) {
                    notificationService.push(listing.sellerUserId, 'TRADE_VERIFIED',
                        "Sold ${listing.item.name}",
                        "Buyer paid \$${listing.price.toPlainString()}",
                        listing.id)
                }
            } catch (Exception e) {
                log.warn("notification fire failed: ${e.message}")
            }
        }

        log.info("Buyer wallet $buyerWalletId bought listing $listingId for \$${listing.price}")
        [
            transactionId: buyerTx.id,
            newBalance   : buyerWallet.balance,
            listing      : listing
        ]
    }
}
