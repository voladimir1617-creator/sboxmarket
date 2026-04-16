package com.sboxmarket

import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.SeedService
import spock.lang.Specification
import spock.lang.Subject

class SeedServiceSpec extends Specification {

    WalletRepository walletRepository = Mock()

    @Subject
    SeedService service = new SeedService(
        walletRepository: walletRepository
    )

    def "seed creates demo wallet when the wallet table is empty"() {
        given:
        walletRepository.count() >> 0L

        when:
        service.seed()

        then:
        1 * walletRepository.save({ Wallet w ->
            w.username == 'demo' && w.balance == new BigDecimal("250.00") && w.currency == 'USD'
        })
    }

    def "seed does NOT recreate the demo wallet on subsequent boots"() {
        given:
        walletRepository.count() >> 5L

        when:
        service.seed()

        then:
        0 * walletRepository.save(_)
    }
}
