package com.sboxmarket

import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TotpService
import com.sboxmarket.util.EmailNormalizer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * V63 — Gmail-alias bypass fix.
 *
 * The original batch-477 email uniqueness gate (intended to close the
 * multi-account vector: chargeback evasion, spam ticket flood, password-
 * reset fishing) compared raw strings via LOWER(email). That was
 * defeated by Gmail's three documented mailbox-aliasing rules — `+tag`
 * sub-addressing, dot-insensitivity in the local part, and the
 * googlemail.com ↔ gmail.com domain alias — so a single Gmail account
 * could create unlimited SkinBox accounts pointing at the same inbox.
 *
 * EmailNormalizer.canonicalize collapses every Google alias onto the
 * same key; ProfileController.setEmail now compares that canonical form
 * via findByCanonicalEmail. This spec pins both layers:
 *
 *   1. EmailNormalizer behaviour — Gmail rules applied, non-Google
 *      addresses pass through with only lowercase+trim (no aggressive
 *      universal `+` strip, since providers like Fastmail treat the
 *      suffix as opaque and we'd false-positive merge two real mailboxes).
 *   2. ProfileController.setEmail rejects every Gmail alias of an
 *      already-registered mailbox with EMAIL_TAKEN, while allowing the
 *      OWNING user to re-save their own canonical mailbox in any
 *      alias variant (re-verification flow) without tripping the check.
 *   3. The canonical column is persisted on every successful write so
 *      the partial UNIQUE index in V63 has a value to enforce against.
 */
class EmailNormalizerSpec extends Specification {

    // ── Pure normalizer rules ───────────────────────────────────────

    @Unroll
    def "canonicalize: '#input' → '#expected'"() {
        expect:
        EmailNormalizer.canonicalize(input) == expected

        where:
        input                                | expected
        // Null / blank → null (caller short-circuits)
        null                                 | null
        ''                                   | null
        '   '                                | null
        // Missing/empty domain → trimmed-lower passthrough
        // (EMAIL_RE upstream rejects these before we'd ever store them)
        'no-at-sign'                         | 'no-at-sign'
        'trailing@'                          | 'trailing@'
        // Non-Google domains: lowercase+trim ONLY. We deliberately do
        // NOT universally strip `+tag` — Fastmail / ProtonMail / privacy
        // providers can route the suffix differently per user, and an
        // aggressive merge would collide two distinct mailboxes.
        'Alice@example.com'                  | 'alice@example.com'
        '  Bob@EXAMPLE.com  '                | 'bob@example.com'
        'alice+work@example.com'             | 'alice+work@example.com'   // preserved
        'a.l.i.c.e@example.com'              | 'a.l.i.c.e@example.com'    // preserved
        // Gmail core: lowercase + dot-strip + plus-strip
        'voladimir1617@gmail.com'            | 'voladimir1617@gmail.com'
        'Voladimir1617@Gmail.com'            | 'voladimir1617@gmail.com'
        'voladimir.1617@gmail.com'           | 'voladimir1617@gmail.com'
        'v.o.l.a.d.i.m.i.r.1.6.1.7@gmail.com'| 'voladimir1617@gmail.com'
        'voladimir1617+anything@gmail.com'   | 'voladimir1617@gmail.com'
        'voladimir1617+@gmail.com'           | 'voladimir1617@gmail.com'  // empty tag
        'voladimir.1617+spam@gmail.com'      | 'voladimir1617@gmail.com'  // combined
        // googlemail.com → gmail.com (Google's UK/DE alias)
        'voladimir1617@googlemail.com'       | 'voladimir1617@gmail.com'
        'voladimir.1617@googlemail.com'      | 'voladimir1617@gmail.com'
        'voladimir1617+abc@GoogleMail.com'   | 'voladimir1617@gmail.com'
    }

    def "canonicalize is idempotent — canon(canon(x)) == canon(x)"() {
        // Critical invariant: the persisted canonical_email column must
        // be stable across re-canonicalisations so the partial UNIQUE
        // index doesn't drift on a backfill / re-save round-trip.
        expect:
        EmailNormalizer.canonicalize(EmailNormalizer.canonicalize(input)) ==
            EmailNormalizer.canonicalize(input)

        where:
        input << [
            'Alice@example.com',
            'voladimir.1617+spam@gmail.com',
            'voladimir1617@googlemail.com',
            'plain@user.org',
            null,
            '',
            'malformed'
        ]
    }

    // ── End-to-end: setEmail rejects Gmail aliases of a taken mailbox ─

    SteamUserRepository steamUserRepository = Mock()
    EmailService        emailService        = Mock()
    TotpService         totpService         = new TotpService()
    TextSanitizer       textSanitizer       = new TextSanitizer()

    @Subject
    ProfileController controller = new ProfileController(
        steamUserRepository: steamUserRepository,
        totpService:         totpService,
        emailService:        emailService,
        textSanitizer:       textSanitizer
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void authedSession(long uid = 100L) {
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    @Unroll
    def "setEmail rejects '#alias' when 'voladimir1617@gmail.com' is already taken by another account"() {
        given: 'an existing account on the canonical Gmail mailbox owned by uid=42'
        authedSession(100L)
        def caller = new SteamUser(id: 100L, steamId64: '1', emailVerificationToken: null)
        def squatter = new SteamUser(id: 42L, steamId64: '2',
            email: 'voladimir1617@gmail.com',
            canonicalEmail: 'voladimir1617@gmail.com')
        steamUserRepository.findById(100L) >> Optional.of(caller)
        // The canonical lookup MUST return the existing row for every
        // alias that collapses to the same key — that's the whole point
        // of the V63 fix.
        steamUserRepository.findByCanonicalEmail('voladimir1617@gmail.com') >> [squatter]

        when: 'a different account tries to claim a Gmail alias of the same mailbox'
        controller.setEmail([email: alias], req)

        then: 'rejected with EMAIL_TAKEN — nothing written, no verification mail sent'
        def e = thrown(BadRequestException)
        e.code == 'EMAIL_TAKEN'
        caller.email == null
        caller.canonicalEmail == null
        0 * steamUserRepository.save(_)
        0 * emailService.sendVerification(_, _)

        where:
        alias << [
            'voladimir1617@gmail.com',           // exact
            'Voladimir1617@Gmail.com',           // case
            'voladimir.1617@gmail.com',          // dot-insensitivity
            'v.o.l.a.d.i.m.i.r.1.6.1.7@gmail.com', // every-char dots
            'voladimir1617+anything@gmail.com',  // plus tag
            'voladimir.1617+spam@gmail.com',     // dots + plus combined
            'voladimir1617@googlemail.com',      // domain alias
            'voladimir.1617+spam@googlemail.com' // every Gmail rule at once
        ]
    }

    def "setEmail allows the OWNING user to re-save the same Gmail mailbox via a different alias"() {
        // Re-verification flow: a user owning voladimir1617@gmail.com
        // saves voladimir.1617+work@gmail.com to update their address.
        // The uniqueness check must NOT trip on their own canonical row
        // — only collisions with OTHER user ids count.
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1',
            email: 'voladimir1617@gmail.com',
            canonicalEmail: 'voladimir1617@gmail.com',
            emailVerified: true,
            emailVerificationToken: null)
        steamUserRepository.findById(100L) >> Optional.of(user)
        // The canonical lookup returns the caller's own row — must be
        // filtered out by the `id != uid` predicate.
        steamUserRepository.findByCanonicalEmail('voladimir1617@gmail.com') >> [user]

        when:
        def resp = controller.setEmail([email: 'voladimir.1617+work@gmail.com'], req)

        then: 'accepted — raw email preserved as-typed, canonical form persisted'
        resp.statusCode.value() == 200
        user.email == 'voladimir.1617+work@gmail.com'
        user.canonicalEmail == 'voladimir1617@gmail.com'
        user.emailVerified == false
        user.emailVerificationToken != null
        !user.emailVerificationToken.startsWith('totp_pending:')
        1 * steamUserRepository.save(user)
        1 * emailService.sendVerification('voladimir.1617+work@gmail.com', _)
    }

    def "setEmail persists canonical_email on a fresh write so the V63 UNIQUE index has a value to enforce"() {
        // Without this write, the column would stay NULL on every new
        // account and the partial UNIQUE index would be defenseless —
        // two concurrent setEmail calls for the same canonical mailbox
        // would both pass the controller-level check (TOCTOU) and both
        // commit NULL canonicals, leaving the bypass open.
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailVerificationToken: null)
        steamUserRepository.findById(100L) >> Optional.of(user)
        steamUserRepository.findByCanonicalEmail(_) >> []

        when:
        controller.setEmail([email: 'New.User+tag@GoogleMail.com'], req)

        then: 'raw email stored lowercased-and-trimmed; canonical column populated'
        user.email == 'new.user+tag@googlemail.com'
        user.canonicalEmail == 'newuser@gmail.com'
        1 * steamUserRepository.save(user)
    }

    def "setEmail on a non-Google address stores canonical == lowercased email (no plus-strip)"() {
        // Defence: confirm the normalizer doesn't aggressively merge
        // alice+work@example.com and alice@example.com — they're
        // distinct mailboxes on most providers and a false-positive
        // collision here would lock legitimate users out.
        given:
        authedSession(100L)
        def user = new SteamUser(id: 100L, steamId64: '1', emailVerificationToken: null)
        steamUserRepository.findById(100L) >> Optional.of(user)
        steamUserRepository.findByCanonicalEmail('alice+work@example.com') >> []

        when:
        controller.setEmail([email: 'Alice+Work@Example.com'], req)

        then:
        user.email == 'alice+work@example.com'
        user.canonicalEmail == 'alice+work@example.com'  // +tag preserved on non-Gmail
        1 * steamUserRepository.save(user)
    }
}
