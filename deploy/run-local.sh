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
