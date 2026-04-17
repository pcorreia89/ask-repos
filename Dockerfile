FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon dependencies || true
COPY src src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/ask-repos/ ./
VOLUME /data
ENV ASK_REPOS_INDEX_BASE=/data/indexes
EXPOSE 3000
CMD ["./bin/ask-repos", "serve"]
