package com.sboxmarket

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.OpsAlertService
import com.sboxmarket.service.SteamBotHealthMonitor
import com.sboxmarket.service.StripeWebhookFailureMonitor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * The stuck-escrow count queries against H2, and the ops-alert block on the
 * admin Health payload, with the real beans wired.
 *
 * Counts are compared as deltas because other specs share this database.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsAlertsDbSpec extends Specification {

    @Autowired EscrowedItemRepository escrowRepository
    @Autowired AdminService adminService
    @Autowired OpsAlertService opsAlertService
    @Autowired SteamBotHealthMonitor steamBotHealthMonitor
    @Autowired StripeWebhookFailureMonitor stripeWebhookFailureMonitor

    static final long DAY = 24L * 60L * 60L * 1000L

    private EscrowedItem row(Map o) {
        escrowRepository.save(new EscrowedItem(
                listingId: System.nanoTime() % 1_000_000_000L,
                sellerUserId: 1L,
                assetId: '555',
                marketHashName: 'OpsDb Hat',
                depositOfferId: 'offer-' + System.nanoTime(),
                custodyState: o.state as String,
                escrowEndsAt: o.endsAt as Long,
                depositAcceptedAt: o.acceptedAt as Long,
                missingSince: o.missing as Long,
                updatedAt: (o.updatedAt ?: System.currentTimeMillis()) as Long))
    }

    def "countOverdueEscrowHolds counts holds past release + grace, and unreadable holds past the longest hold"() {
        given:
        long now = System.currentTimeMillis()
        long releasedBefore = now - 2 * DAY
        long acceptedBefore = now - 17 * DAY
        long base = escrowRepository.countOverdueEscrowHolds(releasedBefore, acceptedBefore)

        when:
        row(state: EscrowedItem.IN_ESCROW_HOLD, endsAt: now - 5 * DAY)                 // overdue
        row(state: EscrowedItem.IN_ESCROW_HOLD, endsAt: now - 1 * DAY)                 // inside grace
        row(state: EscrowedItem.IN_ESCROW_HOLD, endsAt: now + 3 * DAY)                 // hold running
        row(state: EscrowedItem.IN_ESCROW_HOLD, endsAt: null, acceptedAt: now - 20 * DAY) // unreadable, old
        row(state: EscrowedItem.IN_ESCROW_HOLD, endsAt: null, acceptedAt: now - 3 * DAY)  // unreadable, recent
        row(state: EscrowedItem.IN_CUSTODY, endsAt: now - 5 * DAY)                     // arrived

        then:
        escrowRepository.countOverdueEscrowHolds(releasedBefore, acceptedBefore) == base + 2
    }

    def "countMissingFromBotInventory counts only live rows with missingSince set"() {
        given:
        long now = System.currentTimeMillis()
        long base = escrowRepository.countMissingFromBotInventory()

        when:
        row(state: EscrowedItem.IN_CUSTODY, missing: now)
        row(state: EscrowedItem.IN_TRADE_PROTECTION, missing: now)
        row(state: EscrowedItem.IN_CUSTODY, missing: null)
        row(state: EscrowedItem.DELIVERED, missing: now)
        row(state: EscrowedItem.REVERSED, missing: now)

        then:
        escrowRepository.countMissingFromBotInventory() == base + 2
    }

    def "the admin Health payload carries the ops-alert block"() {
        when:
        def health = adminService.systemHealth()

        then:
        health.opsAlerts instanceof Map
        health.opsAlerts.steamBot.configured == false
        health.opsAlerts.stripeWebhook.containsKey('badSignatureTotal')
        health.opsAlerts.stuckEscrow.containsKey('overdueEscrowHolds')
        health.opsAlerts.stuckEscrow.containsKey('missingFromBotInventory')
    }

    def "the alert beans are wired, with email on by default and the bot monitor inert without a bot"() {
        expect:
        opsAlertService.emailEnabled
        opsAlertService.emailService != null
        stripeWebhookFailureMonitor.opsAlertService != null
        steamBotHealthMonitor.failureThreshold == 3

        when:
        steamBotHealthMonitor.checkBotHealth()

        then:
        steamBotHealthMonitor.lastCheckedAt == null
    }
}
