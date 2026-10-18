"""Outputs other systems and people can use: report.json, report.md, docdrift.patch and GitHub Actions annotations."""
from __future__ import annotations

import json
from pathlib import Path

from .pipeline import Result


def write(res: Result, out: Path) -> dict[str, Path]:
    out.mkdir(parents=True, exist_ok=True)
    paths = {"json": out / "report.json", "markdown": out / "report.md", "patch": out / "docdrift.patch"}
    paths["json"].write_text(json.dumps(res.to_dict(), indent=2))
    paths["markdown"].write_text(markdown(res))
    paths["patch"].write_text(res.patch)
    return paths


def markdown(res: Result) -> str:
    s = res.summary()
    lines = [f"# Outdated docs: {res.base} → {res.head}", "",
             f"{s['api_changes']} public API changes. {s['doc_references']} places in the docs still use the old API. "
             f"{s['outdated']} judged outdated ({s['reasoner']}): {s['auto_fixed']} fixed automatically (all guardrails passed), "
             f"{s['needs_person']} need a person. {s['not_outdated']} are fine (they mention the change as history).", ""]
    if s["not_reviewed"]:
        lines += [f"{s['not_reviewed']} references were over the review limit and were not judged.", ""]
    lines += ["| Where | API change | Verdict | Fix |", "|---|---|---|---|"]
    for f in res.findings:
        v, c = res.verdicts.get(f.id), res.checks.get(f.id)
        verdict = "not reviewed" if not v else ("outdated" if v.outdated else "fine") + f" ({v.confidence:.0%}): {v.reason}"
        fix = "—" if not c else ("✅ in docdrift.patch" if c.ok else "❌ blocked: " + "; ".join(c.failures))
        lines.append(f"| `{f.file}:{f.line}` | {f.change.describe()} | {verdict.replace('|', '/')} | {fix.replace('|', '/')} |")
    lines += ["", f"Model calls: {s['model_calls']} · cost ${s['cost_usd']} · {s['seconds']} s"]
    return "\n".join(lines) + "\n"


def annotations(res: Result) -> str:
    """GitHub Actions workflow commands: each outdated reference shows as a warning on the pull request's file."""
    out = []
    for f in res.findings:
        v = res.verdicts.get(f.id)
        if v and not v.outdated:
            continue
        msg = f.change.describe() + (f" — {v.reason}" if v else "")
        out.append(f"::warning file={f.file},line={f.line},title=Outdated docs::{msg.replace(chr(10), ' ')}")
    return "\n".join(out)
