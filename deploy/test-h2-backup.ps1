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
    # EVERY test is hermetic about the second copy too. The script's production
    # default is D:\skinbox-backups; without this, running the suite would fill
    # the operator's real offsite directory with fixture archives -- the same
    # shape as the incident where the test suite wrote into production data.
    # -NoOffsiteVolumeCheck because a sandbox lives on C:, whose label is empty,
    # and NO caller of this script is ever allowed to pass '' (PowerShell 5.1
    # drops an empty-string argument and the next one slides into its slot).
    if (-not $Extra.ContainsKey('OffsiteDir') -and -not $Extra.ContainsKey('NoOffsite')) {
        $a += @('-OffsiteDir', (Join-Path $Sandbox 'off'))
    }
    if (-not $Extra.ContainsKey('OffsiteVolumeLabel')) { $a += '-NoOffsiteVolumeCheck' }
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

function Offsite-Zips([string]$Sandbox) {
    return @(Get-ChildItem -Path (Join-Path $Sandbox 'off') -Filter '*.zip' -File -ErrorAction SilentlyContinue)
}

# Lift ONE function out of the shipped script and make it callable, by parsing
# the production file and re-declaring the function's own source text. There is
# no test hook in h2-backup.ps1 and there must not be: the same reason
# H2Backup.java splits `backup` and `verify` into two invocable commands rather
# than trusting a single process to grade itself. This reads the bytes that ship.
function Import-ScriptFunction([string]$Name) {
    $errs = $null; $toks = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Script, [ref]$toks, [ref]$errs)
    if ($errs -and $errs.Count) { throw "h2-backup.ps1 does not parse: $($errs[0].Message)" }
    $fn = $ast.FindAll({ param($n)
        $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $Name }, $true)
    if (-not $fn -or $fn.Count -ne 1) { throw "expected exactly one function '$Name' in h2-backup.ps1, found $($fn.Count)" }
    return $fn[0].Extent.Text
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

# ==================================================================================
# THE SECOND COPY
#
# The archives and the database were both on C: until now. One drive failure lost
# both, and four verified archives on a dying disk is one copy. The job now writes
# each VERIFIED archive to a second physical device as well.
#
# Everything below asserts one of two things: that the copy is PROVEN by reading it
# back at the destination, or that a destination which is missing, wrong, locked or
# unwritable is REPORTED and never allowed to fail the backup. A backup that fails
# because a secondary location is unavailable is worse than no secondary location.
# ==================================================================================

Write-Host "`noffsite positive control -- a verified archive reaches a second directory:"
try {
    $sb = Track (New-Sandbox)
    $db = Join-Path $sb 'src'
    New-ScratchDb -Base $db
    $r = Invoke-Job -Sandbox $sb -DbBase $db

    if ($r.Rc -eq 0) { ok 'exits 0' } else { bad "exited $($r.Rc)" }
    if ($r.Status.offsite -and $r.Status.offsite.ok -eq $true) { ok 'status records offsite.ok = true' }
    else { bad "offsite.ok was '$($r.Status.offsite.ok)' (reason: $($r.Status.offsite.reason))" }
    $off = Offsite-Zips $sb
    if ($off.Count -eq 1) { ok 'exactly one archive arrived at the destination' }
    else { bad "found $($off.Count) archives at the destination" }

    # The two files must be the same file. Compared HERE, independently of the
    # script's own arithmetic -- a job that grades its own copy is the thing this
    # whole area exists to stop.
    $prim = (Backup-Zips $sb)[0]
    if ($off.Count -eq 1) {
        $ha = (Get-FileHash -LiteralPath $prim.FullName -Algorithm SHA256).Hash
        $hb = (Get-FileHash -LiteralPath $off[0].FullName -Algorithm SHA256).Hash
        if ($ha -eq $hb) { ok 'the copy is byte-identical to the primary archive' }
        else { bad 'the copy DIFFERS from the primary archive' }
        if ($off[0].Length -eq $prim.Length) { ok "same size on both disks ($($prim.Length) bytes)" }
        else { bad "sizes differ: primary $($prim.Length), copy $($off[0].Length)" }
        # The hash in the status file must be the hash of the file that is there.
        if ($r.Status.offsite.sha256 -eq $hb) { ok 'the recorded sha256 is the destination file''s actual hash' }
        else { bad "recorded sha256 $($r.Status.offsite.sha256) is not the file's $hb" }
        if ([int64]$r.Status.offsite.bytes -eq $off[0].Length) { ok 'the recorded byte count matches the file' }
        else { bad "recorded $($r.Status.offsite.bytes) bytes, file is $($off[0].Length)" }
        if ($r.Status.offsite.path -eq $off[0].FullName) { ok 'the recorded path is where the file actually is' }
        else { bad "recorded path $($r.Status.offsite.path) is not $($off[0].FullName)" }
    }
    if (-not (Get-ChildItem (Join-Path $sb 'off') -Filter '*.part' -File -ErrorAction SilentlyContinue)) {
        ok 'left no .part artefact at the destination'
    } else { bad 'left an unproven .part at the destination' }
    if (Test-Path (Join-Path $sb "off\$($off[0].Name).expected")) { ok 'the expected-counts sidecar rode along' }
    else { bad 'the sidecar did not reach the destination' }
} catch { bad "offsite positive control threw: $($_.Exception.Message)" }

# ====================================== an UNVERIFIED archive must never be propagated
# ================== the copy happens only after verification -- STRUCTURALLY
Write-Host "`noffsite -- the copy has exactly ONE call site, and it is after verification:"
try {
    # "Copy only what verified" is not a runtime condition here, it is the SHAPE of
    # the script: Invoke-Offsite is called once, after $state.ok is set true. A
    # structural property cannot be tested by flipping a boolean, so it is asserted
    # on the parse tree. Add a second call site, or move this one above the
    # read-back, and this goes red -- which is the whole risk: propagating an
    # archive nothing has verified.
    $errs = $null; $toks = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Script, [ref]$toks, [ref]$errs)
    $calls = @($ast.FindAll({ param($n)
        $n -is [System.Management.Automation.Language.CommandAst] -and
        $n.GetCommandName() -eq 'Invoke-Offsite' }, $true))
    if ($calls.Count -eq 1) { ok 'exactly one call site' } else { bad "$($calls.Count) call sites for Invoke-Offsite" }

    $okAssign = @($ast.FindAll({ param($n)
        $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $n.Left.Extent.Text -eq '$state.ok' -and $n.Right.Extent.Text -eq '$true' }, $true))
    if ($okAssign.Count -eq 1) { ok 'exactly one place sets $state.ok = $true' }
    else { bad "$($okAssign.Count) places set `$state.ok = `$true" }
    if ($calls.Count -eq 1 -and $okAssign.Count -eq 1 -and
        $calls[0].Extent.StartOffset -gt $okAssign[0].Extent.EndOffset) {
        ok 'the copy is invoked AFTER the archive is marked verified'
    } else { bad 'the copy is invoked before the archive is marked verified' }
} catch { bad "call-site test threw: $($_.Exception.Message)" }

Write-Host "`noffsite -- a failed backup copies NOTHING to the second location:"
try {
    $sb = Track (New-Sandbox)
    $r = Invoke-Job -Sandbox $sb -DbBase (Join-Path $sb 'no-database-here')
    if ($r.Rc -ne 0) { ok 'the run failed (control)' } else { bad 'the run did not fail' }
    if ((Offsite-Zips $sb).Count -eq 0) { ok 'nothing was copied offsite' }
    else { bad 'PROPAGATED an archive that never verified' }
    if ($r.Status.offsite.ok -eq $false) { ok 'status records offsite.ok = false' } else { bad 'status claims an offsite copy' }
    if ($r.Status.offsite.attempted -eq $false) { ok 'status records that no copy was even attempted' }
    else { bad 'status claims a copy was attempted after a failed backup' }
    if ($r.Status.offsite.reason) { ok "says WHY: $($r.Status.offsite.reason)" }
    else { bad 'gave no reason for the absent copy' }
} catch { bad "unverified-propagation test threw: $($_.Exception.Message)" }

# ============================================ a MISSING VOLUME is reported, never fatal
Write-Host "`noffsite -- a destination volume that is not there is REPORTED, not fatal:"
try {
    # A drive letter with no volume behind it: the unplugged-drive case, and the
    # reassigned-letter case, and the never-set-up case, all at once.
    $free = 90..69 | ForEach-Object { [char]$_ } | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -First 1
    if (-not $free) { bad 'no unused drive letter on this machine to test with' }
    else {
        $sb = Track (New-Sandbox)
        $db = Join-Path $sb 'src'
        New-ScratchDb -Base $db
        $r = Invoke-Job -Sandbox $sb -DbBase $db -Extra @{ OffsiteDir = "$($free):\skinbox-backups" }

        if ($r.Rc -eq 0) { ok "exits 0 with $($free): absent -- the backup did not fail" }
        else { bad "THE BACKUP FAILED because a second location was missing (rc=$($r.Rc))" }
        if ($r.Status.ok -eq $true -and $r.Status.outcome -eq 'ok') { ok 'the primary backup still verified' }
        else { bad "primary outcome was '$($r.Status.outcome)'" }
        if ((Backup-Zips $sb).Count -eq 1) { ok 'the primary archive is on disk' } else { bad 'no primary archive' }
        if ($r.Status.offsite.ok -eq $false) { ok 'status records offsite.ok = false' } else { bad 'status claims a copy' }
        if ("$($r.Status.offsite.reason)" -match 'not present') { ok "names the volume: $($r.Status.offsite.reason)" }
        else { bad "reason did not name a missing volume: '$($r.Status.offsite.reason)'" }
        if (-not (Test-Path "$($free):\")) { ok "created nothing on the absent $($free):" }
        else { bad "CREATED a path on $($free): -- a letter is not an identity" }
    }
} catch { bad "missing-volume test threw: $($_.Exception.Message)" }

# ================================== a volume that is NOT the expected one is refused
Write-Host "`noffsite -- a drive letter whose volume label does not match is REFUSED:"
try {
    $sb = Track (New-Sandbox)
    $db = Join-Path $sb 'src'
    New-ScratchDb -Base $db
    # The sandbox is on C:, whose label is not this. This is the reassigned-letter
    # case: the path exists and is writable, and writing there would put the money
    # database on a device nobody chose.
    $r = Invoke-Job -Sandbox $sb -DbBase $db `
                    -Extra @{ OffsiteDir = (Join-Path $sb 'off'); OffsiteVolumeLabel = 'NotThisVolume-8f21' }

    if ($r.Rc -eq 0) { ok 'exits 0 -- a wrong volume is not a failed backup' } else { bad "exited $($r.Rc)" }
    if ($r.Status.ok -eq $true) { ok 'the primary backup still verified' } else { bad 'the primary backup failed' }
    if ($r.Status.offsite.ok -eq $false) { ok 'no copy was claimed' } else { bad 'claimed a copy onto an unidentified volume' }
    if ((Offsite-Zips $sb).Count -eq 0) { ok 'WROTE NOTHING to the unidentified volume' }
    else { bad 'wrote the money database to a volume whose label did not match' }
    if ("$($r.Status.offsite.reason)" -match 'labelled') { ok "names the mismatch: $($r.Status.offsite.reason)" }
    else { bad "reason did not name a label mismatch: '$($r.Status.offsite.reason)'" }
    if ($r.Status.offsite.volume_label -ne $null) { ok "recorded the label it actually found: '$($r.Status.offsite.volume_label)'" }
    else { bad 'did not record the label it found' }
} catch { bad "wrong-volume test threw: $($_.Exception.Message)" }

# ====================== the REAL production destination, identified by its real label
Write-Host "`noffsite -- the shipped destination volume is a DIFFERENT physical disk:"
try {
    # Measured, not assumed. The whole point is a second failure domain: a second
    # volume carved out of the same physical device is one copy wearing two names.
    $dbDrive  = (Split-Path -Qualifier (Resolve-Path (Join-Path $Here '..')).Path)
    $defDir   = (Select-String -Path $Script -Pattern "OffsiteDir\s+=\s+'([^']+)'" |
                 Select-Object -First 1).Matches[0].Groups[1].Value
    $offDrive = (Split-Path -Qualifier $defDir)
    $diskOf = {
        param($letter)
        $p = Get-Partition -DriveLetter $letter.TrimEnd(':') -ErrorAction SilentlyContinue
        if (-not $p) { return $null }
        $vd = Get-VirtualDisk -ErrorAction SilentlyContinue |
              Where-Object { ($_ | Get-Disk -ErrorAction SilentlyContinue).Number -eq $p.DiskNumber }
        if ($vd) { return @($vd | Get-PhysicalDisk | ForEach-Object { $_.SerialNumber }) }
        $d = Get-Disk -Number $p.DiskNumber -ErrorAction SilentlyContinue
        if ($d) { return @($d.SerialNumber) }
        return $null
    }
    $a = & $diskOf $dbDrive
    $b = & $diskOf $offDrive
    if (-not $a -or -not $b) {
        bad "could not resolve the physical devices behind $dbDrive and $offDrive -- NOT DETERMINED"
    } elseif (@($a | Where-Object { $b -contains $_ }).Count -eq 0) {
        ok "$dbDrive [$($a -join ',')] and $offDrive [$($b -join ',')] share NO physical device"
    } else {
        bad "$dbDrive and $offDrive share a physical device [$($a -join ',')] -- that is one copy, not two"
    }
} catch { bad "physical-separation check threw: $($_.Exception.Message)" }

# ============================= an unwritable destination is reported, never fatal
Write-Host "`noffsite -- a destination that cannot be created is REPORTED, not fatal:"
try {
    $sb = Track (New-Sandbox)
    $db = Join-Path $sb 'src'
    New-ScratchDb -Base $db
    # A FILE where the destination directory should be. New-Item cannot make a
    # directory here, and the failure must land in the status file, not in the run.
    $blocker = Join-Path $sb 'blocked'
    Set-Content -Path $blocker -Value 'this is a file, not a directory' -Encoding ascii
    $r = Invoke-Job -Sandbox $sb -DbBase $db -Extra @{ OffsiteDir = (Join-Path $blocker 'inside') }

    if ($r.Rc -eq 0) { ok 'exits 0 -- an unwritable destination is not a failed backup' } else { bad "exited $($r.Rc)" }
    if ($r.Status.ok -eq $true) { ok 'the primary backup still verified' } else { bad 'the primary backup failed' }
    if ((Backup-Zips $sb).Count -eq 1) { ok 'the primary archive is on disk' } else { bad 'no primary archive' }
    if ($r.Status.offsite.ok -eq $false -and $r.Status.offsite.reason) { ok "reported: $($r.Status.offsite.reason)" }
    else { bad "did not report the unwritable destination (ok=$($r.Status.offsite.ok))" }
} catch { bad "unwritable-destination test threw: $($_.Exception.Message)" }

# ================== a copy that cannot take its final name is refused, not claimed
Write-Host "`noffsite -- a copy that cannot be named is REFUSED and reported:"
try {
    $sb  = Track (New-Sandbox)
    $src = Join-Path $sb 'source.zip'
    [System.IO.File]::WriteAllBytes($src, (1..4096 | ForEach-Object { [byte]($_ % 251) }))
    $dst = Join-Path $sb 'dest'; New-Item -ItemType Directory -Path $dst -Force | Out-Null

    # Hold the destination name open with FileShare::None: Move-Item cannot land on
    # it. A real condition -- a restore reading the archive, a scanner, a sync
    # client -- and the honest answer is "no verified copy", never a claimed one.
    $hold = Join-Path $dst 'source.zip'
    [System.IO.File]::WriteAllBytes($hold, (New-Object byte[] 8))
    $lock = New-Object System.IO.FileStream($hold, [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    # Defined and called inside ONE scope block, so the lifted function and any
    # shadowed cmdlet beside it cannot leak into the rest of this suite.
    try {
        $res = & {
            param($s, $d)
            Invoke-Expression (Import-ScriptFunction 'Copy-Verified')
            Copy-Verified -Source $s -DestDir $d
        } $src $dst
    } finally { $lock.Dispose() }

    if ($res -and $res.Ok -eq $false) { ok 'refused to claim a copy it could not name' }
    else { bad "claimed Ok=$($res.Ok) despite a locked destination name" }
    if ("$($res.Reason)" -match 'could not name') { ok "says why: $($res.Reason)" }
    else { bad "reason did not name the rename failure: '$($res.Reason)'" }
    if (-not (Test-Path (Join-Path $dst 'source.zip.part'))) { ok 'left no .part behind' }
    else { bad 'left an unproven .part wearing a partial name' }
    if ((Get-Item $hold).Length -eq 8) { ok 'the file already at that name was not clobbered' }
    else { bad 'overwrote the locked file' }
} catch { bad "locked-destination test threw: $($_.Exception.Message)" }

# ========================= THE READ-BACK: a short or altered copy must be REFUSED
#
# The defect this closes: a Copy-Item that "succeeded" is indistinguishable from one
# that wrote a truncated file. The two cases below manufacture exactly that -- the
# destination's MEASUREMENTS come back wrong while the copy itself reports success --
# by shadowing the two cmdlets Copy-Verified uses to read the destination back.
#
# What is mocked is the measurement. What is ASSERTED is real and on the filesystem:
# the function refuses, says which check failed, DELETES the bad .part, and leaves no
# file wearing the archive's name. Remove either comparison from h2-backup.ps1 and
# the matching case below goes red -- verified by doing it.
Write-Host "`noffsite -- a copy whose SIZE differs at the destination is refused:"
try {
    $sb  = Track (New-Sandbox)
    $src = Join-Path $sb 'source.zip'
    [System.IO.File]::WriteAllBytes($src, (1..4096 | ForEach-Object { [byte]($_ % 251) }))
    $dst = Join-Path $sb 'dest'; New-Item -ItemType Directory -Path $dst -Force | Out-Null

    $res = & {
        param($s, $d)
        function Get-Item {
            [CmdletBinding()] param([string]$LiteralPath)
            $real = Microsoft.PowerShell.Management\Get-Item -LiteralPath $LiteralPath
            if ($LiteralPath -like '*.part') { return [pscustomobject]@{ Length = $real.Length - 17 } }
            return $real
        }
        Invoke-Expression (Import-ScriptFunction 'Copy-Verified')
        Copy-Verified -Source $s -DestDir $d
    } $src $dst

    if ($res.Ok -eq $false) { ok 'REFUSED a copy whose destination size did not match' }
    else { bad 'ACCEPTED a short copy -- the read-back is not reading' }
    if ("$($res.Reason)" -match 'SIZE MISMATCH') { ok "named the check that failed: $($res.Reason)" }
    else { bad "reason did not name a size mismatch: '$($res.Reason)'" }
    if (-not (Test-Path (Join-Path $dst 'source.zip'))) { ok 'no file wears the archive name at the destination' }
    else { bad 'a REJECTED copy is sitting there under the archive name' }
    if (-not (Test-Path (Join-Path $dst 'source.zip.part'))) { ok 'the bad copy was discarded' }
    else { bad 'the bad copy was left on the destination' }
} catch { bad "size-mismatch test threw: $($_.Exception.Message)" }

Write-Host "`noffsite -- a copy whose SHA-256 differs at the destination is refused:"
try {
    $sb  = Track (New-Sandbox)
    $src = Join-Path $sb 'source.zip'
    [System.IO.File]::WriteAllBytes($src, (1..4096 | ForEach-Object { [byte]($_ % 251) }))
    $dst = Join-Path $sb 'dest'; New-Item -ItemType Directory -Path $dst -Force | Out-Null

    # Same length, different content: the case a size check alone cannot see, and
    # the reason the requirement is size AND hash rather than either.
    $res = & {
        param($s, $d)
        function Get-FileHash {
            [CmdletBinding()] param([string]$LiteralPath, [string]$Algorithm)
            if ($LiteralPath -like '*.part') {
                return [pscustomobject]@{ Hash = ('B' * 64); Path = $LiteralPath; Algorithm = $Algorithm }
            }
            return Microsoft.PowerShell.Utility\Get-FileHash -LiteralPath $LiteralPath -Algorithm $Algorithm
        }
        Invoke-Expression (Import-ScriptFunction 'Copy-Verified')
        Copy-Verified -Source $s -DestDir $d
    } $src $dst

    if ($res.Ok -eq $false) { ok 'REFUSED a copy whose destination hash did not match' }
    else { bad 'ACCEPTED an altered copy of the same length' }
    if ("$($res.Reason)" -match 'SHA-256 MISMATCH') { ok "named the check that failed" }
    else { bad "reason did not name a hash mismatch: '$($res.Reason)'" }
    if (-not (Test-Path (Join-Path $dst 'source.zip'))) { ok 'no file wears the archive name at the destination' }
    else { bad 'a REJECTED copy is sitting there under the archive name' }
    if (-not (Test-Path (Join-Path $dst 'source.zip.part'))) { ok 'the bad copy was discarded' }
    else { bad 'the bad copy was left on the destination' }
} catch { bad "hash-mismatch test threw: $($_.Exception.Message)" }

# ============================= the second copy is NOT pruned when the first is broken
Write-Host "`noffsite retention -- never prune the surviving copy because the primary failed:"
try {
    $sb = Track (New-Sandbox)
    New-Item -ItemType Directory -Path (Join-Path $sb 'off') -Force | Out-Null
    # Four ancient offsite archives and NO database to back up. Floors lowered to 1
    # on BOTH sides so that only the health gate stands between a failed run and
    # four deleted copies -- with the production floor of 7 this assertion would
    # pass even with the gate removed, which is an assertion that protects nothing.
    $old = @()
    for ($i = 1; $i -le 4; $i++) {
        $p = Join-Path $sb "off\h2-sboxmarket-2026040${i}T000000Z.zip"
        Set-Content -Path $p -Value 'the only surviving copy of the money database' -Encoding ascii
        (Get-Item $p).LastWriteTime = (Get-Date).AddDays(-400)
        $old += $p
    }
    $r = Invoke-Job -Sandbox $sb -DbBase (Join-Path $sb 'does-not-exist') `
                    -Extra @{ MinimumKeep = 1; OffsiteMinimumKeep = 1; OffsiteRetainDays = 1 }

    $survived = @($old | Where-Object { Test-Path $_ }).Count
    if ($survived -eq 4) { ok 'ALL FOUR offsite archives survived a failed primary run' }
    else { bad "only $survived of 4 offsite archives survived -- the only copies were pruned" }
    if ($r.Status.offsite.prune.ran -eq $false) { ok 'status records that offsite retention did not run' }
    else { bad 'status claims offsite retention ran after a failed backup' }
    if ($r.Status.offsite.prune.skipped_because) { ok "says WHY: $($r.Status.offsite.prune.skipped_because)" }
    else { bad 'gave no reason for skipping offsite retention' }
} catch { bad "offsite-prune-on-failure test threw: $($_.Exception.Message)" }

# ================================================= the prune's own guards, EXECUTED
#
# WHY THIS IS HERE AND NOT ONLY END-TO-END. The three assertions above are satisfied
# by the job never reaching Invoke-OffsitePrune at all: `prune.ran` is false because
# it is initialised false, and the archives survive because nothing looked at them.
# Measured -- with the health gates deleted from Invoke-OffsitePrune, that case
# stayed GREEN. An assertion a mutation cannot turn red is decoration, and this
# project has paid for that lesson twice tonight.
#
# So the function is lifted out of the shipped script and CALLED, with a $state that
# says the primary failed. Deleting the gate turns these cases red, because the
# ancient archives really do get deleted.
function Invoke-LiftedOffsitePrune {
    param([string]$Dir, [bool]$PrimaryOk, [bool]$CopyOk,
          [int]$RetainDays = 14, [int]$MinimumKeep = 1,
          [int]$OffsiteRetainDays = 1, [int]$OffsiteMinimumKeep = 1)
    & {
        param($Dir, $PrimaryOk, $CopyOk, $RetainDays, $MinimumKeep, $OffsiteRetainDays, $OffsiteMinimumKeep, $Body)
        function Write-Log([string]$m) { }
        $Prefix  = 'h2-sboxmarket-'
        $NoPrune = $false
        $state = [ordered]@{
            ok = $PrimaryOk
            offsite = [ordered]@{
                ok = $CopyOk
                prune = [ordered]@{
                    ran = $false; skipped_because = 'did not reach offsite retention'
                    deleted = 0; retained = $null; retain_days = $null; minimum_keep = $null
                }
            }
        }
        Invoke-Expression $Body
        Invoke-OffsitePrune $Dir $null
        return $state.offsite.prune
    } $Dir $PrimaryOk $CopyOk $RetainDays $MinimumKeep $OffsiteRetainDays $OffsiteMinimumKeep `
      (Import-ScriptFunction 'Invoke-OffsitePrune')
}

# ============ THE LAST-RESORT CATCH, actually reached
#
# FOUND BY MUTATION, NOT BY READING. Replacing the outer catch in Invoke-Offsite with
# a bare `throw` left the whole suite GREEN: every test drove a failure that one of
# the INNER catches already handled, so the outer one -- the guarantee that a surprise
# in the second-copy step can never fail the backup -- was never executed once. That
# is the same shape as the untested exception fallback in BackupFreshnessReporter,
# where flipping STALE to OK left 59 tests green. A guard nothing reaches is a guard
# that is not there.
#
# The reachable route into it is real: Copy-Verified reads the source's length and
# hash BEFORE its own try block, so an archive that disappears between the rename and
# the copy -- antivirus quarantine, a hand-run cleanup, a sync client -- throws past
# every inner catch. That is what this drives.
function Invoke-LiftedOffsite {
    param([string]$Zip, [string]$Dir)
    & {
        param($Zip, $Dir, $Bodies)
        function Write-Log([string]$m) { }
        $Prefix = 'h2-sboxmarket-'
        $NoPrune = $false; $NoOffsite = $false; $NoOffsiteVolumeCheck = $true
        $OffsiteDir = $Dir; $OffsiteVolumeLabel = 'unused'
        $RetainDays = 14; $MinimumKeep = 7; $OffsiteRetainDays = 365; $OffsiteMinimumKeep = 30
        $state = [ordered]@{
            ok = $true
            offsite = [ordered]@{
                attempted = $false; ok = $false; reason = 'the run did not reach the offsite copy'
                dir = $null; path = $null; bytes = $null; sha256 = $null
                volume_label = $null; free_bytes = $null; newest_at = $null; count = $null
                prune = [ordered]@{ ran = $false; skipped_because = 'did not reach offsite retention'
                                    deleted = 0; retained = $null; retain_days = $null; minimum_keep = $null }
            }
        }
        foreach ($b in $Bodies) { Invoke-Expression $b }
        # If the outer catch is gone, this THROWS and the caller records it.
        Invoke-Offsite $Zip
        return @{ Threw = $false; State = $state }
    } $Zip $Dir @(
        (Import-ScriptFunction 'Resolve-OffsiteTarget')
        (Import-ScriptFunction 'Copy-Verified')
        (Import-ScriptFunction 'Measure-OffsiteContents')
        (Import-ScriptFunction 'Invoke-OffsitePrune')
        (Import-ScriptFunction 'Invoke-Offsite')
    )
}

Write-Host "`noffsite -- a surprise inside the copy step can NEVER fail the backup:"
try {
    $sb  = Track (New-Sandbox)
    $dst = Join-Path $sb 'off'; New-Item -ItemType Directory -Path $dst -Force | Out-Null
    # An archive that is not there any more: past the free-space guard (which has its
    # own catch), past Resolve-OffsiteTarget, and straight into Copy-Verified's
    # unguarded read of the source. Only the outer catch stands between this and a
    # terminating error taking down a run whose backup already verified.
    $gone = Join-Path $sb 'vanished.zip'

    $threw = $null; $res = $null
    try { $res = Invoke-LiftedOffsite -Zip $gone -Dir $dst } catch { $threw = $_ }

    if (-not $threw) { ok 'the offsite step swallowed it -- nothing escaped to fail the run' }
    else { bad "AN EXCEPTION ESCAPED THE OFFSITE STEP: $($threw.Exception.Message)" }
    if ($res -and $res.State.offsite.ok -eq $false) { ok 'recorded offsite.ok = false' }
    else { bad 'did not record the failure' }
    if ($res -and "$($res.State.offsite.reason)" -match 'threw') { ok "said what happened: $($res.State.offsite.reason)" }
    else { bad "reason did not report the exception: '$($res.State.offsite.reason)'" }
    if ($res -and $res.State.ok -eq $true) { ok 'the primary verdict was left alone' }
    else { bad 'the offsite step changed the primary verdict' }
} catch { bad "outer-catch test threw: $($_.Exception.Message)" }

Write-Host "`noffsite retention -- the health gates, actually executed:"
try {
    # One ancient archive per case, far outside any window, with both floors at 1 so
    # nothing but the gate can save it.
    $mk = {
        param($sb)
        New-Item -ItemType Directory -Path (Join-Path $sb 'off') -Force | Out-Null
        $p = Join-Path $sb 'off\h2-sboxmarket-20260401T000000Z.zip'
        Set-Content -Path $p -Value 'the last surviving copy of the money database' -Encoding ascii
        (Get-Item $p).LastWriteTime = (Get-Date).AddDays(-400)
        return $p
    }

    # BOTH floors at zero. Measured: with MinimumKeep at 1 the newest-N floor alone
    # saved the file, so "the copy survived" stayed GREEN with the health gate
    # deleted -- an assertion no mutation could turn red. With the floors out of the
    # way the gate is the only thing standing between a failed run and a deletion.
    $sb1 = Track (New-Sandbox); $a1 = & $mk $sb1
    $p1 = Invoke-LiftedOffsitePrune -Dir (Join-Path $sb1 'off') -PrimaryOk $false -CopyOk $true `
                                    -RetainDays 1 -MinimumKeep 0 -OffsiteRetainDays 1 -OffsiteMinimumKeep 0
    if (Test-Path $a1) { ok 'primary NOT ok: the 400-day-old copy survived' }
    else { bad 'primary NOT ok: DELETED the last surviving copy' }
    if ($p1.ran -eq $false -and "$($p1.skipped_because)" -match 'primary backup did not verify') {
        ok "primary NOT ok: refused and said why -- $($p1.skipped_because)"
    } else { bad "primary NOT ok: ran=$($p1.ran) because='$($p1.skipped_because)'" }

    $sb2 = Track (New-Sandbox); $a2 = & $mk $sb2
    $p2 = Invoke-LiftedOffsitePrune -Dir (Join-Path $sb2 'off') -PrimaryOk $true -CopyOk $false `
                                    -RetainDays 1 -MinimumKeep 0 -OffsiteRetainDays 1 -OffsiteMinimumKeep 0
    if (Test-Path $a2) { ok 'copy failed: the 400-day-old copy survived' }
    else { bad 'copy failed: DELETED the last surviving copy' }
    if ($p2.ran -eq $false -and "$($p2.skipped_because)" -match 'no verified copy') {
        ok "copy failed: refused and said why -- $($p2.skipped_because)"
    } else { bad "copy failed: ran=$($p2.ran) because='$($p2.skipped_because)'" }

    # THE CONTROL. Without this the two cases above are satisfied by a function that
    # never prunes anything at all, which is not the behaviour being claimed.
    $sb3 = Track (New-Sandbox); $a3 = & $mk $sb3
    $p3 = Invoke-LiftedOffsitePrune -Dir (Join-Path $sb3 'off') -PrimaryOk $true -CopyOk $true `
                                    -RetainDays 1 -MinimumKeep 0 -OffsiteRetainDays 1 -OffsiteMinimumKeep 0
    if (-not (Test-Path $a3)) { ok 'both healthy: an aged copy IS pruned (so the refusals mean something)' }
    else { bad 'both healthy: pruned nothing -- the two refusals above prove nothing' }
    if ($p3.ran -eq $true -and $p3.deleted -eq 1) { ok "both healthy: recorded ran=true, deleted=1" }
    else { bad "both healthy: ran=$($p3.ran) deleted=$($p3.deleted)" }
} catch { bad "offsite prune-guard test threw: $($_.Exception.Message)" }

Write-Host "`noffsite retention -- not pruned when the primary verified but the COPY failed:"
try {
    $free = 90..69 | ForEach-Object { [char]$_ } | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -First 1
    $sb = Track (New-Sandbox)
    $db = Join-Path $sb 'src'
    New-ScratchDb -Base $db
    New-Item -ItemType Directory -Path (Join-Path $sb 'off') -Force | Out-Null
    # The destination the job is told to use is gone; the archives from when it
    # worked are still in the sandbox's own 'off'. A good primary run must not be
    # licence to prune a location this run could not even reach.
    #
    # WHAT THIS PROVES AND WHAT IT DOES NOT. This is an OUTCOME guard: after a run
    # whose copy did not happen, the old copies are still there. It does not
    # exercise the refusal inside Invoke-OffsitePrune -- an unreachable destination
    # returns before the prune is called at all -- so the gate itself is executed
    # directly in "the health gates, actually executed" below.
    $old = Join-Path $sb 'off\h2-sboxmarket-20260401T000000Z.zip'
    Set-Content -Path $old -Value 'an old but surviving second copy' -Encoding ascii
    (Get-Item $old).LastWriteTime = (Get-Date).AddDays(-400)
    $r = Invoke-Job -Sandbox $sb -DbBase $db `
                    -Extra @{ OffsiteDir = "$($free):\skinbox-backups"; OffsiteMinimumKeep = 1; OffsiteRetainDays = 1 }
    if ($r.Status.ok -eq $true) { ok 'the primary verified (control)' } else { bad 'the primary did not verify' }
    if ($r.Status.offsite.prune.ran -eq $false) { ok 'offsite retention did not run' }
    else { bad 'pruned an offsite location this run could not write to' }
    if (Test-Path $old) { ok 'the 400-day-old second copy survived' } else { bad 'DELETED the surviving second copy' }
} catch { bad "offsite-prune-without-copy test threw: $($_.Exception.Message)" }

Write-Host "`noffsite retention -- keeps AT LEAST as long as the primary, and says so:"
try {
    $sb = Track (New-Sandbox)
    $db = Join-Path $sb 'src'
    New-ScratchDb -Base $db
    New-Item -ItemType Directory -Path (Join-Path $sb 'off') -Force | Out-Null
    $old = Join-Path $sb 'off\h2-sboxmarket-20260401T000000Z.zip'
    Set-Content -Path $old -Value 'older than the offsite window, younger than the primary''s' -Encoding ascii
    (Get-Item $old).LastWriteTime = (Get-Date).AddDays(-100)
    # Asked for a SHORTER offsite retention than the primary's. The point of the
    # second copy is surviving the loss of the first, so it must be clamped UP --
    # a second copy that expires first is a second copy for the wrong window.
    $r = Invoke-Job -Sandbox $sb -DbBase $db `
                    -Extra @{ RetainDays = 365; MinimumKeep = 1; OffsiteRetainDays = 2; OffsiteMinimumKeep = 1 }

    if ([int]$r.Status.offsite.prune.retain_days -eq 365) { ok 'offsite retention was raised to the primary''s 365d' }
    else { bad "offsite retain_days was $($r.Status.offsite.prune.retain_days), not the primary's 365" }
    if (Test-Path $old) { ok 'the 100-day-old copy survived a 2-day offsite window' }
    else { bad 'DELETED a copy the primary would still be keeping' }
    if ($r.Status.offsite.prune.ran -eq $true) { ok 'offsite retention did run (so the survival is the clamp, not a skip)' }
    else { bad "offsite retention was skipped ($($r.Status.offsite.prune.skipped_because)) -- this proves nothing" }
} catch { bad "offsite-retention-floor test threw: $($_.Exception.Message)" }

# ---------------------------------------------------------------------------- cleanup
foreach ($d in $sandboxes) {
    try { Remove-Item -LiteralPath $d -Recurse -Force -ErrorAction SilentlyContinue } catch { }
}

Write-Host ""
Write-Host "passed $script:PASS, failed $script:FAIL"
if ($script:FAIL -gt 0) { exit 1 }
exit 0
