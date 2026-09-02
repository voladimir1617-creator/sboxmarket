# Scheduled hot backup of the H2 money database, with a read-back that proves it.
#
# THE GAP THIS CLOSES. The daily `SkinBox DB Backup` task ran deploy/backup-db.sh,
# which is Postgres-only (`docker exec sbox-pg pg_dump`). Docker is not running and
# this project does not currently deploy Postgres, so that job failed 150 times into
# a log nobody read (LastTaskResult 127). Meanwhile the actual money -- 7 users,
# 9 wallets, 44 transactions, 37 audit rows -- lives in H2 at data\sboxmarket.mv.db
# and NOTHING backed it up on a schedule. The only three archives that exist were
# taken by hand on 2026-09-02.
#
# WHAT PROVES A BACKUP HERE. Not an exit code. deploy/H2Backup.java takes the archive
# through the H2 auto-server (the only route that works while the app holds the file),
# then EXTRACTS it, OPENS the restored copy, and compares the row count of every table
# against live. That is the discipline whose absence cost four April backups: on
# 2026-09-01 `docker exec ... | gzip > f` failed at the first stage, `$?` reported
# gzip's success, the script printed "Backup OK" for a 20-byte file and then pruned
# every real backup on the machine.
#
# SO RETENTION RUNS ONLY AFTER A VERIFIED BACKUP, and on any failure it is skipped
# explicitly and says so. Two further floors sit under it, because age-based pruning
# alone is one wrong clock away from deleting everything: the newest -MinimumKeep
# archives are never eligible whatever their age, and the archive from this run is
# never eligible at all.
#
# HOW YOU KNOW THIS RAN. A silent healthy pass and a task the scheduler stopped
# firing look identical from outside -- no amount of logging INSIDE a script can
# report that the script did not start. So every run, success or failure, overwrites
# data\h2-backup-status.json with what it found. A stale `last_run_at` there means
# the SCHEDULER stopped. Same pattern, and the same reason, as cs2bot's
# ops\keepalive.ps1 and its data\keepalive-status.json.
#
# NO CONSOLE WINDOW. Do not point Task Scheduler at powershell.exe. It is a
# console-subsystem binary and the scheduler creates its window before
# -WindowStyle Hidden can apply. Launch deploy\h2-backup-hidden.vbs through
# wscript.exe, which is GUI-subsystem and never has a console at all.
#
# NO CREDENTIAL ON A COMMAND LINE. The password reaches Java through the H2_PASSWORD
# environment variable, never as an argument. PowerShell 5.1 silently DROPS an
# empty-string argument to a native executable -- that is how a SQL string slid into
# a `-password` slot tonight -- and the live SA password is empty today, so the trap
# is live on this exact machine. Every java invocation below logs its argument vector
# and the password LENGTH actually used, because a length is the only way to see an
# argument that vanished.

[CmdletBinding()]
param(
    # Everything is a parameter with a production default so the whole decision path
    # can be driven against scratch databases and a temp directory instead of only
    # ever against the live machine. deploy/test-h2-backup.ps1 does exactly that.
    [string] $Repo,
    [string] $BackupDir  = 'C:\Users\WW\skinbox-backups',
    [string] $H2Jar,
    [string] $DbPath,          # the H2 base path, WITHOUT the .mv.db suffix
    [string] $StatusPath,
    [string] $LogPath,
    [string] $Password,        # defaults to SPRING_DATASOURCE_PASSWORD, else empty
    [string] $Prefix     = 'h2-sboxmarket-',
    [int]    $RetainDays = 14,
    # A floor under retention that age cannot cross. With three archives on disk this
    # alone makes the prune a no-op, which is the correct behaviour today.
    [int]    $MinimumKeep = 7,
    [switch] $NoPrune
)

$ErrorActionPreference = 'Stop'
$started = Get-Date

if (-not $Repo)       { $Repo = Split-Path -Parent $PSScriptRoot }
if (-not $DbPath)     { $DbPath = Join-Path $Repo 'data\sboxmarket' }
if (-not $StatusPath) { $StatusPath = Join-Path $Repo 'data\h2-backup-status.json' }
if (-not $LogPath)    { $LogPath = Join-Path $BackupDir 'h2-backup.log' }
if (-not $PSBoundParameters.ContainsKey('Password')) {
    $Password = if ($env:SPRING_DATASOURCE_PASSWORD) { $env:SPRING_DATASOURCE_PASSWORD } else { '' }
}

$JavaSrc = Join-Path $PSScriptRoot 'H2Backup.java'

# The outcome record. Written LAST and UNCONDITIONALLY -- see the header.
$state = [ordered]@{
    schema         = 1
    last_run_at    = $null
    machine        = $env:COMPUTERNAME
    outcome        = 'error'
    ok             = $false
    db_path        = $DbPath
    zip            = $null
    zip_bytes      = $null
    live_mvdb_bytes = $null
    tables_checked = $null
    counts         = [ordered]@{}
    prune          = [ordered]@{ ran = $false; skipped_because = 'did not reach retention'; deleted = 0; retained = $null }
    duration_ms    = $null
    error          = $null
}

function Write-Log([string]$msg) {
    $line = "{0}  {1}" -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss'), $msg
    try {
        $dir = Split-Path -Parent $LogPath
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Add-Content -Path $LogPath -Encoding utf8 -Value $line -ErrorAction Stop
    } catch { }
    Write-Verbose $line
}

# Locate the H2 driver. The app already depends on it, so the gradle cache is the
# honest source; a copy checked in beside this script would be a second thing to keep
# in step with build.gradle, and it would drift.
function Resolve-H2Jar {
    if ($H2Jar) {
        if (-not (Test-Path $H2Jar)) { throw "H2 jar not found at $H2Jar" }
        return $H2Jar
    }
    $root = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.h2database\h2'
    if (-not (Test-Path $root)) { throw "no H2 jar: $root does not exist (run a gradle build once, or pass -H2Jar)" }
    $found = Get-ChildItem -Path $root -Filter 'h2-*.jar' -Recurse -File -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -notmatch 'sources|javadoc' } |
             Sort-Object Name -Descending | Select-Object -First 1
    if (-not $found) { throw "no h2-*.jar under $root (pass -H2Jar explicitly)" }
    return $found.FullName
}

# One java invocation, argv logged. Returns @{ Rc; Fields } where Fields is the
# KEY=VALUE map the tool prints.
function Invoke-H2Tool([string]$JarPath, [string]$Mode) {
    $argv = @('-cp', $JarPath, $JavaSrc, $Mode)
    # PRINT WHAT YOU PASS. An argument PowerShell dropped is invisible in every other
    # record of this run; the count and the quoted vector are how it becomes visible.
    Write-Log ("java argv[{0}]: {1}" -f $argv.Count, (($argv | ForEach-Object { "'$_'" }) -join ' '))
    Write-Log ("H2_PASSWORD length passed via environment: {0}" -f $Password.Length)

    $raw = & java @argv 2>&1
    $rc = $LASTEXITCODE
    $fields = @{}
    foreach ($line in $raw) {
        $t = "$line"
        Write-Log ("  {0}| {1}" -f $Mode, $t)
        $i = $t.IndexOf('=')
        if ($i -gt 0) {
            $k = $t.Substring(0, $i)
            # Repeated keys (MISMATCH) accumulate rather than overwrite.
            if ($fields.ContainsKey($k)) { $fields[$k] = @($fields[$k]) + $t.Substring($i + 1) }
            else { $fields[$k] = $t.Substring($i + 1) }
        }
    }
    return @{ Rc = $rc; Fields = $fields }
}

# Retention. Reached ONLY with a verified archive on disk, and still bounded twice.
function Invoke-Prune([string]$JustMade) {
    $all = @(Get-ChildItem -Path $BackupDir -Filter "$Prefix*.zip" -File -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending)
    $state.prune.retained = $all.Count
    if ($NoPrune) {
        $state.prune.skipped_because = '-NoPrune was passed'
        Write-Log "retention SKIPPED: -NoPrune"
        return
    }
    # The newest $MinimumKeep survive whatever their age. A wrong clock, or a month
    # away from the machine, must not be able to empty this directory.
    $protected = @($all | Select-Object -First $MinimumKeep | ForEach-Object { $_.FullName })
    $cutoff = (Get-Date).AddDays(-$RetainDays)
    $doomed = @($all | Where-Object {
        $_.FullName -ne $JustMade -and $protected -notcontains $_.FullName -and $_.LastWriteTime -lt $cutoff
    })

    $deleted = 0
    foreach ($f in $doomed) {
        try {
            Remove-Item -LiteralPath $f.FullName -Force -ErrorAction Stop
            $sidecar = "$($f.FullName).expected"
            if (Test-Path -LiteralPath $sidecar) { Remove-Item -LiteralPath $sidecar -Force -ErrorAction SilentlyContinue }
            $deleted++
            Write-Log "pruned $($f.Name) (last written $($f.LastWriteTime.ToString('s')))"
        } catch {
            Write-Log "could not prune $($f.Name): $($_.Exception.Message)"
        }
    }
    $state.prune.ran = $true
    $state.prune.deleted = $deleted
    $state.prune.skipped_because = $null
    $state.prune.retained = $all.Count - $deleted
    Write-Log ("retention: kept {0}, pruned {1} (older than {2}d, newest {3} always kept)" -f `
               $state.prune.retained, $deleted, $RetainDays, $MinimumKeep)
}

function Write-Status {
    $state.last_run_at = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    $state.duration_ms = [int]((Get-Date) - $started).TotalMilliseconds
    try {
        $dir = Split-Path -Parent $StatusPath
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        $json = $state | ConvertTo-Json -Depth 6
        $tmp = "$StatusPath.tmp"
        # No BOM: Set-Content -Encoding utf8 writes one on PowerShell 5.1 and a BOM
        # breaks a plain json.load() on the other side.
        [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))
        Move-Item -LiteralPath $tmp -Destination $StatusPath -Force -ErrorAction Stop
    } catch {
        Write-Log "COULD NOT WRITE THE STATUS FILE at ${StatusPath}: $($_.Exception.Message)"
    }
}

# ---------------------------------------------------------------------------------
# the run
# ---------------------------------------------------------------------------------

$mutex = New-Object System.Threading.Mutex($false, 'Global\SboxMarketH2Backup')
$held = $false
$partZip = $null
$verifyDir = $null

try {
    try { $held = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $held = $true }
    if (-not $held) {
        # A skipped run is not a backup. Exiting 0 here would let a permanently stuck
        # lock read as a healthy daily backup, which is this project's signature defect.
        $state.outcome = 'skipped_locked'
        $state.error = 'another h2-backup run holds the lock'
        Write-Log 'ANOTHER RUN HOLDS THE LOCK -- this run did nothing. Retention SKIPPED.'
        $state.prune.skipped_because = 'another run holds the lock'
        exit 2
    }

    Write-Log '--- h2-backup starting ---'
    if (-not (Test-Path $JavaSrc)) { throw "missing $JavaSrc" }
    $mvdb = "$DbPath.mv.db"
    if (-not (Test-Path $mvdb)) { throw "no H2 store file at $mvdb -- refusing to back up a database that is not there" }
    if (-not (Test-Path $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null }

    $jar = Resolve-H2Jar
    Write-Log "h2 jar: $jar"

    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    # Written as .part so a half-finished or unverified archive can never be mistaken
    # for a backup, and can never be counted by retention: the prune filter is *.zip.
    $partZip  = Join-Path $BackupDir ("{0}{1}.zip.part" -f $Prefix, $stamp)
    $finalZip = Join-Path $BackupDir ("{0}{1}.zip" -f $Prefix, $stamp)
    $verifyDir = Join-Path ([System.IO.Path]::GetTempPath()) ("sbox-h2-verify-" + [guid]::NewGuid().ToString('N'))

    # IFEXISTS=TRUE: a wrong path must fail loudly, never be answered by silently
    # creating a NEW EMPTY DATABASE whose zero rows then verify against themselves.
    #
    # AUTO_SERVER is added ONLY when the app actually holds the database, which
    # data\sboxmarket.lock.db is the evidence of. When the app IS up this is the whole
    # point: the client is handed the server key and reconnects over loopback, the only
    # way to read the file while the JVM holds it.
    #
    # When the app is DOWN, asking for AUTO_SERVER would make THIS process the H2
    # server for the ~2 seconds it runs. The watchdog relaunches the app every 2
    # minutes, so a launch landing inside that window would attach to a server that is
    # about to exit with the backup. A plain embedded open takes an exclusive lock
    # instead, and an app start in that window fails cleanly with 90020 and is
    # relaunched 2 minutes later. Both are rare; only one of them hands the money app a
    # connection to a process that is leaving.
    $lockFile = "$DbPath.lock.db"
    $appHoldsIt = Test-Path -LiteralPath $lockFile
    $url = 'jdbc:h2:file:' + ($DbPath -replace '\\', '/') +
           $(if ($appHoldsIt) { ';AUTO_SERVER=TRUE' } else { '' }) +
           ';MODE=PostgreSQL;IFEXISTS=TRUE'
    Write-Log ("lock file {0} -> {1}" -f `
               $(if ($appHoldsIt) { 'present (the app holds the database)' } else { 'absent (app appears down)' }),
               $(if ($appHoldsIt) { 'connecting as a client over the auto-server' } else { 'opening embedded, no listener started' }))

    $env:H2_URL        = $url
    $env:H2_PASSWORD   = $Password
    $env:H2_BACKUP_ZIP = $partZip
    $env:H2_LIVE_MVDB  = $mvdb
    $env:H2_VERIFY_DIR = $verifyDir

    # ---- phase 1: take it
    $b = Invoke-H2Tool $jar 'backup'
    if ($b.Rc -ne 0 -or $b.Fields['BACKUP_OK'] -ne 'true') {
        $state.outcome = 'backup_failed'
        $state.error = if ($b.Fields['ERROR']) { "$($b.Fields['ERROR'])" } else { "backup exited $($b.Rc)" }
        $state.prune.skipped_because = 'the backup failed'
        Write-Log "BACKUP FAILED: $($state.error)"
        Write-Log 'retention SKIPPED -- existing archives left untouched'
        exit 1
    }
    $state.zip_bytes = $b.Fields['ZIP_BYTES']
    $state.live_mvdb_bytes = $b.Fields['LIVE_MVDB_BYTES']

    # ---- phase 2: read it back
    $v = Invoke-H2Tool $jar 'verify'
    if ($v.Rc -ne 0 -or $v.Fields['VERIFY_OK'] -ne 'true') {
        $state.outcome = 'verify_failed'
        $mm = if ($v.Fields['MISMATCH']) { (@($v.Fields['MISMATCH']) -join '; ') } else { $null }
        # -join, not Join-String: Join-String is PowerShell 7+ and this runs on 5.1.
        $parts = @(@($v.Fields['ERROR']) + @($mm) | Where-Object { $_ } | ForEach-Object { "$_" })
        $state.error = if ($parts.Count) { $parts -join ' | ' } else { "verify exited $($v.Rc)" }
        $state.prune.skipped_because = 'the read-back did not match live'
        Write-Log "VERIFY FAILED: $($state.error)"
        Write-Log 'retention SKIPPED -- existing archives left untouched'
        exit 1
    }

    # ---- verified. Only now does the archive get its real name.
    Move-Item -LiteralPath $partZip -Destination $finalZip -Force
    if (Test-Path -LiteralPath "$partZip.expected") {
        Move-Item -LiteralPath "$partZip.expected" -Destination "$finalZip.expected" -Force
    }
    $partZip = $null
    $state.zip = $finalZip
    $state.tables_checked = $v.Fields['CHECKED_TABLES']
    foreach ($t in 'STEAM_USERS', 'WALLETS', 'TRANSACTIONS', 'AUDIT_LOG') {
        if ($v.Fields["RESTORED_$t"]) { $state.counts[$t.ToLower()] = [int]$v.Fields["RESTORED_$t"] }
    }
    $state.outcome = 'ok'
    $state.ok = $true
    Write-Log ("VERIFIED: {0} ({1} bytes, {2} tables matched live)" -f `
               (Split-Path -Leaf $finalZip), $state.zip_bytes, $state.tables_checked)

    Invoke-Prune $finalZip
    exit 0
}
catch {
    $state.outcome = 'error'
    $state.error = $_.Exception.Message
    $state.prune.skipped_because = 'the run threw before retention'
    Write-Log "ERROR: $($state.error)"
    Write-Log 'retention SKIPPED -- existing archives left untouched'
    exit 1
}
finally {
    # An unverified archive is never left lying around wearing a backup's name.
    if ($partZip -and (Test-Path -LiteralPath $partZip)) {
        Remove-Item -LiteralPath $partZip -Force -ErrorAction SilentlyContinue
        Write-Log "discarded the unverified archive $(Split-Path -Leaf $partZip)"
    }
    if ($partZip -and (Test-Path -LiteralPath "$partZip.expected")) {
        Remove-Item -LiteralPath "$partZip.expected" -Force -ErrorAction SilentlyContinue
    }
    if ($verifyDir -and (Test-Path -LiteralPath $verifyDir)) {
        Remove-Item -LiteralPath $verifyDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Status
    Write-Log "--- h2-backup finished: $($state.outcome) ---"
    if ($held) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
