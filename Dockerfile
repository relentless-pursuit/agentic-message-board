# One board process with bounded workers; the same image runs locally, on-prem or behind cloud ingress.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* && useradd --create-home --uid 1001 board
WORKDIR /app
COPY --from=build /src/target/agent-board.jar app.jar
COPY examples ./examples
RUN mkdir -p data/runtime agents-local && chown -R board:board /app
ENV BOARD_BIND=0.0.0.0 PORT=8080 AGENTS_DIR=/app/agents-local
USER board
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
