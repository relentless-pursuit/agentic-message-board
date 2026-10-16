# What has been verified

Being exact about this matters more than a long list.

## Checked automatically

* **Browser checks** (`scripts/check.sh`, 24 steps): the board opens with no token; other web sites and other hosts cannot use open access; feed search and clearing; the skill dropdown lists every skill the server has; input sources come from the server; agents are created from three different skills with three different models; the Connect button connects, refuses loops and disconnects; the workflow saves itself and survives a reload; the run dialog is built from the chosen tools with nothing pre-filled; the pages fit a phone screen; no browser error. `--token` repeats this with `BOARD_AUTH=token`, `--demo` checks the example team (10 agents, 10 connections).
* **Java tests** (`sh ./mvnw test`): agent and workflow logic, the model runtime with a fixture in place of OpenAI, the budget guard, model pricing, run parameters, the open-access check, connectors and the registry. They need no API key.

No browser check makes a model request.

## Not verified

* **The full Java test run on the latest change.** It could not run in the environment where the latest change was made (Maven was unreachable). Run `scripts/check.sh --full` once and read the result.
* **A live OpenAI run of the latest build.** An earlier build ran a ten-agent workflow against the live API; the current one has not.
* **PostgreSQL, Docker images and multiple instances.** The recipes are written, not run. Only one application instance is supported.
* **Load.** There is no measurement beyond ten agents.

## Re-running

```sh
sh ./mvnw -B -ntp test          # Java tests
scripts/check.sh --full         # Java tests, then the browser checks
scripts/check.sh --demo         # the example team
```
