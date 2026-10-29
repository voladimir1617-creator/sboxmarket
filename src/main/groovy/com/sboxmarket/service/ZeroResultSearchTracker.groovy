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
 *
 * The search box fires after a 300 ms pause, so a buyer who stops between
 * words ("zorro" … "zorro cape") sent a half-typed term too. A term that the
 * same requester extends or trims within REFINE_WINDOW_MS replaces the
 * earlier one, and a refinement that finds listings cancels it.
 */
@Component
class ZeroResultSearchTracker {

    static final int MAX_TERMS = 300
    static final int MAX_TERM_LENGTH = 60
    static final long REPEAT_WINDOW_MS = 60L * 60 * 1000
    static final int MAX_SEEN = 5000
    static final long REFINE_WINDOW_MS = 15_000L

    private final Map<String, Long> counts = new LinkedHashMap<>()
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<String, Long>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) { size() > MAX_SEEN }
    }

    /** requesterKey -> [term, at] of that requester's last counted term. */
    private final LinkedHashMap<String, List> lastByRequester = new LinkedHashMap<String, List>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List> eldest) { size() > MAX_SEEN }
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
        undoRefined(t, requesterKey, now)
        if (!counts.containsKey(t) && counts.size() >= MAX_TERMS) return false
        seen.remove(seenKey)
        seen.put(seenKey, now)
        counts.put(t, (counts.get(t) ?: 0L) + 1L)
        if (requesterKey) lastByRequester.put(requesterKey, [t, now])
        true
    }

    /** A search that found listings: a half-typed term just before it was not a real want. */
    synchronized void noteFound(String term, String requesterKey, long now = System.currentTimeMillis()) {
        def t = normalise(term)
        if (t != null) undoRefined(t, requesterKey, now)
    }

    /** Take back the requester's last term when `t` extends or trims it moments later. */
    private void undoRefined(String t, String requesterKey, long now) {
        if (!requesterKey) return
        def last = lastByRequester.get(requesterKey)
        if (last == null) return
        String prev = last[0] as String
        if (prev == t || now - (last[1] as long) >= REFINE_WINDOW_MS) return
        if (!(t.startsWith(prev) || prev.startsWith(t))) return
        lastByRequester.remove(requesterKey)
        seen.remove("${requesterKey}|${prev}".toString())
        Long c = counts.get(prev)
        if (c == null) return
        if (c <= 1L) counts.remove(prev) else counts.put(prev, c - 1L)
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
