# GhostClean backend image (Phase 12 production).
#
# Multi-stage build: Maven compiles + packages in the builder; only the
# resulting jar ships in the minimal JRE runtime. No source files, no
# build tools, no secrets, and no .env files are present in the final
# image. CI runs `mvn test` separately (the builder skips tests because
# Testcontainers needs a Docker daemon, which plain builders lack).
#
# Build:  docker compose build api
# SBOM:   repository tooling does not currently generate one; the exact
#         dependency inventory is `mvn dependency:list` output for the
#         built commit (recorded in release evidence, never claimed as a
#         scan without running it).

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S ghostclean && adduser -S ghostclean -G ghostclean
WORKDIR /app

COPY --from=builder --chown=ghostclean:ghostclean /build/target/ghostclean-api-*.jar /app/app.jar

USER ghostclean
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/v1/health | grep -q '"status":"UP"'

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
