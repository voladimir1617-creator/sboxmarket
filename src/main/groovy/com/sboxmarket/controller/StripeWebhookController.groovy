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
     *  --forward-to localhost:8080/api/stripe/webhook`) at this URL.
     *
     *  Status-code contract with Stripe's retry machinery:
     *    - 400  → bad signature. Stripe does NOT retry (and shouldn't —
     *             a forged/garbled payload won't get better on retry).
     *    - 200  → handled, OR a PERMANENT domain failure that retrying
     *             cannot fix. Stripe stops retrying. See below.
     *    - 500  → genuinely transient failure (DB blip, Stripe API
     *             timeout). Stripe retries with backoff — which is what
     *             we want for these.
     *
     *  Batch 657 fix (BUG 2): the catch-all used to return 500 for ANY
     *  exception. That is correct for transient faults but wrong for
     *  permanent domain failures — e.g. completeDeposit throwing
     *  IllegalStateException("Amount mismatch") or
     *  IllegalArgumentException("Invalid session id"). Those will throw
     *  identically on every retry, so a 500 makes Stripe hammer a
     *  dead-on-arrival event for up to 3 days. Such failures are already
     *  logged in StripeService; we ACK them with 200 so Stripe moves on.
     *  IllegalState/IllegalArgument are the domain-failure exception
     *  types StripeService raises for unrecoverable conditions; anything
     *  else is treated as transient and still gets a 500 so the retry
     *  happens. */
    @PostMapping("/webhook")
    ResponseEntity<String> webhook(@RequestBody String payload,
                                   @RequestHeader(value = "Stripe-Signature", required = false) String sig) {
        try {
            stripeService.handleWebhookEvent(payload, sig ?: "")
            ResponseEntity.ok("ok")
        } catch (SecurityException e) {
            ResponseEntity.status(400).body("invalid signature")
        } catch (IllegalArgumentException | IllegalStateException domainFailure) {
            // Permanent, non-retryable domain failure (already logged in
            // StripeService). ACK with 200 so Stripe stops retrying a
            // request that can never succeed.
            log.error("Webhook processing failed permanently — ACKing 200 to stop Stripe retries: ${domainFailure.message}", domainFailure)
            ResponseEntity.ok("acknowledged")
        } catch (Exception e) {
            // Genuinely transient — let Stripe retry with backoff.
            log.error("Webhook processing failed (transient — Stripe will retry)", e)
            ResponseEntity.status(500).body("error")
        }
    }
}
