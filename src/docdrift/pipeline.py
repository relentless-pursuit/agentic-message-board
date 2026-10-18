"""check: code changed between two commits -> which docs are now outdated -> verdicts -> guarded fixes -> report.

    detect (code, deterministic)  ->  judge and draft (reasoner)  ->  guardrails (code)  ->  act (patch, report, hooks)
"""
from __future__ import annotations

import difflib
import time
from dataclasses import dataclass, field

from .api import Api
from .changes import Change, compare
from .docscan import Finding, scan
from .gitrepo import Repo, is_doc
from .guardrails import check
from .reasoner import RulesReasoner


@dataclass
class Result:
    repo: str
    base: str
    head: str
    changes: list[Change]
    findings: list[Finding]
    verdicts: dict = field(default_factory=dict)     # finding id -> Verdict
    checks: dict = field(default_factory=dict)       # finding id -> Check
    patch: str = ""
    reasoner: str = "rules"
    seconds: float = 0.0
    model_calls: int = 0
    cost_usd: float = 0.0
    skipped: int = 0

    def summary(self) -> dict:
        outdated = [f for f in self.findings if f.id in self.verdicts and self.verdicts[f.id].outdated]
        fixed = [f for f in outdated if self.checks.get(f.id) and self.checks[f.id].ok]
        return {"api_changes": len(self.changes), "doc_references": len(self.findings), "outdated": len(outdated),
                "auto_fixed": len(fixed), "needs_person": len(outdated) - len(fixed),
                "not_outdated": len([f for f in self.findings if f.id in self.verdicts and not self.verdicts[f.id].outdated]),
                "not_reviewed": self.skipped, "reasoner": self.reasoner, "model_calls": self.model_calls,
                "cost_usd": round(self.cost_usd, 4), "seconds": round(self.seconds, 2)}

    def to_dict(self) -> dict:
        return {"repo": self.repo, "base": self.base, "head": self.head, "summary": self.summary(),
                "changes": [c.to_dict() for c in self.changes],
                "findings": [{**f.to_dict(), "verdict": self.verdicts[f.id].to_dict() if f.id in self.verdicts else None,
                              "guardrails": self.checks[f.id].to_dict() if f.id in self.checks else None}
                             for f in self.findings]}


def run_check(repo: Repo, base: str, head: str, reasoner=None, max_reviews: int = 25, docs: dict[str, str] | None = None) -> Result:
    started = time.time()
    reasoner = reasoner or RulesReasoner()
    old, new = Api.at(repo, base), Api.at(repo, head)
    changes = compare(old, new)
    if docs is None:
        docs = {f: repo.show(head, f) or "" for f in repo.files(head) if is_doc(f)}
    findings = scan(docs, changes, new)
    res = Result(str(repo.path), base, head, changes, findings, reasoner=reasoner.name)
    for i, f in enumerate(findings):
        if i >= max_reviews:
            res.skipped += 1
            continue
        v = reasoner.review(f, new)
        res.verdicts[f.id] = v
        if v.outdated and v.replacement:
            res.checks[f.id] = check(f, v.replacement, new)
    res.patch = build_patch(docs, findings, res)
    res.model_calls = getattr(reasoner, "calls", 0)
    res.cost_usd = getattr(reasoner, "cost_usd", 0.0)
    res.seconds = time.time() - started
    return res


def build_patch(docs: dict[str, str], findings: list[Finding], res: Result) -> str:
    """One unified diff (git apply compatible) with every edit that passed the guardrails."""
    out = []
    for path in sorted({f.file for f in findings}):
        lines = docs[path].splitlines(keepends=True)
        new = list(lines)
        # Apply from the bottom up so earlier line numbers stay valid; skip overlapping sections.
        taken: set[int] = set()
        for f in sorted([f for f in findings if f.file == path], key=lambda f: -f.start):
            c = res.checks.get(f.id)
            if not (c and c.ok):
                continue
            span = set(range(f.start, f.end + 1))
            if span & taken:
                continue
            taken |= span
            repl = [l + "\n" for l in res.verdicts[f.id].replacement.splitlines()]
            new[f.start - 1:f.end] = repl
        if new != lines:
            out += difflib.unified_diff(lines, new, f"a/{path}", f"b/{path}")
    return "".join(out)
