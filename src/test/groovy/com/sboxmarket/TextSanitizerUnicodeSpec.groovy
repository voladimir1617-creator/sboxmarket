package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.ZeroResultSearchTracker
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Odd-text walk: emoji cut in half by length caps, and values made only of
 * invisible characters that were saved as names / messages showing nothing.
 */
class TextSanitizerUnicodeSpec extends Specification {

    TextSanitizer sanitizer = new TextSanitizer()

    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i)
            if (Character.isHighSurrogate(c) && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))) return true
            if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)))) return true
        }
        false
    }

    def "length cap never leaves half an emoji"() {
        given: 'a loadout name of 79 letters and an emoji (81 UTF-16 units, cap 80)'
        def name = 'a' * 79 + '😀'

        when:
        def out = sanitizer.cleanShort(name)

        then:
        !hasLoneSurrogate(out)
        out == 'a' * 79
    }

    def "cap keeps a whole emoji that fits and passes short text through"() {
        expect:
        TextSanitizer.cap('ab😀', 4) == 'ab😀'
        TextSanitizer.cap('ab😀c', 3) == 'ab'
        TextSanitizer.cap('hello', 10) == 'hello'
        TextSanitizer.cap(null, 3) == null
    }

    def "multiline cap never leaves half an emoji"() {
        given:
        def body = 'a' * (TextSanitizer.LIMIT_LONG - 1) + '😀'

        expect:
        !hasLoneSurrogate(TextSanitizer.multiline(sanitizer, body))
    }

    @Unroll
    def "only invisible characters cleans to empty: #label"() {
        expect:
        sanitizer.cleanShort(input) == ''

        where:
        label          | input
        'zwsp'         | '​'
        'zwsp+bom'     | '​​﻿'
        'word joiner'  | ' ⁠ '
        'nbsp'         | '  '
        'rlm only'     | '‏'
        'hangul filler'| '\u3164'
        'hw filler'    | '\uFFA0 \u115F\u1160'
        'braille blank'| '\u2800\u2800'
    }

    def "zero-width and no-break spaces at the ends are trimmed, inner ZWJ kept"() {
        given:
        def family = '👨‍👩‍👧'

        expect:
        sanitizer.clean('​ ' + family + ' ok ​') == family + ' ok'
    }

    def "bidi override controls are dropped"() {
        expect:
        sanitizer.clean('evil‮gpj.exe') == 'evilgpj.exe'
        sanitizer.clean('a⁦b⁩c') == 'abc'
    }

    def "Arabic, Hebrew and combining marks pass through untouched"() {
        given:
        def s = 'سعر 120$ שלום ₪45.50 Z̴̡a̷'

        expect:
        sanitizer.clean(s) == s
    }

    def "Korean text and a filler inside visible text are kept"() {
        expect:
        sanitizer.clean('안녕하세요') == '안녕하세요'
        sanitizer.clean('a\u3164b') == 'a\u3164b'
    }

    def "staff search term and audit summary caps never leave half an emoji"() {
        given:
        AuditLogRepository repo = Mock()
        repo.save(_) >> { args -> args[0] }
        SteamUserRepository users = Mock()
        users.findById(_) >> Optional.of(new SteamUser(id: 1L, displayName: 'b' * 79 + '😀'))
        def audit = new AuditService(auditLogRepository: repo, steamUserRepository: users)

        when:
        def term  = ZeroResultSearchTracker.normalise('a' * (ZeroResultSearchTracker.MAX_TERM_LENGTH - 1) + '😀')
        def entry = audit.log('TRADE_DISPUTED', 1L, 1L, null, 'Disputed: ' + 'x' * 489 + '😀 tail')

        then:
        !hasLoneSurrogate(term)
        !hasLoneSurrogate(entry.summary)
        !hasLoneSurrogate(entry.actorName)
        entry.summary.length() == 499
    }
}
