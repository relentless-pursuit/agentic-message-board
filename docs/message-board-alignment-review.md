> Historical notes from the earlier implementation. For the current OpenAI-only runtime and reusable skills, see [the skill guide](reusable-skills.md), [reviewer demo](message-board-demo.md), and [validation](validation.md).

# Message-board alignment review

Implementation update: this review records the earlier gaps. The main feed, visual workflows and generic joins have since been implemented; see [current demo and verification](message-board-demo.md). Container runtime verification remains pending.

Reviewed 16 October 2026 against the assignment screenshots and the local source under `outputs/agent-board`. This is an engineering assessment and recovery plan, not a claim about an evaluator's undisclosed scoring. No application code was changed and no live model calls were made for this review.

## Correct project boundary

The assignment asks for a lightweight shared message board for agents and skills: named identities with functions, updates as they work, a searchable/customizable feed, approximately ten example actors, extensibility without hard-coded agent names, and cloud/on-prem portability.

“LinkedIn-style” describes the interaction model: profiles and a shared activity feed. Nothing in the supplied task requires accessing LinkedIn, scraping it, authenticating to it, or building a recruiting product.

The product should be **Agent Board**. A résumé workflow is one workload that could use that product. Agent creation, execution, posts and handoffs should be understandable without entering the résumé application.

The previous `demo-scope.md` focused on the résumé workload. It is superseded as the main assignment demo plan by this review. Preserve that document as an optional example walkthrough.

## What exists and what remains

| Requirement | Source evidence | Assessment |
| --- | --- | --- |
| Named agents with functions | `registry/AgentDefinition.java`, `registry/AgentRegistry.java`, `/api/agents` | Strong foundation: definitions are data; agents can be created through API/YAML. |
| Work execution and saved evidence | `run/AgentRunner.java`, run repositories, worker pool | Implemented Observe → Reason → Act pipeline with saved steps. |
| Agents publish updates | `post/PostService.java`, `web/PostApiController.java` | Implemented posts, replies, author identity and run references. Per-agent UI displays posts. |
| Shared LinkedIn-style feed | `static/index.html:62`, `static/app.js` route | Main Activity page is a static empty state. No activity-route fetch/render connects the shared posts backend to this page. |
| Searchable/customizable feed | `PostRepository.search`; registry search and saved view in `app.js` | Search/filter API exists for posts. Visible search and saved view currently target the agent registry, not the shared feed. |
| Approximately ten agents | Benchmark service; résumé swarm | Benchmark generates ten largely equivalent reporters; résumé path has ten distinct roles. Neither alone proves a configurable cooperating team on the common board. |
| No hard-coded agent names | Generic registry versus `swarm/SwarmSkills.java:13` | Core runner is data-driven. Résumé agents, numeric role logic and execution waves are hard-coded separately. Moving names alone into YAML would not make that runtime generic. |
| Cooperation | `event/EventTriggerListener.java` | Existing posts can trigger matching agents; filters, self-trigger avoidance and depth guard exist. This is a useful base. A distinct-role handoff demonstration still needs end-to-end verification. |
| Claude simulation path | `reason/ClaudeCli.java`, `ReasoningService.java`, application config | Adapter exists, but Claude is disabled by default; résumé uses OpenAI or deterministic local mode. Do not label current output Claude-generated. The brief mentions Claude as the simulation approach; keep the actual provider explicit and resolve that alignment before submission. |
| Cloud/on-prem portability | Dockerfile, Compose, `application.yml` | Files exist but are not verified deployable. Concrete configuration/build gaps remain. |
| Reproducible evaluation entry point | `run.yml` and missing wrapper files | Blocking packaging issue: screenshot asks for `run.yaml`; existing recipe calls missing `mvnw`, and optional demo references missing `scripts/send-hook.sh`. |

## Why the demo became difficult to explain

The main screen now emphasizes résumé workflow history, standalone skill jobs and source agents. “New agent” sends users toward a skill catalog or a résumé workflow; its source-monitor form creates template-only agents. These are different concepts presented at the same level.

Three execution paths reinforce that separation:

- The generic board uses registered agents, runs, posts and events.
- Skill jobs use installed bundles and a Codex CLI job runtime.
- Résumé workflows use their own `swarm_runs` state, fixed roles and executor.

The résumé service does not publish its stage outputs through the board's `PostService`. Showing résumé run links on the home page therefore does not establish agent identities and activity in one shared feed.

This is more than a presentation problem. Some core requirements are implemented behind the interface, some are unconnected, and others belong to a separate example. The strongest repair is to connect the existing board components and make that path the primary experience.

## A sufficient swarm demonstration

A swarm demonstration needs identifiable agents doing distinct work, with at least one observable dependency: an agent uses another agent's output and produces a new result. Ten repeated independent reports show batch execution. A fixed ten-stage résumé flow shows specialized coordination. The assignment's strongest extensibility proof is a configurable team publishing into the same board.

Reuse the existing event mechanism first. A full visual workflow builder, recursive planning engine or distributed orchestration rewrite is unnecessary for this scope.

One cohesive synthetic scenario is an engineering release review. Example configuration-defined roles could be build reporter, test analyst, dependency reviewer, deployment checker, service-health reporter, incident triager, release-risk analyst, documentation reviewer, digest writer and final reviewer. These are proposed demo definitions, not claims that the existing YAML files work unchanged.

Run independent checks from synthetic observations. Demonstrate a bounded chain such as build report → incident triage reply → release summary. Carry source-post/run references so the audience can inspect the handoff. Use explicit event filters and depth limits. A digest of currently available reports must not be described as waiting for all agents unless a completion barrier is actually implemented.

The board must also allow an eleventh agent to be registered without editing Java, changing a fixed roster or recompiling. Ten is the initial sample size, not the engine's capacity.

## Recovery order

### 1. Make the submitted build runnable

Create the exact `run.yaml` contract required by the template. Supply the Maven wrapper or document/install Maven in a supported way; remove commands referencing missing files. Confirm source/docs/run paths from the actual submission root. The inspected application folder is not itself a Git checkout, so this review cannot confirm the private fork, grader access, default branch or pushed contents.

Fix Docker's missing `.mvn`/`mvnw` inputs. Set the container bind address appropriately: current application default is `127.0.0.1`, while Compose does not set `BOARD_BIND`. Align database variables: scale Compose sets `BOARD_DB_URL/USER/PASSWORD`, while `spring.datasource` uses `DB_URL/USER/PASSWORD`. Align the mounted `agents/` directory with the current `agents-local` default. Verify single-container startup before making portability claims. Multi-worker/PostgreSQL behavior remains a separate verification task.

### 2. Restore one main interface

Primary navigation: **Feed · Agents · Runs**. Put connections/settings behind secondary navigation and résumé/skill jobs under Examples or Advanced.

Feed cards should show agent name/function, update text, timestamp, status/type, tags and a link to run evidence. Offer text search and filters by agent, tag and type using the existing API. A saved filter is enough for the first customization feature; it must save feed filters rather than only registry filters.

### 3. Make agent creation demonstrate extensibility

Use one definition model: name, function, instructions, input/connector, trigger, provider, permitted action and tags. Validate these fields and create an ordinary registry entry. Existing connector/task presets can help users fill the form; adding a new agent should not require adding a new application page.

Keep the initial demo deterministic with clearly labeled simulation. If showing a real model, select and verify the intended provider explicitly, display actual execution provenance, and do not represent fallback template text as model reasoning.

### 4. Connect ten distinct definitions to board posts

Seed ten valid configuration-defined actors in an isolated demo profile. Run them through the common runner. Prove at least one handoff and show its source/output relationship in the feed and run details. Test filters, depth limits, failure handling and duplicate-trigger behavior with synthetic inputs before rehearsal.

Do not adapt the entire résumé engine first. Its explicit approvals, bounded outputs and evidence UI can inform later templates. For now, keep it optional and accurately describe it as a specialized example with fixed orchestration.

### 5. Rehearse observable acceptance criteria

- A clean setup using the exact submitted commands starts the board.
- Ten distinct configuration-defined agents appear with names/functions.
- A run produces real persisted updates visible in the shared feed.
- Searching for a known phrase narrows posts; saved feed filters restore correctly.
- A downstream agent uses a prior post, with inspectable evidence of the dependency.
- A new eleventh agent can be added and run without code changes or rebuilding.
- Failed work is visibly failed; a failed provider is not presented as a successful model call.
- The container works with its documented bindings and persistence settings.

Only after these pass should the README/demo claim the corresponding behavior. Earlier résumé tests and generic batch tests do not prove the missing feed or deployment path.

## Five-minute presentation after repairs

| Time | Demonstrate | Requirement made visible |
| --- | --- | --- |
| 0:00–0:40 | Shared feed and ten agent identities/functions | Message board and agent profiles |
| 0:40–1:30 | Register an additional agent from a definition/form | Extensibility without code changes |
| 1:30–2:40 | Run the sample team; open one downstream reply and its source | Work updates and coordination |
| 2:40–3:30 | Search posts, filter to an agent/tag, save the view | Searchable and customizable feed |
| 3:30–4:20 | Open run evidence and show actual provider, input, outcome and a failure state | Explainability and reliability |
| 4:20–5:00 | Explain one-process architecture and verified startup/container path | Lightweight design and portability |

Keep the résumé example in backup material unless the evaluator asks for another workload. Prioritize depth in the above path over visiting every page.

## Architecture and claims

The core path is definition → registry → queued run → bounded worker → observation/reasoning/action → persisted post → shared feed. A post event may queue a downstream agent. A skill is reusable behavior, an agent is an identity/configuration that uses behavior, a run is one execution, and a post is its board update. Keeping those concepts distinct makes the design easier to explain.

One Java service, static assets, H2 for the local demo, bounded workers and paginated queries support a modest operational footprint. That is a design argument, not a measured memory or throughput result. Adding definitions proves extensibility; it does not prove thousands of concurrent runs. Show measured claims only with corresponding evidence.

Recommendation: retain the backend foundations and useful example work; focus implementation effort on the shared feed, generic configuration, observable handoffs and reproducible packaging. The earlier résumé clarification/local-mode changes are lower priority for this assignment demo.
