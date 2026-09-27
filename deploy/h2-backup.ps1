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
#
# ONE DISK IS ONE COPY. Until now the archives and the database were both on C:.
# Four verified archives on a dying disk is one copy, not four -- and this box has
# other disks. MEASURED 2026-09-02: C: is partition 3 of physical disk 0 (Samsung SSD
# 990 PRO 1TB, serial 0025_384C_41A0_F708, the boot device). D: is a Simple Storage
# Space whose ONLY backing device is physical disk 1 (Samsung SSD 990 PRO 2TB, serial
# 0025_3842_5143_2D4F). Different silicon, different controller, different failure.
# E: is a 4.6 TB Seagate on USB. D: is the default here because it is INTERNAL: the
# job fires at 04:00 unattended, and a destination that is routinely unplugged makes
# the "no second copy" report a daily false alarm, which is how a report dies. E: is
# strictly better against fire and theft *when it is unplugged*, and it is one
# parameter away -- see deploy/RUNBOOK.md.
#
# THE SECOND COPY IS NEVER ALLOWED TO BREAK THE FIRST. Every branch of the offsite
# block below reports and returns; none of them throws, changes the exit code, or
# touches the primary archive. A backup that fails because a secondary location is
# unavailable is worse than having no secondary location at all.
#
# AND THE COPY IS NOT A BACKUP UNTIL SOMETHING READS IT BACK. Copy-Item returning
# without an error means the API accepted the request, which is the same class of
# evidence as `gzip` exiting 0 on an empty pipe. The bytes are written through to the
# device (FILE_FLAG_WRITE_THROUGH + FlushFileBuffers), then the file is re-opened AT
# THE DESTINATION and its length and SHA-256 compared with the source's.

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
    [switch] $NoPrune,

    # ── the second copy, on a second physical device ──────────────────────────
    # A DRIVE LETTER, made configurable and identity-checked, because a letter is
    # an assignment and not an identity. There is no letter-free way to name a
    # local fixed volume on Windows that an operator can read: a UNC path needs a
    # share (and a credential the S4U task cannot carry), and a \\?\Volume{GUID}\
    # path is stable but unreadable and would have to be re-derived by hand the
    # first time the volume is reformatted. So the letter stays, and the VOLUME
    # LABEL underneath it is checked before a single byte is written. If D: is
    # ever reassigned, the label no longer matches and the copy is SKIPPED and
    # reported as `wrong-volume` -- it does not silently write the money database
    # onto whichever device answered to D: that morning. Change the letter with
    # -OffsiteDir, the expected label with -OffsiteVolumeLabel, or drop the check
    # entirely with -NoOffsiteVolumeCheck (a UNC destination skips it anyway,
    # having no local volume to read).
    #
    # NOT ONE OF THESE DEFAULTS IS AN EMPTY STRING, deliberately. PowerShell 5.1
    # drops an empty-string argument to a native executable and the NEXT argument
    # slides into the vacated slot -- the failure that put a SQL string in a
    # -password position tonight. "No offsite at all" and "no label check" are
    # therefore SWITCHES, so no caller (including deploy/test-h2-backup.ps1) ever
    # has to pass '' to this script.
    [string] $OffsiteDir          = 'D:\skinbox-backups',
    [string] $OffsiteVolumeLabel  = 'Storage space',
    # Retention here is its OWN decision, and a longer one. The whole point of the
    # second copy is to survive the loss of the first, so it must never be the
    # shorter-lived of the two: both numbers are clamped UP to the primary's below
    # and the clamp is recorded. 112 KB a day is 40 MB a decade -- against 203 GB
    # free, keeping a year is free and keeping fourteen days would be a choice
    # made for no reason.
    [int]    $OffsiteRetainDays   = 365,
    [int]    $OffsiteMinimumKeep  = 30,
    [switch] $NoOffsite,
    [switch] $NoOffsiteVolumeCheck
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
    # The second copy. Present on EVERY run, including the ones that never get
    # near it, so a consumer can always tell "no second copy was made" from "this
    # script is too old to make one" -- BackupFreshnessReporter treats those as
    # two different reasons and it can only do that if the key is either a filled
    # block or absent, never a silently-omitted one.
    #
    # `ok` starts false and `reason` starts loud, for the same reason `outcome`
    # starts 'error': a branch added later that forgets to set these reports a
    # problem rather than a pass. An absence read as a success is the failure this
    # entire area exists to close.
    offsite        = [ordered]@{
        attempted     = $false
        ok            = $false
        reason        = 'the run did not reach the offsite copy'
        dir           = $null
        path          = $null
        bytes         = $null
        sha256        = $null
        volume_label  = $null
        free_bytes    = $null
        # The newest archive ALREADY at the destination, whether or not today's
        # copy worked. "Today failed but there is one from yesterday" and "there
        # has never been a second copy" are different emergencies.
        newest_at     = $null
        count         = $null
        prune         = [ordered]@{
            ran = $false; skipped_because = 'did not reach offsite retention'
            deleted = 0; retained = $null; retain_days = $null; minimum_keep = $null
        }
    }
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

# ---------------------------------------------------------------------------------
# the second copy
# ---------------------------------------------------------------------------------

# Decide whether there is a destination to write to, or say exactly why not.
# Returns @{ Dir = <path|$null>; Label = <string|$null>; Reason = <string|$null> }.
# EVERY return here is a REPORT. Nothing in this function throws.
function Resolve-OffsiteTarget {
    if ($NoOffsite)     { return @{ Dir = $null; Label = $null; Reason = '-NoOffsite was passed' } }
    if (-not $OffsiteDir) { return @{ Dir = $null; Label = $null; Reason = 'no offsite directory is configured' } }

    # The VOLUME ROOT, checked separately from the directory, because the two
    # absences mean opposite things. A missing directory on a volume that IS
    # present is a first run and we create it. A missing VOLUME is an unplugged
    # drive or a reassigned letter, and creating D:\skinbox-backups on whatever
    # currently answers to D: is how a money database ends up somewhere nobody
    # meant to put it.
    $root = $null
    try { $root = [System.IO.Path]::GetPathRoot($OffsiteDir) } catch { }
    if (-not $root) { return @{ Dir = $null; Label = $null; Reason = "cannot determine the volume of '$OffsiteDir'" } }
    if (-not (Test-Path -LiteralPath $root)) {
        return @{ Dir = $null; Label = $null
                  Reason = "the volume $root is not present -- drive absent, or the letter changed" }
    }

    # Identity, not merely presence. A letter is an assignment; the label is the
    # closest thing to an identity an operator can read off the machine.
    $label = $null
    $isLocalVolume = ($root -match '^[A-Za-z]:\\$')
    if ($isLocalVolume) {
        try {
            $ld = Get-CimInstance Win32_LogicalDisk -Filter ("DeviceID='{0}'" -f $root.TrimEnd('\')) -ErrorAction Stop
            $label = if ($ld) { [string]$ld.VolumeName } else { $null }
        } catch {
            return @{ Dir = $null; Label = $null
                      Reason = "could not read the volume label of ${root}: $($_.Exception.Message)" }
        }
        if (-not $NoOffsiteVolumeCheck -and $label -ne $OffsiteVolumeLabel) {
            return @{ Dir = $null; Label = $label
                      Reason = ("volume {0} is labelled '{1}' but '{2}' was expected -- refusing to write the " +
                                "money database to a volume this is not") -f $root, $label, $OffsiteVolumeLabel }
        }
    }

    # -PathType Container, not a bare Test-Path: a FILE sitting at the destination
    # path answers "yes it exists" to the bare form, and we would hand it back as a
    # directory. The copy would still fail and still be reported -- but with a
    # reason about a stream constructor rather than about the path, and a reason
    # that sends the operator to the wrong place is most of the way to no reason.
    if (-not (Test-Path -LiteralPath $OffsiteDir -PathType Container)) {
        try { New-Item -ItemType Directory -Path $OffsiteDir -Force -ErrorAction Stop | Out-Null }
        catch { return @{ Dir = $null; Label = $label; Reason = "could not create ${OffsiteDir}: $($_.Exception.Message)" } }
        Write-Log "offsite: created $OffsiteDir"
    }
    return @{ Dir = $OffsiteDir; Label = $label; Reason = $null }
}

# Copy one file and PROVE it arrived, by re-opening it at the destination.
# Returns @{ Ok; Path; Bytes; Sha256; Reason }. Never throws.
function Copy-Verified {
    param([string]$Source, [string]$DestDir)

    # DELIBERATELY OUTSIDE A TRY. If the archive we just verified is not there any
    # more -- antivirus quarantine, a hand-run cleanup, a sync client -- that is not
    # a copy failure with a tidy reason, it is a surprise, and surprises belong to
    # the last-resort catch in Invoke-Offsite. Wrapping these two lines would make
    # that catch unreachable, and an unreachable guard is one that is not there:
    # deploy/test-h2-backup.ps1 drives exactly this path to prove it still catches.
    $srcLen  = (Get-Item -LiteralPath $Source -ErrorAction Stop).Length
    $srcHash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256 -ErrorAction Stop).Hash

    $name      = Split-Path -Leaf $Source
    $destFinal = Join-Path $DestDir $name
    $destPart  = "$destFinal.part"

    # Same .part discipline as the primary: an unproven copy never wears a
    # backup's name, and the retention filter (*.zip) can never count one.
    if (Test-Path -LiteralPath $destPart) { Remove-Item -LiteralPath $destPart -Force -ErrorAction SilentlyContinue }

    # WriteThrough + Flush($true) is FILE_FLAG_WRITE_THROUGH plus
    # FlushFileBuffers: the bytes are on the device before we claim anything,
    # rather than sitting in a write cache that a power loss discards. Copy-Item
    # gives no such guarantee and no way to ask for one.
    $in = $null; $out = $null
    try {
        $in  = [System.IO.File]::OpenRead($Source)
        $out = New-Object System.IO.FileStream(
                    $destPart, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write,
                    [System.IO.FileShare]::None, 81920, [System.IO.FileOptions]::WriteThrough)
        $in.CopyTo($out)
        $out.Flush($true)
    } catch {
        if ($out) { try { $out.Dispose() } catch { } ; $out = $null }
        if ($in)  { try { $in.Dispose()  } catch { } ; $in  = $null }
        if (Test-Path -LiteralPath $destPart) { Remove-Item -LiteralPath $destPart -Force -ErrorAction SilentlyContinue }
        return @{ Ok = $false; Path = $null; Bytes = $null; Sha256 = $null
                  Reason = "the copy to $DestDir failed: $($_.Exception.Message)" }
    } finally {
        if ($out) { try { $out.Dispose() } catch { } }
        if ($in)  { try { $in.Dispose()  } catch { } }
    }

    # THE READ-BACK. Re-open the file AT THE DESTINATION and measure it. Both
    # halves are load-bearing: a length alone misses a same-size corruption, and
    # a hash alone is what a hash of the SOURCE would also be -- the point is
    # that these numbers come off the destination path, not off the thing we
    # were asked to copy.
    #
    # What this does NOT prove: that the media is good. The read-back may be
    # served from the OS cache. It proves the destination holds a whole,
    # byte-identical file under a name we can find again, which is precisely
    # what a Copy-Item that "succeeded" onto a full disk would not.
    $dstLen = $null; $dstHash = $null
    try {
        $dstLen  = (Get-Item -LiteralPath $destPart -ErrorAction Stop).Length
        $dstHash = (Get-FileHash -LiteralPath $destPart -Algorithm SHA256 -ErrorAction Stop).Hash
    } catch {
        if (Test-Path -LiteralPath $destPart) { Remove-Item -LiteralPath $destPart -Force -ErrorAction SilentlyContinue }
        return @{ Ok = $false; Path = $null; Bytes = $null; Sha256 = $null
                  Reason = "the copy could not be read back at ${destPart}: $($_.Exception.Message)" }
    }
    if ($dstLen -ne $srcLen) {
        Remove-Item -LiteralPath $destPart -Force -ErrorAction SilentlyContinue
        return @{ Ok = $false; Path = $null; Bytes = $null; Sha256 = $null
                  Reason = "SIZE MISMATCH at the destination: source $srcLen bytes, copy $dstLen bytes (discarded)" }
    }
    if ($dstHash -ne $srcHash) {
        Remove-Item -LiteralPath $destPart -Force -ErrorAction SilentlyContinue
        return @{ Ok = $false; Path = $null; Bytes = $null; Sha256 = $null
                  Reason = "SHA-256 MISMATCH at the destination: source $srcHash, copy $dstHash (discarded)" }
    }

    try { Move-Item -LiteralPath $destPart -Destination $destFinal -Force -ErrorAction Stop }
    catch {
        Remove-Item -LiteralPath $destPart -Force -ErrorAction SilentlyContinue
        return @{ Ok = $false; Path = $null; Bytes = $null; Sha256 = $null
                  Reason = "could not name the verified copy ${destFinal}: $($_.Exception.Message)" }
    }
    # And confirm it is there under the name we will record, so a status file
    # can never point at a path that does not exist.
    try {
        $finalLen = (Get-Item -LiteralPath $destFinal -ErrorAction Stop).Length
        if ($finalLen -ne $srcLen) {
            return @{ Ok = $false; Path = $null; Bytes = $null; Sha256 = $null
                      Reason = "the renamed copy at $destFinal is $finalLen bytes, expected $srcLen" }
        }
    } catch {
        return @{ Ok = $false; Path = $null; Bytes = $null; Sha256 = $null
                  Reason = "the renamed copy at $destFinal could not be read: $($_.Exception.Message)" }
    }
    return @{ Ok = $true; Path = $destFinal; Bytes = $srcLen; Sha256 = $srcHash; Reason = $null }
}

# What is already at the destination, so "today failed" and "there has never
# been a second copy" never look alike.
function Measure-OffsiteContents([string]$Dir) {
    try {
        $all = @(Get-ChildItem -Path $Dir -Filter "$Prefix*.zip" -File -ErrorAction SilentlyContinue |
                 Sort-Object LastWriteTime -Descending)
        $state.offsite.count = $all.Count
        if ($all.Count -gt 0) {
            $state.offsite.newest_at = $all[0].LastWriteTimeUtc.ToString('yyyy-MM-ddTHH:mm:ssZ')
        }
    } catch { }
}

# Retention at the destination. ITS OWN DECISION, and a deliberately more
# cautious one than the primary's.
#
# THE RULE THAT MATTERS: this runs only when the primary VERIFIED *and* this run
# placed a verified copy here. Pruning the second copy on the strength of a run
# that failed is deleting the only surviving copy because the surviving-copy
# machinery broke -- the 2026-09-01 incident with the two locations swapped.
function Invoke-OffsitePrune([string]$Dir, [string]$JustCopied) {
    # Never shorter-lived than the primary. The clamp is applied here rather
    # than trusted to the defaults so a caller cannot configure the second copy
    # into being the weaker one, and both numbers are recorded so the clamp is
    # visible instead of surprising.
    $days = [Math]::Max($OffsiteRetainDays, $RetainDays)
    $keep = [Math]::Max($OffsiteMinimumKeep, $MinimumKeep)
    $state.offsite.prune.retain_days  = $days
    $state.offsite.prune.minimum_keep = $keep
    if ($days -ne $OffsiteRetainDays -or $keep -ne $OffsiteMinimumKeep) {
        Write-Log ("offsite retention raised to the primary's floor: {0}d/{1} (asked for {2}d/{3})" -f `
                   $days, $keep, $OffsiteRetainDays, $OffsiteMinimumKeep)
    }

    $all = @(Get-ChildItem -Path $Dir -Filter "$Prefix*.zip" -File -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending)
    $state.offsite.prune.retained = $all.Count

    if ($NoPrune) {
        $state.offsite.prune.skipped_because = '-NoPrune was passed'
        Write-Log 'offsite retention SKIPPED: -NoPrune'
        return
    }
    # A GUARD, not a live branch: the call site above is only reached after the
    # primary verified, and that structural gate is the real control -- the same
    # one Invoke-Prune sits behind. This exists so a future caller that moves the
    # call cannot silently acquire the power to delete the last surviving copy.
    # A guard nothing executes is decoration, so deploy/test-h2-backup.ps1 calls
    # this function directly with ok=$false and asserts the archives survive;
    # deleting these four lines turns that named case red.
    if (-not $state.ok) {
        $state.offsite.prune.skipped_because = 'the primary backup did not verify'
        Write-Log 'offsite retention SKIPPED -- the primary backup did not verify'
        return
    }
    if (-not $state.offsite.ok) {
        $state.offsite.prune.skipped_because = 'this run placed no verified copy here'
        Write-Log 'offsite retention SKIPPED -- this run placed no verified copy here'
        return
    }

    $protected = @($all | Select-Object -First $keep | ForEach-Object { $_.FullName })
    $cutoff = (Get-Date).AddDays(-$days)
    $doomed = @($all | Where-Object {
        $_.FullName -ne $JustCopied -and $protected -notcontains $_.FullName -and $_.LastWriteTime -lt $cutoff
    })

    $deleted = 0
    foreach ($f in $doomed) {
        try {
            Remove-Item -LiteralPath $f.FullName -Force -ErrorAction Stop
            $deleted++
            Write-Log "offsite pruned $($f.Name) (last written $($f.LastWriteTime.ToString('s')))"
        } catch {
            Write-Log "could not prune offsite $($f.Name): $($_.Exception.Message)"
        }
    }
    $state.offsite.prune.ran = $true
    $state.offsite.prune.deleted = $deleted
    $state.offsite.prune.skipped_because = $null
    $state.offsite.prune.retained = $all.Count - $deleted
    Write-Log ("offsite retention: kept {0}, pruned {1} (older than {2}d, newest {3} always kept)" -f `
               $state.offsite.prune.retained, $deleted, $days, $keep)
}

# The whole second-copy step. REACHED ONLY WITH A VERIFIED ARCHIVE ON DISK --
# copying an unproven archive propagates a bad one, which is the same gate the
# primary's retention already sits behind.
#
# The outer try/catch is not decoration. $ErrorActionPreference is 'Stop' at
# script scope, so any stray non-terminating error in here would otherwise
# become a terminating one and take the whole run down AFTER a perfectly good
# backup. Nothing about a second location may ever fail the first.
function Invoke-Offsite([string]$FinalZip) {
    try {
        $t = Resolve-OffsiteTarget
        $state.offsite.dir          = if ($t.Dir) { $t.Dir } else { $OffsiteDir }
        $state.offsite.volume_label = $t.Label
        if (-not $t.Dir) {
            $state.offsite.reason = $t.Reason
            Write-Log "offsite copy SKIPPED: $($t.Reason)"
            return
        }

        # Free space, best effort. A destination that cannot hold the file is a
        # reported condition, not an exception in the middle of a write -- and
        # when the check itself is unavailable (a UNC path), the copy below
        # still fails safely and reports.
        try {
            $root = [System.IO.Path]::GetPathRoot($t.Dir)
            if ($root -match '^[A-Za-z]:\\$') {
                $ld = Get-CimInstance Win32_LogicalDisk -Filter ("DeviceID='{0}'" -f $root.TrimEnd('\')) -ErrorAction Stop
                if ($ld) {
                    $state.offsite.free_bytes = [int64]$ld.FreeSpace
                    $need = (Get-Item -LiteralPath $FinalZip).Length + 1MB
                    if ([int64]$ld.FreeSpace -lt $need) {
                        $state.offsite.reason = ("insufficient free space on {0}: {1} bytes free, {2} needed" -f `
                                                 $root, $ld.FreeSpace, $need)
                        Write-Log "offsite copy SKIPPED: $($state.offsite.reason)"
                        Measure-OffsiteContents $t.Dir
                        Invoke-OffsitePrune $t.Dir $null
                        return
                    }
                }
            }
        } catch { Write-Log "offsite: could not read free space ($($_.Exception.Message)); continuing" }

        $state.offsite.attempted = $true
        $r = Copy-Verified -Source $FinalZip -DestDir $t.Dir
        if (-not $r.Ok) {
            $state.offsite.reason = $r.Reason
            Write-Log "OFFSITE COPY FAILED (the primary backup is unaffected): $($r.Reason)"
            Measure-OffsiteContents $t.Dir
            # Called even though the copy failed, so the refusal is a branch that
            # actually EXECUTES and gets recorded, rather than a guard nothing
            # ever reaches. It reports what is at the destination and prunes
            # nothing -- deleting old copies on a run that could not place a new
            # one is deleting the only surviving copy.
            Invoke-OffsitePrune $t.Dir $null
            return
        }

        $state.offsite.ok     = $true
        $state.offsite.reason = $null
        $state.offsite.path   = $r.Path
        $state.offsite.bytes  = $r.Bytes
        $state.offsite.sha256 = $r.Sha256
        Write-Log ("OFFSITE COPY VERIFIED at {0} ({1} bytes, sha256 {2})" -f $r.Path, $r.Bytes, $r.Sha256)

        # The expected-counts sidecar rides along so a restore from the second
        # copy can be verified the same way as one from the first. Best effort
        # by design: the ZIP is the data, and a missing sidecar must not demote
        # a byte-verified archive.
        if (Test-Path -LiteralPath "$FinalZip.expected") {
            try {
                Copy-Item -LiteralPath "$FinalZip.expected" -Destination "$($r.Path).expected" -Force -ErrorAction Stop
            } catch { Write-Log "offsite: sidecar copy failed (archive is still verified): $($_.Exception.Message)" }
        }

        Measure-OffsiteContents $t.Dir
        Invoke-OffsitePrune $t.Dir $r.Path
    } catch {
        # Belt and braces over the per-step catches above. A surprise here is a
        # report, never a failed backup.
        $state.offsite.reason = "the offsite step threw: $($_.Exception.Message)"
        Write-Log "OFFSITE STEP THREW (the primary backup is unaffected): $($_.Exception.Message)"
    }
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
        $state.offsite.reason = 'another run holds the lock'
        exit 2
    }

    Write-Log '--- h2-backup starting ---'
    # PRINT WHAT YOU ACTUALLY GOT. PowerShell 5.1 coerces the INTEGER 1 to $true
    # against a [switch], and drops an empty string bound to a [string] passed
    # through -File -- both of which turn "-OffsiteMinimumKeep 1" or an empty
    # label into something the caller never wrote. A value beside its .NET type
    # is the only way that becomes visible in the record of the run.
    Write-Log ("offsite params: Dir='{0}' [{1}] Label='{2}' [{3}] RetainDays={4} MinimumKeep={5} NoOffsite={6} NoOffsiteVolumeCheck={7} NoPrune={8}" -f `
               $OffsiteDir, $OffsiteDir.GetType().Name, $OffsiteVolumeLabel, $OffsiteVolumeLabel.GetType().Name,
               $OffsiteRetainDays, $OffsiteMinimumKeep,
               [bool]$NoOffsite, [bool]$NoOffsiteVolumeCheck, [bool]$NoPrune)
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
        $state.offsite.reason = 'the backup failed, so there was nothing proven to copy'
        Write-Log "BACKUP FAILED: $($state.error)"
        Write-Log 'retention SKIPPED -- existing archives left untouched'
        Write-Log 'offsite copy SKIPPED -- an unverified archive must never be propagated'
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
        $state.offsite.reason = 'the read-back did not match live, so there was nothing proven to copy'
        Write-Log "VERIFY FAILED: $($state.error)"
        Write-Log 'retention SKIPPED -- existing archives left untouched'
        Write-Log 'offsite copy SKIPPED -- an unverified archive must never be propagated'
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

    # The second copy goes FIRST, before anything anywhere is deleted, so the
    # newest archive exists in two places before retention removes an old one
    # from either. It cannot fail this run: every branch inside reports.
    Invoke-Offsite $finalZip

    # And the primary's retention is NOT gated on the offsite copy. An unplugged
    # drive must not change how the primary directory behaves -- its own two
    # floors (the newest $MinimumKeep, and this run's archive) already bound it,
    # and coupling them would let a second-location problem quietly alter the
    # first location.
    Invoke-Prune $finalZip

    # EXIT 0 EVEN WITH NO SECOND COPY. The primary backup verified; that is what
    # this exit code means and Task Scheduler is the wrong channel for anything
    # else. Non-zero here would train the operator that a red task means an
    # unplugged drive, and then a red task would mean nothing. The offsite result
    # travels in the status file, where BackupFreshnessReporter reads it.
    exit 0
}
catch {
    $state.outcome = 'error'
    $state.error = $_.Exception.Message
    $state.prune.skipped_because = 'the run threw before retention'
    if (-not $state.offsite.attempted) { $state.offsite.reason = 'the run threw before the offsite copy' }
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
