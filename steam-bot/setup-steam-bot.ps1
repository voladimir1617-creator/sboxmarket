<#
  setup-steam-bot.ps1 — everything about the Steam bot sidecar that CAN be automated.

  What this does NOT do, deliberately: create the Steam account, solve its CAPTCHA, or
  enrol the mobile authenticator. Those are gated by bot detection, and scripting past
  that is the same act no matter who runs the script. Steps 1-3 below are yours; this
  script does 4 onward, and verifies 1-3 actually worked.

  Run:  powershell -ExecutionPolicy Bypass -File .\setup-steam-bot.ps1
#>
[CmdletBinding()]
param(
  [switch]$VerifyOnly,
  [string]$BotDir = ""
)

# $PSScriptRoot is EMPTY inside a param() default under some invocation modes, and
# Join-Path then throws "Cannot bind argument to parameter 'Path'" before the script has
# printed a single line. Resolve it in the body, with two fallbacks, so the script works
# however it is launched -- double-clicked, dot-sourced, or piped through powershell -File.
if (-not $BotDir) {
  $BotDir = $PSScriptRoot
  if (-not $BotDir) { $BotDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
  if (-not $BotDir) { $BotDir = (Get-Location).Path }
}

$ErrorActionPreference = "Stop"
function Say($m){ Write-Host $m }
function Ok ($m){ Write-Host "  [ok]   $m"   -ForegroundColor Green }
function Bad($m){ Write-Host "  [FAIL] $m"   -ForegroundColor Red }
function Warn($m){ Write-Host "  [warn] $m"  -ForegroundColor Yellow }
function Step($n,$m){ Write-Host ""; Write-Host "== $n. $m" -ForegroundColor Cyan }

$BotDir = (Resolve-Path $BotDir).Path
$EnvFile = Join-Path $BotDir ".env"

Say ""
Say "SkinBox Steam bot setup"
Say "-----------------------"
Say "You do steps 1-3 by hand (Steam blocks automation there). This does the rest."

# ---------------------------------------------------------------- manual steps
Step 1 "Create a DEDICATED Steam account  [YOURS - about 5 min]"
Say "   https://store.steampowered.com/join"
Say "   Not your personal account. This one holds other people's items."
Say "   Then add ~5 USD of funds once: Steam keeps new accounts 'limited'"
Say "   and a limited account CANNOT TRADE AT ALL."

Step 2 "Enable the MOBILE authenticator  [YOURS - about 3 min]"
Say "   Steam mobile app -> Steam Guard -> Add Authenticator."
Say "   Email Steam Guard is NOT enough; trading needs the mobile one."
Say "   This starts a 7-15 day trade hold. It runs whether or not you"
Say "   touch anything else, so do it FIRST and let it tick."

Step 3 "Get the authenticator secrets onto this PC  [YOURS - about 5 min]"
Say "   Easiest: Steam Desktop Authenticator (SDA) -> it writes maFiles\<id>.maFile"
Say "   Or a rooted/Android pull of /data/data/com.valvesoftware.android.steam.community/"
Say "   files/Steamguard-<steamid>. Either way it is a JSON file with"
Say "   shared_secret and identity_secret in it. This script finds it for you."

# ---------------------------------------------------------------- automated
Step 4 "Node and dependencies"
try {
  $nodeV = (& node --version) 2>$null
  if (-not $nodeV) { throw "not found" }
  Ok "node $nodeV"
} catch {
  Bad "node is not installed or not on PATH - get it from https://nodejs.org"
  exit 1
}
if (-not (Test-Path (Join-Path $BotDir "node_modules"))) {
  Say "  installing dependencies (npm install)..."
  Push-Location $BotDir
  try { & npm install --no-audit --no-fund 2>&1 | Select-Object -Last 3 | ForEach-Object { "    $_" } }
  finally { Pop-Location }
}
if (Test-Path (Join-Path $BotDir "node_modules\steam-totp")) { Ok "steam-totp present" }
else { Bad "steam-totp missing - npm install did not complete"; exit 1 }

Step 5 "Find the authenticator file and read the secrets"
$candidates = @()
foreach ($root in @("$env:USERPROFILE\Desktop","$env:USERPROFILE\Downloads","$env:USERPROFILE\Documents","C:\")) {
  if (-not (Test-Path $root)) { continue }
  $depth = if ($root -eq "C:\") { 3 } else { 4 }
  try {
    $candidates += Get-ChildItem -Path $root -Recurse -Depth $depth -ErrorAction SilentlyContinue `
      -Include "*.maFile","Steamguard-*" -File | Select-Object -First 20
  } catch {}
}
$candidates = $candidates | Sort-Object FullName -Unique

$shared = $null; $identity = $null; $foundIn = $null
foreach ($c in $candidates) {
  try {
    $j = Get-Content $c.FullName -Raw | ConvertFrom-Json
    if ($j.shared_secret -and $j.identity_secret) {
      $shared = $j.shared_secret; $identity = $j.identity_secret; $foundIn = $c.FullName
      break
    }
  } catch {}
}
if ($foundIn) {
  Ok "found secrets in: $foundIn"
} else {
  Warn "no maFile / Steamguard file found automatically."
  Say  "  If you have one elsewhere, pass its path:"
  Say  "     .\setup-steam-bot.ps1   then paste the full path when asked"
  $manual = Read-Host "  Full path to the .maFile / Steamguard file (blank to skip)"
  if ($manual -and (Test-Path $manual)) {
    $j = Get-Content $manual -Raw | ConvertFrom-Json
    $shared = $j.shared_secret; $identity = $j.identity_secret; $foundIn = $manual
    if ($shared) { Ok "read secrets from $manual" }
  }
}

Step 6 "Validate the secrets"
if ($shared) {
  $probe = Join-Path $env:TEMP "steam_totp_probe.js"
  # NOTE: generateAuthCode() returns a plausible 5-char code for ANY input --
  # including garbage and an empty string. Measured. So a code alone proves
  # NOTHING. Structure is what can actually be checked here: a real Steam
  # shared_secret is base64 of exactly 20 bytes. Everything else is rejected.
  # The only true test is you comparing the code to your phone, so we print it.
  @"
const totp = require('steam-totp');
const s = process.argv[2] || '';
const clean = /^[A-Za-z0-9+/]+={0,2}$/.test(s);
const n = clean ? Buffer.from(s, 'base64').length : -1;
if (!clean)      { console.log('REJECT:not base64'); process.exit(2); }
if (n !== 20)    { console.log('REJECT:decodes to ' + n + ' bytes, expected 20'); process.exit(2); }
console.log('SHAPE_OK:' + totp.generateAuthCode(s));
"@ | Set-Content -Path $probe -Encoding utf8
  Push-Location $BotDir
  try { $out = (& node $probe $shared) 2>&1 | Out-String }
  finally { Pop-Location; Remove-Item $probe -ErrorAction SilentlyContinue }

  if ($out -match "SHAPE_OK:([0-9A-Z]{5})") {
    $code = $Matches[1]
    Ok "shared_secret has the right shape (base64 of 20 bytes)"
    Write-Host ""
    Write-Host "     CODE RIGHT NOW:  $code" -ForegroundColor Yellow
    Write-Host "     Open Steam Guard on your phone. If it does NOT show this exact" -ForegroundColor Yellow
    Write-Host "     code, the secret is for a different account - stop and re-extract." -ForegroundColor Yellow
    Write-Host ""
    Say "     This comparison is the only real proof. A generated code on its own"
    Say "     means nothing: the library returns one for garbage input too."
  } else {
    Bad "shared_secret rejected: $($out.Trim())"
    Say "     A wrong secret means every trade confirmation fails silently."
    $shared = $null
  }
} else {
  Warn "no shared_secret yet - finish steps 1-3 and re-run"
}

if ($identity) {
  $iclean = $identity -match '^[A-Za-z0-9+/]+={0,2}$'
  $ilen = if ($iclean) { [Convert]::FromBase64String($identity).Length } else { -1 }
  if ($iclean -and $ilen -eq 20) { Ok "identity_secret has the right shape too" }
  else { Bad "identity_secret looks wrong (base64=$iclean, bytes=$ilen, expected 20)"; $identity = $null }
} elseif ($shared) {
  Bad "identity_secret missing - shared_secret alone cannot CONFIRM trades"
}

Step 7 "Write .env"
if ($VerifyOnly) { Warn "-VerifyOnly set, not writing .env" }
else {
  $user = Read-Host "  STEAM_BOT_USERNAME (the LOGIN name, not the display name)"
  $pwSec = Read-Host "  STEAM_BOT_PASSWORD" -AsSecureString
  $pw = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
          [Runtime.InteropServices.Marshal]::SecureStringToBSTR($pwSec))
  $token = -join ((1..32) | ForEach-Object { '{0:x}' -f (Get-Random -Max 16) })

  if (Test-Path $EnvFile) {
    $bak = "$EnvFile.bak-$(Get-Date -Format yyyyMMddHHmmss)"
    Copy-Item $EnvFile $bak
    Warn "existing .env backed up to $(Split-Path $bak -Leaf)"
  }
  $lines = @(
    "# Written by setup-steam-bot.ps1. NEVER commit this file.",
    "STEAM_BOT_USERNAME=$user",
    "STEAM_BOT_PASSWORD=$pw",
    "STEAM_BOT_SHARED_SECRET=$shared",
    "STEAM_BOT_IDENTITY_SECRET=$identity",
    "BOT_API_TOKEN=$token",
    "BOT_PORT=3001"
  )
  Set-Content -Path $EnvFile -Value $lines -Encoding utf8
  Ok ".env written ($($lines.Count) lines)"
  Say "     BOT_API_TOKEN was generated for you: $token"
  Say "     Put that SAME value in the Spring app's BOT_API_TOKEN."
}

Step 8 "Start it the RIGHT way"
Say "   node --env-file=.env index.js"
Say ""
Warn "NOT 'npm start' and NOT 'node index.js' - neither reads .env, and the bot"
Say  "   will report every variable as missing even though the file is right there."
Say ""
Say "If it boots, it prints its listening port and logs in to Steam."
Say "Then set STEAM_BOT_BASE_URL in the Spring app to http://localhost:3001"
Say ""
Ok "Done. Steps 1-3 are yours; everything else above is finished."
