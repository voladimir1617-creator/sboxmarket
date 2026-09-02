# Tests for deploy/h2-backup.ps1 and deploy/H2Backup.java.
#
# WHY THIS FILE EXISTS. On 2026-09-01 deploy/backup-db.sh was run with Docker
# stopped. `docker exec ... | gzip > file` failed at the FIRST stage, but `$?`
# reports the LAST command in a pipeline -- gzip, which happily succeeded writing
# zero bytes. The script printed "Backup OK", kept a 20-byte file, and then ran its
# 14-day retention prune, destroying four April backups. That script is now fixed.
# This suite exists so the H2 job it was replaced by cannot repeat the shape of that
# failure: a missing signal rendered as a healthy one, and then acted on destructively.
#
# EVERY TEST BELOW IS A MUTATION THAT MUST GO RED. A green suite that stays green when
# the fix is removed is decoration. The three that matter most:
#
#   * an archive taken from a DIFFERENT database must be REFUSED (proves the read-back
#     actually reads, rather than reporting on the exit code of the thing that wrote it)
#   * a failed run must leave every pre-existing archive ON DISK (the incident itself)
#   * a failed run must still write the status file (a stopped scheduler is only
#     detectable from outside; a script that dies silently reports nothing)
#
# Fixtures use a REAL 27-character password, so no empty-string argument is passed to
# any native executable anywhere in this file. The production path passes the password
# through the environment for the same reason -- PowerShell 5.1 silently drops an
# empty-string argument, which is how a SQL string slid into a `-password` slot.

[CmdletBinding()]
param([string] $H2Jar)

$ErrorActionPreference = 'Stop'
$Here    = $PSScriptRoot
$Script  = Join-Path $Here 'h2-backup.ps1'
$JavaSrc = Join-Path $Here 'H2Backup.java'
$FixturePw = 'fixture-password-0123456789'

$script:PASS = 0
$script:FAIL = 0
function ok  ([string]$m) { $script:PASS++; Write-Host "  ok   - $m" }
function bad ([string]$m) { $script:FAIL++; Write-Host "  FAIL - $m" -ForegroundColor Red }

if (-not $H2Jar) {
    $root = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.h2database\h2'
    $H2Jar = (Get-ChildItem -Path $root -Filter 'h2-*.jar' -Recurse -File |
              Where-Object { $_.Name -notmatch 'sources|javadoc' } |
              Sort-Object Name -Descending | Select-Object -First 1).FullName
}
if (-not $H2Jar -or -not (Test-Path $H2Jar)) { throw 'no H2 jar found; pass -H2Jar' }

# ------------------------------------------------------------------ fixtures

function New-Sandbox {
    $d = Join-Path ([System.IO.Path]::GetTempPath()) ("sbox-h2-test-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $d -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $d 'bd') -Force | Out-Null
    return $d
}

# A scratch H2 database carrying the same table names as production, so a decoy
# substituted for a real archive fails on ROW COUNTS rather than on missing tables --
# which is the check being tested.
function New-ScratchDb {
    param([string]$Base, [int]$Users = 7, [int]$Wallets = 9, [int]$Tx = 44, [int]$Audit = 37)
    $sql = "$Base.seed.sql"
    $lines = @(
        'CREATE TABLE STEAM_USERS(ID INT PRIMARY KEY, NAME VARCHAR(64), PAD VARCHAR(400));'
        'CREATE TABLE WALLETS(ID INT PRIMARY KEY, BALANCE_CENTS BIGINT, PAD VARCHAR(400));'
        'CREATE TABLE TRANSACTIONS(ID INT PRIMARY KEY, AMOUNT_CENTS BIGINT, PAD VARCHAR(400));'
        'CREATE TABLE AUDIT_LOG(ID INT PRIMARY KEY, ACTION VARCHAR(64), PAD VARCHAR(400));'
    )
    # The PAD column pushes the store comfortably over the tool's size floors, so a
    # legitimate small fixture is never rejected for being small.
    if ($Users   -gt 0) { $lines += "INSERT INTO STEAM_USERS SELECT X, 'u'||X, RPAD('x',400,'x') FROM SYSTEM_RANGE(1,$Users);" }
    if ($Wallets -gt 0) { $lines += "INSERT INTO WALLETS SELECT X, X*100, RPAD('x',400,'x') FROM SYSTEM_RANGE(1,$Wallets);" }
    if ($Tx      -gt 0) { $lines += "INSERT INTO TRANSACTIONS SELECT X, X*7, RPAD('x',400,'x') FROM SYSTEM_RANGE(1,$Tx);" }
    if ($Audit   -gt 0) { $lines += "INSERT INTO AUDIT_LOG SELECT X, 'a'||X, RPAD('x',400,'x') FROM SYSTEM_RANGE(1,$Audit);" }
    Set-Content -Path $sql -Value $lines -Encoding ascii
    $out = & java -cp $H2Jar org.h2.tools.RunScript -url "jdbc:h2:file:$($Base -replace '\\','/');MODE=PostgreSQL" `
                  -user sa -password $FixturePw -script $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "fixture creation failed: $out" }
}

# Run the orchestrator against a sandbox. Returns @{ Rc; Status }.
function Invoke-Job {
    param([string]$Sandbox, [string]$DbBase, [hashtable]$Extra = @{})
    $statusPath = Join-Path $Sandbox 'status.json'
    $a = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Script,
        '-BackupDir', (Join-Path $Sandbox 'bd'),
        '-DbPath', $DbBase,
        '-StatusPath', $statusPath,
        '-LogPath', (Join-Path $Sandbox 'job.log'),
        '-Password', $FixturePw,
        '-H2Jar', $H2Jar
    )
    foreach ($k in $Extra.Keys) {
        $a += "-$k"
        # `$v -ne $true` is NOT the test for "is this a switch": PowerShell coerces the
        # comparand to the left operand's type, so the INTEGER 1 equals $true and its
        # value gets silently dropped, turning `-MinimumKeep 1` into a bare switch. That
        # is the same shape as the empty-string argument this whole job is careful about,
        # and it was caught here only because a test asserted on the result.
        $v = $Extra[$k]
        if (-not (($v -is [bool] -or $v -is [switch]) -and $v)) { $a += "$v" }
    }
    & powershell.exe @a 2>&1 | Out-Null
    $rc = $LASTEXITCODE
    $st = $null
    if (Test-Path $statusPath) { $st = Get-Content $statusPath -Raw | ConvertFrom-Json }
    return @{ Rc = $rc; Status = $st }
}

# Run one phase of the Java tool directly, so a test can tamper with the artefact
# BETWEEN the two phases. This is why backup and verify are separate commands.
function Invoke-Tool {
    param([string]$Mode, [hashtable]$Env)
    foreach ($k in $Env.Keys) { Set-Item -Path "env:$k" -Value $Env[$k] }
    $out = & java -cp $H2Jar $JavaSrc $Mode 2>&1
    return @{ Rc = $LASTEXITCODE; Out = ($out | ForEach-Object { "$_" }) -join "`n" }
}

function Backup-Zips([string]$Sandbox) {
    return @(Get-ChildItem -Path (Join-Path $Sandbox 'bd') -Filter '*.zip' -File -ErrorAction SilentlyContinue)
}

$sandboxes = @()
function Track([string]$d) { $script:sandboxes += $d; return $d }

# =================================================================== positive control
Write-Host "`npositive control -- a real backup of a real database:"
try {
    $sb = Track (New-Sandbox)
    $db = Join-Path $sb 'src'
    New-ScratchDb -Base $db
    $r = Invoke-Job -Sandbox $sb -DbBase $db

    if ($r.Rc -eq 0) { ok 'exits 0 on a good backup' } else { bad "exited $($r.Rc) on a good backup" }
    if ($r.Status -and $r.Status.outcome -eq 'ok') { ok 'status records outcome=ok' } else { bad "status outcome was '$($r.Status.outcome)'" }
    $z = Backup-Zips $sb
    if ($z.Count -eq 1) { ok 'wrote exactly one archive' } else { bad "wrote $($z.Count) archives" }
    if ($r.Status.counts.steam_users -eq 7 -and $r.Status.counts.wallets -eq 9 -and
        $r.Status.counts.transactions -eq 44 -and $r.Status.counts.audit_log -eq 37) {
        ok 'the read-back reported the real row counts (7/9/44/37)'
    } else { bad "read-back counts were $($r.Status.counts | ConvertTo-Json -Compress)" }
    if ([int]$r.Status.tables_checked -ge 4) { ok "compared $($r.Status.tables_checked) tables, not just one" }
    else { bad "compared only $($r.Status.tables_checked) tables" }
    if (-not (Get-ChildItem (Join-Path $sb 'bd') -Filter '*.part' -File)) { ok 'left no .part artefact behind' }
    else { bad 'left a .part artefact behind' }
} catch { bad "positive control threw: $($_.Exception.Message)" }

# ============================================== MUTATION 1: an archive of another database
Write-Host "`nmutation -- the archive is a backup of a DIFFERENT database:"
try {
    $sb = Track (New-Sandbox)
    $good  = Join-Path $sb 'src'
    $decoy = Join-Path $sb 'decoy'
    New-ScratchDb -Base $good
    New-ScratchDb -Base $decoy -Users 2 -Wallets 1 -Tx 3 -Audit 4    # same tables, wrong counts

    $zip = Join-Path $sb 'bd\take.zip'
    $r1 = Invoke-Tool 'backup' @{
        H2_URL = "jdbc:h2:file:$($good -replace '\\','/');MODE=PostgreSQL;IFEXISTS=TRUE"
        H2_PASSWORD = $FixturePw; H2_BACKUP_ZIP = $zip; H2_LIVE_MVDB = "$good.mv.db"
    }
    if ($r1.Rc -eq 0) { ok 'the honest backup succeeded first (control)' } else { bad "control backup failed: $($r1.Out)" }

    # Swap the archive for one taken from the decoy, leaving the real sidecar in place.
    $decoyZip = Join-Path $sb 'decoy.zip'
    $r2 = Invoke-Tool 'backup' @{
        H2_URL = "jdbc:h2:file:$($decoy -replace '\\','/');MODE=PostgreSQL;IFEXISTS=TRUE"
        H2_PASSWORD = $FixturePw; H2_BACKUP_ZIP = $decoyZip; H2_LIVE_MVDB = "$decoy.mv.db"
    }
    Copy-Item -LiteralPath $decoyZip -Destination $zip -Force

    $v = Invoke-Tool 'verify' @{
        H2_BACKUP_ZIP = $zip; H2_PASSWORD = $FixturePw
        H2_VERIFY_DIR = (Join-Path $sb 'vdir')
    }
    if ($v.Rc -ne 0) { ok 'REFUSED an archive of the wrong database' }
    else { bad 'ACCEPTED an archive of the wrong database -- the read-back is not reading' }
    if ($v.Out -match 'MISMATCH=') { ok 'named the mismatching tables' } else { bad 'reported no mismatch detail' }
    if ($v.Out -notmatch 'VERIFY_OK=true') { ok 'never printed VERIFY_OK=true' } else { bad 'printed VERIFY_OK=true on a wrong archive' }
} catch { bad "mutation 1 threw: $($_.Exception.Message)" }

# ==================================================== MUTATION 2: a truncated archive
Write-Host "`nmutation -- the archive is truncated:"
try {
    $sb = Track (New-Sandbox)
    $db = Join-Path $sb 'src'
    New-ScratchDb -Base $db
    $zip = Join-Path $sb 'bd\take.zip'
    Invoke-Tool 'backup' @{
        H2_URL = "jdbc:h2:file:$($db -replace '\\','/');MODE=PostgreSQL;IFEXISTS=TRUE"
        H2_PASSWORD = $FixturePw; H2_BACKUP_ZIP = $zip; H2_LIVE_MVDB = "$db.mv.db"
    } | Out-Null
    [System.IO.File]::WriteAllBytes($zip, (New-Object byte[] 40))     # 40 zero bytes
    $v = Invoke-Tool 'verify' @{ H2_BACKUP_ZIP = $zip; H2_PASSWORD = $FixturePw; H2_VERIFY_DIR = (Join-Path $sb 'vdir') }
    if ($v.Rc -ne 0) { ok 'REFUSED a truncated archive' } else { bad 'ACCEPTED a truncated archive' }
} catch { bad "mutation 2 threw: $($_.Exception.Message)" }

# ================================================== MUTATION 3: no expected-counts sidecar
Write-Host "`nmutation -- the expected-counts sidecar is missing:"
try {
    $sb = Track (New-Sandbox)
    $db = Join-Path $sb 'src'
    New-ScratchDb -Base $db
    $zip = Join-Path $sb 'bd\take.zip'
    Invoke-Tool 'backup' @{
        H2_URL = "jdbc:h2:file:$($db -replace '\\','/');MODE=PostgreSQL;IFEXISTS=TRUE"
        H2_PASSWORD = $FixturePw; H2_BACKUP_ZIP = $zip; H2_LIVE_MVDB = "$db.mv.db"
    } | Out-Null
    Remove-Item -LiteralPath "$zip.expected" -Force
    $v = Invoke-Tool 'verify' @{ H2_BACKUP_ZIP = $zip; H2_PASSWORD = $FixturePw; H2_VERIFY_DIR = (Join-Path $sb 'vdir') }
    if ($v.Rc -ne 0) { ok 'REFUSED to verify against nothing' } else { bad 'verified an archive with no expected counts' }
} catch { bad "mutation 3 threw: $($_.Exception.Message)" }

# =================================================== THE INCIDENT: a failure must not prune
Write-Host "`nthe incident -- when the backup fails, existing archives must SURVIVE:"
try {
    $sb = Track (New-Sandbox)
    # Four archives, all far older than the retention window: exactly the population
    # the 2026-09-01 prune destroyed.
    $old = @()
    for ($i = 1; $i -le 4; $i++) {
        $p = Join-Path $sb "bd\h2-sboxmarket-2026040${i}T000000Z.zip"
        Set-Content -Path $p -Value "pretend this is a real April backup" -Encoding ascii
        (Get-Item $p).LastWriteTime = (Get-Date).AddDays(-120)
        $old += $p
    }
    # No database at this path at all -- the H2 equivalent of Docker being down.
    #
    # MinimumKeep is deliberately lowered to 1 here. At the production default of 7 the
    # floor ALONE protects a population of four, so this assertion would pass even with
    # the prune-on-failure guard removed -- measured: with that guard deleted the suite
    # went red on the status fields and stayed GREEN on "the archives survived". An
    # assertion that cannot fail is not protecting anything. With the floor out of the
    # way, age alone decides, and only the skip-on-failure rule stands between a failed
    # run and four destroyed archives.
    $r = Invoke-Job -Sandbox $sb -DbBase (Join-Path $sb 'does-not-exist') -Extra @{ MinimumKeep = 1 }

    if ($r.Rc -ne 0) { ok "exits non-zero so Task Scheduler records a failure (rc=$($r.Rc))" }
    else { bad 'exited 0 on a failed backup' }
    $survived = @($old | Where-Object { Test-Path $_ }).Count
    if ($survived -eq 4) { ok 'ALL FOUR pre-existing archives survived' }
    else { bad "THE INCIDENT: only $survived of 4 archives survived a failed run" }
    if ($r.Status -and $r.Status.ok -eq $false) { ok 'status records ok=false' } else { bad 'status did not record the failure' }
    if ($r.Status -and $r.Status.prune.ran -eq $false) { ok 'status records that retention did not run' }
    else { bad 'status claims retention ran after a failure' }
    if ($r.Status -and $r.Status.prune.skipped_because) { ok "says WHY it skipped: $($r.Status.prune.skipped_because)" }
    else { bad 'gave no reason for skipping retention' }
    if (-not (Get-ChildItem (Join-Path $sb 'bd') -Filter '*.part' -File)) { ok 'kept no empty artefact' }
    else { bad 'kept an unverified .part artefact' }
} catch { bad "incident test threw: $($_.Exception.Message)" }

# ============================================ a failed run still reports (stopped scheduler)
Write-Host "`na failed run must still write the status file:"
try {
    $sb = Track (New-Sandbox)
    $r = Invoke-Job -Sandbox $sb -DbBase (Join-Path $sb 'nothing-here')
    if (Test-Path (Join-Path $sb 'status.json')) { ok 'status.json exists after a failure' }
    else { bad 'no status file after a failure -- a dead job would be indistinguishable from a stopped scheduler' }
    if ($r.Status.last_run_at -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$') { ok 'carries a UTC last_run_at a monitor can age out' }
    else { bad "last_run_at was '$($r.Status.last_run_at)'" }
    if ($r.Status.error) { ok "records the reason: $($r.Status.error.Substring(0, [Math]::Min(70, $r.Status.error.Length)))" }
    else { bad 'recorded no error text' }
} catch { bad "status-on-failure test threw: $($_.Exception.Message)" }

# ================================================================ retention behaviour
Write-Host "`nretention -- only after success, and never below the floor:"
try {
    $sb = Track (New-Sandbox)
    $db = Join-Path $sb 'src'
    New-ScratchDb -Base $db
    # 3 ancient archives, MinimumKeep 7 -> the floor alone forbids every deletion.
    for ($i = 1; $i -le 3; $i++) {
        $p = Join-Path $sb "bd\h2-sboxmarket-2026010${i}T000000Z.zip"
        Set-Content -Path $p -Value 'ancient' -Encoding ascii
        (Get-Item $p).LastWriteTime = (Get-Date).AddDays(-300)
    }
    $r = Invoke-Job -Sandbox $sb -DbBase $db -Extra @{ MinimumKeep = 7; RetainDays = 14 }
    if ($r.Rc -eq 0) { ok 'the run succeeded (control)' } else { bad "run failed rc=$($r.Rc)" }
    if ((Backup-Zips $sb).Count -eq 4) { ok 'the MinimumKeep floor protected all 3 ancient archives' }
    else { bad "expected 4 archives, found $((Backup-Zips $sb).Count) -- the floor did not hold" }

    # Same population, floor lowered to 1: now age is allowed to bite.
    $sb2 = Track (New-Sandbox)
    $db2 = Join-Path $sb2 'src'
    New-ScratchDb -Base $db2
    for ($i = 1; $i -le 3; $i++) {
        $p = Join-Path $sb2 "bd\h2-sboxmarket-2026010${i}T000000Z.zip"
        Set-Content -Path $p -Value 'ancient' -Encoding ascii
        (Get-Item $p).LastWriteTime = (Get-Date).AddDays(-300)
    }
    $r2 = Invoke-Job -Sandbox $sb2 -DbBase $db2 -Extra @{ MinimumKeep = 1; RetainDays = 14 }
    $left = Backup-Zips $sb2
    if ($r2.Rc -eq 0 -and $left.Count -eq 1 -and $left[0].Name -notmatch '202601') {
        ok 'with the floor lowered, aged archives are pruned and the new one is kept'
    } else { bad "expected only the fresh archive, found: $(($left | ForEach-Object { $_.Name }) -join ', ')" }
} catch { bad "retention test threw: $($_.Exception.Message)" }

# ================================================================== a wrong path cannot lie
Write-Host "`na wrong database path must fail, not invent an empty database:"
try {
    $sb = Track (New-Sandbox)
    $r = Invoke-Job -Sandbox $sb -DbBase (Join-Path $sb 'typo-in-the-path')
    if ($r.Rc -ne 0) { ok 'refused a path with no database at it' } else { bad 'backed up a database that does not exist' }
    if (-not (Test-Path (Join-Path $sb 'typo-in-the-path.mv.db'))) { ok 'created no new empty database as a side effect' }
    else { bad 'CREATED an empty database at the typo path and would have backed it up' }
} catch { bad "wrong-path test threw: $($_.Exception.Message)" }

# ---------------------------------------------------------------------------- cleanup
foreach ($d in $sandboxes) {
    try { Remove-Item -LiteralPath $d -Recurse -Force -ErrorAction SilentlyContinue } catch { }
}

Write-Host ""
Write-Host "passed $script:PASS, failed $script:FAIL"
if ($script:FAIL -gt 0) { exit 1 }
exit 0
