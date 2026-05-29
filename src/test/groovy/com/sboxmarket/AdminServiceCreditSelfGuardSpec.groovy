package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.AdminAuthorization
import spock.lang.Specification

/**
 * Wave 143 regression pin for {@link AdminService#creditWallet}.
 *
 * Pre-fix an admin could credit (or debit) their OWN wallet — up to
 * the $10k single-call cap, with an operator-written audit note
 * attributing the grant to themselves. The audit row's
 * subjectUserId == admin's own id meant the action wouldn't appear
 * under any other user's audit filter, and the only forensic trail
 * was an ADMIN_CREDIT row whose actor and subject collapse. The hard
 * cap + note requirement do nothing to deter a corrupt admin from
 * walking $10k home every day.
 *
 * Mirrors banUser / grantAdmin / forceLogout / grantCsr which already
 * have self-target guards. creditWallet was the last admin mutation
 * missing it — and the most exploitable.
 */
class AdminServiceCreditSelfGuardSpec extends Specification {

    AdminAuthorization adminAuthorization = Mock()
    SteamUserRepository steamUserRepository = Mock()
    WalletRepository walletRepository = Mock()
    TransactionRepository transactionRepository = Mock()
    NotificationService notificationService = Mock()
    TextSanitizer textSanitizer = Mock()

    AdminService service = new AdminService(
        adminAuthorization:    adminAuthorization,
        steamUserRepository:   steamUserRepository,
        walletRepository:      walletRepository,
        transactionRepository: transactionRepository,
        notificationService:   notificationService,
        textSanitizer:         textSanitizer
    )

    def setup() {
        adminAuthorization.requireAdmin(_) >> { /* no-op for happy paths */ }
    }

    def "creditWallet refuses an admin's attempt to credit their own wallet"() {
        when:
        service.creditWallet(7L, 7L, new BigDecimal('500'), 'pls')

        then: "no wallet lookup or save — guard fires before any IO"
        BadRequestException ex = thrown()
        ex.code == 'CANT_CREDIT_SELF'
        0 * walletRepository.findByUsername(_)
        0 * walletRepository.save(_)
        0 * transactionRepository.save(_)
    }

    def "creditWallet refuses an admin's attempt to DEBIT their own wallet too (symmetry)"() {
        when:
        service.creditWallet(7L, 7L, new BigDecimal('-500'), 'pls')

        then: "self-target guard is amount-direction-agnostic — debit-self also blocked"
        BadRequestException ex = thrown()
        ex.code == 'CANT_CREDIT_SELF'
        0 * walletRepository.save(_)
    }

    def "creditWallet still proceeds when admin credits a DIFFERENT user (regression guard)"() {
        given:
        steamUserRepository.findById(42L) >> Optional.of(new SteamUser(id: 42L, steamId64: '76561198000000042'))
        walletRepository.findByUsername('steam_76561198000000042') >> new Wallet(id: 99L, balance: new BigDecimal('100'), frozen: false)
        textSanitizer.medium(_) >> { String s -> s }

        when:
        service.creditWallet(7L, 42L, new BigDecimal('500'), 'goodwill')

        then: "guard passes — wallet save happens"
        1 * walletRepository.save(_) >> { Wallet w -> w }
    }
}
