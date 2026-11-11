package com.sboxmarket.controller

import com.sboxmarket.service.EmailEventService
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Mail-provider webhook (Resend): bounces and spam complaints. Point the
 * provider's webhook at {@code <APP_PUBLIC_URL>/api/email/webhook} with the
 * email.bounced and email.complained events, and put its signing secret in
 * RESEND_WEBHOOK_SECRET. No session or CSRF token: the signature is the auth,
 * checked in {@link EmailEventService#verify} before anything is read.
 * Unconfigured = 404, bad or stale signature = 401.
 */
@RestController
@RequestMapping('/api/email')
@Slf4j
class EmailWebhookController {

    @Autowired EmailEventService emailEventService

    @PostMapping('/webhook')
    ResponseEntity<String> webhook(@RequestBody(required = false) String body,
                                   @RequestHeader(value = 'svix-id', required = false) String id,
                                   @RequestHeader(value = 'svix-timestamp', required = false) String timestamp,
                                   @RequestHeader(value = 'svix-signature', required = false) String signature) {
        if (!emailEventService.isConfigured()) return ResponseEntity.notFound().build()
        if (!emailEventService.verify(id, timestamp, signature, body)) {
            log.warn('Email webhook refused: bad or stale signature')
            return ResponseEntity.status(401).body('bad signature')
        }
        emailEventService.handle(body)
        ResponseEntity.ok('ok')
    }
}
