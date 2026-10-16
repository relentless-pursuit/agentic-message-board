# Local integration status — 16 October 2026

The scope is now a ten-agent local pilot using inexpensive OpenAI reasoning. The earlier source-collection audit is superseded by this integration status. Tests and implementation were authorized when the user asked to start testing; earlier import-only instructions no longer govern this phase.

## Resolved for local testing

- Removed the eleven synthetic Pilot Agent records from the active local registry and disabled their scheduling. The synthetic benchmark is now disabled in the local profile, and the normal default agent directory is the empty `agents-local` folder. The board no longer advertises the pilot; model usage accounting remains available separately. Existing real skill-job history and its PowerPoint deliverable were retained.

- Added existing Codex skill jobs through a Jenkins-style parameter form: installed skill/model/format/time dropdowns, free-text task instructions and optional file upload. Full bundle snapshots reject changed or deleted choices; authenticated history, cancellation, token usage and artifact downloads are implemented. See `codex-skill-jobs.md`. These jobs use the current Codex login, separately from the API pilot budget; no live skill execution was started during implementation.

- Root POM aligned to supplied Spring Boot 4.1.1 / Java 17; Jackson 2 mapper usages migrated to Jackson 3. All 76 production Java files compile, with no compiler exclusions.
- Added missing registry definition/view/parse/register APIs, InvalidAgentException, scheduling/event repository queries and run/post metadata updates. AgentDefinition has inboundSecret/eventName; AgentView has lastPostTitle.
- Full supplied schema installed as the startup schema, with the local status field retained and model_usage/model_budget added. A fresh `data/runtime` database preserves the previous local registry database.
- Worker, runner, posting, token security, event listener and supplied controllers are compiled. Automatic scheduling remains off for the pilot.
- Added OpenAI Responses integration and a generated-observation ten-agent harness, authenticated endpoints, command-line script and browser pilot page.
- Added prompt/output bounds, no-tools requests, persistent cost/attempt reservations, token usage records, explicit OpenAI failure reporting and unchanged-observation suppression.
- Agent detail pages now show real run history and posts. The browser can supply a board admin token for writes.

## Verified

46 tests passed with zero failures/errors/skips. This includes ten generated agents completing 20 total runs: ten template-generated posts followed by ten unchanged skips, with zero model calls. A changed payload with the same event ID creates a new post. Separate checks cover authorization, model attempt limits, dollar reservations and cached/output token accounting. Eleven new skill-job tests use fake executors/processes and cover missing/changed skills, snapshots, choices, paths, artifact downloads, cancellation, usage parsing and timeout. Existing registry/helper tests remain in the suite; legacy registry tests bypass filters, while runtime authorization tests exercise real security filters.

JavaScript syntax checks passed. Browser interaction, OpenAI model access and actual model quality require separate evidence. Live-pilot status is saved in `reports/` when executed; a missing live report must not be treated as success.

The packaged server was also started on loopback port 8087 and exercised through authenticated HTTP endpoints. The ten-agent template pilot and unchanged repeat completed in 2.82 seconds with 20 successful runs, ten posts, zero API attempts and zero model tokens. Its report is `reports/pilot-template.json`.

The user then authorized one live agent and supplied a credential. That pilot made exactly one GPT-5.4 nano request and succeeded with engine=openai, saving alert post #11 from run #21. API-reported usage: 208 input tokens, zero cached input, 70 output tokens. Estimated cost: $0.0001291 using the documented rates. Run duration was 7,889 ms; client-observed elapsed time was 8.737 seconds. The conservative reservation was $0.0017928, which is a local budget hold rather than the estimated charge. The full result is `reports/pilot-openai-one.json`. This verifies one generated CI-summary example, not a ten-agent live run or broad model quality. No repeat or additional model request was started in that one-agent test.

## Live test prerequisites and limits

- A full key must exist in OPENAI_API_KEY or private `data/secrets/openai-api-key`; screenshots contain only partial keys and are not usable credentials.
- The default model is gpt-5.4-nano, reasoning effort none, 400 output tokens, and 3,000 observation characters. Small purpose/instruction limits bound the rest of the prompt.
- Maximum 25 request attempts, with a persistent $0.25 conservative reservation ceiling and two requests in flight. The current ten-agent test needs at most ten initial attempts; unchanged repeats should need none after successful initial runs.
- Usage-based costs are estimates from documented rates, not account billing receipts. Failed/unknown-outcome requests keep their reservations. Resetting the database resets the local ledger.
- The pilot uses generated data. It is not evidence that company integrations work, that ten thousand agents scale, or that API rate limits permit a larger workload.

## Still missing or outside the pilot

1. Original application.yml prefix, Maven wrapper files, original test Java/fixtures, original README and remaining original static UI files; these do not block the provided local workflow.
2. Full shell/PowerShell hook-demo JSON payloads. Cropped fragments remain references.
3. Review of reconstructed connector expressions. Seeded agents refer to absent github_actions/security_alerts connectors and use target where implementations expect urls/repo/repos/feeds. Their placeholder destinations also need replacement and allow-list alignment. The pilot uses an empty agents-local folder and generated event observations, so these seeds are not executed.
4. The supplied full AgentApiController/ApiErrors remain references. The active local API keeps YAML registration plus integrated manual-run/history endpoints; external registration/token minting has not replaced that contract.
5. Existing registry data needs a deliberate migration before reuse with the full schema. It has not been modified for this pilot.
6. Docker needs wrapper files, bind/database configuration alignment and registration-volume decisions. PostgreSQL, multiple processes, lease contention, recovery after crashes and sustained event fan-out remain unverified.
7. Claude CLI restrictions/authentication and MCP/outbound actions are compiled but not exercised against external services. No company service or outbound webhook is contacted by the ten-agent fixture.

The original source provenance and labelled reconstructions remain in source-import-status.md. Full-application production readiness is not claimed.
