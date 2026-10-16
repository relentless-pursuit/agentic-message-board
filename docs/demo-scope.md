> Historical notes from the earlier implementation. For the current OpenAI-only runtime and reusable skills, see [the skill guide](reusable-skills.md), [reviewer demo](message-board-demo.md), and [validation](validation.md).

# Job-search swarm demo scope

Scope correction, 16 October 2026: this is an optional example walkthrough, not the main assignment presentation. The user's original goal is a configurable agent message board. See [message-board alignment review](message-board-alignment-review.md) for the revised priorities and primary demo.

Reviewed 16 October 2026 against the current source. This is the recommended presentation scope; the readiness items below remain outstanding.

## Demonstration objective

Show how ten specialized agents turn one fictional CV and one job description into evidence-based suggestions and a cover letter, with the user deciding what gets applied. Present this as a local CV-to-job review workflow. The visual design draws on the observed Jack & Jill interface; its internal architecture is unknown.

## Include in the main demo

| Part | What the audience sees | Current implementation |
| --- | --- | --- |
| Inputs | Paste text or upload a PDF for either CV or job; review extracted text | Implemented; text PDFs, DOCX and TXT, up to 2 MB |
| Sample | Alex Example applying to the fictional ClearNote Tools backend role | Bundled in `src/main/resources/static/swarm-sample.json` |
| Match analysis | Supported Java/Spring Boot/SQL experience and gaps such as Docker/Kubernetes | Implemented; local results are deterministic heuristics |
| Clarification | One motivation question before drafting | Question exists; move it earlier as described below |
| Ten agents | Names, statuses, inputs, outputs and contributions | Implemented, with at most two agents executing concurrently |
| CV review | Before/after suggestions, supporting evidence, accept/reject/edit controls | Implemented; original input is retained |
| Cover letter | A draft using approved CV text and the motivation answer | Implemented |
| Final approval | Final review findings, editable letter and explicit approval | Implemented; downloads require approval |
| Deliverables | Approved CV and letter as DOCX or TXT | Implemented; simple formatting |

Retain the pale neutral background, compact sidebar, pill controls, thin borders, rounded cards and clear primary buttons. Keep the main path focused on inputs, analysis, review and outputs; agent details provide the explanation of how the result was produced.

## Five-minute presentation

1. **0:00–0:45 — Inputs:** Open `/resume-swarm.html` and connect before presenting. Load the fictional sample. Briefly show the paste/PDF options; use the synthetic PDF fixture for an optional upload demonstration.
2. **0:45–1:45 — Analysis:** Start the workflow. Show supported matches and missing evidence. Explain one dependency between agents rather than reading every card.
3. **1:45–2:15 — Clarification:** Answer the motivation question: “I enjoy building reliable Java services and improving tools that other engineers use.” In the target flow this precedes CV suggestions; the current implementation presents the question alongside suggestions.
4. **2:15–3:30 — CV review:** Show one source passage and its proposed replacement. Accept or revise one suggestion. Explain that unsupported Docker/Kubernetes experience must remain a gap. Approve the selected CV changes.
5. **3:30–4:30 — Documents:** Review the resulting cover letter and final reviewer findings. Make an optional wording change and explicitly approve the final documents.
6. **4:30–5:00 — Outcome:** Download a DOCX or TXT. Show all ten agent results and explain how the approved inputs contributed to the output.

## Keep outside the main presentation

- Jack & Jill login, browser debugging, REA/Hopper setup and private account documents.
- Public URL import, which introduces login/redirect/network dependencies.
- General board monitors, other AI skill jobs and model administration pages.
- Automatic job discovery, application submission, email sending or external document edits.
- OCR, preservation of a PDF's original layout, production scaling or hiring-score claims.

These are presentation boundaries, not instructions to remove existing features.

## Readiness items before the final rehearsal

1. **Move clarification before drafting.** Current execution is `1+2+3 → 4 → 5 → 6+7 → 8 → answer/CV approval → 9 → 10 → final approval`. Agent 5 therefore proposes edits before the user's answer. The target sequence should be extraction and requirements → fit/gaps → clarifier → user answer → CV tailoring → evidence/consistency review → CV approval → cover letter → final reviewer → final approval. Preserve exactly ten roles. Update clarifier dependencies, persist a separate clarification pause, and pass the answer to the tailoring stage. Add focused regression coverage for the new gate.
2. **Make local mode selectable explicitly.** Currently the provider is selected automatically from whether model credentials exist. For a predictable demo, add a local-provider override so the sample can run without model calls even on a configured machine. Show the actual provider in the UI. Keep any real-model demonstration separate and intentional.
3. **Rehearse the final flow once after these changes.** Use synthetic data in an isolated database. Confirm clarification precedes drafting, rejected edits stay out of the approved CV, all ten roles finish, downloads remain gated, and restart instructions work.

## Evidence and limitations

The dashboard returned HTTP 200 at `http://127.0.0.1:8087/resume-swarm.html` during this review. Existing saved test reports show 12 focused tests passing with zero failures or skips; tests were not rerun for this documentation change. Earlier browser validation covered synthetic PDF extraction and the existing approval flow. No model request was made during this scope review.

The current final reviewer runs before the user can make last-minute cover-letter edits. Those user edits are saved without another automatic verification pass; describe final approval as human review, not verification of every final keystroke.

## Code locations

- Workflow and persistence: `src/main/java/dev/agentboard/swarm/ResumeSwarmService.java`
- Ten role definitions and dependencies: `src/main/java/dev/agentboard/swarm/SwarmSkills.java`
- Offline behavior: `src/main/java/dev/agentboard/swarm/LocalSwarmProvider.java`
- Interface: `src/main/resources/static/resume-swarm.html`, `resume-swarm.js`, `resume-swarm.css`
- Startup and implementation notes: `scripts/start-local.sh`, `docs/resume-swarm.md`
