package com.sboxmarket.config

import org.springframework.core.annotation.AnnotationUtils
import org.springframework.core.annotation.Order
import spock.lang.Specification

/**
 * Regression for the silent filter-order collision: PresenceFilter and
 * RateLimitFilter both carried @Order(5). Spring's filter chain ordering
 * is non-deterministic for equal orders, so PresenceFilter could win the
 * race and bump `last_seen_at` for a user whose request RateLimitFilter
 * was about to reject with 429 — a phantom-presence bug that defeats the
 * exact "don't bump on rejected requests" invariant the PresenceFilter
 * class doc promises.
 *
 * Fix: PresenceFilter is now @Order(6), strictly after every business
 * filter that can short-circuit the chain (RateLimit/CSRF/SessionEpoch/
 * BodySizeLimit/ApiKey/CorrelationId).
 */
class PresenceFilterOrderSpec extends Specification {

    def "PresenceFilter is ordered strictly AFTER every short-circuiting business filter"() {
        when:
        int presence    = AnnotationUtils.findAnnotation(PresenceFilter,     Order).value()
        int rateLimit   = AnnotationUtils.findAnnotation(RateLimitFilter,    Order).value()
        int sessionEpoch= AnnotationUtils.findAnnotation(SessionEpochFilter, Order).value()
        int csrf        = AnnotationUtils.findAnnotation(CsrfFilter,         Order).value()
        int apiKey      = AnnotationUtils.findAnnotation(ApiKeyAuthFilter,   Order).value()
        int bodySize    = AnnotationUtils.findAnnotation(BodySizeLimitFilter,Order).value()
        int correlation = AnnotationUtils.findAnnotation(CorrelationIdFilter,Order).value()

        then: 'no order collisions among business filters'
        [rateLimit, sessionEpoch, csrf, apiKey, bodySize, correlation].toSet().size() == 6

        and: 'PresenceFilter wins the chain race against every short-circuiting filter'
        presence > rateLimit
        presence > sessionEpoch
        presence > csrf
        presence > apiKey
        presence > bodySize
        presence > correlation
    }
}
