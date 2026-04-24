# syntax=docker/dockerfile:1.6

# ───── Build stage ────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ───── Tool-fetch stage (cached) ──────────────────────────
FROM eclipse-temurin:21-jre-jammy AS tools
ARG APKTOOL_VERSION=2.11.1
ARG UBER_SIGNER_VERSION=1.3.0
WORKDIR /tools
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /tools/apktool.jar \
      "https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VERSION}/apktool_${APKTOOL_VERSION}.jar" \
 && curl -fsSL -o /tools/uber-apk-signer.jar \
      "https://github.com/patrickfav/uber-apk-signer/releases/download/v${UBER_SIGNER_VERSION}/uber-apk-signer-${UBER_SIGNER_VERSION}.jar"

# ───── Runtime stage ──────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.title="apk-rebirth"
LABEL org.opencontainers.image.description="Modernize legacy Android APKs so they install on modern devices."

RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app

COPY --from=tools /tools/apktool.jar /app/apktool.jar
COPY --from=tools /tools/uber-apk-signer.jar /app/uber-apk-signer.jar
COPY --from=builder /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar

RUN mkdir -p /app/workspace && chown -R app:app /app
USER app

ENV APKTOOL_PATH=/app/apktool.jar \
    UBER_SIGNER_PATH=/app/uber-apk-signer.jar \
    APK_REBIRTH_WORKSPACE=/app/workspace \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.awt.headless=true"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/ >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
