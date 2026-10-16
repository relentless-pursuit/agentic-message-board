# Validation record — 16 October 2026

## Automated checks

A clean Java build and subsequent focused regressions passed **101 tests**, with **0 failures, 0 errors and 0 skips**. These tests use explicit model/source fixtures and incur no API usage. They cover authentication, graph validation and joins, model-only execution, removed providers, missing credentials, output parsing/phases, malformed results, context limits, source boundaries, citation validation, tool/request caps, budget accounting, résumé approval and historical provider migration.

Browser checks passed reusable-skill creation/editing, manual agent creation, canvas connections/handoff inspection, the OpenAI-only run dialog, no synthetic input defaults, a 390px layout and absence of JavaScript errors. Evidence: `reports/openai-automated-validation.json`, `reports/openai-ui-summary.json`.

## Live OpenAI acceptance

All requested examples completed against real OpenAI and public sources:

| Workflow | Agents | Model requests in successful run | Result |
|---|---:|---:|---|
| GitHub impact report | 4 | 8 | Completed; joins verified |
| Technology digest | 3 | 5 | Completed; joins verified |
| Ten-agent engineering briefing | 10 | 19 | Completed; joins verified |

The news sources and independent branches of the ten-agent graph overlapped. Every successful agent has recorded response IDs, source evidence and a detailed report. The GitHub analysis inspected pinned `src/requests/models.py`, described the reported multipart field-name failure and proposed `.prepare()` regression tests. It did not edit code or run repository tests. The ten-agent evidence reviewer and editor retained uncertainty about dependency internals and excerpted context.

Live acceptance used **$0.071823 estimated API usage** and **$0.844893 additional conservative reservations**, below the authorized $1 reservation allowance. These figures include failed acceptance attempts. Failures exposed output limits, intermediate/final message parsing, invalid citations and an oversized join; they remained recorded rather than being replaced with simulated success. The final implementation constrains report size, selects final structured messages, restricts generated evidence IDs/ranges and records explicit larger-join excerpts.

Evidence: `reports/openai-live-summary.json` and the detailed `reports/openai-live-validation.json`. No real user résumé, email inbox or authenticated Jack & Jill account was used.

## Migration and persistence

The application was restarted repeatedly against persistent H2 during acceptance. An offline comparison verified **63 original rows** across runs, posts, usage, workflow executions and saved definitions. Original rows were unchanged; the existing usage ledger was extended with all live requests. The administration token was retained.

The delivery installs **reusable skills only**. All 22 disposable verification actors were retired, their bindings removed, 9 test workflow definitions removed, and their posts archived. Detailed reports and historical execution evidence remain. The original four saved workflows and original visible activity were retained. Evidence: `reports/openai-migration-validation.json`.

Existing agent files normalize legacy providers to OpenAI for future execution without replay. New API definitions reject removed provider selections. The budget ledger is cumulative: existing attempts and reservations consume the current allowance. To authorize more future work, explicitly adjust `BOARD_OPENAI_BUDGET_USD` and `BOARD_OPENAI_MAX_CALLS` and restart; clearing the feed does not reset billing records.

## Deployment verification and limits

Both Compose recipes pass `docker compose config --quiet`. The installed Docker engine did not respond within the bounded check. A native PostgreSQL 14 initialization attempt also failed because its ICU 74 shared library is missing. Consequently **container startup, container health/persistence and PostgreSQL runtime remain unverified** in this environment. No Docker images, databases or hosted infrastructure are claimed to have run successfully.

The Java/H2 application build, startup and persisted state are verified. PostgreSQL configuration uses the application's actual datasource names, one coordinator, the database queue, health-ordered startup and explicit generated-key columns. Multiple application instances remain unsupported pending the changes described in `docs/scale.md`.
