package com.sboxmarket.service.security

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Narrow admin-role check. Separated from AdminService so that cross-cutting
 * code paths (e.g. TradeService dispute flow) can depend on the minimal
 * interface they actually need instead of the full admin facade. Clean ISP
 * — a client that only needs to know "is this user an admin?" shouldn't
 * depend on the methods that ban users or process withdrawals.
 */
@Component
class AdminAuthorization {

    @Autowired SteamUserRepository steamUserRepository

    void requireAdmin(Long userId) {
        def user = steamUserRepository.findById(userId)
                .orElseThrow { new ForbiddenException("Unknown user") }
        if (user.role != 'ADMIN') {
            throw new ForbiddenException("Admin privileges required")
        }
    }

    boolean isAdmin(Long userId) {
        if (userId == null) return false
        def user = steamUserRepository.findById(userId).orElse(null)
        user?.role == 'ADMIN'
    }
}
