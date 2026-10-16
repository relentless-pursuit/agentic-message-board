> Historical notes from the earlier implementation. For the current OpenAI-only runtime and reusable skills, see [the skill guide](reusable-skills.md), [reviewer demo](message-board-demo.md), and [validation](validation.md).

# Monitoring inputs and skill inputs

## Simple agent creation

The main form asks for a task, its source and a run frequency. The default is **Manual**. Name and ID are generated from the selected task/source; optional name, owner and tags are under Advanced settings. Numeric source tuning is collapsed under Source options. Tags only help organize/search agents and do not filter GitHub issues. Repository fields also accept a GitHub repository URL, which the form converts to `owner/repository`.

Creating an agent saves its definition and opens its dashboard; it does not start a run. Click Run there only when ready. New form agents store `reasoningProvider: template`, so listing tasks do not invoke an LLM, even if the global provider is OpenAI. Older definitions without this optional field retain their configured global provider. No chat model settings or skill-run model choices are changed by this behavior.

An empty trigger `{}` means manual-only and has no next scheduled time. Non-manual frequency choices are disabled while the local scheduler is disabled. Event-driven tasks explicitly show their supported event dropdown instead of a frequency.

**List GitHub issues** reads open issues, excludes pull requests, and lists issue numbers, titles and links. The default is ten issues, with an optional limit of 1–25. Reading is bounded to five API pages, and a page-limit message reports an incomplete scan. Plain reports are bounded by the board's post size limit and state if items were omitted. Private repositories require server-side GITHUB_TOKEN. No repository access was tested against a live GitHub service during implementation; fixture tests use a mocked HTTP client.

The user's selected chat model is for code changes. Application model runs are not used for verification unless the user subsequently asks to test them. Fixture checks here do not call OpenAI, Claude or Codex models.

The simplification passed 27 targeted Java fixture checks and the form fixture check. A packaged localhost read-only check confirmed the new form and GitHub-issues choice. The OpenAI API attempt count stayed at one, and skill-job history stayed at five; neither ledger gained a model run. No live repository fetch was performed. Evidence: `reports/simple-agent-validation.json`. Browser interaction and live repository access remain to be checked by the user when ready.

The monitoring-agent registration form now loads a real dropdown from `GET /api/connectors`. Its choices come from the runtime ConnectorRegistry, independently of registered agents. The Connections page uses the same catalog, so an empty board still displays its available connector implementations. New plugins without a form schema are listed but disabled in the registration dropdown rather than accepting guessed configuration.

| Connector | Input source | Field saved in the definition |
| --- | --- | --- |
| GitHub issues | `owner/repository` or its GitHub repository URL | `repo` string; optional `limit` |
| HTTP health check | One HTTPS website/endpoint URL per line | `urls` list |
| GitHub pull requests | `owner/repository` | `repo` string |
| GitHub releases | One `owner/repository` per line | `repos` list |
| RSS / Atom | One HTTPS feed URL per line | `feeds` list |
| Hacker News | Fixed Hacker News top-stories source; choose story count | `top` number |
| Agent Board posts | This board's posts; choose a look-back duration | `since` duration |
| Board events | A selected supported board event, with the payload supplied by that event | Empty options; `trigger.on_event` |

The form shows connector-specific numeric settings with their defaults and limits. Event sources use an event dropdown instead of an interval. The obsolete shared `target` field and unsupported `github_actions` placeholder have been removed. Output for this form is a post on the Agent Board; it does not need an output address or file path. The former audience field was removed because the post action did not use it.

Server-side registration validates current connector availability, known option keys, required sources, repository syntax, numeric limits, duration syntax and HTTPS source hosts before saving YAML. Host validation uses the same SafeHttp allow-list as execution, without fetching the source. This does not confirm repository existence, credentials, feed contents or service health; those are checked when the connector runs. GitHub private repositories require server-side GITHUB_TOKEN. Scheduling remains disabled in the local profile; timed monitors can be tested using Run now on the agent detail page.

The simple event dropdown offers `post.created` and `agent.registered`. Existing signed `hook.received` configurations remain supported through API/YAML with their inbound-secret settings.

GitHub API reference: [list repository issues](https://docs.github.com/en/rest/issues/issues#list-repository-issues). The API can return pull requests alongside issues; this connector filters them using the documented `pull_request` field.

Creating a PowerPoint, PDF, Word document or spreadsheet uses **Build with parameters**, not this monitoring form. Choose the installed skill, upload the source image/document, and describe the deliverable. Skill output is a downloadable file on the Agentic dashboard. A prominent link in the monitoring form leads to that workflow.

The Java catalog and registration checks use local validation only; the JavaScript form check uses an in-memory DOM. No connector network fetch or model call is needed to verify this change. Run the form check with `node src/test/js/connector-form-ui.cjs`.

The targeted fifteen Java tests and the form regression passed. Packaged localhost checks confirmed seven connector choices on a board with zero agents, correct source keys, rejected unknown connectors and rejected legacy `target` options. No agent was created for those checks. Evidence: `reports/connector-form-validation.json`. Browser interaction remains unverified.
