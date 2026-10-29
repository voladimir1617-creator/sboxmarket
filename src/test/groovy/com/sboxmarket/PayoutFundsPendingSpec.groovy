package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.StripeService
import com.stripe.exception.InvalidRequestException
import spock.lang.Specification

/**
 * The first seller cash-out on a new live Stripe account: card deposits are
 * still in the platform's PENDING balance, so Transfer.create is refused with
 * balance_insufficient. The seller must get a plain PAYOUT_FUNDS_PENDING
 * answer (not "Stripe error, try again"), and nothing may be written.
 */
class PayoutFundsPendingSpec extends Specification {

    TransactionRepository transactionRepository = Mock()
    WalletRepository      walletRepository      = Mock()

    StripeService service = new StripeService(
        transactionRepository: transactionRepository,
        walletRepository:      walletRepository,
        steamUserRepository:   Mock(SteamUserRepository),
        auditService:          Mock(AuditService),
        notificationService:   Mock(NotificationService),
        secretKey:             'sk_test_live_unit_fake',
        publishableKey:        'pk_test_live_unit_fake',
        webhookSecret:         'whsec_unit_fake',
        successUrl:            'http://localhost/ok',
        cancelUrl:             'http://localhost/cancel',
        currency:              'usd'
    )

    private Wallet sellerWallet() {
        def wallet = new Wallet(id: 901L, balance: new BigDecimal('50.00'), username: 'steam_76561198000000901',
            payoutsEnabled: true, stripeConnectAccountId: 'acct_unit_test')
        walletRepository.findById(901L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.countByWalletAndType(_, _) >> 0L
        wallet
    }

    def "balance_insufficient from Stripe becomes PAYOUT_FUNDS_PENDING and records no withdrawal"() {
        given:
        sellerWallet()
        GroovySpy(com.stripe.model.Transfer, global: true)
        com.stripe.model.Transfer.create(_, _) >> {
            throw new InvalidRequestException('Insufficient funds in Stripe account.', null, 'req_1',
                'balance_insufficient', 400, null)
        }

        when:
        service.requestWithdrawal(901L, new BigDecimal('20.00'), 'acct_unit_test')

        then:
        def e = thrown(BadRequestException)
        e.code == 'PAYOUT_FUNDS_PENDING'
        e.message.contains('not charged')
        0 * transactionRepository.save(_)
    }

    def "any other Stripe failure keeps the generic retryable error"() {
        given:
        sellerWallet()
        GroovySpy(com.stripe.model.Transfer, global: true)
        com.stripe.model.Transfer.create(_, _) >> {
            throw new InvalidRequestException('No such destination', 'destination', 'req_2',
                'resource_missing', 400, null)
        }

        when:
        service.requestWithdrawal(901L, new BigDecimal('20.00'), 'acct_unit_test')

        then:
        thrown(IllegalStateException)
        0 * transactionRepository.save(_)
    }

    def "the check reads the Stripe code through wrapping"() {
        expect:
        StripeService.isInsufficientPlatformBalance(new RuntimeException('wrap',
            new InvalidRequestException('x', null, 'r', 'balance_insufficient', 400, null)))
        !StripeService.isInsufficientPlatformBalance(new RuntimeException('plain'))
        !StripeService.isInsufficientPlatformBalance(null)
    }
}
