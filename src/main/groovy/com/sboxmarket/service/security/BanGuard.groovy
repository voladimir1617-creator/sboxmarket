package com.sboxmarket.service.security

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Single-responsibility guard that rejects state-changing operations from
 * banned users. Extracted from AdminService so that services which only need
 * the ban check (TradeService, OfferService, SellService, …) don't have to
 * pull the entire AdminService graph — that coupling used to force a @Lazy
 * AdminService injection to break a TradeService ↔ AdminService cycle.
 */
@Component
class BanGuard {

    @Autowired SteamUserRepository steamUserRepository

    /** Any controller / service doing a write op should call this at the top. */
    void assertNotBanned(Long userId) {
        if (userId == null) return
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user != null && Boolean.TRUE.equals(user.banned)) {
            throw new ForbiddenException("Your account is banned: " + (user.banReason ?: 'contact support'))
        }
    }

    /** Non-throwing variant for background jobs (sweeper) that need a boolean check. */
    boolean isBanned(Long userId) {
        if (userId == null) return false
        def user = steamUserRepository.findById(userId).orElse(null)
        user != null && Boolean.TRUE.equals(user.banned)
    }
}
