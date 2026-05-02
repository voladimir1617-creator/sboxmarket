# Deploy the SessionAttributeNulStripFilter (commit 5b3590f) into the running container

You committed the wrapper filter at `5b3590f` AFTER the rebuild that picked up V59 — so the filter is in git but **NOT** in the running JAR. The defense-in-depth layer is currently absent at runtime; only the V59 Postgres CHECK constraint is active.

`docker logs sbox-app --tail 50 | grep "Started SboxMarketApplication"` shows the JVM started at 19:21:50Z. Your V59 commit landed at 19:23Z, the wrapper at 19:31Z. Container is older than both.

## Do this

```bash
docker build -t sbox-app:latest .
bash deploy/run-local.sh

# Wait ~15s for the container to come up, then verify:
for i in 1 2 3 4 5; do
  HEALTH=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8082/api/health)
  if [ "$HEALTH" = "200" ]; then break; fi
  sleep 3
done

# Confirm the wrapper class is in the JAR:
docker exec sbox-app jar -tf /opt/skinbox/skinbox.jar | grep NulStrip
# Expect:
#   BOOT-INF/classes/com/sboxmarket/config/SessionAttributeNulStripConfig.class
#   BOOT-INF/classes/com/sboxmarket/config/SessionAttributeNulStripFilter.class

# Confirm Spring registered the bean at startup:
docker logs sbox-app --since 2m | grep -i "NulStripFilter\|sessionAttributeNulStrip"

# Public probes:
curl -s -o /dev/null -w 'public:%{http_code}\n'  https://skinbox.market/
curl -s -o /dev/null -w 'market:%{http_code}\n'  https://skinbox.market/market
curl -s -o /dev/null -w 'api:%{http_code}\n'     https://skinbox.market/api/listings

# Zero new errors:
docker logs sbox-app --since 2m | grep -iE 'exception|error|0x00' | grep -v EmailService
```

All probes 200, zero errors → done.

## Then update operator-side bookkeeping

Append a one-line entry to `_qa_boss/cycle_*` or whatever incident ledger you maintain — TODAY 2026-05-02:

> Spring Session NUL-byte detonation (`P0` — site hard-down on every visitor). Root mitigation: V58 truncate (was already in main from cycle 12 this morning). New backstops landed today: V59 CHECK constraint (commit f9737d9) + SessionAttributeNulStripFilter at HIGHEST_PRECEDENCE+100 (commit 5b3590f). Wrapper deployed in container at <timestamp>.

No need for a separate /grind cycle — this is just closing out the P0.

GO.
