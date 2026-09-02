package com.sboxmarket

import com.sboxmarket.config.BackupFreshnessReporter
import com.sboxmarket.controller.AdminController
import com.sboxmarket.controller.HealthController
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.nio.file.Path
import java.time.Instant

/**
 * <b>A backup that stopped running must be impossible to miss.</b>
 *
 * <h3>The gap this closes</h3>
 *
 * {@code deploy/h2-backup.ps1} has written {@code data\h2-backup-status.json}
 * on every run since 4cf7b53. <b>Nothing read it.</b> The agent that built it
 * said so in the commit: "I built the signal; no monitor reads it."
 *
 * <p>That is not a cosmetic gap. No amount of logging <i>inside</i> a
 * scheduled job can report that the job stopped being scheduled — the log
 * simply stops, and a stopped scheduler and a quiet machine produce identical
 * evidence. This repo has the receipt: {@code SkinBox DB Backup} failed with
 * {@code LastTaskResult 127} every day from April to September, writing 150
 * identical lines into a log nobody opened.</p>
 *
 * <h3>What is asserted here</h3>
 *
 * That the three states stay apart — <b>ran and verified</b>, <b>ran and
 * FAILED</b>, and <b>did not run at all</b> — and that the third is reached by
 * a <i>missing file</i> as well as by an old timestamp. Collapsing them is
 * this project's signature defect; a missing file read as an error, or worse
 * as a pass, is the same substitution in a new place.
 *
 * <p>Every assertion below is written to fail when the specific branch it
 * names is removed. The mutations are recorded in {@code deploy/RUNBOOK.md}.
 * An assertion that cannot be made to fail by deleting the thing it claims to
 * test is decoration.</p>
 */
class BackupFreshnessIsReportedSpec extends Specification {

    @TempDir Path tmp

    static final long MINUTE = 60_000L
    static final long NOW    = Instant.parse('2026-09-02T09:20:00Z').toEpochMilli()
    static final long WINDOW = BackupFreshnessReporter.DEFAULT_MAX_AGE_MINUTES

    /** An ISO-8601 UTC stamp `ageMinutes` before {@link #NOW}, in the exact
     *  format `Write-Status` emits (`yyyy-MM-ddTHH:mm:ssZ`). */
    private static String stamp(long ageMinutes) {
        Instant.ofEpochMilli(NOW - (ageMinutes * MINUTE)).toString().replaceAll(/\.\d+Z$/, 'Z')
    }

    /**
     * <b>The exact record `deploy/h2-backup.ps1` wrote on this machine at
     * 2026-09-02T09:09:45Z</b>, copied byte-for-byte out of
     * `data\h2-backup-status.json` rather than paraphrased.
     *
     * <p>A reporter that is flawless about a shape the writer never produces
     * is worth nothing — that is the "two modules disagreed" defect, and this
     * repo has shipped it more than once. Every field name below is a contract
     * with the PowerShell that emits it, cross-checked against the script
     * itself in the last test in this file.</p>
     *
     * <p><b>One exception to "byte-for-byte", stated rather than glossed:</b>
     * the {@code offsite} block. That run predates the second copy and wrote no
     * such block; see {@link #OFFSITE_OK} for exactly which of its numbers came
     * from which real run.</p>
     */
    private static Map liveRecord(Map overrides = [:]) {
        [
            schema:          1,
            last_run_at:     '2026-09-02T09:09:45Z',
            machine:         'DESKTOP-QP5HOUC',
            outcome:         'ok',
            ok:              true,
            db_path:         'C:\\Users\\WW\\Desktop\\sboxmarket\\data\\sboxmarket',
            zip:             'C:\\Users\\WW\\skinbox-backups\\h2-sboxmarket-20260902T090943Z.zip',
            zip_bytes:       '112004',
            live_mvdb_bytes: '380928',
            tables_checked:  '35',
            counts:          [steam_users: 7, wallets: 9, transactions: 44, audit_log: 37],
            prune:           [ran: true, skipped_because: null, deleted: 0, retained: 4],
            offsite:         OFFSITE_OK.collectEntries { k, v -> [k, v] },
            duration_ms:     1768,
            error:           null,
        ] + overrides
    }

    /**
     * <b>The second copy, as the script records it when it worked.</b>
     *
     * <p>Until 2026-09-02 there was no such block. The archives and the database
     * were both on C:, so four verified archives were <i>one</i> copy and a
     * single drive failure took all of them. {@code h2-backup.ps1} now writes
     * each VERIFIED archive to D: as well — a Simple Storage Space whose only
     * backing device is physical disk 1, a different NVMe from the one C: lives
     * on — and reads it back <i>there</i> by length and SHA-256 before recording
     * any of this.</p>
     *
     * <p><b>Where these numbers come from, exactly.</b> This is a composite of
     * two consecutive real runs, and saying so matters more than pretending
     * otherwise: the 09:09:45Z run above <i>predates the feature</i> and could
     * not have had an offsite block at all. So {@code dir}, {@code volume_label},
     * {@code free_bytes}, {@code count} and the retention numbers are copied
     * from {@code data\h2-backup-status.json} as written at 2026-09-02T10:32:06Z
     * — the first run that ever made a second copy — while {@code path},
     * {@code bytes} and {@code sha256} are the real values <i>of the archive the
     * record above names</i>, measured on disk, so the composite is internally
     * consistent instead of describing two different files. {@code prune.ran} is
     * the ordinary-run value; the recorded run was invoked with {@code -NoPrune}
     * so that a first live exercise of new code could not delete anything.</p>
     */
    private static final Map OFFSITE_OK = [
        attempted:    true,
        ok:           true,
        reason:       null,
        dir:          'D:\\skinbox-backups',
        path:         'D:\\skinbox-backups\\h2-sboxmarket-20260902T090943Z.zip',
        bytes:        112004,
        sha256:       'B5376E7AA612B731F583078BA6575E677B886E02C38CEF032869B9F721F2279F',
        volume_label: 'Storage space',
        free_bytes:   218163998720,
        newest_at:    '2026-09-02T09:09:45Z',
        count:        1,
        prune:        [ran: true, skipped_because: null, deleted: 0,
                       retained: 1, retain_days: 365, minimum_keep: 30],
    ]

    /** The same block as the job writes it when the copy did NOT happen. */
    private static Map offsiteMissing(String reason) {
        [attempted: false, ok: false, reason: reason,
         dir: 'D:\\skinbox-backups', path: null, bytes: null, sha256: null,
         volume_label: null, free_bytes: null, newest_at: null, count: null,
         prune: [ran: false, skipped_because: 'this run placed no verified copy here',
                 deleted: 0, retained: null, retain_days: null, minimum_keep: null]]
    }

    // ══ STATE 1: ran and verified ═══════════════════════════════════

    def "the record this machine actually wrote reads as OK"() {
        // Ten minutes old, ok:true, a read-back that matched 35 tables.
        given:
        def r = BackupFreshnessReporter.assess(liveRecord(), NOW, WINDOW)

        expect:
        r.state       == BackupFreshnessReporter.STATE_OK
        r.reason      == null
        r.ran
        r.ranVerified
        !r.ranFailed
        !r.stale
        !r.loud
        r.ageMinutes  == 10L
    }

    // ══ STATE 3: did not run at all ═════════════════════════════════

    def "a MISSING status file is STALE — the third state, not an error"() {
        // The distinction the whole class turns on. "No backup has ever run
        // here" and "no backup has run since Tuesday" are the same fact about
        // the data. Reporting the first as an exception (or, worse, letting it
        // fall through to ok) is the absence-read-as-a-result substitution
        // this repo keeps shipping.
        given:
        def r = BackupFreshnessReporter.assess(null, NOW, WINDOW)

        expect: 'the third state by name, not a fourth one and not an error'
        r.state  == BackupFreshnessReporter.STATE_STALE
        r.reason == BackupFreshnessReporter.REASON_NO_STATUS_FILE

        and: 'and it does not claim a run happened'
        !r.ran
        !r.ranVerified
        r.stale
        r.loud
        r.ageMinutes == null
    }

    def "a VERIFIED backup older than the window is STALE — the scheduler stopped"() {
        // The failure no logging inside the backup job can ever report. The
        // last run succeeded; the point is that there has not been one since.
        given: 'three days old, and it verified perfectly when it ran'
        def r = BackupFreshnessReporter.assess(
            liveRecord(last_run_at: stamp(3 * 24 * 60)), NOW, WINDOW)

        expect:
        r.state  == BackupFreshnessReporter.STATE_STALE
        r.reason == BackupFreshnessReporter.REASON_TOO_OLD

        and: 'the run itself is still reported as having verified — that is WHY it is confusing'
        r.ran
        r.ranVerified
        !r.ranFailed

        and:
        r.ageMinutes == 3L * 24 * 60
        r.loud
    }

    def "an UNREADABLE status file is STALE, with its own reason — never confused with an absent one"() {
        // Same verdict about the data, completely different thing to go fix:
        // an absent file means the job was never wired up, a corrupt one means
        // a write was interrupted.
        given:
        def absent  = BackupFreshnessReporter.assess(null, NOW, WINDOW)
        def corrupt = BackupFreshnessReporter.assess(BackupFreshnessReporter.UNREADABLE, NOW, WINDOW)

        expect: 'both land in state 3'
        absent.state  == BackupFreshnessReporter.STATE_STALE
        corrupt.state == BackupFreshnessReporter.STATE_STALE

        and: 'and are still told apart'
        absent.reason  == BackupFreshnessReporter.REASON_NO_STATUS_FILE
        corrupt.reason == BackupFreshnessReporter.REASON_UNREADABLE
        absent.reason  != corrupt.reason
    }

    @Unroll
    def "a record with #label cannot establish a run, so it is STALE not ok"() {
        // A status file claiming ok:true with no usable timestamp is exactly
        // the shape that would let a stopped scheduler read as healthy.
        given:
        def r = BackupFreshnessReporter.assess(record, NOW, WINDOW)

        expect:
        r.state  == BackupFreshnessReporter.STATE_STALE
        r.reason == reason
        !r.ran

        where:
        label                    | record                                        || reason
        'no last_run_at at all'  | [ok: true, outcome: 'ok']                     || BackupFreshnessReporter.REASON_NO_TIMESTAMP
        'a null last_run_at'     | [ok: true, last_run_at: null]                 || BackupFreshnessReporter.REASON_NO_TIMESTAMP
        'an empty last_run_at'   | [ok: true, last_run_at: '']                   || BackupFreshnessReporter.REASON_NO_TIMESTAMP
        'a garbage last_run_at'  | [ok: true, last_run_at: 'yesterday-ish']      || BackupFreshnessReporter.REASON_BAD_TIMESTAMP
        'a local-time stamp'     | [ok: true, last_run_at: '2026-09-02 09:09:45']|| BackupFreshnessReporter.REASON_BAD_TIMESTAMP
    }

    // ══ STATE 2: ran and FAILED ═════════════════════════════════════

    def "a fresh run that did NOT verify is FAILED — a distinct state from stale"() {
        // The scheduler is alive and firing; the backup is broken. Telling the
        // operator "stale" here would send him to Task Scheduler for a fault
        // that is in the job.
        given:
        def r = BackupFreshnessReporter.assess(
            liveRecord(ok: false, outcome: 'error',
                       error: 'restored WALLETS=8 outside live window [9,9]',
                       last_run_at: stamp(11)),
            NOW, WINDOW)

        expect:
        r.state  == BackupFreshnessReporter.STATE_FAILED
        r.reason == BackupFreshnessReporter.REASON_RUN_FAILED

        and: 'it DID run — that is the whole difference from state 3'
        r.ran
        !r.stale
        r.ranFailed
        !r.ranVerified

        and: 'and the recorded error rides along, so the operator is not sent looking for it'
        r.outcome == 'error'
        r.error.contains('outside live window')
        r.loud
    }

    def "a record with NO ok field is FAILED and says so differently from one that reported failure"() {
        // A malformed record must not certify a backup. Distinct reason,
        // because "the script reported an error" and "the script wrote a
        // record with no verdict in it" are different bugs.
        given:
        def reported = BackupFreshnessReporter.assess(
            liveRecord(ok: false, last_run_at: stamp(5)), NOW, WINDOW)
        def missing  = BackupFreshnessReporter.assess(
            [last_run_at: stamp(5), outcome: 'ok'], NOW, WINDOW)

        expect:
        reported.state  == BackupFreshnessReporter.STATE_FAILED
        missing.state   == BackupFreshnessReporter.STATE_FAILED

        and:
        reported.reason == BackupFreshnessReporter.REASON_RUN_FAILED
        missing.reason  == BackupFreshnessReporter.REASON_NOT_VERIFIED
    }

    @Unroll
    def "ok=#value is treated as verified=#verified — a stringified flag never reads as success"() {
        // Groovy's `as Boolean` on the non-empty String "false" returns TRUE.
        // AdminController.parseConfirmFlag exists because that exact coercion
        // was one misrouted UI away from an irreversible PII wipe. A backup
        // status is the last place to repeat it.
        expect:
        BackupFreshnessReporter.isStrictlyTrue(value) == verified

        where:
        value   || verified
        true    || true
        'true'  || true
        'TRUE'  || true
        false   || false
        'false' || false
        'yes'   || false
        1       || false
        '1'     || false
        ''      || false
        null    || false
    }

    def "the string 'false' in the ok field produces FAILED, not ok"() {
        // The end-to-end consequence of the row above, asserted through assess
        // so a loosening of the coercion fails here too and not only in the
        // unit table.
        expect:
        BackupFreshnessReporter.assess(
            liveRecord(ok: 'false', last_run_at: stamp(5)), NOW, WINDOW).state ==
                BackupFreshnessReporter.STATE_FAILED
    }

    // ══ the two states that hold at once ════════════════════════════

    def "a run that FAILED and then never ran again reports BOTH facts"() {
        // Precedence: "nothing has backed this database up in three days"
        // subsumes "the last attempt errored", so the state is stale. But the
        // failure must not be swallowed by that precedence — collapsing the
        // two is the defect, not the summary.
        given:
        def r = BackupFreshnessReporter.assess(
            liveRecord(ok: false, outcome: 'error', error: 'BACKUP TO threw',
                       last_run_at: stamp(3 * 24 * 60)),
            NOW, WINDOW)

        expect: 'the more urgent fact is the state'
        r.state == BackupFreshnessReporter.STATE_STALE
        r.stale

        and: 'and the other one is NOT lost'
        r.ranFailed
        !r.ranVerified
        r.outcome == 'error'
        r.error   == 'BACKUP TO threw'

        and: 'the banner an operator reads says both, in those words'
        String text = BackupFreshnessReporter.banner(r)
        text.contains('STALE')
        text.contains('reported failure')
        text.contains('both are true')
        text.contains('BACKUP TO threw')
    }

    def "a merely-stale backup does NOT carry the failure line — stale advice is worse than none"() {
        given:
        String text = BackupFreshnessReporter.banner(
            BackupFreshnessReporter.assess(
                liveRecord(last_run_at: stamp(3 * 24 * 60)), NOW, WINDOW))

        expect:
        text.contains('STALE')
        !text.contains('reported failure')
        !text.contains('both are true')
    }

    // ══ STATE 1b: verified, and on ONE disk ═════════════════════════
    //
    // The hole this closes was stated in the RUNBOOK and not engineered around:
    // C:\Users\WW\skinbox-backups and data\sboxmarket.mv.db were both on C:, so
    // one drive failure lost the database and every archive of it in the same
    // event. h2-backup.ps1 now copies each VERIFIED archive to a second physical
    // device. This state is what the reporter says when that copy is not there —
    // and the whole point is that it is neither of its neighbours.

    def "a verified backup with NO second copy is its own state — not ok, not stale"() {
        given: 'the archive verified eleven minutes ago; the second copy did not happen'
        def r = BackupFreshnessReporter.assess(
            liveRecord(last_run_at: stamp(11),
                       offsite: offsiteMissing('the volume D:\\ is not present -- drive absent, or the letter changed')),
            NOW, WINDOW)

        expect: 'its own word'
        r.state == BackupFreshnessReporter.STATE_OK_NO_OFFSITE

        and: '''NOT ok. Saying ok here rebuilds the exact hole the second copy
                closes, and the public probe carries the state word and nothing
                else — a fact that is not in the word does not leave the box.'''
        r.state != BackupFreshnessReporter.STATE_OK

        and: '''NOT stale. The archive verified eleven minutes ago; the scheduler
                is alive. Calling this stale sends the operator to Task Scheduler
                and teaches him that the word means nothing.'''
        r.state != BackupFreshnessReporter.STATE_STALE
        !r.stale

        and: 'NOT failed — the run did verify'
        r.state != BackupFreshnessReporter.STATE_FAILED
        !r.ranFailed
        r.ran
        r.ranVerified

        and: 'and it is loud, because a silent one-copy state is what we had before'
        r.loud
        r.reason == BackupFreshnessReporter.REASON_OFFSITE_NOT_CURRENT
    }

    def "the job's OWN words about the destination ride along, not just a reason code"() {
        // "offsite-copy-not-current" tells the operator nothing about WHICH of
        // the six ways it can fail happened. The script already knows; the
        // reporter's job is to carry it, not to re-derive it.
        given:
        def r = BackupFreshnessReporter.assess(
            liveRecord(last_run_at: stamp(11),
                       offsite: offsiteMissing("volume D:\\ is labelled 'Scratch' but 'Storage space' was expected")),
            NOW, WINDOW)

        expect:
        r.offsiteReason.contains('Storage space')
        !r.offsiteOk
        r.offsitePath == null

        and: 'and the banner puts it in front of the operator'
        BackupFreshnessReporter.banner(r).contains('Storage space')
    }

    def "a status file with NO offsite block at all is a DIFFERENT reason from a failed copy"() {
        // The two mean opposite things. No block: the machine is still running
        // a h2-backup.ps1 from before the second copy existed — ship the job.
        // A block saying ok:false: the job ran and the destination is the
        // problem — go look at the drive. Same verdict, different fix; this is
        // the same split as no-status-file versus unreadable-status-file, and
        // it exists for the same reason.
        given:
        def older = liveRecord(last_run_at: stamp(11))
        older.remove('offsite')
        def tried = liveRecord(last_run_at: stamp(11),
                               offsite: offsiteMissing('the volume D:\\ is not present'))

        when:
        def absent  = BackupFreshnessReporter.assess(older, NOW, WINDOW)
        def failed  = BackupFreshnessReporter.assess(tried, NOW, WINDOW)

        then: 'the same verdict'
        absent.state == BackupFreshnessReporter.STATE_OK_NO_OFFSITE
        failed.state == BackupFreshnessReporter.STATE_OK_NO_OFFSITE

        and: '''and DIFFERENT reasons — asserted against each other, not merely
                against their own constants. Comparing each to its own constant
                is satisfied by defining the two constants identically, which is
                exactly the collapse this test exists to forbid.'''
        absent.reason != failed.reason

        and: 'and each is the one it should be'
        absent.reason == BackupFreshnessReporter.REASON_OFFSITE_NOT_REPORTED
        failed.reason == BackupFreshnessReporter.REASON_OFFSITE_NOT_CURRENT

        and: 'an absence never certifies a copy'
        !absent.offsiteOk
        absent.offsiteReason == null
    }

    def "the record this machine wrote WITH a verified second copy is plain ok"() {
        // The control. Without it every assertion above is satisfied by a
        // reporter that can never say `ok` at all.
        given:
        def r = BackupFreshnessReporter.assess(liveRecord(last_run_at: stamp(11)), NOW, WINDOW)

        expect:
        r.state == BackupFreshnessReporter.STATE_OK
        r.offsiteOk
        !r.loud
        r.offsitePath.startsWith('D:')
        r.offsiteNewestAt == '2026-09-02T09:09:45Z'
    }

    @Unroll
    def "offsite ok=#value is treated as a verified copy=#verified — no stringified flag reads as one"() {
        // Same coercion trap as the primary `ok` field, in a field that arrives
        // through JSON from PowerShell. The string "false" is truthy in Groovy.
        given:
        def r = BackupFreshnessReporter.assess(
            liveRecord(last_run_at: stamp(11), offsite: OFFSITE_OK + [ok: value]), NOW, WINDOW)

        expect:
        r.offsiteOk == verified
        r.state == (verified ? BackupFreshnessReporter.STATE_OK
                             : BackupFreshnessReporter.STATE_OK_NO_OFFSITE)

        where:
        value   || verified
        true    || true
        'true'  || true
        false   || false
        'false' || false
        1       || false
        null    || false
    }

    @Unroll
    def "#label subsumes a missing second copy, and still reports it"() {
        // Precedence: stale > failed > ok-no-offsite. "Nothing has backed this
        // database up in three days" says everything "there is one copy of last
        // night's archive" would have — but the offsite facts still ride along,
        // so the precedence costs no information. Flattening a state into one of
        // its halves is this project's signature defect.
        given:
        def r = BackupFreshnessReporter.assess(
            liveRecord(record + [offsite: offsiteMissing('the volume D:\\ is not present')]),
            NOW, WINDOW)

        expect:
        r.state == expected
        r.state != BackupFreshnessReporter.STATE_OK_NO_OFFSITE
        r.state != BackupFreshnessReporter.STATE_OK

        and: 'the second-copy facts survive the precedence'
        !r.offsiteOk
        r.offsiteReason.contains('not present')

        where:
        label                     | record                                        || expected
        'a stale timestamp'       | [last_run_at: '2020-01-01T00:00:00Z']          || BackupFreshnessReporter.STATE_STALE
        'a fresh run that failed' | [ok: false, last_run_at: stamp(5)]             || BackupFreshnessReporter.STATE_FAILED
    }

    def "the ONE COPY banner is not the STALE banner and not the FAILED one"() {
        // Three states arriving through the same warn-level log must not read
        // the same. The stale banner sends the operator to Task Scheduler; that
        // is the wrong place entirely when the archive verified minutes ago.
        given:
        def text = BackupFreshnessReporter.banner(
            BackupFreshnessReporter.assess(
                liveRecord(last_run_at: stamp(11),
                           offsite: offsiteMissing('the volume D:\\ is not present')),
                NOW, WINDOW))

        expect: 'it says what is actually true'
        text.contains('ONE COPY')
        text.contains('same disk as the database')

        and: 'and does NOT send him to the scheduler, which is running fine'
        !text.contains('Get-ScheduledTask')
        !text.contains('IS STALE')
        !text.contains('did not verify')

        and: 'it names where to look instead'
        text.contains('skinbox-backups')
    }

    // ══ the window ══════════════════════════════════════════════════

    def "the window clears a daily job's full period, so a healthy backup is never cried wolf over"() {
        expect: '''the task fires daily at 04:00. A window of exactly 24h would
                   flag every ordinary day the run drifted by a minute, and a
                   reporter that cries wolf is one the operator learns to
                   close.'''
        BackupFreshnessReporter.DEFAULT_MAX_AGE_MINUTES > 24L * 60L

        and: '''but not so wide that a whole missed day hides inside it — two
                consecutive skipped runs must be visible.'''
        BackupFreshnessReporter.DEFAULT_MAX_AGE_MINUTES < 48L * 60L
    }

    @Unroll
    def "an age of #ageMin min against a window of #window min is stale=#stale"() {
        expect: 'the boundary is exclusive — exactly at the window is still fresh'
        BackupFreshnessReporter.assess(
            liveRecord(last_run_at: stamp(ageMin)), NOW, window).stale == stale

        where:
        ageMin | window || stale
        0      | 1560   || false
        1559   | 1560   || false
        1560   | 1560   || false
        1561   | 1560   || true
        4320   | 1560   || true
    }

    // ══ where it looks ══════════════════════════════════════════════

    def "the status path FOLLOWS the database — it is not a hard-coded guess"() {
        // h2-backup.ps1 defaults its status file to the sibling of the database
        // it archives. If this reporter derived the path any other way, a moved
        // database would leave it watching an empty directory and reporting
        // no-status-file forever — a permanent false alarm, which is exactly
        // how a real one gets ignored.
        //
        // THE DATABASE IS DELIBERATELY NOT IN data/. Pointing this test at the
        // shipped location made it inert: the fallback constant is
        // `data/h2-backup-status.json`, so a mutation that ignored the URL
        // entirely and always returned the fallback still produced a file named
        // h2-backup-status.json inside a directory named data, and the test
        // stayed GREEN with the derivation deleted. A directory the fallback
        // can never name is what makes this assertion load-bearing.
        given:
        String derived = BackupFreshnessReporter.deriveStatusPath(
            null, 'jdbc:h2:file:./var/moneydb/sboxmarket;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE;MODE=PostgreSQL')

        expect: 'beside the database, wherever the database is'
        new File(derived).name == 'h2-backup-status.json'
        new File(derived).absoluteFile.parentFile.name == 'moneydb'

        and: 'and demonstrably not the shipped fallback'
        derived != BackupFreshnessReporter.FALLBACK_STATUS_PATH
        !(new File(derived).absoluteFile.parentFile.name == 'data')
    }

    @Unroll
    def "status path for #label lands beside #expectedDir"() {
        given:
        String derived = BackupFreshnessReporter.deriveStatusPath(configured, url)

        expect: 'named for the status file'
        new File(derived).name == 'h2-backup-status.json'

        and: '''and in the directory the rule for THIS case names — asserted
                against a directory the fallback cannot produce wherever the
                case is not itself the fallback.'''
        new File(derived).absoluteFile.parentFile.name == expectedDir

        where:
        label                  | configured                         | url                                          || expectedDir
        'an explicit override' | 'C:\\opsdir\\h2-backup-status.json'| 'jdbc:h2:file:./var/moneydb/sboxmarket'      || 'opsdir'
        'a file: url'          | null                               | 'jdbc:h2:file:./var/moneydb/sboxmarket'      || 'moneydb'
        'no file: prefix'      | null                               | 'jdbc:h2:./var/moneydb/sbox;MODE=PostgreSQL' || 'moneydb'
        'an absolute url'      | null                               | 'jdbc:h2:file:C:/srv/skinbox/db/sboxmarket'  || 'db'
        'an in-memory url'     | null                               | 'jdbc:h2:mem:testdb'                         || 'data'
        'an empty url'         | null                               | ''                                           || 'data'
        'a null url'           | null                               | null                                         || 'data'
    }

    def "the reported path has no dot segments left in it"() {
        // The shipped datasource URL is `./data/sboxmarket`, so the raw
        // derivation is `...\sboxmarket\.\data\h2-backup-status.json`. It opens
        // fine either way; the point is that the banner's whole job is to say
        // WHERE it looked, and an operator should not have to decide whether
        // the odd-looking path is itself the bug.
        given:
        def r = new BackupFreshnessReporter(
            configuredStatusPath: '',
            datasourceUrl: 'jdbc:h2:file:./data/sboxmarket;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE',
            configuredMaxAgeMinutes: 0L)

        expect:
        !r.resolvedStatusPath().contains(File.separator + '.' + File.separator)
        r.resolvedStatusPath().endsWith('data' + File.separator + 'h2-backup-status.json')
        new File(r.resolvedStatusPath()).absolute
    }

    def "an explicit configured path wins over the derived one, verbatim"() {
        expect: 'returned unchanged — not normalised, not relocated'
        BackupFreshnessReporter.deriveStatusPath(
            'C:\\elsewhere\\st.json', 'jdbc:h2:file:./var/moneydb/sboxmarket') == 'C:\\elsewhere\\st.json'
    }

    def "an in-memory or TCP url falls back rather than inventing a directory"() {
        // mem:/tcp: databases have no directory to sit beside. Deriving one
        // from the alias would send the reporter looking in a directory named
        // `mem:` that cannot exist.
        expect:
        BackupFreshnessReporter.deriveStatusPath(null, url) ==
            BackupFreshnessReporter.FALLBACK_STATUS_PATH

        where:
        url << ['jdbc:h2:mem:testdb', 'jdbc:h2:tcp://localhost/~/sbox', 'jdbc:h2:ssl://host/db',
                '', null, 'jdbc:postgresql://localhost/sbox']
    }

    // ══ the file is actually READ — not merely assessed ═════════════

    def "a real status file on disk is parsed and reported"() {
        // assess() being flawless is worth nothing if nothing ever hands it
        // the file. This walks the whole path: resolve, read, parse, assess.
        given: 'the live record, written to disk exactly as Write-Status writes it'
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText(new groovy.json.JsonBuilder(
            liveRecord(last_run_at: nowStampMinus(5))).toPrettyString(), 'UTF-8')

        when:
        def r = reporterFor(f).currentReport()

        then:
        r.state == BackupFreshnessReporter.STATE_OK

        and: 'and the admin-only detail comes through with it'
        r.tablesChecked == '35'
        r.counts.wallets == 9
        r.machine == 'DESKTOP-QP5HOUC'
        r.statusPath == f.absolutePath
    }

    def "a status path pointing at nothing reports STALE and NAMES the path it looked at"() {
        // A wrong path must not be a mystery. "no-status-file" with no path
        // attached sends the operator to check a scheduler that is running
        // fine.
        given:
        File missing = tmp.resolve('not-written-yet.json').toFile()

        when:
        def r = reporterFor(missing).currentReport()

        then:
        r.state      == BackupFreshnessReporter.STATE_STALE
        r.reason     == BackupFreshnessReporter.REASON_NO_STATUS_FILE
        r.statusPath == missing.absolutePath

        and: 'the banner carries the path, so the operator can see WHERE it looked'
        BackupFreshnessReporter.banner(r).contains(missing.absolutePath)
    }

    def "a truncated status file on disk is UNREADABLE, not ok"() {
        // A half-written file — the shape an interrupted Write-Status leaves
        // if the atomic move ever regresses.
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText('{ "ok": true, "last_run_at": "2026-09', 'UTF-8')

        when:
        def r = reporterFor(f).currentReport()

        then:
        r.state  == BackupFreshnessReporter.STATE_STALE
        r.reason == BackupFreshnessReporter.REASON_UNREADABLE
    }

    def "a status file holding a JSON array rather than an object is UNREADABLE, not ok"() {
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText('[1,2,3]', 'UTF-8')

        expect:
        reporterFor(f).currentReport().reason == BackupFreshnessReporter.REASON_UNREADABLE
    }

    // ══ the public probe ════════════════════════════════════════════

    def "the public probe answers 200 only when the backup verified"() {
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText(new groovy.json.JsonBuilder(
            liveRecord(last_run_at: nowStampMinus(5))).toString(), 'UTF-8')
        def c = new HealthController(backupFreshnessReporter: reporterFor(f))

        when:
        def resp = c.backup()

        then:
        resp.statusCode.value() == 200
        resp.body.backup == BackupFreshnessReporter.STATE_OK
    }

    @Unroll
    def "the public probe answers 503 when the backup is #label"() {
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        if (content != null) f.setText(content, 'UTF-8')
        def c = new HealthController(backupFreshnessReporter: reporterFor(f))

        when:
        def resp = c.backup()

        then: '''503, because an uptime monitor that reads only status codes is
                 the one consumer that alerts with nobody logged in.'''
        resp.statusCode.value() == 503
        resp.body.status == 'DOWN'
        resp.body.backup == state

        where:
        label                       | content                                                          || state
        'stale (file never written)'| null                                                             || BackupFreshnessReporter.STATE_STALE
        'stale (old timestamp)'     | '{"ok":true,"last_run_at":"2020-01-01T00:00:00Z"}'               || BackupFreshnessReporter.STATE_STALE
        'unreadable'                | '{ truncated'                                                    || BackupFreshnessReporter.STATE_STALE
    }

    def "the public probe answers 503 for a verified backup with only ONE copy, and names it"() {
        // The deliberate call. `ok-no-offsite` means the money database HAS a
        // read-back-verified archive from tonight — sitting on the disk it lives
        // on. That was this deployment's permanent condition until 2026-09-02
        // and it is what a quietly unplugged second drive leaves behind, so it
        // must reach the ONLY consumer that speaks with nobody logged in. The
        // probe is exact-matched out of the nginx and Docker health targets, so
        // a 503 here evicts nothing and costs no outage.
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText(new groovy.json.JsonBuilder(
            liveRecord(last_run_at: nowStampMinus(5),
                       offsite: offsiteMissing('the volume D:\\ is not present'))).toString(), 'UTF-8')
        def c = new HealthController(backupFreshnessReporter: reporterFor(f))

        when:
        def resp = c.backup()

        then:
        resp.statusCode.value() == 503

        and: '''and the body carries the DISTINCT word, so a monitor that reads
                the body can tell a missing second copy from a stopped
                scheduler. Collapsing the two here would undo the whole point
                of keeping them apart in assess().'''
        resp.body.backup == BackupFreshnessReporter.STATE_OK_NO_OFFSITE
        resp.body.backup != BackupFreshnessReporter.STATE_STALE
        resp.body.backup != BackupFreshnessReporter.STATE_OK
    }

    def "the public probe answers 503 when the reporter bean is MISSING"() {
        // An absence must never read as a pass — the substitution this whole
        // feature exists to refuse must not sneak back in through its own
        // wiring.
        given:
        def c = new HealthController(backupFreshnessReporter: null)

        expect:
        c.backup().statusCode.value() == 503
        c.backup().body.backup == BackupFreshnessReporter.STATE_STALE
    }

    def "publicState reports STALE when the report itself throws — an exception never reads as ok"() {
        // The defensive catch inside publicState(). Found untested by mutation:
        // changing its `return STATE_STALE` to `return STATE_OK` left the whole
        // suite GREEN, because nothing reached that branch. An unhandled
        // surprise resolving to "backups are fine" is the precise substitution
        // this class exists to refuse, so the branch gets an assertion of its
        // own rather than trust.
        given: 'a reporter whose report cannot be produced at all'
        def blowUp = new BackupFreshnessReporter() {
            @Override Map currentReport() { throw new IllegalStateException('disk on fire') }
        }

        expect: 'state 3 — we cannot establish that a backup ran'
        blowUp.publicState() == BackupFreshnessReporter.STATE_STALE

        and: 'and the probe in front of it answers 503, not 200'
        new HealthController(backupFreshnessReporter: blowUp).backup().statusCode.value() == 503
    }

    def "a throwing reporter does not take down the admin Health tile"() {
        // Same branch from the other side: systemHealth() must survive it. A
        // reporter that can 500 the staff panel is a gate wearing a reporter's
        // name.
        given:
        def blowUp = new BackupFreshnessReporter() {
            @Override Map currentReport() { throw new IllegalStateException('disk on fire') }
        }

        when:
        def health = new com.sboxmarket.service.AdminService(
            backupFreshnessReporter: blowUp).systemHealth()

        then: 'the rest of the tile still renders'
        health.uptimeMs != null
        health.containsKey('backup')

        and: '''and the unproduceable report is null — which the UI renders as
                "Reporter not wired", never as a verified backup.'''
        health.backup == null
    }

    def "the public probe body carries the state word and NOTHING else"() {
        // Naming the archive tells an anonymous caller which single file on
        // this box holds every wallet row; the row counts size the money. Both
        // are on the admin surface instead. HealthController already withholds
        // a far weaker fingerprint (startupAt) from anonymous callers.
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText(new groovy.json.JsonBuilder(
            liveRecord(last_run_at: nowStampMinus(5))).toString(), 'UTF-8')

        when:
        def body = new HealthController(backupFreshnessReporter: reporterFor(f)).backup().body

        then: 'exactly two keys'
        body.keySet() as Set == ['status', 'backup'] as Set

        and: 'and none of the detail that names this deployment'
        String rendered = new groovy.json.JsonBuilder(body).toString()
        !rendered.contains('skinbox-backups')
        !rendered.contains('DESKTOP-QP5HOUC')
        !rendered.contains('sboxmarket.mv.db')
        !rendered.contains(f.absolutePath.replace('\\', '\\\\'))
        !rendered.contains('wallets')
        !rendered.contains('last_run_at')
    }

    def "the public probe is a SEPARATE path from the load-balancer and Docker targets"() {
        // /api/health and /api/ready are the Docker HEALTHCHECK, the deploy
        // gate and the LB targets, matched with `location = ` (exact) in both
        // nginx configs. A stale backup returning 503 on either of those would
        // evict a healthy app from rotation — turning a data-protection
        // warning into the outage it exists to prevent.
        given:
        def paths = HealthController.getDeclaredMethod('backup')
            .getAnnotation(GetMapping).value().toList()

        expect:
        paths == ['/api/health/backup', '/api/health/backup/']

        and:
        !paths.contains('/api/health')
        !paths.contains('/api/ready')

        and: 'the liveness probe is untouched and still unconditional'
        HealthController.getDeclaredMethod('health')
            .getAnnotation(GetMapping).value().toList() == ['/api/health', '/api/health/']
    }

    def "a stale backup does NOT change what the liveness probe says"() {
        // Asserted by calling it, not by reading the source: liveness must not
        // acquire a dependency on backup state by accident later.
        given: 'a reporter that will report stale — the file does not exist'
        def c = new HealthController(
            backupFreshnessReporter: reporterFor(tmp.resolve('nope.json').toFile()))

        expect:
        c.backup().statusCode.value() == 503
        c.health().statusCode.value() == 200
        c.health().body.status == 'UP'
    }

    // ══ the admin surface ═══════════════════════════════════════════

    def "the admin endpoint is MAPPED, and sits behind the admin-authed prefix"() {
        given:
        def m = AdminController.getDeclaredMethod(
            'backupStatus', jakarta.servlet.http.HttpServletRequest)

        expect: 'mapped, not merely written — this repo has shipped correct code nothing called'
        m.isAnnotationPresent(GetMapping)
        m.getAnnotation(GetMapping).value().toList() == ['/security/backup-status']

        and: '''under /api/admin, so the archive path and the money row counts
                need an ADMIN session — same reasoning that keeps the ADMIN
                Steam IDs off the public surface.'''
        AdminController.class.getAnnotation(
            org.springframework.web.bind.annotation.RequestMapping).value().toList() == ['/api/admin']
    }

    def "the admin report carries the detail the public probe withholds"() {
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText(new groovy.json.JsonBuilder(
            liveRecord(last_run_at: nowStampMinus(5))).toString(), 'UTF-8')

        when:
        def r = reporterFor(f).currentReport()

        then: 'the things an anonymous caller must not get'
        r.zip.contains('skinbox-backups')
        r.dbPath.contains('sboxmarket')
        r.counts.transactions == 44
        r.prune.retained == 4

        and: '''including the WHOLE second-copy block — the destination, the
                label of the volume it is on, the SHA-256 that was read back
                there and how many copies are sitting on it. Admin-only by
                placement, exactly like `zip`: it names a second machine-
                readable location of the money database.'''
        r.offsite.dir == 'D:\\skinbox-backups'
        r.offsite.volume_label == 'Storage space'
        r.offsite.sha256 != null
        r.offsite.count == 1
        r.offsite.prune.retain_days == 365
    }

    def "the public probe leaks NOTHING about the second copy"() {
        // The admin block above names a second location of the money database.
        // The public probe gets one word, and the word must not become a path.
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText(new groovy.json.JsonBuilder(
            liveRecord(last_run_at: nowStampMinus(5))).toString(), 'UTF-8')

        when:
        def body = new HealthController(backupFreshnessReporter: reporterFor(f)).backup().body

        then:
        body.keySet() == ['status', 'backup'] as Set
        !body.toString().contains('D:')
        !body.toString().contains('skinbox-backups')
    }

    // ══ the registration, not merely the code ═══════════════════════

    def "the reporter is a @Component — a class Spring never loads reports nothing"() {
        expect:
        BackupFreshnessReporter.class.isAnnotationPresent(Component)
    }

    def "the freshness check runs on a SCHEDULE, not only at boot"() {
        // THE load-bearing annotation. A boot-only check fires when the app
        // restarts; this app is deliberately long-lived (the watchdog keeps it
        // up), and a backup goes stale WHILE it is running. Without this tick
        // the whole feature only ever notices staleness by accident.
        given:
        def m = BackupFreshnessReporter.getDeclaredMethod('reportOnSchedule')

        expect: 'annotated, so Spring actually calls it'
        m.isAnnotationPresent(Scheduled)

        and: 'on a real repeating delay, not a one-shot'
        m.getAnnotation(Scheduled).fixedDelay() > 0L

        and: '''at most hourly — a daily backup checked once a day could be
                nearly 24h stale before anything said so.'''
        m.getAnnotation(Scheduled).fixedDelay() <= 60L * 60L * 1000L

        and: 'and delayed past boot so it does not double the startup banner'
        m.getAnnotation(Scheduled).initialDelay() > 0L
    }

    def "the boot banner is an @EventListener on ApplicationReadyEvent"() {
        given:
        def m = BackupFreshnessReporter.getDeclaredMethod('reportOnStartup')

        expect:
        m.isAnnotationPresent(org.springframework.context.event.EventListener)
        m.getAnnotation(org.springframework.context.event.EventListener)
         .value().contains(org.springframework.boot.context.event.ApplicationReadyEvent)
    }

    def "the admin Health tile is fed the report — the surface the operator already reads"() {
        // A report behind a URL the operator has to learn is most of the way
        // back to a status file nobody opens. systemHealth() is what the staff
        // panel already polls every 5s while it is open, so that is where the
        // signal has to land. Asserted by CALLING it, not by reading the
        // source: a field wired to nothing would pass a reflection check.
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText(new groovy.json.JsonBuilder(
            liveRecord(last_run_at: nowStampMinus(5))).toString(), 'UTF-8')
        def svc = new com.sboxmarket.service.AdminService(
            backupFreshnessReporter: reporterFor(f))

        when:
        def health = svc.systemHealth()

        then:
        health.backup.state == BackupFreshnessReporter.STATE_OK
        health.backup.reason == null
    }

    def "the Health tile survives a reporter that is not wired, rather than throwing"() {
        // Null-safe like priceSync and stripeWebhook beside it — a missing
        // collaborator must not take down the whole staff panel.
        expect:
        new com.sboxmarket.service.AdminService().systemHealth().backup == null
    }

    def "a one-copy backup reaches the Health tile as its own state, not as ok"() {
        // One layer up from assess(), where the collapse would actually hurt:
        // the tile renders data.backup.state, and this arriving as `ok` would
        // paint "Verified" over a database whose only archive is on the drive
        // it lives on.
        given:
        File f = tmp.resolve('h2-backup-status.json').toFile()
        f.setText(new groovy.json.JsonBuilder(
            liveRecord(last_run_at: nowStampMinus(5),
                       offsite: offsiteMissing('the volume D:\\ is not present'))).toString(), 'UTF-8')
        def svc = new com.sboxmarket.service.AdminService(backupFreshnessReporter: reporterFor(f))

        when:
        def health = svc.systemHealth()

        then:
        health.backup.state == BackupFreshnessReporter.STATE_OK_NO_OFFSITE
        health.backup.state != BackupFreshnessReporter.STATE_OK
        health.backup.state != BackupFreshnessReporter.STATE_STALE

        and: 'with the destination detail the card needs to say WHERE to look'
        health.backup.offsiteReason.contains('not present')
        health.backup.offsite.dir == 'D:\\skinbox-backups'
    }

    def "the staff panel renders the one-copy state instead of falling through to STALE"() {
        // The tile's state ladder is a chain of ternaries with a final `else`.
        // Before this feature that else was 'STALE', so a new state added in
        // Groovy would have silently rendered as STALE in the browser — the
        // collapse happening in the one place nobody would have looked. Pinned
        // against the shipped source because the panel is React-in-JS with no
        // test runner in this project.
        given:
        String js = new File('src/main/resources/static/js/staff-modals.js').getText('UTF-8')

        expect: 'the state has a branch of its own'
        js.contains("b.state === 'ok-no-offsite'")

        and: 'and it does not read as the two states it must stay distinct from'
        js.contains("'ONE COPY'")
    }

    def "a stale backup reaches the Health tile as stale, not as a missing field"() {
        // The UI reads data.backup.state. A stale backup arriving as an absent
        // key would render the same as "reporter not wired" — the exact
        // collapse this feature exists to prevent, one layer up.
        given:
        def svc = new com.sboxmarket.service.AdminService(
            backupFreshnessReporter: reporterFor(tmp.resolve('never-written.json').toFile()))

        expect:
        svc.systemHealth().backup.state  == BackupFreshnessReporter.STATE_STALE
        svc.systemHealth().backup.reason == BackupFreshnessReporter.REASON_NO_STATUS_FILE
    }

    // ══ the contract with the script that writes the file ═══════════

    def "every field this reporter reads is a field deploy/h2-backup.ps1 actually writes"() {
        // The "two modules disagreed" defect: a consumer that is flawless
        // about a shape the producer never emits. Cross-checked against the
        // script itself so a rename on either side fails here.
        given:
        File ps1 = new File('deploy/h2-backup.ps1')

        expect: 'the script is where this test believes it is'
        ps1.isFile()

        and:
        String src = ps1.getText('UTF-8')
        ['last_run_at', 'outcome', 'ok', 'zip', 'db_path', 'machine',
         'tables_checked', 'counts', 'prune', 'duration_ms', 'error'].every {
            src.contains(it)
        }

        and: '''and it writes the same default location this reporter derives —
                the sibling of the database it archives.'''
        src.contains('h2-backup-status.json')

        and: '''every second-copy field this reporter reads is one the script
                emits. `offsite.ok` and `offsite.reason` are the two the state
                turns on; the rest are what the admin tile and banner show, and
                a rename on either side has to fail somewhere.'''
        ['offsite', 'attempted', 'sha256', 'volume_label', 'free_bytes',
         'newest_at', 'retain_days', 'minimum_keep'].every { src.contains(it) }
    }

    def "the script's own default destination is on a DIFFERENT volume from the database"() {
        // THE CLAIM THIS FILE EXISTS TO PROTECT. The point of the second copy is
        // a second failure domain; a default that landed back on C: would be one
        // copy wearing two names, and it would pass every other test here. This
        // is the case that catches a future edit pointing the offsite copy home.
        given: 'the two defaults the script ships with'
        String src = new File('deploy/h2-backup.ps1').getText('UTF-8')
        String backupDir  = psStringDefault(src, 'BackupDir')
        String offsiteDir = psStringDefault(src, 'OffsiteDir')

        and: '''and the volume the DATABASE is on — which is not a literal in the
                script at all. h2-backup.ps1 derives $DbPath from its own location
                ($Repo = the parent of $PSScriptRoot, then data\\sboxmarket), so the
                database is always on the volume the checkout is on. Resolved here
                the same way, rather than assumed to be C:.'''
        String dbVolume = volumeOf(new File('data').absoluteFile.path)

        expect: '''both defaults were actually FOUND. Without this the comparisons
                   below are `null != "D:"`, which is true — a regex that matched
                   nothing would report the strongest possible result. That is the
                   precise shape of the bug this test itself shipped with: the
                   pattern was written as a slashy string, `$BackupDir` inside one
                   is a GString property reference rather than text, and it threw
                   at runtime having compiled cleanly.'''
        backupDir  != null
        offsiteDir != null

        and: 'and what was captured really is a rooted path, not an empty capture'
        backupDir.length()  > 3
        offsiteDir.length() > 3
        backupDir[1]  == ':'
        offsiteDir[1] == ':'

        and: 'the second copy is not on the volume the primary archives are on'
        volumeOf(offsiteDir) != volumeOf(backupDir)

        and: 'nor on the volume the database itself lives on — the actual claim'
        volumeOf(offsiteDir) != dbVolume

        and: 'and the primary archives ARE beside the database, which is why this matters'
        volumeOf(backupDir) == dbVolume
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** A reporter pinned to one file, with the shipped window. A fresh
     *  instance per case, because the parse cache is per-instance and a shared
     *  one would let an earlier case's content answer a later case. */
    private static BackupFreshnessReporter reporterFor(File f) {
        new BackupFreshnessReporter(
            configuredStatusPath:    f.absolutePath,
            datasourceUrl:           '',
            configuredMaxAgeMinutes: 0L)
    }

    /**
     * The single-quoted default of a {@code [string] $Name = '...'} parameter in
     * {@code deploy/h2-backup.ps1}, or {@code null} if it is not there.
     *
     * <p><b>The pattern is built from single-quoted strings, never a slashy
     * one.</b> Groovy's {@code /.../} is a GString, so {@code $BackupDir} inside
     * it is a <i>property reference on the spec class</i>, not the four
     * characters of a PowerShell variable name. The first version of the caller
     * did exactly that: {@code compileTestGroovy} returned 0 and the case threw
     * {@code MissingPropertyException} on execution. In this language a clean
     * compile says nothing about whether a spec's references exist, so the
     * escaping lives here, once, behind a name.</p>
     */
    private static String psStringDefault(String src, String param) {
        def p = java.util.regex.Pattern.compile(
            '(?m)^[ \\t]*\\[string\\][ \\t]*\\$' + java.util.regex.Pattern.quote(param) +
            '[ \\t]*=[ \\t]*\'([^\']*)\'')
        def m = p.matcher(src)
        m.find() ? m.group(1) : null
    }

    /** {@code 'D:\skinbox-backups'} -> {@code 'D:'}. Case-folded, because a
     *  drive letter's case is display, not identity. */
    private static String volumeOf(String path) {
        path == null ? null : (path.length() >= 2 ? path.substring(0, 2).toUpperCase() : null)
    }

    /** Real wall-clock stamp, for the tests that go through currentReport()
     *  (which uses System.currentTimeMillis and cannot be handed NOW). */
    private static String nowStampMinus(long minutes) {
        Instant.ofEpochMilli(System.currentTimeMillis() - (minutes * MINUTE))
               .toString().replaceAll(/\.\d+Z$/, 'Z')
    }
}
