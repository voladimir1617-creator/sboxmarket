package com.sboxmarket

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * Narrow-interface security guards (BanGuard, AdminAuthorization). Pure
 * lookup logic on top of SteamUserRepository; no network, no Spring.
 *
 * These are called by every write-path service (Purchase, Sell, Offer,
 * Bid, Trade, BuyOrder, Review), so the guarantees they provide must be
 * pinned by tests — a regression here silently lets banned users trade.
 */
class SecurityGuardsSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()

    BanGuard banGuard = new BanGuard(steamUserRepository: steamUserRepository)
    AdminAuthorization adminAuthorization = new AdminAuthorization(steamUserRepository: steamUserRepository)

    // ── BanGuard ──────────────────────────────────────────────────

    def "banGuard.assertNotBanned is a no-op for null userId"() {
        when:
        banGuard.assertNotBanned(null)

        then:
        noExceptionThrown()
        0 * steamUserRepository.findById(_)
    }

    def "banGuard.assertNotBanned passes through for a non-banned user"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, banned: false))

        when:
        banGuard.assertNotBanned(10L)

        then:
        noExceptionThrown()
    }

    def "banGuard.assertNotBanned throws ForbiddenException for a banned user"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(
            new SteamUser(id: 10L, banned: true, banReason: 'suspected laundering')
        )

        when:
        banGuard.assertNotBanned(10L)

        then:
        def e = thrown(ForbiddenException)
        e.message.contains('banned')
        e.message.contains('suspected laundering')
    }

    def "banGuard.assertNotBanned no-ops when user id is unknown"() {
        given:
        steamUserRepository.findById(_) >> Optional.empty()

        when:
        banGuard.assertNotBanned(999L)

        then:
        // Not our job to validate user existence — controllers do that.
        // We only check banned users don't pass.
        noExceptionThrown()
    }

    // ── BanGuard.isBanned (non-throwing variant) ───────────────────

    def "isBanned returns false for null userId"() {
        expect:
        !banGuard.isBanned(null)
    }

    def "isBanned returns false for non-banned user"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, banned: false))

        expect:
        !banGuard.isBanned(10L)
    }

    def "isBanned returns true for banned user"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, banned: true))

        expect:
        banGuard.isBanned(10L)
    }

    def "isBanned returns false for unknown user id"() {
        given:
        steamUserRepository.findById(_) >> Optional.empty()

        expect:
        !banGuard.isBanned(999L)
    }

    // ── AdminAuthorization ────────────────────────────────────────

    def "requireAdmin passes for a user with role=ADMIN"() {
        given:
        steamUserRepository.findById(1L) >> Optional.of(new SteamUser(id: 1L, role: 'ADMIN'))

        when:
        adminAuthorization.requireAdmin(1L)

        then:
        noExceptionThrown()
    }

    def "requireAdmin forbids a regular USER"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'USER'))

        when:
        adminAuthorization.requireAdmin(10L)

        then:
        thrown(ForbiddenException)
    }

    def "requireAdmin forbids a CSR (not ADMIN)"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'CSR'))

        when:
        adminAuthorization.requireAdmin(10L)

        then:
        thrown(ForbiddenException)
    }

    def "requireAdmin forbids an unknown user id"() {
        given:
        steamUserRepository.findById(_) >> Optional.empty()

        when:
        adminAuthorization.requireAdmin(999L)

        then:
        thrown(ForbiddenException)
    }

    def "isAdmin returns false for null"() {
        expect:
        !adminAuthorization.isAdmin(null)
    }

    def "isAdmin returns true only for ADMIN role"() {
        given:
        steamUserRepository.findById(10L) >> Optional.of(new SteamUser(id: 10L, role: 'ADMIN'))
        steamUserRepository.findById(11L) >> Optional.of(new SteamUser(id: 11L, role: 'USER'))
        steamUserRepository.findById(12L) >> Optional.of(new SteamUser(id: 12L, role: 'CSR'))
        steamUserRepository.findById(99L) >> Optional.empty()

        expect:
        adminAuthorization.isAdmin(10L) == true
        adminAuthorization.isAdmin(11L) == false
        adminAuthorization.isAdmin(12L) == false
        adminAuthorization.isAdmin(99L) == false
    }
}
