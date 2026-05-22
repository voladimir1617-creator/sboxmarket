package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.PriceHistoryService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.Specification

/**
 * Regression proof for the P1 transaction-poisoning bug.
 *
 * NotificationService.push(), AuditService.log() and
 * PriceHistoryService.record() are best-effort side-effects: ~40 call
 * sites wrap each in a swallowing try/catch whose intent is "a
 * bell/audit/price-history hiccup must NEVER fail the parent purchase /
 * trade / offer".
 *
 * THE BUG (now fixed): all three were @Transactional(REQUIRED). Called
 * inside a caller's transaction they JOINED it, so a failing
 * repository.save() inside them marked the SHARED transaction
 * rollback-only. The swallowing try/catch let the caller "succeed", then
 * the caller's commit threw UnexpectedRollbackException and the REAL
 * purchase/trade/offer was rolled back.
 *
 * THE FIX: the three methods defer their write to run AFTER the caller's
 * transaction commits, via TransactionSynchronizationManager. Post-commit
 * the parent is already durably persisted and its row locks released — a
 * failing deferred write can neither poison the parent nor extend the
 * lock-hold window.
 *
 * This spec drives a REAL transaction (TransactionTemplate over the real
 * PlatformTransactionManager + H2). The "real write" is an Item rename.
 * The "failing side-effect" is forced with a NOT NULL constraint
 * violation: Notification.kind / AuditLog.eventType / PriceHistory
 * columns are all @Column(nullable = false), so a null there throws a
 * DataIntegrityViolationException at save() time — exactly the class of
 * failure the production try/catch blocks were written to swallow.
 *
 * Each "the fix" case is paired with a CONTROL case that calls the
 * repository's save directly inside the same transaction (i.e. the old
 * REQUIRED-join behaviour). The control proves the bug is real: the
 * parent transaction IS poisoned and the commit DOES throw. The fix
 * cases prove the deferral immunises the parent.
 */
@SpringBootTest
@ActiveProfiles("test")
class SideEffectTransactionIsolationIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx

    NotificationService    notificationService
    AuditService           auditService
    PriceHistoryService    priceHistoryService
    NotificationRepository notificationRepository
    AuditLogRepository     auditLogRepository
    PriceHistoryRepository priceHistoryRepository
    ItemRepository         itemRepository
    TransactionTemplate    txTemplate

    def setup() {
        notificationService    = ctx.getBean(NotificationService)
        auditService           = ctx.getBean(AuditService)
        priceHistoryService    = ctx.getBean(PriceHistoryService)
        notificationRepository = ctx.getBean(NotificationRepository)
        auditLogRepository     = ctx.getBean(AuditLogRepository)
        priceHistoryRepository = ctx.getBean(PriceHistoryRepository)
        itemRepository         = ctx.getBean(ItemRepository)
        txTemplate             = new TransactionTemplate(ctx.getBean(PlatformTransactionManager))
    }

    /** Seed a fresh catalogue item — our stand-in for the "real" parent
     *  write (a purchase/trade/offer). Unique name so the spec coexists
     *  with siblings sharing the Spring context. */
    private Item seedItem() {
        def uniq = System.nanoTime()
        itemRepository.save(new Item(
            name:        "IsolationItem-${uniq}",
            category:    'Hats',
            rarity:      'Limited',
            iconEmoji:   '🎩',
            supply:      10,
            totalSold:   0,
            lowestPrice: new BigDecimal('10.00')
        ))
    }

    // ── NotificationService.push() ──────────────────────────────────

    def "a failing notification save does NOT roll back the parent transaction"() {
        given: "a committed item that the parent transaction will rename"
        def item = seedItem()
        def renamed = "renamed-notif-${System.nanoTime()}"

        when: "inside ONE real transaction: rename the item, then push a doomed notification"
        txTemplate.executeWithoutResult { status ->
            // The real parent write.
            item.name = renamed
            itemRepository.save(item)
            // The best-effort side-effect. kind == null violates
            // Notification.kind @Column(nullable = false). Pre-fix this
            // joined the transaction and marked it rollback-only; the fix
            // defers the save to afterCommit so it runs (and fails)
            // post-commit, harmlessly.
            notificationService.push(99L, null, 'Doomed title', 'body')
        }

        then: "the commit succeeded — no UnexpectedRollbackException"
        noExceptionThrown()

        and: "the REAL write is durably persisted"
        itemRepository.findById(item.id).get().name == renamed

        and: "the doomed notification was NOT written (its deferred save failed silently)"
        notificationRepository.findForUser(99L,
            org.springframework.data.domain.PageRequest.of(0, 50)).isEmpty()
    }

    def "CONTROL: a REQUIRED-join notification save DOES poison the parent transaction"() {
        given: "a committed item the parent transaction will rename"
        def item = seedItem()
        def renamed = "renamed-control-${System.nanoTime()}"

        when: "inside ONE transaction: rename the item, then save a bad notification DIRECTLY"
        // This reproduces the OLD behaviour — push() was @Transactional
        // (REQUIRED) so its save() joined this transaction. Saving a bad
        // row (null kind) directly + flushing inside the tx marks it
        // rollback-only, exactly as the buggy push() did.
        txTemplate.executeWithoutResult { status ->
            item.name = renamed
            itemRepository.save(item)
            try {
                notificationRepository.saveAndFlush(
                    new com.sboxmarket.model.Notification(
                        userId: 98L, kind: null, title: 'x'))
            } catch (Exception swallowed) {
                // The production try/catch swallows this — and that is
                // precisely what let the buggy caller "succeed" and then
                // explode at commit time.
            }
        }

        then: "the parent transaction was marked rollback-only — commit throws"
        thrown(UnexpectedRollbackException)

        and: "and crucially the REAL write was rolled back too — the bug's blast radius"
        itemRepository.findById(item.id).get().name != renamed
    }

    // ── AuditService.log() ──────────────────────────────────────────

    def "a failing audit-log save does NOT roll back the parent transaction"() {
        given:
        def item = seedItem()
        def renamed = "renamed-audit-${System.nanoTime()}"

        when: "inside ONE real transaction: rename the item, then log a doomed audit row"
        txTemplate.executeWithoutResult { status ->
            item.name = renamed
            itemRepository.save(item)
            // eventType == null violates AuditLog.eventType
            // @Column(nullable = false). The deferral runs this save
            // post-commit so it can't poison the parent.
            auditService.log(null, 1L, 2L, item.id, 'doomed audit summary')
        }

        then:
        noExceptionThrown()

        and: "the REAL write is durably persisted"
        itemRepository.findById(item.id).get().name == renamed

        and: "the doomed audit row was NOT written"
        auditLogRepository.bySubject(2L,
            org.springframework.data.domain.PageRequest.of(0, 50))
            .findAll { it.resourceId == item.id }.isEmpty()
    }

    // ── PriceHistoryService.record() ────────────────────────────────

    def "record's happy path still persists post-commit and never poisons the parent"() {
        given: "a committed item that the parent transaction will rename"
        def item = seedItem()
        def renamed = "renamed-price-${System.nanoTime()}"

        when: "inside ONE real transaction: rename the item, then record a price point"
        txTemplate.executeWithoutResult { status ->
            item.name = renamed
            itemRepository.save(item)
            // record() defers its WHOLE body (findLatestByItem + insert)
            // to afterCommit. The find must be deferred too: if it ran in
            // this transaction the `latest` entity would be managed and
            // Hibernate dirty-checking would flush at commit regardless.
            priceHistoryService.record(item, new BigDecimal('12.34'), 1)
        }

        then: "the commit succeeded"
        noExceptionThrown()

        and: "the REAL write is durably persisted"
        itemRepository.findById(item.id).get().name == renamed

        and: "record's deferred write DID land post-commit — happy path intact"
        priceHistoryRepository.findLatestByItem(item.id).isPresent()
        priceHistoryRepository.findLatestByItem(item.id).get().price == new BigDecimal('12.34')
    }

    def "a failing price-history save does NOT roll back the parent transaction"() {
        given: "a committed item the parent transaction will rename"
        def item = seedItem()
        def renamed = "renamed-price-fail-${System.nanoTime()}"
        // A detached Item carrying an id that does NOT exist in the DB.
        // record()'s leading guard only checks `item?.id == null`, so a
        // non-null-but-orphan id sails through — then the deferred
        // INSERT INTO price_history hits the NOT NULL + FK on item_id and
        // throws a DataIntegrityViolationException. This is the genuine
        // record() code path failing on its deferred write.
        def ghost = new Item(id: 9_000_000_000L, name: 'ghost',
            category: 'Hats', rarity: 'Limited', supply: 1,
            totalSold: 0, lowestPrice: new BigDecimal('1.00'))

        when: "inside ONE real transaction: rename the item, then record against the ghost item"
        txTemplate.executeWithoutResult { status ->
            item.name = renamed
            itemRepository.save(item)
            priceHistoryService.record(ghost, new BigDecimal('7.77'), 1)
        }

        then: "the commit succeeded — record's deferred FK failure ran post-commit, harmlessly"
        noExceptionThrown()

        and: "the REAL write is durably persisted — no rollback blast radius"
        itemRepository.findById(item.id).get().name == renamed

        and: "the doomed price row was NOT written"
        priceHistoryRepository.findLatestByItem(ghost.id).isEmpty()
    }
}
