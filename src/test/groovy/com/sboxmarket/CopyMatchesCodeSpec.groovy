package com.sboxmarket

import com.sboxmarket.service.SupportService
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Visitor-facing copy has to say what the code does:
 *  - the Verified badge is granted at 10+ sales with a 4★+ average OR no
 *    reviews yet (SellerStatsController), so no line may say 4+ stars alone;
 *  - the Stripe reference is shown under Wallet → History, not on Trades;
 *  - a listing turns SOLD at purchase (PurchaseService), so "marked sold"
 *    never means the buyer confirmed receipt;
 *  - an auto-bid raise is skipped when the wallet can't cover it (BidService);
 *  - price alerts fire from a 5-minute sweep (WatchlistAlertService), not
 *    the instant a listing appears;
 *  - the ♡ watchlist sends no email; only a hit price alert does.
 */
class CopyMatchesCodeSpec extends Specification {

    static final String APP = 'src/main/resources/static/js/app.js'
    static final String MODALS = 'src/main/resources/static/js/modals.js'
    static final String CSFLOAT_MODALS = 'src/main/resources/static/js/csfloat-modals.js'
    static final String HELP = 'src/main/resources/static/js/help-modal.js'
    static final String STAFF_MODALS = 'src/main/resources/static/js/staff-modals.js'
    static final String GROOVY = 'src/main/groovy/com/sboxmarket/service/'

    @Unroll
    def "#file never says a verified seller is rated 4+ stars without the no-reviews case"() {
        given:
        def text = new File(file).text

        expect:
        !text.contains('10+ completed sales at 4+ stars')
        !text.contains('10+ completed sales at 4+ star avg')
        !text.contains('Verified seller · 10+ sales · ≥4.0★')

        where:
        file << [APP, MODALS]
    }

    def "the seller page and search row use the shared Verified rule"() {
        given:
        def app = new File(APP).text
        def modals = new File(MODALS).text

        expect:
        app.contains("h('strong', null, VERIFIED_SELLER_RULE)")
        app.contains("title: VERIFIED_SELLER_RULE,")
        modals.contains("title: VERIFIED_SELLER_RULE")
        modals =~ /import \{ GridCard, VERIFIED_SELLER_RULE \} from '\.\/cards\.js\?v=\d+'/
    }

    def "the payment auto-reply points to Wallet → History for the Stripe reference"() {
        given:
        String reply = SupportService.autoReply('PAYMENT')

        expect:
        !reply.contains('Trades tab')
        reply.contains('Wallet → History')
    }

    def "the trade auto-reply does not say a sold listing means the buyer confirmed"() {
        given:
        String reply = SupportService.autoReply('TRADE')

        expect:
        !reply.contains('marked sold')
        !reply.contains('confirmed receipt')
    }

    def "the auto-bid line says the cap holds only while the wallet covers it"() {
        expect:
        new File(CSFLOAT_MODALS).text.contains("we\\'ll keep you on top up to this amount while your wallet covers it.")
    }

    def "price-alert copy matches the 5-minute sweep and the email it really sends"() {
        given:
        def text = new File(MODALS).text

        expect:
        !text.contains('the instant an ACTIVE listing dips')
        text.contains('within about 5 minutes of when an ACTIVE listing dips')
        !text.contains('fires the moment any listing appears')
        text.contains('fires within a few minutes of a listing appearing')
        !text.contains('Price-drop emails for items you are watching')
        text.contains('Emails when a price alert you set is hit')
    }

    /** Profile's tabs are Personal Info, Listings, Transactions, Buy Orders,
     *  Active Bids, Trades, Offers, Reviews, Support and Developers; 2FA,
     *  email settings, sign-out-everywhere and the trade URL all sit on
     *  Personal Info. Wallet is its own page, and a returned item is
     *  relisted from Sell → Platform Inventory (ListingController /inventory). */
    @Unroll
    def "#file only points users at Profile tabs that exist"() {
        given:
        def text = new File(file).text

        expect:
        !text.contains('Profile → Security')
        !text.contains('Profile → 2FA')
        !text.contains('Profile → Settings')
        !text.contains('Profile → Steam settings')
        !text.contains('Profile → Wallet')
        !text.contains('Profile → Sold')

        where:
        file << [GROOVY + 'EmailService.groovy', GROOVY + 'TradeService.groovy',
                 GROOVY + 'AdminService.groovy', HELP, STAFF_MODALS]
    }

    def "cancelled-trade copy sends sellers to the screen that relists"() {
        expect:
        new File(GROOVY + 'EmailService.groovy').text.contains('Relist it from Sell → Platform Inventory')
        new File(GROOVY + 'TradeService.groovy').text.contains('Relist from Sell → Platform Inventory')
    }

    def "the sign-in alert says what 'new' means: no sign-in from that IP in 30 days"() {
        given:
        def text = new File(GROOVY + 'EmailService.groovy').text

        expect:
        !text.contains('new IP address')
        text.contains("IP address we haven't seen on your account in the last 30 days")
    }

    @Unroll
    def "#file names the public inventory the Sell flow reads, not only the profile"() {
        given:
        def text = new File(file).text

        expect:
        !text.contains('public Steam profile via OpenID')
        text.contains('public Steam profile and, when you sell, your public inventory')

        where:
        file << [APP, HELP]
    }
}
