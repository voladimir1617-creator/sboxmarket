package com.sboxmarket.service

import org.springframework.stereotype.Component

/**
 * In-memory tally of marketplace searches that found nothing, so staff can
 * see which items buyers want and nobody sells. Staff-only (the admin
 * dashboard reads `top`).
 *
 * Kept in memory on purpose: no table, no user id, cleared on restart. The
 * term is trimmed, lower-cased and cut to 60 chars. The tally is capped at
 * MAX_TERMS; once full, new terms are dropped rather than evicting counted
 * ones. The grid re-polls the same search every 30 s, so one requester's
 * repeat of a term only counts once per REPEAT_WINDOW_MS.
 */
@Component
class ZeroResultSearchTracker {

    static final int MAX_TERMS = 300
    static final int MAX_TERM_LENGTH = 60
    static final long REPEAT_WINDOW_MS = 60L * 60 * 1000
    static final int MAX_SEEN = 5000

    private final Map<String, Long> counts = new LinkedHashMap<>()
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<String, Long>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) { size() > MAX_SEEN }
    }

    static String normalise(String term) {
        if (term == null) return null
        def t = term.replace('\u0000', '').trim().toLowerCase().replaceAll(/\s+/, ' ')
        if (t.isEmpty()) return null
        t.length() > MAX_TERM_LENGTH ? t.substring(0, MAX_TERM_LENGTH) : t
    }

    /** Count one empty search. `requesterKey` only dedupes polls; it is never stored past the window or shown. */
    synchronized boolean record(String term, String requesterKey, long now = System.currentTimeMillis()) {
        def t = normalise(term)
        if (t == null) return false
        def seenKey = "${requesterKey ?: '-'}|${t}".toString()
        Long last = seen.get(seenKey)
        if (last != null && now - last < REPEAT_WINDOW_MS) return false
        if (!counts.containsKey(t) && counts.size() >= MAX_TERMS) return false
        seen.remove(seenKey)
        seen.put(seenKey, now)
        counts.put(t, (counts.get(t) ?: 0L) + 1L)
        true
    }

    /** Most-searched empty terms, highest count first. */
    synchronized List<Map<String, Object>> top(int n = 10) {
        counts.entrySet()
            .sort { a, b -> b.value <=> a.value ?: a.key <=> b.key }
            .take(Math.max(n, 0))
            .collect { [term: it.key, count: it.value] as Map<String, Object> }
    }

    synchronized int size() { counts.size() }
}
