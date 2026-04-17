package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Sitewide announcement row — a single-message queue that the frontend
 * pulls on every load to render a dismissible banner at the top of the
 * marketplace. Only admins can create/edit; users can dismiss client-side
 * (localStorage) so they don't see the same message on every page load.
 *
 * `severity` is one of INFO / WARN / CRITICAL and just drives the banner
 * colour on the client. `expiresAt` lets admins schedule a message that
 * auto-clears itself after the incident window.
 */
@Entity
@Table(name = "announcements")
class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    @Column(nullable = false, length = 500)
    String message

    @Column(nullable = false, length = 12)
    String severity = 'INFO'

    @Column(nullable = false)
    Boolean active = true

    /** Null means "until manually disabled". Otherwise the banner hides
     *  once `System.currentTimeMillis() > expiresAt`. */
    @Column
    Long expiresAt

    @Column(nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column
    Long createdByUserId
}
