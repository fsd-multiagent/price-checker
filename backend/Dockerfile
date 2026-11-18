# Build context is the repo root: the build reads ../api, ../config and ../docs (AC 3.2.1).
FROM eclipse-temurin:21.0.8_9-jdk-noble AS build
WORKDIR /src
COPY api/ api/
COPY config/ config/
COPY docs/source-register.md docs/source-register.md
COPY backend/ backend/
RUN backend/gradlew -p backend bootJar --no-daemon

FROM eclipse-temurin:21.0.8_9-jre-noble
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /src/backend/build/libs/*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
