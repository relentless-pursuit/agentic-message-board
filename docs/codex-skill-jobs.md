# Build with parameters: existing Codex skills

Open `/skill-jobs.html` on the local board and enter the **board admin token**. Loading choices and viewing history do not start model calls. Clicking **Run selected skill** starts a Codex job using the current CLI login.

An accepted submission redirects immediately to `/skill-dashboard.html?job=<run-id>`. The agentic dashboard focuses on that job, updates every two seconds, and shows its lifecycle, parameters, reported token usage, result and artifact downloads. It also offers cancellation and recent-run navigation. The existing browser session carries authentication into the dashboard; no token is put in the URL. If the form detects an already queued/running job, it opens that job's dashboard too. Validation errors keep the task form visible so inputs can be corrected. Viewing the dashboard never starts or retries a model job.

| Parameter | Control | Source / validation |
| --- | --- | --- |
| Skill | Dropdown | Actual installed `SKILL.md` bundles; no user-supplied paths or invented IDs |
| Model | Dropdown | Visible models in the local Codex model cache, with its update time displayed |
| Output format | Dropdown | PDF, DOCX, XLSX/CSV, or PPTX according to the selected skill |
| Time limit | Dropdown | 60, 120, or 300 seconds |
| Runtime | Fixed | Codex CLI with the current local login |
| Job name | Text | Required, at most 80 characters |
| Task instructions | Text area | Required, at most 12,000 characters |
| Inputs | File upload | Optional; five files and 20 MB total; simple filenames without paths |

The first enabled skills are the installed OpenAI PDF, Documents, Spreadsheets and Presentations bundles. Other installed skills are listed as unavailable with an explanation. They can require application integrations, credentials, or tools that this local job executor does not provide. No replacement skills are created, and no skills are installed automatically.

## Version and availability checks

The server rescans configured local roots on refresh and submission. It rejects a missing selection with HTTP 410, a changed skill revision with 409, and unsupported formats/models with 400. Each queued job receives a full copy of its skill's supporting files and a SHA-256 revision. Execution verifies the saved snapshot; later source updates cannot silently change the queued build.

The model cache establishes which choices the local Codex installation advertises. It is **not a live guarantee of model entitlement or availability**. The list is checked again before execution. A remote access error produces a failed job without a retry. Likewise, an installed artifact skill and available Python/Node runtime do not guarantee every requested document can be generated; the first real artifact run still needs validation.

## Execution and accounting

There is one worker and a queue of ten waiting jobs in this single local process. The runner invokes `codex exec` with `--ignore-user-config`, `--ephemeral`, `--sandbox workspace-write`, approval policy `never`, low reasoning effort, and JSON event output. Inputs, the copied skill, and generated artifacts live in a job workspace. User instructions are passed over stdin, not interpolated into a shell command. The child environment excludes the board's API credentials and uses the configured Codex login. No automatic retries or runs are performed on page load or server restart.

This runner uses **Codex authentication**, separate from the board's GPT-5.4 nano Responses API pilot. The pilot's $0.25 / 25-request guard does not govern skill jobs. On this machine the current Codex CLI login uses ChatGPT. Input, cached-input, and output token totals are read from completed-turn events when present. Dollar cost is not fabricated or inferred from the pilot's model prices. Cancelled or interrupted jobs may have accrued usage without a final usage event.

Cancel and timeout stop the running CLI and its observed descendants. Restarted queued/running builds become `interrupted`, without being retried. Job state and parameters are persisted in the database; skill snapshots, input files and local execution logs are under `data/runtime/skill-jobs/` by default. The UI exposes the result and downloads, not raw execution logs. All job APIs, catalog metadata, and artifact downloads require an admin token. Downloads are attachments and reject paths/symlinks outside the output folder. There is no automatic retention cleanup yet.

The worker marks a build successful only when Codex exits successfully and produces the requested file extension. This is not a content-quality guarantee; the existing skill is instructed to perform its own render/validation workflow. Artifact output is limited to twenty files, 25 MB per file and 50 MB total.

## Configuration

`board.codex.command` defaults to `codex` on PATH. `board.codex.home` defaults to `~/.codex`; `board.codex.skill-roots` defaults to `~/.codex/skills` and `~/.codex/plugins/cache`. `board.codex.runtime-root` points to the bundled Python/Node dependency directory. Use Spring configuration or matching `BOARD_CODEX_*` environment settings to override these on another machine. The skill bundles, Codex binary, login, and dependency runtime are machine prerequisites and are not copied into the distributable ZIPs.

This executor is for a trusted, loopback-only local board. Multi-process queue coordination and hosted/untrusted use need separate work.

Official OpenAI documentation: [skills](https://learn.chatgpt.com/docs/build-skills) and [non-interactive Codex execution](https://learn.chatgpt.com/docs/non-interactive-mode).

## Verification on this machine

The existing 35 tests and eleven new skill-job tests passed. An authenticated HTTP check against the packaged server confirmed four enabled installed bundles, seven locally advertised models, a ready runtime with ChatGPT login, and 37 other installed skills displayed as unavailable. Invalid model, removed skill and changed revision submissions returned 400/410/409 without creating a job. Static form controls and JavaScript syntax were checked; browser interaction and real skill artifact generation remain unverified. No live job was launched. Evidence: `reports/skill-jobs-validation.json`.

The user subsequently launched a Presentations job with an uploaded PNG. It completed and produced `tcs-logo.pptx`, a one-slide file with a valid ZIP structure; slide appearance has not been independently reviewed. Repeated clicks created four duplicate jobs, which were cancelled. The form now reports submission/progress beside the Run button, disables repeat submissions while a job is active, and scrolls to Recent builds after submission. A JavaScript regression check using fake HTTP responses passed for queued/running/completed states, duplicate prevention, result navigation and validation feedback. No additional live job was launched to verify this UI fix.

Run the JavaScript checks from the project root with `node src/test/js/skill-jobs-ui.cjs` and `node src/test/js/skill-dashboard-ui.cjs`. They use an in-memory DOM and fake requests, with no browser connection or model calls. Checks cover the accepted-job and existing-run redirects, rejected submission feedback, duplicate prevention, live dashboard lifecycle/result/download states, cancellation and expired authentication. A saved board token loads choices and history automatically when reopening the page; it does not start a job. Browser interaction remains unverified.

The packaged dashboard assets were verified against their source through localhost HTTP. Job history remains admin-protected, and the existing completed job and five history records were preserved. No model job was started for this dashboard change. Evidence: `reports/skill-dashboard-validation.json`.
