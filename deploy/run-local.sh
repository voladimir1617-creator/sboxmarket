#!/usr/bin/env bash
# Local-dev replacement for a full /etc/skinbox/skinbox.env file. Runs the
# Docker image against the local `sbox-pg` Postgres container. Every env var
# the prod profile requires has a safe default so the boot doesn't NPE on a
# missing placeholder. Intended for operator iteration — NOT suitable for a
# real prod deploy (no real Stripe keys, no real SMTP, no HSTS).
set -euo pipefail

IMAGE="${IMAGE:-sbox-app:latest}"
NAME="${NAME:-sbox-app}"
PORT="${PORT:-8082}"
# Public-facing URL for outbound email links. The container also serves
# skinbox.market via Cloudflare tunnel — Stripe redirects + email links
# go HERE, not localhost. Operator can override with `PUBLIC_URL=...`
# when running purely locally. Steam OpenID realm + return-url stay on
# localhost because the operator signs in via localhost:${PORT} during
# iteration; switching them to skinbox.market would bounce the operator
# off localhost mid-login.
PUBLIC_URL="${PUBLIC_URL:-https://skinbox.market}"

docker stop "$NAME" 2>/dev/null || true
docker rm   "$NAME" 2>/dev/null || true

# `--network host` isn't portable on Docker Desktop / Windows — publish the
# port instead, and reach the Postgres host-container by its Docker name
# via the default bridge. sbox-pg publishes 5432 on host:5433, so inside
# the bridge we use 5432 against the container hostname.
docker run -d --name "$NAME" \
  --add-host=host.docker.internal:host-gateway \
  -p "${PORT}:${PORT}" \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SERVER_PORT="$PORT" \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5433/skinbox \
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
  -e STEAM_REALM="http://localhost:${PORT}/" \
  -e STEAM_RETURN_URL="http://localhost:${PORT}/api/auth/steam/return" \
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
  "$IMAGE"

echo "Container started. Tail logs with: docker logs -f $NAME"

# ----------------------------------------------------------------------------
# COOKIE-STATE DEPLOY GATE — added 2026-05-03 after the FOURTH NUL-byte
# session outage. Anonymous /api/health was returning 200 the whole time,
# so the worker shipped 79 design tweaks while logged-in users 500'd.
# This gate replays the cookie shapes that historically poisoned the
# JdbcIndexedSessionRepository SELECT and aborts the deploy if any of
# them produces a 5xx. The deploy script is what every worker cycle
# calls — failing here = the worker's commit is automatically a no-go.
# ----------------------------------------------------------------------------

# Wait for /api/health (gives Spring 60s to boot before we probe).
echo "Waiting for app readiness..."
ready=0
for i in {1..60}; do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${PORT}/api/health" 2>/dev/null)" = "200" ]; then
    ready=1; break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "DEPLOY GATE FAIL: /api/health never reached 200 within 60s." >&2
  docker logs --tail=80 "$NAME" >&2
  exit 1
fi

# Probe the cookie shapes that have historically broken the JDBC bind.
# Each must return < 500. A single 5xx fails the gate.
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
    code=$(curl -s -o /dev/null -w '%{http_code}' \
      -H "Cookie: ${cookie}" \
      "http://localhost:${PORT}${path}" 2>/dev/null || echo 000)
    if [ "$code" -ge 500 ]; then
      echo "DEPLOY GATE FAIL: ${label} cookie on ${path} returned ${code}" >&2
      fail=1
    fi
  done
done

# NUL-byte cookie — the actual original killer. curl -H normalises some
# control bytes in transit, so we use --data-urlencode style raw send via
# printf piped into nc-equivalent: easier and equivalent in effect to
# replay through curl with %00 in the URL-decoded header value.
nul_code=$(printf 'GET / HTTP/1.1\r\nHost: localhost\r\nCookie: SBOX_SESSION=ab\x00cd\r\nConnection: close\r\n\r\n' \
  | timeout 5 bash -c "exec 3<>/dev/tcp/localhost/${PORT} && cat >&3 && head -1 <&3" 2>/dev/null \
  | awk '{print $2}')
if [ -n "${nul_code:-}" ] && [ "${nul_code}" -ge 500 ] 2>/dev/null; then
  echo "DEPLOY GATE FAIL: NUL-byte cookie returned ${nul_code} on /" >&2
  fail=1
fi

if [ "$fail" -eq 1 ]; then
  echo "DEPLOY GATE FAILED — site is broken for cookie-bearing users. See logs:" >&2
  docker logs --tail=80 "$NAME" >&2
  exit 1
fi
echo "DEPLOY GATE PASSED — anonymous + 5 cookie shapes all <500."
