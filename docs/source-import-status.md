# Screenshot source import status

## Current local integration

All 76 production Java files now compile without exclusions on Spring Boot 4.1.1 / Jackson 3. Missing registry APIs and startup schema have been integrated, and OpenAI reasoning plus a ten-agent generated-observation pilot have been added. The root README and missing-code-audit.md describe the current runnable scope and validation. The sections below preserve historical import notes; statements about exclusions or missing APIs in those earlier batches are superseded by the current integration status. Provisional cropped connector expressions remain provisional.

## Latest POM, registration event, board API and shell demo batch

AgentRegisteredEvent.java is transcribed from the complete record screenshot. All four visible event-package files are now present; EventTriggerListener remains excluded pending repository/library/runtime integration.

BoardApiController.java is complete across the screenshot batches, including TokenRequest, and replaces the partial text reference. It is saved under the main web package but explicitly excluded pending runtime and AgentRepository.countActive integration.

The complete original POM is saved at `docs/imports/pom.xml`: Spring Boot 4.1.1, Java 17, Web MVC/JDBC/Actuator/Security, Jackson YAML, H2/PostgreSQL and test dependencies. The root POM remains the local Spring Boot 3.1.5 milestone until migration. The supplied versions have not been resolved or verified.

The visible shell hook-demo source is saved at `docs/imports/send-hook.partial.sh.txt`. Its literal BODY JSON is cropped on the right; the complete executable script remains pending. No missing payload text was guessed.

No tests, builds, syntax checks, dependency downloads or runtime commands were run for this batch.

## Earlier agent API, event and demo batch

AgentApiController is complete across the latest two screenshots and saved at `docs/imports/AgentApiController.java.txt`, replacing the earlier tail-only reference. The current local controller remains active pending the supplied version's token/run/registry integration.

EventTriggerListener.java and HookReceivedEvent.java are transcribed from complete visible source. EventTriggerListener preserves Jackson 3 and is explicitly excluded: AgentRepository.EventAgent and findByEvent are still missing, while AgentRegistry.parse and run-service integration are also pending. AgentRegisteredEvent has since been supplied. HookReceivedEvent is in the configured compiler scope; no build or tests were run for these additions.

The supplied send-hook.ps1 has a cropped literal JSON payload. Its readable commands and the payload gap are preserved in `docs/imports/send-hook.partial.ps1.txt`; it is not installed as an executable demo script. The application.yml screenshot repeats the existing tail, leaving the original lines 1–43 missing.

## Earlier file-tree and run recipe batch

The supplied run.yml is saved at the project root. The CLI install line is clipped after the npm command and start of a fallback echo, so only the visible complete command prefix is copied; the omission is labelled. No commands from the recipe, tests, builds or runtime checks were executed for this batch.

The directory tree identified three previously missing event sources; all three are now supplied, as is the BoardApiController tail. Maven wrapper scripts and four test YAML fixtures remain missing. The test Java and static folders are collapsed, so their original file inventories remain unknown. run.yml additionally references scripts/send-hook.sh, now partially supplied but missing its complete payload line. See missing-code-audit.md for the full inventory. Filenames alone were not used to invent implementations.

## Action package

Location: `src/main/java/dev/agentboard/act/`.

| Class | Status |
| --- | --- |
| Action | Interface reconstructed from the three supplied implementations; its original source was not pictured |
| ActionRegistry | Transcribed from the earlier screenshot |
| ActionResult | Transcribed from the earlier screenshot |
| PostAction | Transcribed once; two earlier screenshots were identical |
| ReplyAction | Transcribed from the earlier screenshot |
| WebhookAction | Transcribed from the earlier screenshot |
| ActContext | Transcribed from the complete source screenshot in the latest batch |

ActContext, Action, ActionRegistry and ActionResult now compile because RunContext and Reasoning are present. PostAction, ReplyAction and WebhookAction remain explicitly excluded until their post/webhook services and associated dependencies are integrated. The compiled registry has no active action implementations yet.

## Connector package

Location: `src/main/java/dev/agentboard/connect/`.

`CircuitBreaker.java` is transcribed from the complete visible source, compiles normally, and has tests for failure thresholds, host isolation, reset after success, and retry after an expired open window. No connector is wired to it yet.

Seven implementations are saved under `connect/connectors/`. They are explicitly excluded from compilation until their shared dependencies are available. Parsing the source for syntax is separate from checking types or running these connectors.

| Class | Screenshot coverage and reconstruction |
| --- | --- |
| BoardConnector | Import completion and clipped fact/summary/change-key expressions reconstructed |
| EventConnector | Clipped fallback severity, summary fields, error text and truncation suffix reconstructed |
| GithubPullsConnector | Clipped URL query parameters, validation message, labels and fact formatting reconstructed |
| GithubReleasesConnector | Clipped regexes, API query, cutoff filter, validation messages, fact text and summary reconstructed |
| HackerNewsConnector | Clipped story fact formatting and final observation arguments reconstructed |
| HttpCheckConnector | Visible control flow preserved; clipped validation message and latency-unit text completed |
| RssConnector | Clipped status regexes, lookback maximum, request headers, change key, summaries and parser expressions completed; visible XML security and date handling preserved |

These reconstructions are provisional. They should be compared with the actual Java files before integration; they are not claimed as exact copies of text outside the screenshot boundaries. In particular, inferred accesses on PostView, AgentStats, Observation, With and SafeHttp need verification against their eventual declarations.

The shared connector source files are now all present and compile: Connector, ConnectorRegistry, Observation, SafeHttp, With and CircuitBreaker. SafeHttp's ObjectMapper import was adapted to Jackson 2. Condition, RunContext and Schedule are present. All seven connector implementations remain excluded, pending integration and checking of their reconstructed cropped lines. PostRepository is present but excluded pending compatibility/schema integration.

## Post and event packages

The latest six-file batch adds:

| File | Status |
| --- | --- |
| event/PostCreatedEvent.java | Transcribed from the visible record; compiles normally |
| connect/Observation.java | Transcribed from the visible record and combine method; now compiles with Condition |
| post/PostDraft.java | Transcribed; compiles normally |
| post/PostView.java | Transcribed, including nested Link record; compiles normally |
| post/PostRepository.java | Assembled from overlapping screenshots; preserves JdbcClient/Jackson 3 source and remains excluded pending version alignment and the posts schema |
| post/PostService.java | Complete across the screenshot batches, including clean(...); excluded pending repository compatibility and AgentRepository.recordPost; RateLimiter is now present |

The latest screenshots supply the previously missing clean(...) block. The temporary guard has been removed and replaced with the shown post-type, severity, tag and link validation and PostDraft return. The supplied create method additionally requires AgentRepository.recordPost, which is absent from the current repository implementation.

## Reasoning package

The latest batch adds all four shown reasoning files:

| File | Status |
| --- | --- |
| reason/Reasoning.java | Transcribed record and withNote method; compiles normally |
| reason/TemplateReasoner.java | Transcribed fallback; now compiles with Observation and Condition |
| reason/ReasoningService.java | Transcribed two-attempt Claude/fallback orchestration; excluded pending ClaudeCli alignment |
| reason/ClaudeCli.java | Assembled from overlapping screenshots; excluded pending Jackson 3 compatibility and CLI behaviour verification |

No Claude process or model request is executed as part of this source import. The shown CLI wrapper inherits CLAUDE_CODE_OAUTH_TOKEN or the CLI's saved login, but it does not map the assignment's CLAUDE_KEY variable. Its availability check checks the CLI version and whether configuration is enabled; it does not prove that authentication works. The process flags and stated restrictions must be verified against the deployed CLI before enabling it.

## Rules and run packages

Condition and ten run-package files were supplied in the run batch. The repeated PostService screenshots match the previously completed validator; no duplicate file was added.

Compiles now:

- `rules/Condition.java`
- `run/JobQueue.java`
- `run/InMemoryJobQueue.java`
- `run/RunContext.java`
- `run/RunView.java`

Source saved but excluded pending database/library/registry integration:

- `run/AgentRunner.java`
- `run/DatabaseJobQueue.java`
- `run/DbLock.java`
- `run/RunRepository.java`
- `run/RunService.java`
- `run/WorkerPool.java`

DbLock and RunRepository preserve the shown JdbcClient source, while AgentRunner preserves the shown Jackson 3 ObjectMapper. The latest complete reference schema supplies runs, run_steps and board_locks, but it has not been migrated into the local database. AgentRunner additionally requires the missing AgentRepository.recordRunStatus method. A compiled in-memory queue does not execute work by itself; the worker lifecycle remains excluded.

Condition is available for callers to parse rules, but the current registry loader has not been wired to validate all rule settings using it. The original class comment describes intended final behaviour, not a verified integration capability of this milestone.

## Scheduling and security packages

The scheduling/security batch adds:

| File | Status |
| --- | --- |
| schedule/Schedule.java | Transcribed time calculations; compiles and has deterministic interval/daily/weekly tests |
| schedule/Scheduler.java | Source saved; excluded pending repository scheduling APIs, database runtime and TaskScheduler wiring |
| security/BoardPrincipal.java | Transcribed record and scope helper; compiles |
| security/RateLimiter.java | Compiles with a per-key quota test; the cropped windows.compute expression was reconstructed and is labelled in the source |
| security/BearerTokenFilter.java | Source saved; excluded pending Spring Security and TokenService integration |
| security/RequestSizeFilter.java | Source saved; excluded pending request-limit integration/tests |
| security/SecurityConfig.java | Source saved; excluded pending Spring Security/token/schema/API integration |
| security/TokenService.java | Source saved; excluded pending JdbcClient compatibility and api_tokens schema integration; not run and no tokens minted |

All directly referenced top-level project class names have source files, including AgentRegisteredEvent. This does not establish that missing methods, nested types, schema integration, libraries or API/UI routes are implemented.

The latest SQL screenshots complete all eight tables and their indexes. `reference-schema.sql` replaces the earlier partial reference and is not loaded by the application. It passes initialization and uniqueness checks in isolated H2 PostgreSQL mode. The original agents schema uses scheduling/post metadata and last_run_status, whereas the runnable registry currently uses status and different column limits; schema/repository migration must reconcile them without losing existing data. No PostgreSQL server verification has been performed.

## Web/API, bootstrap and deployment batch

| File | Status |
| --- | --- |
| web/Callers.java | Complete source saved; excluded pending Spring Security |
| web/PostApiController.java | Complete source saved; excluded pending post/security integration and AgentRegistry.view |
| docs/imports/AgentApiController.java.txt | Now complete across the supplied screenshots; current local controller retained pending runtime integration |
| web/BoardApiController.java | Now complete including TokenRequest; saved in the main source tree but excluded pending integration |
| docs/imports/ApiErrors.java.txt | Complete supplied handler saved as a reference; current local handler retained because InvalidAgentException and PostService integration are missing |
| AgentBoardApplication.java | Complete supplied bootstrap merged, including EnableScheduling; runtime Scheduler remains excluded |
| application.yml | Visible HTTP allow-list, limits and webhook target settings merged; original lines 1–43 remain missing and local datasource/server settings retained |
| Dockerfile, docker-compose.yml, docker-compose.scale.yml | Supplied deployment definitions saved; Compose models validate without environment interpolation; container build/start not attempted |
| .dockerignore, .gitignore, .gitattributes | Supplied rules added; existing .env and log exclusions retained/added |

The application.yml screenshot tail is also preserved in `docs/imports/application.tail.yml.txt`. The Dockerfile's right edge cuts off the health-check URL; `/actuator/health` is completed from the visible prefix and remains provisional. The copied Dockerfile lacks the Maven wrapper files it references. Its Actuator health endpoint, container bind address and scaled database configuration do not match the current milestone. See the missing-code audit before attempting container startup.

## Webhook and MCP packages

Added from the latest batch:

| File | Status |
| --- | --- |
| hook/Hmac.java | Compiles normally; visible signature and hashing logic preserved |
| hook/HookDeliveries.java | Compiles normally; JdbcClient adapted to JdbcTemplate, with a table and unique per-agent delivery key added |
| hook/InboundHookController.java | Screenshot source saved, excluded pending run/post/security types, registry API alignment, and Jackson 3 compatibility |
| hook/OutboundWebhookService.java | Screenshot source saved, excluded pending post/event/rule types and Jackson 3 compatibility |
| mcp/McpController.java | Screenshot source saved, excluded pending post/security types, AgentView API alignment, and Jackson 3 compatibility |

The three excluded webhook/MCP files preserve their shown `tools.jackson.databind.ObjectMapper` imports; the newly added PostRepository does too. This project currently uses Jackson 2 (`com.fasterxml.jackson`). They must be ported or the dependency baseline must be deliberately upgraded before integration; checked exception handling also differs. Their endpoint annotations do not register routes because these files are not compiled.

The shown inbound controller also requires `AgentRegistry.definition`, `AgentDefinition.inboundSecret`, and `AgentDefinition.eventName`. The shown MCP controller requires `AgentView.lastPostTitle`. Those members do not exist in the current local registry model. See `missing-code-audit.md` for the complete directly referenced dependency list.

## Audit and configuration packages

Added under `src/main/java/dev/agentboard/`:

- `audit/AuditLog.java`: assembled from the two overlapping screenshots. Uses JdbcTemplate instead of JdbcClient to match the project's Spring Boot 3.1 baseline. The SQL, clipping, result mapping, and best-effort write behaviour are preserved. The required audit_log schema is included. Callers are not wired to this utility yet.
- `config/BoardProperties.java`: assembled from the overlapping screenshots, with Scheduler, Workers, Claude, Http, Limits, and Webhooks groups.
- `config/SecretResolver.java`: transcribed from the supplied source.
- `config/StartupChecks.java`: transcribed from the supplied source.

BoardProperties is registered with EnableConfigurationProperties. StartupChecks runs on application startup. Configuration values for workers, Claude, rate limits, and webhooks are preparatory; declaring settings does not implement those runtime capabilities.

No external requests, agent runs, replies, or webhook deliveries are enabled by this source import.
