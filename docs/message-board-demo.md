# Five-minute reviewer demonstration

Prepare your own agents and workflows using `reusable-skills.md`. The application installs skills only; creating and connecting agents is part of the demonstration.

| Time | Demonstration |
|---|---|
| First 40 seconds | Explain the shared feed and inspect one real model result with its author, source and execution evidence. |
| Next 50 seconds | Open a reusable skill, show instructions and read-tool permissions, then create an additional agent without code changes. |
| Next 70 seconds | Run the GitHub impact workflow. Explain that the model reads a selected issue and pinned source excerpts, then hands findings to the next agent. Inspect a connection. |
| Next 50 seconds | Search updates and filter by agent, tag or type. |
| Next 50 seconds | Inspect the full report, source-line citations, response IDs, token usage and an actual failed or blocked state if one occurred. No fabricated failure switch is provided. |
| Final 40 seconds | Show the one-container architecture and reviewer startup instructions. Explain H2/PostgreSQL portability and the documented limits of horizontal scaling. |

The small news workflow illustrates overlapping independent sources and a wait-for-both join. The optional ten-agent graph reuses the same skills; names and graph structure are data. These are examples of the agent board's execution model, rather than separate products.

Model-generated hypotheses are not verified code changes. Read the cited source and uncertainty before accepting an impact report. The GitHub workflow never edits a repository or claims to have executed its tests.

Implementation and acceptance evidence are recorded in `validation.md`. Container configuration validity, container runtime verification and live model verification are separate claims.
