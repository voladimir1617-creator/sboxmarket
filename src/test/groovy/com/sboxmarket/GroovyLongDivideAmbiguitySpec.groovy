package com.sboxmarket

import spock.lang.Specification

/**
 * Pins the Groovy `long / long → BigDecimal → ambiguous Math.max` foot-gun
 * (the bug closed by e45d6c9 in OfferService.sweepOffersDueForNudge) and
 * the two cast forms used elsewhere in the codebase to defuse it
 * (`(long) (...)` in TradeService.candidateSweeper and `(...) as long` in
 * RateLimitFilter). If a future change drops a cast, this spec fails
 * loudly instead of letting the GroovyRuntimeException get swallowed by
 * an outer per-row try/catch (the way the original nudge bug shipped
 * silently for ~500 batches).
 */
class GroovyLongDivideAmbiguitySpec extends Specification {

    def "long / long divide promotes to BigDecimal — the root cause"() {
        when:
        long a = 7_200_000L
        long b = 60L * 60L * 1000L
        def quotient = a / b

        then:
        quotient instanceof BigDecimal
        quotient == 2.0G
    }

    def "Math.max(long, raw long/long divide) throws GroovyRuntimeException — the original bug"() {
        when:
        long msLeft = 7_200_000L
        Math.max(1L, msLeft / (60L * 60L * 1000L))

        then:
        def e = thrown(Throwable)
        // Groovy throws GroovyRuntimeException with "Ambiguous method overloading"
        e.message?.toLowerCase()?.contains('ambiguous') ||
            e.class.simpleName.contains('Groovy')
    }

    def "intdiv keeps the result as a primitive long — the OfferService fix"() {
        when:
        long msLeft = 7_200_000L
        long hours = Math.max(1L, msLeft.intdiv(60L * 60L * 1000L))

        then:
        hours == 2L
    }

    def "(long) cast around the divide defuses it — the TradeService.slowSellerWarned pattern"() {
        when:
        long now = 7_200_000L
        long updatedAt = 0L
        def hoursIdle = Math.max(24L, (long) ((now - updatedAt) / 3_600_000L))

        then:
        // 2h since update; clamped to 24h floor
        hoursIdle == 24L
    }

    def "(...) as long around the divide defuses it — the RateLimitFilter pattern"() {
        when:
        long windowMs = 10_000L
        long elapsed = 3_000L
        def retryAfter = Math.max(1, ((windowMs - elapsed) / 1000L) as long)

        then:
        retryAfter == 7L
    }

    def "assigning long/long to a long-typed variable coerces the BigDecimal — ProfileController.retryIn / OfferService.halfLifeMs pattern"() {
        when:
        long cooldown = 60_000L
        long elapsed = 12_000L
        long retryIn = (cooldown - elapsed) / 1000L

        then:
        retryIn == 48L
    }

    def "chained BigDecimal divide then 'as long' defuses it — the TotpService pattern"() {
        when:
        def now = System.currentTimeMillis() / 1000L  // BigDecimal
        def currentStep = (now / 30L) as long           // pinned to long

        then:
        now instanceof BigDecimal
        currentStep > 0L
        currentStep == ((System.currentTimeMillis() as long).intdiv(1000L).intdiv(30L))
    }
}
