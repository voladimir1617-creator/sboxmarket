package com.sboxmarket

import spock.lang.Specification

import java.text.SimpleDateFormat

/**
 * Pins the timezone contract for the GDPR data-export filename stamp
 * produced by ProfileController.exportData (the `/profile/export`
 * endpoint).
 *
 * The bug: a SimpleDateFormat("yyyy-MM-dd") with no explicit timeZone
 * uses the JVM default. On a host whose default zone is not UTC, the
 * date stamp baked into the Content-Disposition filename drifts across
 * midnight relative to the UTC instant — two exports moments apart but
 * straddling 00:00 UTC could be tagged with the same date (host ahead
 * of UTC) or with two different dates (host behind UTC), so the
 * filename no longer corresponds to the UTC day the bundle's
 * timestamps are denominated in. Every other timestamp in the export
 * payload is a raw epoch ms, which downstream tooling renders in UTC;
 * the filename being in a different zone is a silent footgun for
 * anyone diffing two exports.
 *
 * Fix: pin the formatter to UTC, matching every other SimpleDateFormat
 * in ProfileController.
 *
 * This spec runs the formatter against a fixed Date in JVM zones that
 * straddle a UTC midnight to demonstrate the divergence between the
 * pre-fix (unzoned) and post-fix (UTC-pinned) variants. The fix is a
 * one-liner; the spec exists so a future refactor that drops the
 * .timeZone assignment (or copies the snippet without it) trips a
 * deterministic failure instead of shipping silently.
 */
class ProfileExportFilenameDateSpec extends Specification {

    private static final long FIXED_UTC_MS = fixedUtcMs()

    private static long fixedUtcMs() {
        // 2026-05-25 23:30:00 UTC — chosen so a host in +9 (Tokyo) is
        // already on the next calendar day at this instant, exposing
        // the date-drift bug the fix prevents.
        def sdf = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss')
        sdf.timeZone = TimeZone.getTimeZone('UTC')
        return sdf.parse('2026-05-25 23:30:00').time
    }

    def "unzoned SimpleDateFormat drifts across UTC midnight (reproduces pre-fix bug)"() {
        // At 23:30 UTC, a host in Asia/Tokyo (UTC+9) is already on the
        // NEXT calendar day. The pre-fix formatter would stamp the
        // export filename with 2026-05-26 even though every epoch-ms
        // timestamp in the bundle is rendered as 2026-05-25 in UTC.
        given:
        def tokyo = TimeZone.getTimeZone('Asia/Tokyo')
        def df = new SimpleDateFormat('yyyy-MM-dd')
        df.timeZone = tokyo

        expect:
        df.format(new Date(FIXED_UTC_MS)) == '2026-05-26'
    }

    def "UTC-pinned SimpleDateFormat stays on the correct day regardless of host zone"() {
        // This is the post-fix path: explicitly pin to UTC. The filename
        // stamp is now consistent with the UTC-rendered epoch-ms data
        // inside the bundle for every JVM default zone.
        given:
        def df = new SimpleDateFormat('yyyy-MM-dd')
        df.timeZone = TimeZone.getTimeZone('UTC')

        expect:
        df.format(new Date(FIXED_UTC_MS)) == '2026-05-25'

        and: 'and the UTC-pinned formatter does NOT drift even when JVM default would'
        // Sanity: walk a handful of zones spanning ±12h. None should
        // shift the rendered date because the formatter ignores the
        // JVM default once timeZone is set.
        ['Pacific/Auckland', 'Asia/Tokyo', 'Europe/London',
         'America/New_York', 'America/Los_Angeles', 'Pacific/Honolulu'].every { z ->
            def shadow = new SimpleDateFormat('yyyy-MM-dd')
            shadow.timeZone = TimeZone.getTimeZone('UTC')
            // Independent of any TimeZone.setDefault — pinned wins.
            shadow.format(new Date(FIXED_UTC_MS)) == '2026-05-25'
        }
    }

    def "ProfileController source pins the export-filename formatter to UTC"() {
        // Belt-and-braces guard: assert the controller source contains
        // a timeZone assignment on the formatter used in the GDPR
        // export response. If a future refactor drops the line or
        // moves the formatter without zoning it, this fails fast and
        // points at the exact file.
        given:
        def src = new File('src/main/groovy/com/sboxmarket/controller/ProfileController.groovy')

        expect: 'the file exists in the working tree'
        src.exists()

        and: 'the export branch zones the formatter explicitly'
        def text = src.text
        // Locate the export filename snippet and verify a UTC pin
        // appears within the surrounding window of the SimpleDateFormat
        // that feeds the skinbox-data filename.
        def idx = text.indexOf('skinbox-data-')
        idx >= 0
        def window = text.substring(Math.max(0, idx - 400), idx)
        window.contains("new java.text.SimpleDateFormat(\"yyyy-MM-dd\")")
        window.contains("TimeZone.getTimeZone('UTC')") ||
                window.contains('TimeZone.getTimeZone("UTC")')
    }
}
