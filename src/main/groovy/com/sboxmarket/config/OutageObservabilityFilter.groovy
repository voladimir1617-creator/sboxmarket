package com.sboxmarket.config

import groovy.util.logging.Slf4j
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Outage observability filter (added 2026-05-03 after the FOURTH NUL-byte
 * session outage in 24h).
 *
 * **What this does:**
 * Wraps every request and watches for the two exception classes that
 * historically signal a database/session outage:
 *   - `org.postgresql.util.PSQLException`            (Postgres bind/SQL error,
 *     e.g. SQLSTATE 22021 from a NUL byte in a TEXT bind parameter)
 *   - `org.springframework.dao.DataIntegrityViolationException`
 *     (Spring's translated wrapper around Postgres / JDBC integrity
 *     errors — what most repository calls actually throw)
 *
 * When EITHER fires from the request thread, log a single warn line so
 * the next outage is grep-able by exception class in 10 seconds:
 *   `docker logs sbox-app --since 5m | grep OUTAGE-SIGNAL`
 *
 * **Why an OncePerRequestFilter:**
 * A `@ControllerAdvice` would miss exceptions thrown from filters that
 * run BEFORE Spring MVC (e.g. anything inside SessionRepositoryFilter
 * itself).  A request-thread filter sees every exception that bubbles
 * up to the servlet container regardless of where in the chain it
 * originated.
 *
 * **Order:** runs AFTER SessionCookieSanitizerFilter (which is at
 * `Ordered.HIGHEST_PRECEDENCE + 5`).  We pick `+ 6` so the cookie
 * sanitizer has already cleaned the cookie jar before this filter
 * watches for downstream exceptions — we want to log REAL DB outages,
 * not "garbage cookie that the sanitizer rejected".
 *
 * **Throttling:** to avoid flooding logs during a sustained outage
 * (think: 100 req/s all hitting the same PSQLException), we log a full
 * stack trace at most ONCE PER MINUTE per exception class.  Subsequent
 * occurrences inside the same window emit a single counter line so the
 * log is still grep-able for the rate without ballooning to the disk
 * cap.  Counter is flushed to a log line on each new minute window.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
@Slf4j
class OutageObservabilityFilter extends OncePerRequestFilter {

    /** Exception classes we treat as outage-signal. Matched by FQN so we
     *  don't have to import them at compile time (driver classes might
     *  shift between Postgres versions). */
    private static final Set<String> WATCHED = [
        'org.postgresql.util.PSQLException',
        'org.springframework.dao.DataIntegrityViolationException'
    ] as Set

    /** One-stacktrace-per-minute throttle. Keyed by exception class name.
     *  Value holds the start of the current minute window (ms since
     *  epoch) and a counter of suppressed occurrences within it. */
    private static class ThrottleState {
        final AtomicLong windowStart = new AtomicLong(0L)
        final LongAdder  suppressed  = new LongAdder()
    }
    private static final long WINDOW_MS = 60_000L
    private final ConcurrentHashMap<String, ThrottleState> throttles = new ConcurrentHashMap<>()

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        try {
            chain.doFilter(req, resp)
        } catch (Throwable t) {
            // Walk the cause chain — Spring's translated exception will
            // wrap the original JDBC PSQLException as the cause.
            Throwable cur = t
            int depth = 0
            while (cur != null && depth < 16) {
                String cls = cur.class.name
                if (WATCHED.contains(cls)) {
                    logOutageSignal(req, cur, cls)
                    break
                }
                cur = cur.cause
                depth++
            }
            throw t
        }
    }

    private void logOutageSignal(HttpServletRequest req, Throwable t, String cls) {
        ThrottleState state = throttles.computeIfAbsent(cls, { k -> new ThrottleState() })
        long now = System.currentTimeMillis()
        long start = state.windowStart.get()
        if (start == 0L || (now - start) >= WINDOW_MS) {
            // Try to claim the new window. If another thread beats us,
            // we fall through to the count-only branch so only one
            // stacktrace per minute escapes regardless of contention.
            if (state.windowStart.compareAndSet(start, now)) {
                long suppressedCount = state.suppressed.sumThenReset()
                String pathInfo = "${req.method ?: '?'} ${req.requestURI ?: '?'}"
                if (suppressedCount > 0) {
                    log.warn("OUTAGE-SIGNAL exception=${cls} path=${pathInfo} (also suppressed ${suppressedCount} since last stack)", t)
                } else {
                    log.warn("OUTAGE-SIGNAL exception=${cls} path=${pathInfo}", t)
                }
                return
            }
        }
        // Inside the throttled window — count only, no stack, no log
        // line. Emitting a per-request WARN here defeats the throttle:
        // a 100 req/s sustained outage would still spit ~6000 lines/min
        // ("ballooning the disk cap" — exactly what the class docstring
        // promises NOT to do). The counter sums silently and the next
        // window-flushing stacktrace surfaces "suppressed N since last
        // stack", so the rate is still grep-able without the flood.
        state.suppressed.increment()
    }
}
