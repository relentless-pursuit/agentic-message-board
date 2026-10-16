> Historical notes from the earlier implementation. For the current OpenAI-only runtime and reusable skills, see [the skill guide](reusable-skills.md), [reviewer demo](message-board-demo.md), and [validation](validation.md).

# Ten-agent résumé workflow

Open `/resume-swarm.html`, enter your board admin token, and load the fictional sample or supply a résumé and target job description. With no OpenAI key configured, the workflow runs through a deterministic local demo provider and makes no network model calls. If the server has an OpenAI key, it uses the configured OpenAI model and shared budget; **Start** is the action that begins those calls. Viewing pages, loading the sample, or importing a file does not call a model.

Run from this project directory:

```bash
mvn package
./scripts/start-local.sh
```

Open `http://127.0.0.1:8087/resume-swarm.html`. The generated board token is in `data/runtime/secrets/initial-admin-token`. Opening the HTML file directly displays its styling and a preview notice, but the workflow APIs require the running server.

## Visual reference and evidence boundary

REA passively inspected the already-open Jack & Jill documents page on 16 October 2026. The viewport showed a pale neutral canvas, a roughly 255 px sidebar, compact document rows with dividers, pill filters with blue selected states, and a floating rounded composer. The demo uses those visual patterns for its own controls and a bottom shortcut to the current workflow step. The screenshot evidence is `ev_51921240fa5416dea45e0cadd80cd984eae3537d702336170d4d9b6ed7eec52e`; the structural inspection is `ev_9f16f3544fb91d89c336afebda062921b496fcc2f1bbc0169909d35b1e8e352f`.

REA did not click, type, navigate, submit, open documents, or export document contents. Accessibility text, script sources, payload shapes, and storage keys/fingerprints were excluded from the structural inspection. This captured a single current UI state and does not establish historical transitions or Jack & Jill's backend agent architecture. The earlier user-supplied chat screenshot informs the motivation-question/review sequence; the demo's ten-agent implementation is this project's own design. No account data or personal document contents were copied into the demo.

This is a coordinated workflow with ten custom project skills. Their `SKILL.md` manifests are packaged under `src/main/resources/swarm/skills/`. They are not represented as OpenAI-published skills. The model receives each pinned manifest and only the relevant source material and dependency outputs. The existing published PDF/Documents/Spreadsheets/Presentations jobs remain a separate Codex CLI capability.

| Agent | Receives | Produces |
| --- | --- | --- |
| 1. Orchestrator | Résumé and target JD | Bounded run plan and evidence boundary |
| 2. CV/document extractor | Original résumé | Candidate facts and evidence |
| 3. Job requirements analyst | Target JD | Requirements and responsibilities |
| 4. CV fit and gap analyst | Agents 2 and 3 | Supported matches and gaps |
| 5. Resume tailoring agent | Original résumé, JD, agent 4 | Up to four exact, non-overlapping edits |
| 6. Evidence and claim verifier | Original résumé, agent 5 | Unsupported-claim warnings |
| 7. Consistency reviewer | JD, agent 5 | Contradictions and relevance review |
| 8. User-intent clarifier | Agents 4, 5 and 7 | One proposed motivation question |
| Human review | Proposed edits and question from agent 8 | User's answer plus accepted/rejected/custom edits |
| 9. Cover-letter drafter | Approved résumé, JD, motivation answer | Cover-letter draft |
| 10. Final reviewer | Approved résumé, JD, agents 6, 7 and 9 | Contradictions, unsupported claims and omissions |
| Final user approval | Verified draft and editable cover letter | Final résumé and cover letter as DOCX and TXT |

The graph runs in waves: **1+2+3 → 4 → 5 → 6+7 → 8 → user answer and résumé review → 9 → 10 → final approval**. At most two agents run concurrently. In OpenAI mode, each agent represents one Responses API call; there are no recursive loops, model tools or automatic retries. Local mode uses the same ten visible roles and handoffs with deterministic, offline outputs. Agent inputs, outputs, status and downstream contribution are visible on the dashboard.

## Approval and persistence

The original résumé is immutable. Proposed source passages must be unique, present and non-overlapping; unusable suggestions are displayed but cannot be selected. Select all, select individual edits, keep the original, rewrite a selected passage, or supply your complete edited résumé. The server applies selected replacements against original positions, preserving unselected text. Agent 8 proposes a question, and the workflow requires the user's answer plus résumé approval before agents 9 and 10 run. The cover-letter writer cannot modify the approved résumé. Final downloads require explicit final acceptance and preserve the accepted text.

The database stores workflow inputs, skill revisions and instruction snapshots, per-agent outputs/statuses, approval choices, and final documents. All workflow APIs, source imports and downloads require a board admin token. Restarted active workflows become `interrupted`; paused reviews persist and can continue. There is no automatic retry, resume or retention cleanup. Cancellation prevents new stages; already-sent model requests may still complete and accrue usage.

## Model and cost

With an OpenAI key, the demo uses the board's existing **gpt-5.4-nano** Responses integration with reasoning effort `none`. It shares the board's persistent API ledger, whose local defaults remain 25 attempts and $0.25. Each swarm additionally allows at most ten attempts and $0.10 in conservative reservations. A start preflight checks ten remaining attempts and $0.10 unreserved shared budget; individual atomic reservations enforce the limits during execution. Failed and uncertain calls retain their reservations. Rates currently used are $0.20 per million input tokens, $0.02 cached input, and $1.25 output. The dashboard displays usage, estimated dollars and reserved dollars separately. These estimates are not billing receipts. Without a key, the deterministic local provider runs all ten workflow roles without external calls or cost; its overlap analysis is a simple demo heuristic, not a model evaluation or hiring score.

Official source: [GPT-5.4 nano](https://developers.openai.com/api/docs/models/gpt-5.4-nano), checked 16 October 2026. That page marks the model deprecated; the existing board had a successful account request earlier that day, but this implementation does not claim continued access or validate it with a paid test. Changing the model requires updating its pricing guard and validating the response schema.

Inputs and output sizes are bounded. Agents 1 and 2 run from different source inputs; later agents receive selected dependency outputs instead of an ever-growing conversation transcript. Ten roles are useful for demonstrating coordination and human review. Fewer combined roles would often be more efficient for a production résumé task.

## Inputs and scope

Both the CV and target-role cards have visible **Paste text** and **Upload PDF** options. Choosing an uploaded PDF, DOCX or TXT automatically extracts text into the editable text area; the user reviews it before clicking Start. Switching input methods preserves the current text. Extraction shows progress and errors, and the workflow cannot start while an import is in progress. The **Add file** control opens the CV upload picker. File extraction runs locally and does not start a model call or apply CV edits.

The paste/upload controls were checked in the browser against the running local board using `src/test/resources/swarm/synthetic-cv.pdf`, with no workflow or model call started. The focused `ResumeSwarmTest,SwarmSourcesTest` run passes 12 tests with no skips, including actual PDF text extraction and invalid/oversized source rejection.

Paste text, upload text PDF/DOCX/TXT (up to 2 MB), or import a public document from supported Google Docs, raw GitHub, Greenhouse or Lever hosts. Imports are previewed before model calls. Links requiring login or redirects can be uploaded/pasted instead; scanned PDFs need OCR. Source hosts are fixed, HTTPS-only, without redirects or credential forwarding. The demo accepts a target JD; it does not conduct an open-ended job search or submit applications. Word exports use simple readable formatting rather than preserving the source résumé's original design.

## Validation

Checks use fake model completions and test databases. They cover two-agent concurrency, dependency order, eight-agent review pause, motivation-answer gating, ten-role completion, local no-key mode, partial/custom acceptance, duplicate submission, failure blocking, cancellation, document content, authenticated downloads and persistent caps. All ten active skill manifests define the visible roles. Real bundled parsers extracted text from synthetic TXT, DOCX, PDF and HTML fixtures.

The browser UI was exercised against an isolated local Spring Boot server with an in-memory database and no model credentials: sample loading, the eight-agent clarification pause, selecting a proposed edit, supplying motivation, both approval gates, ten agent cards, and four final download controls passed. The focused `ResumeSwarmTest` suite passes all ten tests, including a bundled-sample regression for extractor-to-fit handoff and drafting from approved experience instead of contact details. Synthetic test runs were not inserted into the user's live database. No live model request was started for implementation validation.

## Alignment with the original board

The shared board, configurable jobs, run evidence and model usage are aligned with the original direction. Plain GitHub monitors demonstrated connector plumbing, not model cooperation or token efficiency. This demo adds explicit agent handoffs, parallel stages and human approval. It is a single-process example; distributed execution, production swarm reliability and large-scale token/quality claims remain unvalidated.

## First live run: output-limit correction

The user-started workflow #1 stopped at the job-description analyst. The résumé reader completed with 460 output tokens; the two requests together used 1,360 output tokens, so the failed analyst consumed its full 900-token allowance. This strongly indicates output truncation, but the original implementation did not preserve the provider’s `incomplete_details.reason`, so that historical reason cannot be asserted as confirmed. The run’s estimated cost was $0.002112; its $0.0081124 reservation is a separate conservative accounting value.

Analysis schemas now accept only a bounded summary and six bounded evidence items. Only the editor returns edits (at most four), and only the cover-letter writer returns document text. The model gets compact-output guidance and the analyst’s allowance is now 1,600 tokens. Future incomplete responses preserve the exact provider reason and billed usage on the failed agent; partial JSON never enters a dependency. Offline regression checks cover output-limit and content-filter responses separately, plus the compact schemas and normal completion. No paid call was made to test this correction.

Failed workflows offer **Review these inputs for a fresh run**, which copies the saved inputs back to the setup form. Only the subsequent explicit **Start** begins new paid calls. The failed record, successful earlier agent output, usage and reservations remain in history.

Reference: [OpenAI Structured Outputs: incomplete responses](https://developers.openai.com/api/docs/guides/structured-outputs).
