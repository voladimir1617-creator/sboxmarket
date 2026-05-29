package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * HTTP pin for GET /api/profile/security-activity covering the buyer-side
 * Trade Protection money-path gap.
 *
 * TradeProtectionService.autoClaim writes a TRADE_PROTECTION_CLAIMED audit
 * row with subjectUserId = protection.buyerUserId every time a protected
 * trade fails and the platform pays the full item price into the buyer's
 * wallet; reverseClaim writes a matching TRADE_PROTECTION_REVERSED row when
 * staff overturns a paid claim and yanks the cover back out. Both touch
 * the buyer's wallet for the FULL covered amount — potentially thousands
 * of dollars per row — and so belong on the buyer's security-activity feed
 * right next to the WITHDRAW_* / DEPOSIT_COMPLETE / REFUND_ISSUED rows the
 * feed already surfaces.
 *
 * Without these two event types on the whitelist the buyer is blind to
 * exactly the kind of money-movement an account-takeover attacker would
 * use to drain cover (open a protected trade, file a dispute → autoClaim
 * pays the cover to the wallet → attacker withdraws). The audit row is
 * written, bySubject() returns it, the consumer-side findAll filter then
 * drops it. Mirrors the wave-109/110/111 TRADE_AUTO_RELEASED gap pinned
 * by SecurityActivityTradeAutoReleasedSpec.
 *
 * Also pins the negative — a non-whitelisted protection event must NOT
 * leak, so a future contributor extending the whitelist can't accidentally
 * broaden the filter to e.g. `r.eventType.startsWith('TRADE_PROTECTION_')`
 * and expose ops-internal rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityActivityTradeProtectionSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc             mockMvc
    SteamUserRepository steamUserRepository
    AuditLogRepository  auditLogRepository

    SteamUser       buyer
    MockHttpSession session

    def setup() {
        mockMvc             = ctx.getBean(MockMvc)
        steamUserRepository = ctx.getBean(SteamUserRepository)
        auditLogRepository  = ctx.getBean(AuditLogRepository)

        def uniq = String.valueOf(System.nanoTime())
        buyer = steamUserRepository.save(new SteamUser(
            steamId64:   "76561199" + uniq.substring(uniq.length() - 9),
            displayName: "SecActProt-${uniq}"
        ))

        long now = System.currentTimeMillis()
        // autoClaim-style row: actor=null (system), subject=buyer, matches
        // the literal AuditService.log() call at
        // TradeProtectionService:308. Summary text is the load-bearing
        // assertion — without the whitelist patch, the row exists in the
        // DB and bySubject() returns it, but the consumer-side findAll
        // drops it because TRADE_PROTECTION_CLAIMED wasn't in the set.
        auditLogRepository.save(new AuditLog(
            eventType:     'TRADE_PROTECTION_CLAIMED',
            actorUserId:   null,
            subjectUserId: buyer.id,
            resourceId:    777L,
            createdAt:     now,
            summary:       'Protection claim paid out $123.45: Trade disputed by buyer'))

        // reverseClaim-style row: actor=null (system), subject=buyer,
        // matches TradeProtectionService:408. The reversal debits the
        // buyer wallet, so the buyer absolutely must see the corresponding
        // "money was clawed back from you" entry on their security feed.
        auditLogRepository.save(new AuditLog(
            eventType:     'TRADE_PROTECTION_REVERSED',
            actorUserId:   null,
            subjectUserId: buyer.id,
            resourceId:    777L,
            createdAt:     now - 500L,
            summary:       'Protection claim reversed, $123.45 reclaimed: trade released as valid'))

        // Internal-only event the user should NEVER see — pin the negative
        // so a future whitelist contributor can't broaden the filter to
        // e.g. `r.eventType.startsWith('TRADE_PROTECTION_')` and leak
        // staff workflow rows. ADMIN_NOTES_UPDATED is the canonical
        // staff-only row used by the sibling auto-released spec.
        auditLogRepository.save(new AuditLog(
            eventType:     'ADMIN_NOTES_UPDATED',
            actorUserId:   42L,
            subjectUserId: buyer.id,
            createdAt:     now - 1_000L,
            summary:       'private-internal-staff-only-notes'))

        session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, buyer.id)
    }

    def "GET /security-activity surfaces TRADE_PROTECTION_CLAIMED to the subject buyer"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/profile/security-activity').session(session)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // The autoClaim audit row must surface so the buyer can see "the
        // platform paid $X into your wallet for your failed trade" in
        // their own security feed.
        body.contains('TRADE_PROTECTION_CLAIMED')
        body.contains('Protection claim paid out $123.45')
        // Whitelist-negative pin — ADMIN_NOTES_UPDATED must NOT leak even
        // though it shares the subjectUserId scope.
        !body.contains('ADMIN_NOTES_UPDATED')
        !body.contains('private-internal-staff-only-notes')
    }

    def "GET /security-activity surfaces TRADE_PROTECTION_REVERSED to the subject buyer"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/profile/security-activity').session(session)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        // The reverseClaim audit row must surface so the buyer can see
        // "$X was clawed back from your wallet" in their security feed —
        // a debit they did not initiate themselves.
        body.contains('TRADE_PROTECTION_REVERSED')
        body.contains('Protection claim reversed, $123.45 reclaimed')
    }
}
