package com.sboxmarket

import com.sboxmarket.service.TradeService
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * GET /api/listings/delivery-policy — the seller-response deadline, served
 * so the buy surfaces can STATE it rather than guess it.
 *
 * <h3>What this is protecting</h3>
 * A buyer on /item/:id is told his wallet is charged instantly. On a listing
 * owned by another user what he actually receives is a wait: a human has to
 * send a Steam trade offer by hand, and the only bound on that wait is
 * {@code trade.seller-response-days}, read by
 * TradeService.autoCancelStaleSellerTrade. Nothing served that number to the
 * client, so the item page could only say nothing or hard-code a copy — and a
 * copy drifts silently the first time ops changes the property. A deadline
 * quoted wrongly at the moment money moves is how a dispute starts, and this
 * codebase has already paid for exactly that ambiguity on the escrow path.
 *
 * <h3>The two teeth</h3>
 * <ol>
 *   <li><b>The route is reachable.</b> It lives under the same
 *       {@code /api/listings} prefix as {@code @GetMapping("/{id}")}, so a
 *       literal-vs-template mis-ordering would turn the policy call into a
 *       listing lookup for a listing whose id is "delivery-policy" — a 400 or
 *       a 404 that the SPA would read as "no policy" and quietly fall back
 *       from. This asserts the real 200 and the real shape, over HTTP, not
 *       against a hand-built controller.</li>
 *   <li><b>It is the SAME number the sweep uses.</b> The value served is
 *       compared to the one Spring injected into the live TradeService bean.
 *       Two declarations of one policy are two policies the day one of them
 *       is edited: this fails if the controller's default and TradeService's
 *       default ever diverge, which is the only way the page can end up
 *       promising a refund deadline the server does not honour.</li>
 * </ol>
 *
 * Verified RED on 2026-09-23 by changing the controller's default from 3 to
 * 7: the second feature fails with "served 7 != TradeService 3".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeliveryPolicyIsServedNotGuessedSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc mockMvc

    def setup() {
        mockMvc = ctx.getBean(MockMvc)
    }

    def "the delivery policy is reachable over HTTP and carries a usable deadline"() {
        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/delivery-policy'))
                         .andReturn().response

        then: 'the literal path wins over /{id} — a 400/404 here would read to the SPA as "no policy"'
        res.status == 200

        and:
        def body = new JsonSlurper().parseText(res.contentAsString)
        body.sellerResponseDays != null

        and: 'a deadline of zero or less is not a deadline — it would mean "already expired"'
        (body.sellerResponseDays as long) > 0
    }

    def "the number served is the number the auto-cancel sweep actually enforces"() {
        given: 'the live TradeService bean, with whatever Spring injected into it'
        TradeService tradeService = ctx.getBean(TradeService)

        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/delivery-policy'))
                         .andReturn().response
        long served = new JsonSlurper().parseText(res.contentAsString).sellerResponseDays as long

        then: 'one policy, not two — the page must not promise a deadline the sweep will not honour'
        served == tradeService.sellerResponseDays
    }
}
