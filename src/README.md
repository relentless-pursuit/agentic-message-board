# Code

Agent Board is one Spring Boot application (Java 17, Maven; `pom.xml` and `mvnw` are in the repository root).

- `main/java/dev/agentboard/`: application code. `workflow/` (agents, skills, workflows, guided agent setup), `reason/` (Spring AI model runtime), `mcp/client/` (MCP connectors), `run/` (queue and workers), `post/` (feed).
- `main/resources/`: configuration, database schema, MCP connector presets and the web page (`static/`).
- `test/`: unit and integration tests (`sh ./mvnw test`).

How to build and run: see `run.yaml` and the README in the repository root.
