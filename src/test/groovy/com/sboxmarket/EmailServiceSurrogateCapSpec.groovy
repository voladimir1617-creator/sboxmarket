package com.sboxmarket

import com.sboxmarket.service.EmailService
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.javamail.JavaMailSender
import spock.lang.Specification

/**
 * Surrogate-pair truncation regression for {@code EmailService#cap}.
 *
 * Java {@code String}s are UTF-16. A supplementary code point (every
 * emoji, mathematical-alphanumeric, Linear-B, etc.) is stored as a
 * high+low surrogate PAIR occupying TWO {@code char} slots. The previous
 * {@code cap()} truncated via {@code s.substring(0, cap - 1)} which can
 * land BETWEEN the high and low surrogate of a pair — leaving the
 * resulting string with an unpaired high surrogate (0xD800..0xDBFF).
 *
 * That's invalid UTF-16. When Jakarta Mail later encodes the subject
 * for the SMTP wire (RFC 2047 encoded-word, base64/QP under UTF-8),
 * {@code String#getBytes("UTF-8")} maps the unpaired surrogate to the
 * replacement character {@code ?} (0x3F). A user (Steam display name
 * is attacker-controllable) who packs emoji into a subject template
 * such that the MAX_SUBJECT_CHARS=200 cut falls on a surrogate
 * boundary therefore sees their recipient-visible subject mangled to
 * garbage chars.
 *
 * The fix backs the cut off by one {@code char} when the boundary
 * lands on a high surrogate so the trailing supplementary codepoint
 * is dropped cleanly. This spec pins that contract: a long emoji-laden
 * subject is decoded back to a string with NO unpaired surrogates and
 * NO {@code ?} replacement chars.
 */
class EmailServiceSurrogateCapSpec extends Specification {

    JavaMailSender mailSender = Mock()

    private EmailService newService() {
        mailSender.createMimeMessage() >> {
            new MimeMessage((jakarta.mail.Session) null)
        }
        def svc = new EmailService(
            mailSender:        mailSender,
            smtpHost:          'smtp.example.com',
            fromAddress:       'no-reply@skinbox.local',
            fromName:          'SkinBox',
            replyToAddress:    'support@skinbox.market',
            unsubscribeSecret: 'test-unsubscribe-secret',
            publicUrl:         'http://localhost:8080'
        )
        svc.init()
        svc
    }

    /** True iff the string contains an unpaired high or low surrogate.
     *  A valid UTF-16 string only ever has surrogates in matched
     *  high-then-low pairs; anything else is malformed and round-trips
     *  through {@code getBytes("UTF-8")} as the replacement char. */
    private static boolean hasUnpairedSurrogate(String s) {
        if (s == null) return false
        int len = s.length()
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i)
            if (Character.isHighSurrogate(c)) {
                // Must be immediately followed by a low surrogate.
                if (i + 1 >= len || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return true
                }
                i++ // skip the matched low
            } else if (Character.isLowSurrogate(c)) {
                // Bare low surrogate — pair was never opened.
                return true
            }
        }
        return false
    }

    def "a subject padded with emoji such that the 200-char cap lands mid-surrogate-pair is not mangled to replacement chars"() {
        given:
        def svc = newService()
        // Build an item name with a supplementary-codepoint emoji
        // sitting exactly at the boundary that cap() would otherwise
        // split. sendAuctionWon's subject template is
        //   "You won · ${itemName}"
        // which is 10 chars of prefix ("You won · " — Y-o-u-SP-w-o-n-
        // SP-·-SP). With MAX_SUBJECT_CHARS=200 the raw subject must
        // be 201+ chars to trip the cap; the pre-fix cut was
        // {@code substring(0, 199)} which keeps indexes 0..198. For
        // that cut to land MID-surrogate the high half must sit at
        // subject index 198. Item-name index = 198 - 10 = 188. Pad
        // with 188 'A' fillers then a single 😀 (U+1F600 — high
        // surrogate \uD83D, low \uDE00) then a tail to push the
        // subject past the cap so truncation actually fires.
        StringBuilder name = new StringBuilder()
        188.times { name.append((char) 'A') }
        name.append('😀')           // U+1F600 — surrogate pair 😀
        50.times { name.append((char) 'B') } // tail so total subject > MAX_SUBJECT_CHARS

        when:
        svc.sendAuctionWon('buyer@example.com', 'Alice', name.toString(),
            new BigDecimal('12.50'), '/item/1')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg ->
            def subj = msg.getSubject() ?: ''
            // Truncation fired (cap() returned).
            subj.endsWith('…') &&
            // The fix: no unpaired surrogate survives in the cut output.
            !hasUnpairedSurrogate(subj) &&
            // And the UTF-8 round-trip preserves every char — i.e. no
            // 0x3F replacement char crept in for the bad surrogate.
            // The bare letter 'A' (0x41) is what fills the head; only
            // a mid-surrogate cut would have produced a '?' at the tail.
            new String(subj.getBytes('UTF-8'), 'UTF-8') == subj
        })
    }

    def "a legitimate short subject with emoji is unchanged"() {
        given:
        def svc = newService()
        // Subject well under the cap — must NOT lose the emoji.
        def itemName = 'Karambit 🔪'    // 🔪 = U+1F52A

        when:
        svc.sendAuctionWon('buyer@example.com', 'Alice', itemName,
            new BigDecimal('100.00'), '/item/2')
        svc.awaitSmtpForTests()

        then:
        1 * mailSender.send({ MimeMessage msg ->
            def subj = msg.getSubject() ?: ''
            subj == "You won · Karambit 🔪" &&
            !hasUnpairedSurrogate(subj)
        })
    }
}
