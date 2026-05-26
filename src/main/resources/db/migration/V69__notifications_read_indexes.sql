-- V69: add the two notifications read-state indexes the model declared
-- but no migration ever shipped.
--
-- Notification.groovy@14 declares:
--   @Index(name = "idx_notifications_user_unread",  columnList = "user_id,read")
--   @Index(name = "idx_notifications_read_created", columnList = "read,created_at")
--
-- Neither was ever committed as DDL. Dev runs on H2 with
-- spring.jpa.hibernate.ddl-auto=update, which obediently materialises both
-- indexes from the @Index annotations, so the bell-badge poll feels snappy
-- locally and nobody notices. Prod runs on Postgres with Flyway in charge,
-- which has never been told about either index — so every `countUnread` and
-- every `deleteReadOlderThan` falls back to wider scans.
--
-- Hot paths that go from O(unread_for_user) to O(all_for_user):
--
--   1. NotificationRepository.countUnread → NotificationController.unreadCount
--      polls on every page render (bell badge across the entire app shell).
--      Without this index Postgres uses idx_notifications_user (from V1) and
--      then has to re-filter every row in the user_id partition by `read =
--      false` in memory. For a long-lived user with thousands of read
--      notifications the ratio degrades unbounded over time.
--
--   2. NotificationRepository.markAllReadForUser is the same shape — UPDATE
--      with WHERE user_id = ? AND read = false. Same wide scan, same in-memory
--      filter, plus the write amplification.
--
--   3. NotificationRepository.deleteReadOlderThan runs daily (retention
--      sweep) and predicates on `read = true AND created_at < cutoff`. With
--      no `(read, created_at)` index it does a sequential scan of the entire
--      notifications table — and notifications is one-row-per-user-per-event,
--      growing unbounded forever. The longer the system runs the worse the
--      nightly sweep gets, until it starts colliding with daytime traffic.
--
-- Strategy: ship the indexes exactly as the @Index annotations declared
-- them. Plain composites (not partial) so the prod DDL matches what H2
-- materialises in dev — keeping the two environments shape-identical means
-- any future EXPLAIN ANALYZE in dev is representative of prod.
--
-- Idempotent via IF NOT EXISTS; safe to re-run if a previous out-of-band
-- attempt already created either index.

CREATE INDEX IF NOT EXISTS idx_notifications_user_unread
    ON notifications (user_id, read);

CREATE INDEX IF NOT EXISTS idx_notifications_read_created
    ON notifications (read, created_at);
