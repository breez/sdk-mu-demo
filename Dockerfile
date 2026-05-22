# Multi-stage Linux x86-64 build for Fly.io / generic Linux deploys.
#
# Stage 1: build the JAR with Gradle.
# Stage 2: thin JRE runtime image. The native `libbreez_sdk_spark_bindings.so`
#          must be supplied at build time at `./libs/libbreez_sdk_spark_bindings.so`
#          — the published `breez-sdk-spark-kmp-jvm:0.1.0` artifact does **not**
#          bundle a Linux .so (only host-arch). README documents how to obtain it
#          (run the LOCAL_SDK build on a Linux host or use cross).

FROM gradle:8.4-jdk17 AS app-builder
WORKDIR /work
# Copy gradle metadata first for better layer caching.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY gradlew gradlew.bat ./
# Stage the local Maven mirror for the KMP-jvm artifact (see README).
COPY libs/m2 ./libs/m2
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

# Native SDK lib. Expected to live at ./libs/ in the build context. JNA loads
# it via `jna.library.path` (set in JAVA_OPTS below). See README "Native lib".
COPY libs/libbreez_sdk_spark_bindings.so /app/libs/libbreez_sdk_spark_bindings.so

ENV JAVA_OPTS="-Djna.library.path=/app/libs -XX:MaxRAMPercentage=75"
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
