#!/usr/bin/env bash
set -uo pipefail
BASE="${BASE:-http://localhost:8082}"
OUT="${OUT:-/c/Users/WW/Desktop/sboxmarket/_qa_boss/after}"
CHROME="${CHROME:-/c/Program Files/Google/Chrome/Application/chrome.exe}"
mkdir -p "$OUT"
routes=(
  "01-home:/"
  "02-market:/market"
  "03-market-grid:/market?view=grid"
  "04-database:/db"
  "05-help:/help"
  "06-faq:/faq"
  "07-changelog:/changelog.html"
  "08-cookies:/cookies.html"
  "09-status:/status.html"
  "10-cart:/cart"
  "11-watchlist:/watchlist"
  "12-wallet:/wallet"
  "13-sell:/sell"
  "14-settings:/settings"
  "15-profile:/profile/personal"
  "16-offers:/offers"
  "17-buyorders:/buyorders"
  "18-mystall:/me/stall"
  "19-affiliate:/affiliate"
  "20-support:/support"
  "21-notifications:/notifications"
  "22-item-missing:/item/missing"
  "23-stall-missing:/stall/missing"
  "24-loadout-missing:/loadout/missing"
  "25-item-real:/item/1"
  "26-stall-real:/stall/1"
  "27-loadout-real:/loadout/3"
  "28-admin:/admin"
  "29-csr:/csr"
  "30-fees:/fees"
  "31-pricing:/pricing"
  "32-trades:/trades"
  "33-deposit-success:/?deposit=success"
  "34-not-real-route:/this-route-does-not-exist"
)
for r in "${routes[@]}"; do
  name="${r%%:*}"
  path="${r#*:}"
  sep="?"
  case "$path" in *\?*) sep="&";; esac
  url="${BASE}${path}${sep}_qa=1"
  out="$OUT/$name.png"
  "$CHROME" --headless=new --disable-gpu --no-sandbox \
    --window-size=1920,1080 --hide-scrollbars \
    --virtual-time-budget=6000 \
    --screenshot="$out" "$url" >/dev/null 2>&1
  if [ -f "$out" ]; then echo "OK $name"; else echo "FAIL $name"; fi
done
