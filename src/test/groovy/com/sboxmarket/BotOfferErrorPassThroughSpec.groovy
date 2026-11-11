package com.sboxmarket

import com.sboxmarket.config.GlobalExceptionHandler
import com.sboxmarket.exception.ApiException
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Trade
import com.sboxmarket.service.BotDeliveryOfferGuard
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The bot-offer cancel refusals and the seller payout pauses carry copy
 * written for the customer ("the buyer already has the item, nothing was
 * refunded", "your balance was not charged, try again in a day or two").
 * They are longer than the handler's 140-char safety cutoff, so before they
 * were allow-listed the client saw only "Request could not be completed".
 */
class BotOfferErrorPassThroughSpec extends Specification {

    GlobalExceptionHandler handler = new GlobalExceptionHandler(verboseErrors: false)

    private static MockHttpServletRequest req() { new MockHttpServletRequest('POST', '/api/trades/7/cancel') }

    private ApiException thrownByGuard(BotDeliveryOfferGuard.Outcome outcome, String state, String detail) {
        BotDeliveryOfferGuard guard = Spy(BotDeliveryOfferGuard) {
            cancelLiveOffer(_) >> new BotDeliveryOfferGuard.Result(
                outcome: outcome, offerId: '9007', state: state, detail: detail)
        }
        try {
            guard.requireNoLiveOfferBeforeRefund(new Trade(id: 7L))
        } catch (ApiException e) {
            return e
        }
        throw new AssertionError('guard did not throw')
    }

    @Unroll
    def "guard refusal #code reaches the client with its real message"() {
        given:
        def ex = thrownByGuard(outcome, state, detail)

        when:
        def resp = handler.handleApi(ex, req())

        then:
        ex.code == code
        resp.body.code == code
        resp.body.message == ex.message
        resp.body.message.contains('nothing was refunded')

        where:
        outcome                                  | state      | detail                               || code
        BotDeliveryOfferGuard.Outcome.ACCEPTED   | 'accepted' | null                                 || 'OFFER_ALREADY_ACCEPTED'
        BotDeliveryOfferGuard.Outcome.FAILED     | 'active'   | 'TRANSPORT_ERROR: offer not found'   || 'OFFER_CANCEL_FAILED'
    }

    @Unroll
    def "#code (#len chars) is passed through, not cut to the generic line"() {
        when:
        def resp = handler.handleApi(new BadRequestException(code, message), req())

        then:
        resp.body.code == code
        resp.body.message == message

        where:
        code                           | message
        // TradeService.cancel
        'OFFER_IN_FLIGHT'              | "Our bot has already sent the Steam trade offer for this trade. If something is wrong, open a dispute instead."
        'BUYER_CANT_CANCEL_AFTER_SENT' | "Seller has already marked the Steam trade offer as sent. If you didn't receive the item or the wrong item arrived, open a dispute instead."
        // AdminService.forceReleaseTrade
        'BOT_OFFER_NOT_ACCEPTED'       | "Not released: the bot's trade offer 9007 was never accepted (active), so the item is still in our bot. Cancel the trade to refund the buyer and return the item to the seller instead."
        // StripeService.requestWithdrawal — payouts paused while funds clear
        'PAYOUT_FUNDS_PENDING'         | "Payouts are paused for a short while because recent card payments are still clearing with our payment processor. Your balance was not charged. Please try again in a day or two."
        // WalletController.withdraw holds
        'WITHDRAW_DISPUTE_HOLD'        | "Withdrawals are paused while you have 2 unresolved deposit disputes on file. Open a support ticket if you believe this is a mistake."
        'WITHDRAW_CLAIM_HOLD'          | "Withdrawals are paused while 12 protected trades are in dispute. They resume once support resolves the dispute."
        len = message.length()
    }

    def "an unlisted code with a long message is still cut to the generic line"() {
        when:
        def resp = handler.handleApi(new BadRequestException('SOMETHING_ELSE', 'x' * 200), req())

        then:
        resp.body.message == 'Request could not be completed'
    }
}
