#!/usr/bin/env bash
set -uo pipefail
BASE="${BASE:-http://localhost:8082}"
OUT="${OUT:-/c/Users/WW/Desktop/sboxmarket/_qa_boss/after-mobile}"
CHROME="${CHROME:-/c/Program Files/Google/Chrome/Application/chrome.exe}"
mkdir -p "$OUT"

echo "Warming up server..."
for _ in 1 2 3; do
  curl -fs "$BASE/" -o /dev/null && \
  curl -fs "$BASE/market" -o /dev/null && \
  curl -fs "$BASE/api/items?limit=1" -o /dev/null && break
  sleep 1
done

UA='Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'

routes=(
  "01-home:/"
  "02-market:/market"
  "03-database:/db"
  "04-help:/help"
  "05-faq:/faq"
  "06-cart:/cart"
  "07-watchlist:/watchlist"
  "08-wallet:/wallet"
  "09-sell:/sell"
  "10-settings:/settings"
  "11-profile:/profile/personal"
  "12-offers:/offers"
  "13-buyorders:/buyorders"
  "14-mystall:/me/stall"
  "15-affiliate:/affiliate"
  "16-support:/support"
  "17-notifications:/notifications"
  "18-item-real:/item/1"
  "19-stall-real:/stall/1"
  "20-loadout-real:/loadout/3"
  "21-item-missing:/item/missing"
  "22-stall-missing:/stall/missing"
  "23-loadout-missing:/loadout/missing"
  "24-not-real:/this-route-does-not-exist"
)
for r in "${routes[@]}"; do
  name="${r%%:*}"
  path="${r#*:}"
  sep="?"
  case "$path" in *\?*) sep="&";; esac
  url="${BASE}${path}${sep}_qa=1"
  out="$OUT/$name.png"
  curl -fs --max-time 8 "$url" -o /dev/null
  "$CHROME" --headless=new --disable-gpu --no-sandbox \
    --window-size=390,852 --hide-scrollbars \
    --user-agent="$UA" \
    --virtual-time-budget=8000 \
    --screenshot="$out" "$url" >/dev/null 2>&1
  if [ -f "$out" ]; then echo "OK $name"; else echo "FAIL $name"; fi
done
