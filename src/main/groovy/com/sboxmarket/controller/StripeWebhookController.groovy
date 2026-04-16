package com.sboxmarket.controller

import com.sboxmarket.service.StripeService
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/stripe")
@Slf4j
class StripeWebhookController {

    @Autowired StripeService stripeService

    /** Stripe posts events here. Point your webhook (or `stripe listen
     *  --forward-to localhost:8080/api/stripe/webhook`) at this URL. */
    @PostMapping("/webhook")
    ResponseEntity<String> webhook(@RequestBody String payload,
                                   @RequestHeader(value = "Stripe-Signature", required = false) String sig) {
        try {
            stripeService.handleWebhookEvent(payload, sig ?: "")
            ResponseEntity.ok("ok")
        } catch (SecurityException e) {
            ResponseEntity.status(400).body("invalid signature")
        } catch (Exception e) {
            log.error("Webhook processing failed", e)
            ResponseEntity.status(500).body("error")
        }
    }
}
