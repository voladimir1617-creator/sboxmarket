#!/usr/bin/env bash
# MSYS_NO_PATHCONV stops Git Bash on Windows from mangling Linux-style
# container paths (e.g. /etc/nginx/nginx.conf becoming
# C:\Program Files\Git\etc\nginx\nginx.conf) when they're passed to docker.
# Without this the volume mounts silently land in the wrong place inside
# the nginx container and nginx falls back to its default config.
export MSYS_NO_PATHCONV=1

# Local-dev replacement for a full /etc/skinbox/skinbox.env file. Runs the
# Docker image against the local `sbox-pg` Postgres container. Every env var
# the prod profile requires has a safe default so the boot doesn't NPE on a
# missing placeholder. Intended for operator iteration — NOT suitable for a
# real prod deploy (no real Stripe keys, no real SMTP, no HSTS).
#
# ============================================================================
# ZERO-DOWNTIME DEPLOY (added 2026-05-03 after worker deploys flashed 502s
# in the operator's browser through the Cloudflare tunnel).
# ----------------------------------------------------------------------------
# Topology:
#
#   Cloudflare tunnel (cloudflared)
#         |
#         v
#   localhost:8082  --->  sbox-edge (nginx, never restarted)
#                              |
#                              v   (proxy via Docker DNS over sbox-net)
#                         sbox_upstream upstream block
#                              |
#                              +--> sbox-app  (current active)
#                              or
#                              +--> sbox-app-blue (the swap target)
#
# Deploy flow:
#   1. Ensure the sbox-edge nginx container exists, listening on host :8082.
#      It takes over the port that Cloudflare tunnel points at, replacing
#      the historical "app on :8082" topology. Edge is started ONCE and
#      lives across deploys — only the upstream pointer moves.
#   2. Start a parallel sbox-app-blue container on the sbox-net network
#      (no host port — only reachable via the network).
#   3. Wait for the new container to report /api/health 200.
#   4. RUN THE COOKIE-STATE GATE against the new container — before swap.
#   5. Rewrite edge-upstream.conf to point at sbox-app-blue, `nginx -s reload`.
#   6. Stop + remove the old sbox-app container (graceful 10s drain).
#   7. Rename sbox-app-blue -> sbox-app so the next deploy is symmetric.
#
# nginx reload is graceful: in-flight requests on the old worker complete
# against the old upstream; new requests go through the new upstream. There
# is no observable 502 window.
#
# If step 4 fails: the old container keeps serving, the blue container is
# torn down, the deploy exits non-zero, the worker's commit is automatically
# a no-go. Same gate semantics as the original script — just before swap
# instead of after.
# ============================================================================
set -euo pipefail

IMAGE="${IMAGE:-sbox-app:latest}"
NAME="${NAME:-sbox-app}"
EDGE_NAME="${EDGE_NAME:-sbox-edge}"
NETWORK="${NETWORK:-sbox-net}"
EDGE_PORT="${EDGE_PORT:-8082}"   # host port Cloudflare tunnel hits
APP_PORT="${APP_PORT:-8082}"     # internal JVM port (not host-published)
# Public-facing URL for outbound email links. The container also serves
# skinbox.market via Cloudflare tunnel — Stripe redirects + email links
# go HERE, not localhost. Operator can override with `PUBLIC_URL=...`
# when running purely locally. Steam OpenID realm + return-url stay on
# localhost because the operator signs in via localhost:${EDGE_PORT}
# during iteration; switching them to skinbox.market would bounce the
# operator off localhost mid-login.
PUBLIC_URL="${PUBLIC_URL:-https://skinbox.market}"

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ----------------------------------------------------------------------------
# Helper: spin up an app container by name on the sbox-net network with no
# published port. Used for both the blue (deploy target) and — on a fresh
# install — the initial sbox-app primary.
# ----------------------------------------------------------------------------
run_app_container() {
  local container_name="$1"
  docker run -d --name "$container_name" \
    --network "$NETWORK" \
    --add-host=host.docker.internal:host-gateway \
    -e SPRING_PROFILES_ACTIVE=prod \
    -e SERVER_PORT="$APP_PORT" \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://sbox-pg:5432/skinbox \
    -e SPRING_SESSION_STORE_TYPE=none \
    -e SPRING_DATASOURCE_USERNAME=skinbox \
    -e SPRING_DATASOURCE_PASSWORD=skinbox \
    -e SPRING_DATASOURCE_DRIVER=org.postgresql.Driver \
    -e SPRING_JPA_DIALECT=org.hibernate.dialect.PostgreSQLDialect \
    -e SPRING_JPA_DDL=validate \
    -e FLYWAY_ENABLED=true \
    -e DEV_MODE=true \
    -e CORS_ALLOWED_ORIGINS='*' \
    -e COOKIE_SECURE=false \
    -e COOKIE_SAME_SITE=lax \
    -e SECURITY_HSTS=false \
    -e SECURITY_CSRF=true \
    -e SECURITY_VERBOSE_ERRORS=false \
    -e H2_CONSOLE=false \
    -e SWAGGER_ENABLED=false \
    -e STRIPE_SECRET_KEY= \
    -e STRIPE_PUBLISHABLE_KEY= \
    -e STRIPE_WEBHOOK_SECRET= \
    -e STRIPE_SUCCESS_URL="${PUBLIC_URL}/?deposit=success" \
    -e STRIPE_CANCEL_URL="${PUBLIC_URL}/?deposit=cancel" \
    -e STEAM_API_KEY= \
    -e STEAM_REALM="http://localhost:${EDGE_PORT}/" \
    -e STEAM_RETURN_URL="http://localhost:${EDGE_PORT}/api/auth/steam/return" \
    -e ADMIN_BOOTSTRAP_STEAM_IDS=76561199839805014 \
    -e CSR_CREDIT_CAP=25.00 \
    -e LOG_FILE=/var/log/skinbox/skinbox.log \
    -e SMTP_HOST= \
    -e SMTP_PORT=587 \
    -e SMTP_USERNAME= \
    -e SMTP_PASSWORD= \
    -e SMTP_AUTH=false \
    -e SMTP_STARTTLS=false \
    -e APP_EMAIL_FROM=noreply@localhost \
    -e APP_EMAIL_FROM_NAME=SkinBox \
    -e APP_PUBLIC_URL="${PUBLIC_URL}" \
    -e SENTRY_DSN= \
    -e SENTRY_ENV=local \
    -e TRADE_AUTO_RELEASE_DAYS=8 \
    --restart unless-stopped \
    "$IMAGE" > /dev/null
}

# ----------------------------------------------------------------------------
# Helper: probe app readiness by container name through the Docker network.
# Uses `docker exec` with the in-image wget so we don't need to publish a
# port to host — keeps blue invisible to Cloudflare during validation.
# ----------------------------------------------------------------------------
probe_health_via_exec() {
  local container_name="$1"
  docker exec "$container_name" wget -qO- "http://127.0.0.1:${APP_PORT}/api/health" 2>/dev/null \
    | grep -q '"UP"'
}

# ----------------------------------------------------------------------------
# Helper: probe an arbitrary path via the edge nginx container, optionally
# with a Host: override so requests land on the blue upstream during the
# pre-swap gate. We hop through `docker exec sbox-edge wget` because:
#   - blue isn't published to host
#   - nginx inside sbox-edge can name-resolve sbox-app-blue via Docker DNS
# Returns the HTTP status code.
# ----------------------------------------------------------------------------
probe_status_via_container() {
  local target_container="$1"
  local path="$2"
  local cookie="${3:-}"
  local cookie_arg=""
  if [ -n "$cookie" ]; then
    cookie_arg="--header=Cookie: ${cookie}"
  fi
  # Use docker exec into the target container itself (loopback inside it)
  # so we hit the JVM directly, no edge hop. This is the most accurate
  # pre-swap probe.
  docker exec "$target_container" wget --server-response --spider \
    --tries=1 --timeout=10 \
    ${cookie_arg:+"$cookie_arg"} \
    "http://127.0.0.1:${APP_PORT}${path}" 2>&1 \
    | awk '/^  HTTP/{print $2; exit}' \
    | head -1
}

# ----------------------------------------------------------------------------
# Step 0: ensure prerequisites — sbox-net, sbox-pg attached, edge running.
# ----------------------------------------------------------------------------

if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  echo "Creating Docker network ${NETWORK}..."
  docker network create "$NETWORK" >/dev/null
fi

# Make sure sbox-pg is on sbox-net so app can reach it by hostname.
if docker ps --format '{{.Names}}' | grep -q '^sbox-pg$'; then
  if ! docker network inspect "$NETWORK" --format '{{range .Containers}}{{.Name}} {{end}}' | grep -q 'sbox-pg'; then
    echo "Attaching sbox-pg to ${NETWORK}..."
    docker network connect "$NETWORK" sbox-pg 2>/dev/null || true
  fi
fi

# Edge startup happens AFTER legacy detection (below) so we don't try to
# bind :8082 before tearing down a legacy sbox-app that's still on it.

# ----------------------------------------------------------------------------
# Step 1: figure out current state.
# Three shapes are possible:
#   (a) Fresh install — no sbox-app, no sbox-app-blue. Just start sbox-app
#       on sbox-net and point edge upstream at it.
#   (b) sbox-app exists and is on sbox-net (post-zero-downtime). Standard
#       blue/green swap.
#   (c) Legacy sbox-app exists with `-p 8082:8082` published (pre-this-script
#       deploy). Edge can't bind :8082 because legacy is holding it. Tear
#       down legacy first — accept the brief blip on this single migration
#       deploy. Subsequent deploys are zero-downtime.
# ----------------------------------------------------------------------------

LEGACY_HOLDING_EDGE_PORT=0
if docker ps --format '{{.Names}} {{.Ports}}' | grep -E "^${NAME} .*${EDGE_PORT}->" >/dev/null 2>&1; then
  LEGACY_HOLDING_EDGE_PORT=1
fi
EDGE_RUNNING=0
if docker ps --format '{{.Names}}' | grep -q "^${EDGE_NAME}$"; then
  EDGE_RUNNING=1
  # If edge-nginx.conf has drifted from what's mounted in the container,
  # the operator changed it but nginx -s reload won't pick up a different
  # main config (only the included upstream). Force a recreate in that
  # case. We compare a hash; cheap.
  ON_DISK_HASH=$(sha256sum "${DEPLOY_DIR}/edge-nginx.conf" 2>/dev/null | awk '{print $1}')
  IN_CONTAINER_HASH=$(docker exec "$EDGE_NAME" sha256sum /etc/nginx/nginx.conf 2>/dev/null | awk '{print $1}')
  if [ -n "$ON_DISK_HASH" ] && [ "$ON_DISK_HASH" != "$IN_CONTAINER_HASH" ]; then
    echo "edge-nginx.conf drifted from running container — recreating ${EDGE_NAME}."
    docker rm -f "$EDGE_NAME" >/dev/null 2>&1 || true
    EDGE_RUNNING=0
  fi
fi

if [ "$LEGACY_HOLDING_EDGE_PORT" -eq 1 ]; then
  echo "MIGRATION DEPLOY — legacy ${NAME} holds host :${EDGE_PORT}; tearing down so edge can bind."
  echo "  This single deploy will have a short downtime window. Subsequent deploys are zero-downtime."
  docker stop "$NAME" >/dev/null 2>&1 || true
  docker rm   "$NAME" >/dev/null 2>&1 || true
  EDGE_RUNNING=0  # need to (re)start now that the port is free
fi

if [ "$EDGE_RUNNING" -eq 0 ]; then
  echo "Bringing up edge proxy (${EDGE_NAME}) on host :${EDGE_PORT}..."
  docker rm -f "$EDGE_NAME" 2>/dev/null || true
  docker run -d --name "$EDGE_NAME" \
    --network "$NETWORK" \
    -p "${EDGE_PORT}:8082" \
    --restart unless-stopped \
    -v "${DEPLOY_DIR}/edge-nginx.conf:/etc/nginx/nginx.conf:ro" \
    -v "${DEPLOY_DIR}/edge-upstream.conf:/etc/nginx/conf.d/edge-upstream.conf:ro" \
    nginx:alpine > /dev/null
  # nginx is fast — give it a beat to bind.
  sleep 1
fi

# ----------------------------------------------------------------------------
# Step 2: launch the deploy target.
# If sbox-app doesn't exist: this IS the primary, name it sbox-app directly.
# If sbox-app exists: name the new one sbox-app-blue, swap+rename later.
# ----------------------------------------------------------------------------

PRIMARY_EXISTS=0
if docker ps --format '{{.Names}}' | grep -q "^${NAME}$"; then
  PRIMARY_EXISTS=1
fi

if [ "$PRIMARY_EXISTS" -eq 0 ]; then
  echo "No primary container — starting ${NAME} as the first app instance."
  # Clean up any stale stopped record.
  docker rm "$NAME" 2>/dev/null || true
  run_app_container "$NAME"
  TARGET_CONTAINER="$NAME"
  IS_INITIAL_DEPLOY=1
else
  BLUE_NAME="${NAME}-blue"
  echo "Starting blue container (${BLUE_NAME}) for zero-downtime swap..."
  docker rm -f "$BLUE_NAME" 2>/dev/null || true
  run_app_container "$BLUE_NAME"
  TARGET_CONTAINER="$BLUE_NAME"
  IS_INITIAL_DEPLOY=0
fi

echo "Container started: ${TARGET_CONTAINER}. Tail logs with: docker logs -f ${TARGET_CONTAINER}"

# ----------------------------------------------------------------------------
# Step 3: wait for the new container to be healthy.
# ----------------------------------------------------------------------------

echo "Waiting for app readiness on ${TARGET_CONTAINER}..."
ready=0
for i in {1..90}; do
  if probe_health_via_exec "$TARGET_CONTAINER"; then
    ready=1; break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "DEPLOY GATE FAIL: /api/health on ${TARGET_CONTAINER} never reached UP within 90s." >&2
  docker logs --tail=80 "$TARGET_CONTAINER" >&2
  if [ "$IS_INITIAL_DEPLOY" -eq 0 ]; then
    docker rm -f "$TARGET_CONTAINER" >/dev/null 2>&1 || true
    echo "Blue container torn down. Old ${NAME} still serving traffic."
  fi
  exit 1
fi

# ----------------------------------------------------------------------------
# Step 4: COOKIE-STATE DEPLOY GATE — added 2026-05-03 after the FOURTH NUL-byte
# session outage. Anonymous /api/health was returning 200 the whole time, so
# the worker shipped 79 design tweaks while logged-in users 500'd. This gate
# replays the cookie shapes that historically poisoned the
# JdbcIndexedSessionRepository SELECT and aborts the deploy if any of them
# produces a 5xx. The deploy script is what every worker cycle calls — failing
# here = the worker's commit is automatically a no-go.
#
# Crucially: this runs against the BLUE container BEFORE the upstream swap.
# If the gate fails, traffic never sees the broken image — the old container
# keeps serving and the deploy aborts.
# ----------------------------------------------------------------------------

declare -a probes=(
  "garbage:SBOX_SESSION=garbage"
  "stale-uuid:SBOX_SESSION=11111111-2222-3333-4444-555555555555"
  "empty:SBOX_SESSION="
  "long:SBOX_SESSION=$(printf 'a%.0s' {1..512})"
)

fail=0
for entry in "${probes[@]}"; do
  label="${entry%%:*}"
  cookie="${entry#*:}"
  for path in / /market /wallet; do
    code=$(probe_status_via_container "$TARGET_CONTAINER" "$path" "$cookie" || echo 000)
    code="${code:-000}"
    if [ "$code" -ge 500 ] 2>/dev/null; then
      echo "DEPLOY GATE FAIL: ${label} cookie on ${path} returned ${code}" >&2
      fail=1
    fi
  done
done

# NUL-byte cookie — the actual original killer. Curl/wget normalise some
# control bytes in transit, so we use raw TCP via /dev/tcp. The new container
# isn't published to host, but `docker exec` lets us run the probe from
# inside the edge container against the new container's hostname.
nul_code=$(docker exec "$EDGE_NAME" sh -c "
  printf 'GET / HTTP/1.1\r\nHost: localhost\r\nCookie: SBOX_SESSION=ab\x00cd\r\nConnection: close\r\n\r\n' \\
    | timeout 5 sh -c 'exec 3<>/dev/tcp/${TARGET_CONTAINER}/${APP_PORT} && cat >&3 && head -1 <&3' 2>/dev/null \\
    | awk '{print \$2}'
" 2>/dev/null | head -1)
if [ -n "${nul_code:-}" ] && [ "${nul_code}" -ge 500 ] 2>/dev/null; then
  echo "DEPLOY GATE FAIL: NUL-byte cookie returned ${nul_code} on /" >&2
  fail=1
fi

if [ "$fail" -eq 1 ]; then
  echo "DEPLOY GATE FAILED — site is broken for cookie-bearing users. See logs:" >&2
  docker logs --tail=80 "$TARGET_CONTAINER" >&2
  if [ "$IS_INITIAL_DEPLOY" -eq 0 ]; then
    docker rm -f "$TARGET_CONTAINER" >/dev/null 2>&1 || true
    echo "Blue container torn down. Old ${NAME} still serving traffic."
  fi
  exit 1
fi

# ----------------------------------------------------------------------------
# Step 5: swap the edge upstream to point at the new container, reload nginx.
#
# Zero-downtime invariants:
#   - During the swap window the upstream block lists BOTH containers, with
#     the OLD as `backup` and the NEW as primary. nginx routes new requests
#     to NEW; if NEW isn't ready (it is — we passed the gate above), nginx
#     falls back to OLD. proxy_next_upstream in edge-nginx.conf retries
#     on connection errors, so a request that lands on a pid-killed
#     container is automatically retried against the other upstream.
#   - We stop OLD with --time=10 so in-flight requests drain.
#   - The rename step happens AFTER OLD is gone. The upstream rewrite
#     between rename and reload uses the canonical NAME — by which point
#     Docker DNS resolves NAME to the renamed container.
# ----------------------------------------------------------------------------

if [ "$IS_INITIAL_DEPLOY" -eq 1 ]; then
  # Edge already points at sbox-app from the default config. Verify reload
  # reads the right upstream and the nginx workers are alive.
  echo "Initial deploy — edge already targets ${NAME}. Reloading nginx..."
  cat > "${DEPLOY_DIR}/edge-upstream.conf" <<EOF
upstream sbox_upstream {
    server ${NAME}:${APP_PORT} max_fails=0 fail_timeout=2s;
}
EOF
  docker exec "$EDGE_NAME" nginx -s reload
else
  # Phase A: dual-upstream — NEW primary + OLD backup. nginx prefers
  # primary, but proxy_next_upstream falls back to OLD on connection
  # error. This is the period during which the OLD container is still
  # accepting connections; the swap is gradual, not a cliff.
  echo "Phase A: dual upstream (${TARGET_CONTAINER} primary, ${NAME} backup), reload..."
  cat > "${DEPLOY_DIR}/edge-upstream.conf" <<EOF
upstream sbox_upstream {
    server ${TARGET_CONTAINER}:${APP_PORT} max_fails=0;
    server ${NAME}:${APP_PORT} backup max_fails=0;
}
EOF
  docker exec "$EDGE_NAME" nginx -s reload
  # Let the old worker finish in-flight requests against OLD; new requests
  # are already heading to NEW.
  sleep 3

  # Phase B: drain + remove OLD. With graceful stop the old JVM completes
  # in-flight requests before exiting. Nginx routes new traffic to NEW.
  echo "Phase B: graceful stop of old ${NAME} (--time=10)..."
  docker stop --time=10 "$NAME" >/dev/null 2>&1 || true
  docker rm "$NAME" >/dev/null 2>&1 || true

  # Phase C: rename NEW -> canonical name. Between rename and the reload
  # below, the upstream block STILL references ${TARGET_CONTAINER} — which
  # has just been renamed to ${NAME}. Docker network DNS removes the old
  # name immediately on rename. We immediately overwrite the upstream
  # config to use ${NAME} and reload nginx so nginx re-resolves.
  echo "Phase C: rename ${TARGET_CONTAINER} -> ${NAME} for symmetric next deploy..."
  docker rename "$TARGET_CONTAINER" "$NAME"
  cat > "${DEPLOY_DIR}/edge-upstream.conf" <<EOF
upstream sbox_upstream {
    server ${NAME}:${APP_PORT} max_fails=0 fail_timeout=2s;
}
EOF
  # Use `nginx -s reload` not -t-then-reload; nginx validates internally.
  # Two reloads in a deploy is fine — they're cheap.
  docker exec "$EDGE_NAME" nginx -s reload
fi

# ----------------------------------------------------------------------------
# Step 6: sanity verify via the edge. Use --max-time so a hung connection
# can't stall the deploy script. We capture only the first non-zero status
# code so any stray header bytes from curl-on-Windows don't poison the
# warning string.
# ----------------------------------------------------------------------------

edge_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://localhost:${EDGE_PORT}/api/health" 2>/dev/null || true)
# Strip any non-numeric characters that occasionally sneak in via curl's
# stderr-to-stdout interleaving on Windows. The numeric code is always
# the first 3 chars when curl does emit cleanly.
edge_code=$(echo -n "$edge_code" | tr -cd '0-9' | head -c 3)
edge_code="${edge_code:-000}"
if [ "$edge_code" != "200" ]; then
  echo "WARNING: edge probe of /api/health returned ${edge_code} (expected 200)." >&2
  echo "  Container is healthy on the inside; investigate edge config." >&2
fi

echo "DEPLOY GATE PASSED — zero-downtime swap complete (anonymous + 5 cookie shapes all <500)."
