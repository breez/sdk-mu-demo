# Multi-stage Linux x86-64 build for Fly.io / generic Linux deploys.
#
# Stage 1 pulls the SDK from mvn.breez.technology; the published KMP-jvm
# artifact bundles native libs for darwin + linux on both arches, so the
# image needs no per-host staging. Stage 2 is a thin JRE runtime.

FROM gradle:8.4-jdk17 AS app-builder
WORKDIR /work
# Copy gradle metadata first for better layer caching.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY gradlew gradlew.bat ./
# Prime the dependency graph before sources change.
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon assemble

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# Tini for graceful PID-1 shutdown (Fly sends SIGINT on stop).
RUN apt-get update \
 && apt-get install -y --no-install-recommends tini ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Application jar.
COPY --from=app-builder /work/build/libs/sdk-mu-demo-0.1.0.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
