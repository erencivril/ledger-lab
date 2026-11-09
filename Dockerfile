# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
# Resolve dependencies in their own layer so source changes do not re-download them.
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre
# Pick up OS security fixes released after the base image was built.
RUN apt-get update && apt-get -y upgrade && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 ledger
WORKDIR /app
COPY --from=build /src/build/install/ledger-lab/ /app/
USER ledger
EXPOSE 8080
ENTRYPOINT ["/app/bin/ledger-lab"]
