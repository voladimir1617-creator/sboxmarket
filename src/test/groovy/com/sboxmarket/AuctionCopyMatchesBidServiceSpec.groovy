package com.sboxmarket

import com.sboxmarket.service.BidService
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The auction help copy has to say what BidService does:
 *  - a late bid moves the close to 30s AFTER that bid (it does not add
 *    30s to the clock), capped at 20 extensions;
 *  - the $100 increment starts at $5,000, not at four figures;
 *  - the first bid only has to meet the starting price;
 *  - settle charges the winner and opens a trade; the seller is paid
 *    only when that trade is confirmed, not at the close.
 */
class AuctionCopyMatchesBidServiceSpec extends Specification {

    static final String HELP = 'src/main/resources/static/js/help-modal.js'
    static final String MODALS = 'src/main/resources/static/js/modals.js'
    static final String APP = 'src/main/resources/static/js/app.js'

    def "the \$100 increment starts at \$5,000"() {
        expect:
        BidService.incrementFor(new BigDecimal('1000')) == new BigDecimal('25')
        BidService.incrementFor(new BigDecimal('4999.99')) == new BigDecimal('25')
        BidService.incrementFor(new BigDecimal('5000')) == new BigDecimal('100')
    }

    @Unroll
    def "#file states the increment range and first-bid rule the code uses"() {
        given:
        def text = new File(file).text

        expect:
        !text.contains('four-figure')
        text.contains('up to $100 from $5,000')
        text.contains('the first bid must meet the starting price')

        where:
        file << [HELP, MODALS]
    }

    def "the home Live Auctions tile states the real anti-snipe rule"() {
        given:
        def text = new File(APP).text

        expect:
        !text.contains('adds 30 more to the clock')
        text.contains('A bid in the last 30 seconds moves the close to 30 seconds after that bid, up to 20 times.')
    }

    def "the buyer FAQ says the seller is paid once delivery is confirmed"() {
        given:
        def text = new File(HELP).text

        expect:
        !text.contains('the seller is credited (minus 2%)')
        text.contains("the winner's wallet is charged and a trade opens; the seller is paid (minus 2%) once delivery is confirmed.")
    }
}
