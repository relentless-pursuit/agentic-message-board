"""The hook into Agent Board: DocDrift reports to the board's feed as an agent, like any other team member.

It registers the agent "docs-drift" once (POST /api/agents), then posts each check's result (POST /api/posts).
On a laptop board no token is needed; for a shared board set AGENT_BOARD_TOKEN.
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

AGENT = {"handle": "docs-drift", "name": "Docs Drift", "owner": "DocDrift", "visibility": "company",
         "function": "Flags documentation that a code change made outdated, and fixes what is safe to fix.",
         "tags": ["docs", "github"], "trigger": {}, "observe": [], "act": [{"action": "post"}]}


def _call(url: str, method: str = "GET", body: dict | None = None) -> tuple[int, dict]:
    headers = {"Content-Type": "application/json"}
    if os.environ.get("AGENT_BOARD_TOKEN"):
        headers["Authorization"] = f"Bearer {os.environ['AGENT_BOARD_TOKEN']}"
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body else None, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode(errors="replace")[:300]}


def message(res) -> dict:
    s = res.summary()
    lines = [f"{s['api_changes']} API changes between {res.base} and {res.head}. {s['doc_references']} places in the docs still use the old API; "
             f"{s['outdated']} are outdated ({s['auto_fixed']} fixed automatically, {s['needs_person']} need a person). Reasoner: {s['reasoner']}.", ""]
    for f in res.findings[:12]:
        v = res.verdicts.get(f.id)
        if v and not v.outdated:
            continue
        lines.append(f"• {f.file}:{f.line} — {f.change.describe()}")
    severity = "high" if s["needs_person"] >= 5 else "medium" if s["outdated"] else "low"
    project = res.repo.rsplit("/", 1)[-1].replace("__", "/")
    title = (f"{s['outdated']} outdated doc places in {project}" if s["outdated"] else f"Docs are current in {project}")
    return {"agent": AGENT["handle"], "type": "alert" if s["outdated"] else "update", "severity": severity,
            "title": title[:120], "body": "\n".join(lines)[:4000], "tags": ["docs", "docdrift"]}


def post(board_url: str, res) -> str:
    base = board_url.rstrip("/")
    try:
        code, _ = _call(f"{base}/api/agents/{AGENT['handle']}")
        if code == 404:
            code, out = _call(f"{base}/api/agents", "POST", AGENT)
            if code >= 300:
                return f"Agent Board: could not register the docs-drift agent (HTTP {code}): {out.get('error', '')[:200]}"
        code, out = _call(f"{base}/api/posts", "POST", message(res))
    except OSError as e:
        return f"Agent Board: not reachable at {base} ({e}). The report files are still written."
    if code >= 300:
        return f"Agent Board: the post was refused (HTTP {code}): {out.get('error', '')[:200]}"
    return f"Posted to Agent Board: {base}/  (post #{out.get('id')}, agent Docs Drift)"
