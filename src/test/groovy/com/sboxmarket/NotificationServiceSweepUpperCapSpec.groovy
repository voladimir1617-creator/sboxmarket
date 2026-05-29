package com.sboxmarket

import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.service.NotificationService
import org.springframework.data.domain.Pageable
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression pin for the unbounded-mass-deletion bug in
 * {@code NotificationService.sweepOldReadNotifications}.
 *
 * Before the fix the daily sweep was a single un-paged
 * {@code DELETE FROM notifications WHERE read = true AND created_at < :cutoff}
 * with no per-tick row cap. On a heavy deploy (years of accumulated
 * read history) — or after an ops change that lowered
 * {@code notifications.retain-read-days} from 180 to a smaller value —
 * a single tick would match millions of rows in one statement,
 * locking the table for the duration of the undo-log write, blowing
 * the transaction log, and risking commit-log overflow / OOM on
 * the smaller pods.
 *
 * The fix introduces {@link NotificationService#SWEEP_BATCH_LIMIT}
 * and a paged {@code findReadIdsOlderThan(cutoff, Pageable)} repo
 * query so the sweep can only ever touch a bounded number of rows
 * per tick. Remaining backlog drains across subsequent daily ticks
 * (the rolling cutoff still includes the older rows).
 */
class NotificationServiceSweepUpperCapSpec extends Specification {

    NotificationRepository notificationRepository = Mock()

    @Subject
    NotificationService service = new NotificationService(
        notificationRepository: notificationRepository
    )

    def "sweep declares a sane upper-cap constant (BUG: unbounded mass deletion)"() {
        expect:
        // Without this constant the sweep is unbounded. Pin its presence
        // and a sane value so a future refactor can't silently restore
        // the unbounded DELETE.
        NotificationService.SWEEP_BATCH_LIMIT > 0
        // Generous ceiling so we don't churn the test on tuning, but
        // prove the cap is real (not Integer.MAX_VALUE).
        NotificationService.SWEEP_BATCH_LIMIT <= 1_000_000
    }

    def "sweep calls the paged ids query with PageRequest sized to the cap (not the legacy unbounded DELETE)"() {
        given:
        service.retainReadDays = 180L
        Pageable capturedPageable = null
        notificationRepository.findReadIdsOlderThan(_, _) >> { args ->
            capturedPageable = args[1] as Pageable
            return []
        }

        when:
        service.sweepOldReadNotifications()

        then:
        capturedPageable != null
        capturedPageable.pageSize == NotificationService.SWEEP_BATCH_LIMIT
        capturedPageable.pageNumber == 0
        // The legacy unbounded DELETE must never fire — that path is
        // the bug we're guarding against.
        0 * notificationRepository.deleteReadOlderThan(_)
    }

    def "sweep deletes only the bounded set returned by the paged query, even when more rows match"() {
        given:
        // Simulate a heavy DB where MANY more rows are technically
        // eligible than the cap. The paged query already returned the
        // capped slice; the service must delete exactly that slice and
        // nothing more in this tick.
        service.retainReadDays = 180L
        def cappedIds = (1L..(long) NotificationService.SWEEP_BATCH_LIMIT).toList()
        notificationRepository.findReadIdsOlderThan(_, _) >> cappedIds

        when:
        service.sweepOldReadNotifications()

        then:
        // Deletes exactly the bounded set — not millions of rows in
        // one shot. The leftover backlog drains across subsequent
        // daily ticks (the rolling cutoff still includes them).
        1 * notificationRepository.deleteAllByIdInBatch({ List<Long> ids ->
            ids.size() == NotificationService.SWEEP_BATCH_LIMIT &&
            ids.size() <= NotificationService.SWEEP_BATCH_LIMIT
        })
        // No unbounded DELETE on any path.
        0 * notificationRepository.deleteReadOlderThan(_)
    }

    def "sweep skips the delete entirely when the paged query returns no candidates"() {
        given:
        service.retainReadDays = 180L
        notificationRepository.findReadIdsOlderThan(_, _) >> []

        when:
        service.sweepOldReadNotifications()

        then:
        // Nothing to delete — must not fire deleteAllByIdInBatch with
        // an empty list (Spring Data tolerates it but it's pointless
        // SQL churn).
        0 * notificationRepository.deleteAllByIdInBatch(_)
        0 * notificationRepository.deleteReadOlderThan(_)
    }

    def "sweep stays a no-op when retention is disabled (retainReadDays <= 0)"() {
        given:
        service.retainReadDays = 0L

        when:
        service.sweepOldReadNotifications()

        then:
        // No query, no delete — disabled-retention path must touch
        // nothing.
        0 * notificationRepository.findReadIdsOlderThan(_, _)
        0 * notificationRepository.deleteAllByIdInBatch(_)
        0 * notificationRepository.deleteReadOlderThan(_)
    }

    def "sweep tolerates a null return from the paged query without NPE"() {
        given:
        // Defensive: the repo contract returns a List, but a custom
        // adapter or a mocked-without-stubs return could yield null.
        // The sweeper must not blow up the @Scheduled tick — a
        // thrown NPE would silently kill the daily sweep on the
        // subsequent pod restart cycle.
        service.retainReadDays = 180L
        notificationRepository.findReadIdsOlderThan(_, _) >> null

        when:
        service.sweepOldReadNotifications()

        then:
        noExceptionThrown()
        0 * notificationRepository.deleteAllByIdInBatch(_)
    }
}
