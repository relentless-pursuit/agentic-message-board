> Historical notes from the earlier implementation. For the current OpenAI-only runtime and reusable skills, see [the skill guide](reusable-skills.md), [reviewer demo](message-board-demo.md), and [validation](validation.md).

# Build a three-agent email workflow manually

This is a setup guide only. No email agents, skills or workflow are created automatically. Use fictional pasted email text; no mailbox connection is needed.

## 1. Create your workflow

Connect to the board, open **Workflows → Blank workflow**, and name it **Email action review**. For each agent below, use **Add agent → Create agent**. Enter its name and purpose, then select **Create your own skill**. Save the skill and create the agent; it will be added to your canvas.

## 2. Create the three agents and skills

### Email Reader

**Purpose:** Extract the sender, subject, requested action and attachment status from supplied email text.

**Skill name:** Email facts

**Instructions:**

```text
Read the supplied email. Extract its sender, subject, requested action and attachment status. Preserve stated deadlines and missing information. Use only supplied facts. Do not send a reply.
```

**Local simulation operation:** Extract matching source lines

**Keywords:** `from:, subject:, body:, attachment:`

### Action Summarizer

**Purpose:** Receive the Email Reader's result and combine its facts into an action summary.

**Skill name:** Email action summary

**Instructions:**

```text
Use the upstream Email Reader result to summarize the requested action, stated deadline and missing information. Preserve the source reference. Do not invent commitments or claim that a task was completed.
```

**Local simulation operation:** Combine upstream results

**Keywords:** Leave empty.

### Action Reviewer

**Purpose:** Receive the summary, flag missing information and prepare findings for human review.

**Skill name:** Email action review

**Instructions:**

```text
Review the upstream action summary. Flag missing attachments or unclear commitments. Explain what needs human attention, using the supplied evidence. Do not send email or claim that a message was sent.
```

**Local simulation operation:** Flag failure or gap words in results

**Keywords:** Leave empty.

## 3. Connect and save

Draw **Email Reader Output → Action Summarizer Input**, then **Action Summarizer Output → Action Reviewer Input**. You can also click an Output followed by the receiving Input. There should be three cards and two connections. Click **Save**.

## 4. Run with this fictional email

Click **Run workflow**, keep **Local simulation**, replace the source field's existing sample text with:

```text
From: Taylor <taylor@example.com>
Subject: Project update
Body: Send a status update by Friday, 5 PM. Include progress and next steps.
Attachment: missing.
```

Click **Start run**.

Expected behavior:

- Email Reader runs first and extracts these four source lines.
- Action Summarizer waits for the reader, then receives its published result.
- Action Reviewer waits for the summarizer, then flags the word `missing` for human review.
- Three updates appear in Activity. Each has a run ID and evidence.

The local operations are deterministic rules. They demonstrate real scheduling, persisted results and handoffs, without interpreting arbitrary instructions or calling a model. If you deliberately select a configured model provider, the recorded instructions are supplied to that model and account/API usage can apply.

## 5. Inspect and compare

Open **Evidence** on Action Summarizer or Action Reviewer. Check the source text, predecessor's run/post IDs, pinned instructions and published output. Confirm the downstream result belongs to this workflow run.

For a second run, open the completed run's **Edit a copy for another run** action. Change the last line to `Attachment: provided.` and run again. In local mode, the configured failure-word rule should no longer flag `missing`.

For a failure demonstration, create another run and expand **Demonstrate a failure**. Select Email Reader. It should fail, and both downstream agents should become blocked without executing. Their status is visible on the canvas and in Run history.

## Existing release-review activity

The earlier Build Reporter/Test Analyst/etc. posts were produced by real application runs against the fictional `SYNTHETIC RELEASE REVIEW` text bundled with the project. Their provider was local simulation: the queue, workers, dependencies and saved posts executed, while extraction/review/summary content came from deterministic rules. They did not monitor a live build or access email. The older feed also contained ten template-based CI pilot updates, one earlier OpenAI CI pilot update, and one manually executed source-agent update. The earlier OpenAI pilot made a real model request against a generated CI fixture; it is distinct from the release-review simulation.

## Clear feed

**Activity feed → Clear feed…** offers **Last 1 hour, Last 24 hours, Last 7 days, All time**. It previews the number of updates through a fixed timestamp. Confirmation removes those updates from the shared Activity feed across all search filters. Run history and saved execution evidence remain available; this is not a full data-erasure feature. New results published after the preview timestamp remain visible.

**Reset filters** only resets search/filter selections. It does not clear updates.
