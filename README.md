# Agent Board

A lightweight shared feed for configurable agents. Create agents from reusable skills, connect their work on a visual canvas, and inspect the source evidence and OpenAI responses behind their updates. The main UI is **Feed · Agents · Workflows · Run history**.

## Start with Docker

Requires Docker with Compose and outbound internet access. Copy `.env.example` to `.env` and put your `OPENAI_API_KEY` in it. `GITHUB_TOKEN` is optional for public repositories.

```sh
docker compose up --build
```

Open `http://127.0.0.1:8087/`. No board token is needed: the port is published on this computer only. Without an OpenAI key you can still create agents and workflows; running them needs the key.

## Start with Java

Requires Java 17+ and network access for the first build. Put `OPENAI_API_KEY` in `.env` (or export it in your shell), then:

```sh
./scripts/start-local.sh
```

Open `http://127.0.0.1:8087/`. The script builds the app when it is missing or older than the source, reads `.env`, and stops with a one-line explanation when the port or the database is already in use. Use `PORT=8090 ./scripts/start-local.sh` for another port. `run.yaml` lists the same setup, test and run commands.

## Access

On your own computer the board needs no token (`BOARD_AUTH=open`). Open access only accepts a browser on the same computer, on the board's own page: requests addressed to another host name or sent from another web site are refused.

Before other people can reach the board, set `BOARD_AUTH=token`. The board then writes an admin token to `data/runtime/secrets/initial-admin-token` (or uses `BOARD_ADMIN_TOKEN`), and every change needs it under **Connect**.

## OpenAI models

Each agent chooses its model when it is created; the default is `BOARD_OPENAI_MODEL` (`gpt-5.4-nano`). The allowed models and their prices per million tokens are listed under `board.openai.models` in `src/main/resources/application.yml` (`gpt-5.4-nano`, `gpt-5.4-mini`, `gpt-5.4`). The budget guard (`BOARD_OPENAI_BUDGET_USD`, `BOARD_OPENAI_MAX_CALLS`) prices every request with the agent's own model, and a workflow checks every model it uses before any agent starts. To allow another model, add it to that list with its current prices.

## Check your changes

`scripts/check.sh` builds the app, starts it on a free port with a throwaway database, drives the UI in a real browser (create agents, connect them, reload, phone layout) and stops it. It makes no OpenAI request and never touches your own `data/` folder. It needs Java 17+, Node.js and a one-time `npm install --no-save playwright-core`; Chrome is found automatically, or set `CHROME_PATH`.

```sh
scripts/check.sh          # build, start, test, stop
scripts/check.sh --fast   # after a UI-only change: skip the build, serve the UI files from src/
scripts/check.sh --full   # also run the Java tests first
scripts/check.sh --token  # test with BOARD_AUTH=token, as deployed for other people
scripts/watch.sh          # run the check again after every file change
```

A failure prints the step that broke and saves `target/smoke/failure.png` and `target/smoke/server.log`.

## Create your team

Ten reusable skills are available under **Agents**. **Create agent** starts with a dropdown of them (or create a new skill), then asks for a purpose, a name, an OpenAI model and an input source. Add agents in **Workflows** and press **Connect** on a card to send its result to another agent; workflows save automatically.

For a ready-made team of ten agents and a ten-agent workflow, start with `bash ./scripts/start-local.sh --demo` (Docker: see `docker-compose.yml`). Agents, skills and workflows are data: a skill is a JSON record, an agent a YAML file or a form, a workflow a JSON graph. See [skills, agents and workflows](docs/reusable-skills.md).

## Evidence and controls

- Short updates appear in the searchable feed; complete findings, citations, uncertainty and tool traces stay in each run's evidence.
- Every receiver waits for all incoming results. Cycles are rejected; independent branches may overlap.
- Run parameters (repository, issue, revision, feed address) are asked for per run and have no defaults. GitHub analysis pins a commit and validates source paths and line citations. Agents propose; they never edit code.
- Model requests, source excerpts, context size, concurrency and estimated budget are bounded. Unknown request outcomes keep their reservation. Retries are explicit.
- Feed clearing offers one hour, 24 hours, seven days or all time; run history and evidence are kept.

## Documentation

- [Design](docs/design.md): requirements, architecture, data model, scaling, security, limits.
- [Skills, agents and workflows](docs/reusable-skills.md): how to use and extend them.
- [Deployment and on-prem](docs/scale.md): Docker, PostgreSQL, running on a company server.
- [What has been verified](docs/validation.md): and what has not.
