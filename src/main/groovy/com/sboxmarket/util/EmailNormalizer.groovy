package com.sboxmarket.util

/**
 * Canonicalises an email address into the form Gmail/Google actually
 * routes to. Used by ProfileController.setEmail to enforce one-mailbox-
 * per-account in the face of Google's two well-documented aliasing
 * behaviours:
 *
 *   - Dot insensitivity in the local part: `voladimir1617@gmail.com`,
 *     `v.oladimir1617@gmail.com`, and `vol.adi.mir.1617@gmail.com` all
 *     route to the same inbox.
 *   - Plus-tag sub-addressing (RFC 5233): `victim+anything@gmail.com`
 *     routes to `victim@gmail.com`. Google forwards everything after the
 *     plus straight to spam-filter land but the mailbox is the same.
 *   - `@googlemail.com` is the legacy UK/DE-region alias for
 *     `@gmail.com`; identical inbox.
 *
 * Without this canonicalisation, the email-uniqueness check (batch 477
 * — "closes the multi-account vector: chargeback evasion, spam ticket
 * flood, password-reset fishing") is defeated by a single Gmail account
 * minting unlimited SkinBox identities pointing at the same mailbox.
 *
 * For non-Gmail domains we apply only lowercase + trim. We do NOT
 * universally strip `+` tags because some privacy-conscious providers
 * (Fastmail with custom domains, ProtonMail's `+` sub-addressing) treat
 * them as opaque, and aggressively merging two distinct mailboxes would
 * lock out a legitimate user. Gmail is the documented multi-account
 * abuse surface and the bulk of consumer email — the conservative
 * heuristic catches the realistic abuse case without false positives on
 * any other provider.
 *
 * The canonical form is suitable for storage in a UNIQUE-indexed column
 * (`canonical_email`) — see V63__steam_user_canonical_email.sql. It is
 * NOT the form rendered back to the user (we still show their original
 * casing) and it is NOT the form delivered to via SMTP (Gmail accepts
 * both shapes, but other audiences may not).
 */
final class EmailNormalizer {

    private EmailNormalizer() { /* static-only */ }

    /** Domains whose local part is dot-insensitive and `+tag` aliased. */
    private static final Set<String> GOOGLE_DOMAINS = ['gmail.com', 'googlemail.com'] as Set

    /**
     * Returns the canonical form of {@code email} for uniqueness
     * comparison. Null/blank/no-@ inputs return null — callers that
     * accept the email must run the regex validator first; the
     * canonical form is only meaningful for already-syntactically-valid
     * addresses. Idempotent: {@code canonicalize(canonicalize(x)) == canonicalize(x)}.
     */
    static String canonicalize(String email) {
        if (email == null) return null
        String s = email.trim().toLowerCase()
        if (s.isEmpty()) return null
        int at = s.lastIndexOf('@')
        // Bare local-part / missing domain → not canonicalisable; return
        // the trimmed-lower form so a malformed input still hashes to a
        // stable key (the EMAIL_RE gate upstream would have rejected it
        // before we got here, but be defensive).
        if (at <= 0 || at == s.length() - 1) return s
        String local = s.substring(0, at)
        String domain = s.substring(at + 1)
        // Google's two-way alias: googlemail.com → gmail.com. Apply
        // BEFORE the domain-membership check so the dot/plus rules below
        // fire for both shapes.
        if (domain == 'googlemail.com') domain = 'gmail.com'
        if (GOOGLE_DOMAINS.contains(domain)) {
            // Drop everything from the first `+` onward (RFC 5233
            // sub-addressing — Gmail's documented routing rule). An
            // empty local after the strip means "+something@gmail.com"
            // (no real localpart) which Gmail rejects at send time; we
            // keep the empty so the canonical still differs from a
            // genuinely-empty user, but the EMAIL_RE upstream gate
            // already requires {1,64} chars before the @.
            int plus = local.indexOf('+')
            if (plus >= 0) local = local.substring(0, plus)
            // Strip every dot. Gmail ignores them in routing. An empty
            // local after this would be a bare "@gmail.com" — same
            // caveat as above; the regex upstream rejects.
            local = local.replace('.', '')
        }
        return local + '@' + domain
    }
}
