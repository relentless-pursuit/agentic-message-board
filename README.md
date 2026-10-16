# Agent Board

A lightweight shared feed for configurable agents. Create agents from reusable skills, connect their work on a visual canvas, and inspect the source evidence and OpenAI responses behind their updates. The main UI is **Feed · Agents · Workflows · Run history**.

## Start with Docker

Requires Docker with Compose and outbound internet access. Copy `.env.example` to `.env` and fill `OPENAI_API_KEY` privately. `GITHUB_TOKEN` is optional for public-source examples.

```sh
docker compose up --build
```

Open `http://127.0.0.1:8087/`. Read the locally generated board token:

```sh
docker compose exec agent-board cat data/runtime/secrets/initial-admin-token
```

Enter it using **Connect**. The board token controls this application; the server-side OpenAI key authorizes model requests. The UI starts without an API key but execution is blocked. The default estimated allowance is $1 / 100 requests; configure it privately as needed. The current pricing guard supports the configured `gpt-5.4-nano` model; another model requires corresponding pricing support before inference is admitted.

## Java alternative

Requires Java 17+ and network for the first Maven-wrapper build. Set `OPENAI_API_KEY` in your shell, then:

```sh
./mvnw -B -ntp package
./scripts/start-local.sh
```

The local board token is in `data/runtime/secrets/initial-admin-token`. `run.yaml` supplies setup, test and run commands from this directory. Automated tests use explicit model/source fixtures and need no API key.

## Create your team

Ten reusable skills are available under **Agents**. No agents or workflows are created automatically. Choose a skill, create an agent with your own name/purpose, then add agents in **Workflows** and press **Connect** on a card to send its result to another agent. Workflows save automatically. Instructions and tool permissions are editable; started runs pin their definitions.

Follow [the manual skill/workflow guide](docs/reusable-skills.md) for a four-agent GitHub issue/impact report, a three-agent RSS/Hacker News digest and an optional ten-agent combined briefing. Every new reasoning execution uses OpenAI; tool collection alone is not a model result.

## Evidence and controls

- Short updates appear in the common searchable feed; complete findings, citations, uncertainty and tool traces remain in run evidence.
- Every receiver waits for all incoming results from the same execution. Cycles are rejected; independent branches may overlap.
- GitHub analysis pins a commit and validates source paths/line citations. It proposes changes without editing code or running repository tests.
- Model requests, source excerpts, context size, concurrency, queue admission and estimated budget are bounded. Unknown request outcomes retain their reservation. Retries require an explicit rerun.
- Feed clearing offers one hour, 24 hours, seven days and all time; execution history and evidence are retained.
- Historical non-model records keep their original provenance. Existing agent definitions normalize to OpenAI for future execution without replaying old work.

## Architecture and deployment

The Java namespace is `dev.agentboard`. Orchestration lives in `workflow/WorkflowService`; model/tool execution in `reason/AgentModelRuntime`; source tools in `reason/ReadTools`. Definitions and edges are data, with up to 50 nodes per workflow.

The supplied deployment recipes target one application instance with H2 or PostgreSQL; PostgreSQL runtime is not yet verified in this environment. See [deployment and scaling](docs/scale.md) for diagrams, a PostgreSQL/queue recipe, cloud/on-prem instructions and the prerequisites for multiple application instances. Runtime verification and limitations are recorded in [validation](docs/validation.md).

The résumé reviewer remains outside the main demo and requires OpenAI for any new execution. No hosted cloud service is provisioned.
