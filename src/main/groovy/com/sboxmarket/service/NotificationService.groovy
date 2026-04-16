package com.sboxmarket.service

import com.sboxmarket.model.Notification
import com.sboxmarket.repository.NotificationRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Persists user-facing notifications. Every non-trivial business event in the
 * system (sale, purchase, bid, offer, buy-order fill, deposit, withdrawal) calls
 * into this service so the bell in the top-nav and the Notifications tab in the
 * profile stay in sync with reality.
 */
@Service
@Slf4j
class NotificationService {

    @Autowired NotificationRepository notificationRepository

    @Transactional
    Notification push(Long userId, String kind, String title, String body = null, Long refId = null) {
        if (userId == null) return null
        def n = new Notification(
            userId: userId,
            kind:   kind,
            title:  title,
            body:   body,
            refId:  refId
        )
        notificationRepository.save(n)
    }

    /** Up to 100 most-recent notifications. The bell UI only shows 12
     *  but admin debugging wants more history. Hard cap prevents a
     *  long-lived account from dumping its entire notification log. */
    List<Notification> listFor(Long userId) {
        notificationRepository.findForUser(userId, PageRequest.of(0, 100))
    }

    Long countUnread(Long userId) {
        notificationRepository.countUnread(userId) ?: 0L
    }

    @Transactional
    void markRead(Long userId, Long id) {
        def n = notificationRepository.findById(id).orElse(null)
        if (n && n.userId == userId) {
            n.read = true
            notificationRepository.save(n)
        }
    }

    @Transactional
    void markAllRead(Long userId) {
        // Only mark the most-recent 100 as read — matches what `listFor`
        // returns, so "Mark all read" feels consistent with what the user
        // sees in the bell. Older backfill rows stay untouched and don't
        // hold Hibernate's session hostage.
        def recent = notificationRepository.findForUser(userId, PageRequest.of(0, 100))
        def unread = recent.findAll { !it.read }
        unread.each { it.read = true }
        if (!unread.isEmpty()) notificationRepository.saveAll(unread)
    }
}
