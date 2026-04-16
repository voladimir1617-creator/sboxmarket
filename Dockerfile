# Multi-stage build:
#   1) builder — compiles the fat JAR with Gradle + JDK 17
#   2) runtime — runs it on a hardened JRE base image as a non-root user
#
# The runtime image is ~210 MB uncompressed (eclipse-temurin:17-jre-jammy) and
# contains nothing the app doesn't need at runtime — no Gradle, no build deps.

# ── Stage 1: build ─────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /work
COPY gradlew .
COPY gradle gradle
COPY settings.gradle build.gradle ./
# Warm the Gradle cache before pulling in all sources so any future rebuild
# with unchanged dependencies skips straight to compile.
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ── Stage 2: runtime ───────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

# OCI labels — show up in `docker inspect` and any registry UI.
LABEL org.opencontainers.image.title="SkinBox Marketplace" \
      org.opencontainers.image.description="s&box skin marketplace — Spring Boot + Groovy + React CDN" \
      org.opencontainers.image.licenses="Proprietary" \
      org.opencontainers.image.source="https://github.com/your-org/sboxmarket"

RUN useradd --system --uid 1001 --gid 0 --no-create-home skinbox \
 && mkdir -p /opt/skinbox /var/log/skinbox /opt/skinbox/data \
 && chown -R 1001:0 /opt/skinbox /var/log/skinbox

USER 1001:0
WORKDIR /opt/skinbox

# Ship the fat JAR with a stable name so the systemd unit / docker CMD don't
# have to know about the 1.0.0 version suffix.
COPY --from=builder --chown=1001:0 /work/build/libs/sboxmarket-*.jar /opt/skinbox/skinbox.jar

EXPOSE 8080

# Prod defaults — override on every deploy via --env / env_file / k8s secrets:
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom" \
    LOG_FILE=/var/log/skinbox/skinbox.log

# HEALTHCHECK hits the app-controlled /api/health endpoint (actuator is
# disabled in prod to avoid leaking framework version to scanners).
# start-period covers the ~10s JVM + Spring Boot warmup so the container
# isn't flagged unhealthy during its first boot cycle.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD \
  wget -qO- http://127.0.0.1:8080/api/health | grep -q '"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/opt/skinbox/skinbox.jar"]
