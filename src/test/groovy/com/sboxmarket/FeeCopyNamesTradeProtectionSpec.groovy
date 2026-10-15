package com.sboxmarket

import spock.lang.Specification
import spock.lang.Unroll

/**
 * The 2% seller fee is not the only fee SkinBox keeps: a buyer can add
 * Trade Protection to a trade for 2% of the price with a $0.25 minimum
 * (TradeProtectionService.PROTECTION_RATE / MIN_FEE), and that is platform
 * revenue. The Help Center and the wallet fee notes once said the seller
 * fee was the only one.
 */
class FeeCopyNamesTradeProtectionSpec extends Specification {

    @Unroll
    def "#file does not call the 2% seller fee the only fee"() {
        given:
        def text = new File(file).text

        expect:
        !(text =~ /(?i)only fee SkinBox keeps/)
        !(text =~ /(?i)our only fee is 2%/)
        text.contains('Trade Protection')

        where:
        file << ['src/main/resources/static/js/help-modal.js',
                 'src/main/resources/static/js/modals.js']
    }
}
