package com.sboxmarket.config

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * <b>A backup that stopped running must SAY SO, from inside the app that
 * would lose the data.</b>
 *
 * <h3>The half this closes</h3>
 *
 * {@code deploy/h2-backup.ps1} writes {@code data\h2-backup-status.json} on
 * every run, success or failure. Until this class, <b>nothing read it</b>. A
 * signal nobody consumes is the same shape as the defect the backup job was
 * built to end: {@code SkinBox DB Backup} failed with {@code LastTaskResult
 * 127} every day from April to September, writing 150 identical lines into a
 * log nobody opened.
 *
 * <p>And the failure that matters most cannot be reported from inside the job
 * at all. <b>No amount of logging inside a scheduled task can report that the
 * task stopped being scheduled.</b> The log simply stops, and a stopped
 * scheduler is byte-for-byte indistinguishable from a quiet machine. The only
 * way to see it is for something <i>else</i> — something that is running — to
 * notice that the timestamp has not moved. That is this class, and it is why
 * the check is on a {@link Scheduled} tick and not only on boot: the app runs
 * for days, and a backup goes stale <i>while it is running</i>.</p>
 *
 * <h3>The four states, kept apart on purpose</h3>
 *
 * Collapsing these is this project's signature defect — an absence read as a
 * pass. So {@link #assess} returns exactly one of four, and never invents a
 * fifth:
 *
 * <ol>
 *   <li>{@code ok} — a run happened inside the window, its read-back verified,
 *       <b>and a verified copy reached the second physical device.</b> The
 *       archive was extracted, opened, every table's row count matched live,
 *       and the copy on the other disk was read back and matched by size and
 *       SHA-256.</li>
 *   <li>{@code ok-no-offsite} — <b>the database is backed up, on one disk.</b>
 *       The primary run verified inside the window; the second copy did not
 *       happen, or the status file predates the job that makes one. Its own
 *       state because the two facts have opposite fixes and opposite
 *       urgencies: {@code stale} means "there may be no current backup at
 *       all", this means "there is one, and it is sitting on the same drive as
 *       the database it protects". Folding it into {@code ok} would rebuild
 *       the exact hole this feature closes — four verified archives on a dying
 *       disk is one copy. Folding it into {@code stale} would cry wolf about a
 *       backup that verified an hour ago, and a reporter that cries wolf is one
 *       the operator learns to close.</li>
 *   <li>{@code failed} — a run happened inside the window and reported
 *       failure. Retention was skipped; the archives on disk are the older
 *       ones. Something is wrong with the backup, but the scheduler is
 *       alive.</li>
 *   <li>{@code stale} — <b>we do not know that a run happened recently.</b>
 *       The scheduler stopped, the machine slept through 04:00, the task was
 *       deleted, or the status file was never written. <b>A missing file is
 *       this state, not an error</b> — "no backup has ever run here" and "no
 *       backup has run since Tuesday" are the same fact about the data, and
 *       both are louder than any exception.</li>
 * </ol>
 *
 * <p>Precedence runs {@code stale} → {@code failed} → {@code ok-no-offsite} →
 * {@code ok}, worst first, because each earlier state subsumes the ones after
 * it: "nothing has backed this database up in three days" says everything
 * "there is only one copy of last night's archive" would have. Nothing is lost
 * to the precedence — {@code ranFailed}, the recorded {@code error} and the
 * whole {@code offsite} block are reported alongside whichever state wins. An
 * unreadable status file is also {@code stale} (we cannot establish that a run
 * happened) and carries its own {@code reason} code, so it is never confused
 * with a file that is simply absent.</p>
 *
 * <h3>Where this is surfaced, and why the split</h3>
 *
 * <ul>
 *   <li><b>{@code GET /api/health/backup} — public, one word.</b> 200 when
 *       {@code ok}, 503 when {@code failed}, {@code stale} or
 *       {@code ok-no-offsite}, and the body
 *       carries the state word and nothing else: no path, no row count, no
 *       hostname, no timestamp. An uptime monitor that only reads status codes
 *       is the only consumer that alerts without a human logged in, and this
 *       deployment's whole documented history is of signals no human looked
 *       at. The leak is one bit — "this deployment's backups are not current"
 *       — against a signal that otherwise reaches nobody. It is deliberately
 *       NOT {@code /api/health} or {@code /api/ready}: those are the Docker
 *       HEALTHCHECK and load-balancer targets (both exact-match in
 *       {@code deploy/nginx.conf}), and a stale backup must never evict a
 *       healthy app from rotation. A data-protection warning that causes an
 *       outage would be a gate wearing a reporter's name.</li>
 *   <li><b>{@code GET /api/admin/security/backup-status} and the admin Health
 *       tile — everything else.</b> Archive path, database path, row counts,
 *       machine name and retention detail name the file worth stealing and the
 *       size of the money table. Admin-authed, exactly as
 *       {@link UnconfiguredAdminReporter} keeps the list of ADMIN Steam IDs
 *       off the anonymous surface.</li>
 *   <li><b>The log</b> — a banner on boot and on every hourly tick while the
 *       state is not {@code ok}. Kept because it costs nothing, and NOT relied
 *       on: the 150-line receipt above is what a log-only signal is worth
 *       here.</li>
 * </ul>
 *
 * <h3>What it does not do</h3>
 *
 * It does not run a backup, refuse to start, or touch an archive. It reads one
 * small file and reports. A reporter that can abort a boot is a gate wearing a
 * reporter's name, and a stale backup is a reason to look, never a reason to
 * take the marketplace down.
 */
@Component
@Slf4j
class BackupFreshnessReporter {

    // ── the four states ─────────────────────────────────────────────
    static final String STATE_OK     = 'ok'
    static final String STATE_FAILED = 'failed'
    static final String STATE_STALE  = 'stale'
    /** Verified, inside the window — and on one disk only. Deliberately its own
     *  word rather than a flag on {@code ok}: the public probe carries the state
     *  and nothing else, so a boolean nobody transmits is a boolean nobody sees. */
    static final String STATE_OK_NO_OFFSITE = 'ok-no-offsite'

    // ── why, underneath the state. Never collapsed into the state
    //    itself: "the file is absent" and "the file is corrupt" are the
    //    same verdict about the data and completely different fixes.
    static final String REASON_NO_STATUS_FILE   = 'no-status-file'
    static final String REASON_UNREADABLE       = 'unreadable-status-file'
    static final String REASON_NO_TIMESTAMP     = 'no-last-run-at'
    static final String REASON_BAD_TIMESTAMP    = 'unparseable-last-run-at'
    static final String REASON_TOO_OLD          = 'last-run-too-old'
    static final String REASON_RUN_FAILED       = 'last-run-failed'
    static final String REASON_NOT_VERIFIED     = 'last-run-not-verified'
    /** The status file carries no {@code offsite} block at all — written by a
     *  version of {@code h2-backup.ps1} from before the second copy existed, so
     *  we cannot establish that one was made. Distinct from the block below for
     *  the same reason an absent status file is distinct from a corrupt one:
     *  this one means "ship the new job", that one means "go look at the
     *  destination". */
    static final String REASON_OFFSITE_NOT_REPORTED = 'offsite-not-reported'
    /** The job ran, tried (or deliberately skipped) the second copy, and did not
     *  place one. {@code offsiteReason} carries the job's own words for why. */
    static final String REASON_OFFSITE_NOT_CURRENT  = 'offsite-copy-not-current'

    /**
     * Sentinel handed to {@link #assess} when the file exists but could not be
     * parsed. A distinct object rather than a magic key inside the parsed map,
     * so it can never collide with a field the writer might one day emit.
     */
    static final Map UNREADABLE = Collections.unmodifiableMap([__unreadable: true])

    /**
     * Default window: the scheduled task fires daily at 04:00, so 26 hours is
     * one full period plus two hours of slack. The slack is not decoration —
     * a DST shift moves a local-time daily trigger by an hour, and the run
     * itself takes seconds but has to queue behind whatever else woke at 04:00.
     * A window of exactly 24h would cry wolf twice a year, and a reporter that
     * cries wolf is one the operator learns to close.
     */
    static final long DEFAULT_MAX_AGE_MINUTES = 26L * 60L

    /** Default location, used only when neither the property nor the datasource URL resolves one. */
    static final String FALLBACK_STATUS_PATH = 'data/h2-backup-status.json'

    /** Re-parse at most this often. `/api/admin/health` polls every 5s while the
     *  staff panel is open; a daily job does not need a disk read per poll. The
     *  freshness ARITHMETIC is redone on every call regardless — only the parse
     *  is cached, so a cached record can never report a stale age as fresh. */
    static final long PARSE_CACHE_MS = 30_000L

    @Value('${sbox.backup.status-path:}')       String configuredStatusPath
    @Value('${spring.datasource.url:}')          String datasourceUrl
    @Value('${sbox.backup.max-age-minutes:0}')   long   configuredMaxAgeMinutes

    // Cached parse + the mtime/size it was taken from. Guarded by `this`.
    private Map    cachedStatus
    private long   cachedAtMs      = 0L
    private String cachedFrom
    private String lastLoggedState

    // ── the decision, as a pure function ────────────────────────────

    /**
     * The whole verdict with no Spring context, no clock and no disk — the
     * shape {@link UnconfiguredAdminReporter#assess} and
     * {@code H2CredentialGuard.violation} already use here, so every branch is
     * unit-testable and every mutation of it fails a named test.
     *
     * @param status         the parsed status file, {@code null} when the file
     *                       does not exist, or {@link #UNREADABLE} when it
     *                       exists and could not be parsed.
     * @param nowEpochMs     the moment to measure against.
     * @param maxAgeMinutes  how old {@code last_run_at} may be before the
     *                       scheduler is presumed stopped.
     */
    static Map assess(Map status, long nowEpochMs, long maxAgeMinutes) {
        Map out = [
            state:         STATE_STALE,
            reason:        null,
            ran:           false,
            ranVerified:   false,
            ranFailed:     false,
            stale:         true,
            lastRunAt:     null,
            ageMinutes:    null,
            maxAgeMinutes: maxAgeMinutes,
            outcome:       null,
            error:         null,
            // The second copy. `false` is the loud default for the same reason
            // `state` starts STALE: a path that forgets to set it must report a
            // problem, never a pass. There is no third value here — "we could
            // not tell" is false, because an unproven second copy is not one.
            offsiteOk:      false,
            offsiteReason:  null,
            offsitePath:    null,
            offsiteNewestAt: null,
            // Pre-set loud, so a branch added later that forgets to set it
            // defaults to "say something" rather than to silence. Absence
            // read as a pass is the failure this whole class is about.
            loud:          true,
        ]

        // ── state 3a: nothing has ever written a status file here.
        // NOT an error. A backup that never ran and a backup that stopped
        // running leave the data in exactly the same place.
        if (status == null) {
            out.reason = REASON_NO_STATUS_FILE
            out.loud = true
            return out
        }
        // ── state 3b: a file we cannot read tells us nothing about whether a
        // run happened, so it must not certify one. Distinct reason code: an
        // absent file means "wire up the job", a corrupt one means "the write
        // was interrupted".
        if (status.is(UNREADABLE)) {
            out.reason = REASON_UNREADABLE
            out.loud = true
            return out
        }

        out.outcome = str(status.outcome)
        out.error   = str(status.error)

        // The second copy, read here rather than at the decision below so that a
        // stale or failed primary still REPORTS what it knows about the offsite
        // location. "It failed AND the second copy is three weeks old" must not
        // be flattened into whichever half wins the precedence.
        Map offsite = (status.offsite instanceof Map) ? (Map) status.offsite : null
        if (offsite != null) {
            out.offsiteOk       = isStrictlyTrue(offsite.ok)
            out.offsiteReason   = str(offsite.reason)
            out.offsitePath     = str(offsite.path)
            out.offsiteNewestAt = str(offsite.newest_at)
        }

        // The run's own verdict. Read the SAME way AdminController parses its
        // confirm gate: Boolean.TRUE or the exact string "true", nothing else.
        // Groovy's `as Boolean` would make the string "false" mean true, and a
        // backup is the last place to let a stringified flag read as success.
        boolean verified = isStrictlyTrue(status.ok)
        out.ranVerified = verified

        // ── the timestamp. A record with no usable timestamp cannot establish
        // that a run happened recently, whatever else it claims — so it lands
        // in state 3, not in state 1.
        String raw = str(status.last_run_at)
        if (!raw) {
            out.reason = REASON_NO_TIMESTAMP
            out.ranFailed = !verified
            out.loud = true
            return out
        }
        out.lastRunAt = raw
        Instant at
        try {
            at = Instant.parse(raw)
        } catch (DateTimeParseException | IllegalArgumentException ignored) {
            out.reason = REASON_BAD_TIMESTAMP
            out.ranFailed = !verified
            out.loud = true
            return out
        }

        long ageMs = nowEpochMs - at.toEpochMilli()
        // A timestamp in the future is not fresh, it is a clock problem —
        // clamp the age at zero so a machine whose clock jumped forward cannot
        // manufacture an arbitrarily "recent" backup. It still has to pass the
        // window test below, which it will; the honest reading of a future
        // timestamp is "we cannot tell", but a skewed clock on a box that runs
        // one daily job is a far weaker signal than a genuinely missed run, and
        // reporting it as fresh-but-odd beats a permanent false alarm.
        long ageMinutes = Math.max(0L, ageMs) / 60_000L
        out.ageMinutes = ageMinutes
        out.ran = true

        boolean stale = ageMinutes > maxAgeMinutes
        out.stale = stale

        if (stale) {
            // ── state 3c: the scheduler stopped, the machine slept, or the
            // task was deleted. The recorded outcome is reported alongside so
            // "it failed AND then never ran again" is not flattened into one
            // of its halves.
            out.state     = STATE_STALE
            out.reason    = REASON_TOO_OLD
            out.ranFailed = !verified
            out.loud      = true
            return out
        }

        if (!verified) {
            // ── state 2: it ran, inside the window, and did not verify.
            out.state     = STATE_FAILED
            out.ranFailed = true
            // `ok` absent is a malformed record, `ok` present-and-false is a
            // reported failure. Same verdict, different thing to go look at.
            out.reason    = status.containsKey('ok') ? REASON_RUN_FAILED : REASON_NOT_VERIFIED
            out.loud      = true
            return out
        }

        // ── state 1b: the primary verified, and there is no current second copy.
        //
        // The database IS backed up. It is backed up onto the disk it lives on,
        // which is what "four verified archives" was worth on 2026-09-02: one
        // copy. This is not `ok` — saying ok here rebuilds the hole the second
        // copy exists to close, and the public probe carries the state word and
        // nothing else, so anything not in the word does not leave the box.
        // Neither is it `stale`: an archive that verified an hour ago is not a
        // stopped scheduler, and reporting it as one teaches the operator to
        // ignore the word that means a stopped scheduler.
        if (offsite == null) {
            // No block at all: an older h2-backup.ps1 wrote this file. We cannot
            // establish a second copy, and an absence must never certify one.
            out.state  = STATE_OK_NO_OFFSITE
            out.reason = REASON_OFFSITE_NOT_REPORTED
            out.loud   = true
            return out
        }
        if (!out.offsiteOk) {
            out.state  = STATE_OK_NO_OFFSITE
            out.reason = REASON_OFFSITE_NOT_CURRENT
            out.loud   = true
            return out
        }

        // ── state 1: ran, inside the window, read back and verified — and a
        // verified copy of that same archive is on a second physical device.
        out.state = STATE_OK
        out.loud  = false
        out
    }

    /** Boolean.TRUE or the exact string "true". Everything else — including
     *  the string "false", the number 1, and absence — is false. */
    static boolean isStrictlyTrue(Object v) {
        if (v instanceof Boolean) return ((Boolean) v).booleanValue()
        if (v instanceof String)  return 'true'.equalsIgnoreCase((String) v)
        false
    }

    private static String str(Object v) { v == null ? null : String.valueOf(v) }

    /**
     * Where the status file is, decided so the reporter cannot end up watching
     * a directory the backup never writes to — a wrong path here would report
     * {@code no-status-file} forever, which is a false alarm, and a false alarm
     * is how a real one gets ignored.
     *
     * <ol>
     *   <li>an explicit {@code sbox.backup.status-path} wins;</li>
     *   <li>otherwise it is derived from the H2 file URL, because
     *       {@code deploy/h2-backup.ps1} defaults its own status path to the
     *       sibling of the database it backs up — same rule, so the two cannot
     *       drift;</li>
     *   <li>otherwise the shipped relative default.</li>
     * </ol>
     */
    static String deriveStatusPath(String configured, String datasourceUrl) {
        if (configured?.trim()) return configured.trim()
        String url = datasourceUrl?.trim()
        if (url) {
            // jdbc:h2:file:./data/sboxmarket;DB_CLOSE_DELAY=-1;...
            def m = (url =~ /(?i)^jdbc:h2:(?:file:)?([^;]+)/)
            if (m.find()) {
                String dbPath = m.group(1)
                // mem:/tcp: databases have no directory to sit beside.
                if (!(dbPath ==~ /(?i)^(mem|tcp|ssl):.*/)) {
                    File parent = new File(dbPath).absoluteFile.parentFile
                    if (parent != null) return new File(parent, 'h2-backup-status.json').path
                }
            }
        }
        FALLBACK_STATUS_PATH
    }

    /**
     * The absolute path, with `.` / `..` segments folded out.
     *
     * <p>Normalised because this string is printed in the banner and returned
     * to the admin surface, and the derived form is literally
     * {@code ...\sboxmarket\.\data\h2-backup-status.json} — the datasource URL
     * ships as {@code ./data/sboxmarket}. It resolves correctly either way;
     * the point is that an operator reading "I looked here and found nothing"
     * should not first have to decide whether the odd-looking path is the
     * bug.</p>
     *
     * <p>{@code Path.normalize} is textual and touches no filesystem — unlike
     * {@code canonicalPath}, which resolves 8.3 short names and symlinks and
     * would make the reported path differ from the one actually opened.</p>
     */
    String resolvedStatusPath() {
        new File(deriveStatusPath(configuredStatusPath, datasourceUrl))
            .absoluteFile.toPath().normalize().toString()
    }

    long maxAgeMinutes() {
        configuredMaxAgeMinutes > 0 ? configuredMaxAgeMinutes : DEFAULT_MAX_AGE_MINUTES
    }

    // ── reading the file ────────────────────────────────────────────

    /**
     * Parse the status file, or return {@code null} (absent) /
     * {@link #UNREADABLE} (present but unparseable). Best-effort by
     * construction: this must not be able to throw into a scheduled tick or a
     * health probe.
     */
    private synchronized Map readStatus(String path, long nowMs) {
        if (cachedFrom == path && (nowMs - cachedAtMs) < PARSE_CACHE_MS) {
            return cachedStatus
        }
        Map parsed
        File f = new File(path)
        if (!f.isFile()) {
            parsed = null
        } else {
            try {
                def obj = new JsonSlurper().parseText(f.getText('UTF-8'))
                parsed = (obj instanceof Map) ? (Map) obj : UNREADABLE
            } catch (Exception e) {
                log.warn("Backup status file at ${path} could not be parsed: ${e.class.name}: ${e.message}")
                parsed = UNREADABLE
            }
        }
        cachedStatus = parsed
        cachedFrom   = path
        cachedAtMs   = nowMs
        parsed
    }

    /** The full report, for the admin surfaces. Includes the resolved path so
     *  "I looked here and found nothing" is visible rather than mysterious. */
    Map currentReport() {
        long now = System.currentTimeMillis()
        String path = resolvedStatusPath()
        Map status
        try {
            status = readStatus(path, now)
        } catch (Exception e) {
            log.warn("Backup status file at ${path} could not be read: ${e.class.name}: ${e.message}")
            status = UNREADABLE
        }
        Map report = assess(status, now, maxAgeMinutes())
        report.statusPath = path
        if (status != null && !status.is(UNREADABLE)) {
            // Detail that is admin-only by placement: it names the archive
            // worth stealing and the size of the money tables.
            report.zip           = str(status.zip)
            report.dbPath        = str(status.db_path)
            report.machine       = str(status.machine)
            report.tablesChecked = str(status.tables_checked)
            report.counts        = (status.counts instanceof Map) ? status.counts : null
            report.prune         = (status.prune instanceof Map) ? status.prune : null
            report.durationMs    = str(status.duration_ms)
            // The whole offsite block: destination, volume label, byte count,
            // SHA-256, free space, how many copies are there and how old the
            // newest one is. Admin-only by placement, exactly like `zip` above —
            // it names a second machine-readable location of the money database.
            report.offsite       = (status.offsite instanceof Map) ? status.offsite : null
        }
        report
    }

    /** The one word the public probe is allowed to say. Nothing else about
     *  this deployment's backups leaves the box unauthenticated. */
    String publicState() {
        try {
            return currentReport().state
        } catch (Exception e) {
            log.warn("Backup freshness check failed: ${e.class.name}: ${e.message}")
            // Cannot establish that a backup ran → state 3. An exception here
            // must never read as ok; that is the exact substitution this whole
            // class exists to refuse.
            return STATE_STALE
        }
    }

    // ── the loud channels ───────────────────────────────────────────

    @EventListener(ApplicationReadyEvent)
    void reportOnStartup() { check() }

    /**
     * Hourly, for as long as the process lives. This is the tick that makes a
     * stopped scheduler visible: the boot banner alone would only ever fire on
     * a restart, and this app is deliberately long-lived. Delay before the
     * first tick so it does not double up with the boot banner.
     */
    @Scheduled(fixedDelay = 60L * 60L * 1000L, initialDelay = 60L * 60L * 1000L)
    void reportOnSchedule() { check() }

    private void check() {
        Map report
        try {
            report = currentReport()
        } catch (Exception e) {
            log.warn("Backup freshness report failed to run: ${e.class.name}: ${e.message}")
            return
        }
        String state = report.state
        if (state == STATE_OK) {
            // Say something even when clean, so a silent log is not ambiguous
            // between "nothing to report" and "the reporter never ran" — the
            // same reason UnconfiguredAdminReporter logs its clean case. Only
            // on a change of state, so a healthy log stays quiet.
            if (lastLoggedState != STATE_OK) {
                log.info("H2 backup check: verified ${report.ageMinutes}m ago " +
                         "(${report.tablesChecked ?: '?'} tables matched), window ${report.maxAgeMinutes}m.")
            }
            lastLoggedState = state
            return
        }
        lastLoggedState = state
        log.warn(banner(report))
    }

    /** The banner. Split out so a spec can pin the wording. */
    static String banner(Map report) {
        def lines = []
        lines << ''
        lines << '=================================================================='
        if (report.state == STATE_STALE) {
            lines << ' H2 BACKUP IS STALE — no verified backup inside the window'
        } else if (report.state == STATE_OK_NO_OFFSITE) {
            lines << ' H2 BACKUP HAS ONE COPY — verified, and only on the primary disk'
        } else {
            lines << ' H2 BACKUP FAILED — the last run did not verify'
        }
        lines << '=================================================================='
        lines << " state          : ${report.state}"
        lines << " reason         : ${report.reason}"
        lines << " status file    : ${report.statusPath ?: '(unresolved)'}"
        lines << " last_run_at    : ${report.lastRunAt ?: '(none recorded)'}"
        lines << " age            : ${report.ageMinutes == null ? '(unknown)' : report.ageMinutes + ' min'}" +
                 " (window ${report.maxAgeMinutes} min)"
        if (report.outcome) lines << " recorded outcome: ${report.outcome}"
        if (report.error)   lines << " recorded error  : ${report.error}"
        lines << ''
        if (report.state == STATE_STALE) {
            // The failure that no logging inside the job can report.
            lines << ' NOTHING INSIDE THE BACKUP JOB CAN REPORT THIS. A scheduler that'
            lines << ' stopped firing and a machine with nothing to say produce the same'
            lines << ' silence. Check that the task exists and ran:'
            lines << "   Get-ScheduledTask -TaskName 'SkinBox DB Backup' | Select-Object State"
            lines << "   Get-ScheduledTaskInfo -TaskName 'SkinBox DB Backup'"
            if (report.ranFailed) {
                lines << ''
                lines << ' AND the last run that DID happen reported failure — both are true.'
            }
        } else if (report.state == STATE_OK_NO_OFFSITE) {
            lines << " offsite reason : ${report.offsiteReason ?: '(the job reported no offsite result at all)'}"
            // '(none recorded)', NOT '(none has ever arrived)'. When the volume
            // was unreachable the job could not look, and reporting "there has
            // never been a second copy" on the strength of a directory we could
            // not read is an absence dressed up as a finding.
            lines << " newest copy    : ${report.offsiteNewestAt ?: '(none recorded)'}"
            lines << ''
            lines << ' THE ARCHIVE VERIFIED. It is on the same disk as the database it'
            lines << ' protects, so one drive failure takes both. Nothing is broken and'
            lines << ' nothing is urgent — but this is the state that was permanent until'
            lines << ' 2026-09-02, and it is the state a quietly unplugged second drive'
            lines << ' leaves behind. Check the destination:'
            lines << "   Get-Volume -DriveLetter D | Select-Object FileSystemLabel, SizeRemaining"
            lines << "   Get-ChildItem D:\\skinbox-backups -Filter '*.zip' | Select-Object -Last 3"
        } else {
            lines << ' The scheduler is alive; the backup itself did not verify. Retention'
            lines << ' is skipped on any failure, so the archives already on disk are intact.'
            lines << ' Read the run log: C:\\Users\\WW\\skinbox-backups\\h2-backup.log'
        }
        lines << ''
        lines << ' This is a report, not a gate. It never blocks a boot, a request or a'
        lines << ' trade. See deploy/RUNBOOK.md, "The scheduled H2 backup".'
        lines << '=================================================================='
        lines.join('\n')
    }
}
