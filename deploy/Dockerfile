# One board process with bounded workers; the same image runs locally, on-prem or behind cloud ingress.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
COPY docs/architecture-lab.html ./docs/architecture-lab.html
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates git python3 \
    && rm -rf /var/lib/apt/lists/* && useradd --create-home --uid 1001 board
WORKDIR /app
# uvx starts local MCP servers such as the web fetch connector (no key needed); uv downloads its own Python.
COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /uvx /usr/local/bin/
COPY --from=build /src/target/agent-board.jar app.jar
COPY examples ./examples
# DocDrift (problem 2.12) for the Docs check page: Python standard library only.
COPY src/docdrift ./src/docdrift
RUN mkdir -p data/runtime agents-local && chown -R board:board /app
ENV BOARD_BIND=0.0.0.0 PORT=8080 AGENTS_DIR=/app/agents-local
USER board
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=6 CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
