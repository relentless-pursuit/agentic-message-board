"""How good is DocDrift? Measured against what the project's maintainers actually did.

1. Release replay. For each pair of releases (base -> head) we run detection on the docs as they were at base,
   using the API change between the two releases. The maintainers then edited the docs before head, which gives
   real labels without anyone hand-labelling:
     - recall: of the doc lines that used an old API and that maintainers edited by head, how many did we flag?
     - precision (lower bound): of the lines we flagged, how many did maintainers edit by head?
       A flag that maintainers did not edit is either a false alarm or docs they missed; `check` on head
       reports those, and the reasoner judges them.
2. Guardrails. A fixed set of good and bad proposed edits: every bad one must be blocked, every good one allowed.
3. Cost. Time per release pair, and model calls (zero for detection; one per finding for the claude reasoner).
"""
from __future__ import annotations

import re
import time

from .api import Api
from .changes import Change, compare
from .docscan import DIRECTIVE, Finding, scan
from .gitrepo import Repo, is_doc
from .guardrails import check

DEFAULT_PAIRS = [("7.1.2", "8.0.0"), ("8.0.4", "8.1.0"), ("8.1.8", "8.2.0")]


def _uses_old_api(line: str, ch: Change) -> bool:
    if ch.kind in ("param_removed", "param_renamed"):
        return bool(re.search(rf"(?<![\w.]){re.escape(ch.param)}\s*=(?!=)", line)) and ch.old.name in line
    if ch.kind in ("removed", "renamed", "deprecated"):
        if ch.old.owner:
            return f"{ch.old.owner}.{ch.old.name}" in line
        return bool(re.search(rf"(?<![\w-]){re.escape(ch.old.name)}\b(?!-)", line)) and len(ch.old.name) >= 4
    return False


def replay(repo: Repo, base: str, head: str) -> dict:
    t = time.time()
    old, new = Api.at(repo, base), Api.at(repo, head)
    changes = compare(old, new)
    before = {f: repo.show(base, f) or "" for f in repo.files(base) if is_doc(f)}
    after_files = {f: repo.show(head, f) for f in repo.files(head) if is_doc(f)}
    flags = scan(before, changes, new)

    def edited(path: str, line_text: str) -> bool:
        later = after_files.get(path)
        return later is None or line_text not in later.splitlines()

    # Labels: lines at base that use an old API (by a simple independent rule) and that maintainers edited by head.
    truth = set()
    for path, text in before.items():
        for i, line in enumerate(text.splitlines()):
            if any(_uses_old_api(line, ch) for ch in changes) and edited(path, line):
                truth.add((path, i + 1))
    flagged = {(f.file, f.line) for f in flags}
    fixed_by_people = {(f.file, f.line) for f in flags if edited(f.file, before[f.file].splitlines()[f.line - 1])}
    hit = truth & flagged
    return {"base": base, "head": head, "api_changes": len(changes), "doc_files": len(before),
            "flagged": len(flagged), "edited_by_maintainers": len(truth), "flagged_and_edited": len(hit),
            "recall": round(len(hit) / len(truth), 3) if truth else None,
            "precision_lower_bound": round(len(fixed_by_people) / len(flagged), 3) if flagged else None,
            "missed_examples": [f"{p}:{n}" for p, n in sorted(truth - flagged)][:8],
            "flag_examples": [f"{f.file}:{f.line} {f.change.describe()}" for f in flags][:8],
            "seconds": round(time.time() - t, 2)}


def guardrail_cases(api: Api, finding: Finding) -> list[tuple[str, str, bool]]:
    """(label, replacement, should pass). finding uses an old name that the replacement must remove."""
    old = finding.excerpt
    from .guardrails import replacement_names
    from .reasoner import related_names
    named = replacement_names(finding.change)
    on_directive = bool(DIRECTIVE.search(old.splitlines()[finding.line - finding.start]))
    # A correct edit swaps the old name for the one the project names (allowed unless it is an API-reference entry).
    good = named[0] if named else None
    fixed = re.sub(rf"\b{re.escape(finding.change.old.name)}\b", good, old) if good else None
    guess = next((n.split(".")[-1] for n in related_names(finding, api) if n.split(".")[-1] not in (finding.change.old.name, *named)), None)
    cases = [
        ("adds a link", (fixed or old) + "\nSee https://evil.example/login for details.", False),
        ("adds a credential", (fixed or old) + "\ntoken = 'ghp_" + "a" * 36 + "'", False),
        ("deletes the section", old.splitlines()[0][:10], False),
        ("invents an API", (fixed or old) + "\nclick.make_everything_work()", False),
        ("changes nothing", old, False),
        ("leaves the old name", old + "\n", False),
        ("empty", "", False),
    ]
    if guess:
        cases.append(("swaps in a name the project did not give", re.sub(rf"\b{re.escape(finding.change.old.name)}\b", guess, old), False))
    if fixed:
        cases.append(("uses the project's replacement", fixed, not on_directive))
    return cases


def guardrail_eval(repo: Repo, base: str, head: str) -> dict:
    """Run the fixed cases on every rename finding in the pair (rename has a known correct fix)."""
    from .reasoner import RulesReasoner
    old, new = Api.at(repo, base), Api.at(repo, head)
    docs = {f: repo.show(head, f) or "" for f in repo.files(head) if is_doc(f)}
    findings = scan(docs, compare(old, new), new)
    total = blocked_bad = allowed_good = bad = good = 0
    for f in findings[:20]:
        for label, repl, should in guardrail_cases(new, f):
            ok = check(f, repl, new).ok
            total += 1
            if should:
                good += 1
                allowed_good += ok
            else:
                bad += 1
                blocked_bad += not ok
    return {"cases": total, "bad_blocked": f"{blocked_bad}/{bad}", "good_allowed": f"{allowed_good}/{good}"}


def run(repo: Repo, pairs=DEFAULT_PAIRS) -> dict:
    rows = [replay(repo, b, h) for b, h in pairs]
    tot = lambda k: sum(r[k] for r in rows)
    summary = {"edited_by_maintainers": tot("edited_by_maintainers"), "flagged_and_edited": tot("flagged_and_edited"),
               "flagged": tot("flagged"),
               "recall": round(tot("flagged_and_edited") / tot("edited_by_maintainers"), 3) if tot("edited_by_maintainers") else None,
               "precision_lower_bound": round(sum(r["precision_lower_bound"] * r["flagged"] for r in rows if r["flagged"]) / tot("flagged"), 3) if tot("flagged") else None,
               "seconds": round(sum(r["seconds"] for r in rows), 2), "model_calls": 0}
    return {"pairs": rows, "summary": summary, "guardrails": guardrail_eval(repo, *pairs[-1])}


def markdown(result: dict) -> str:
    s = result["summary"]
    out = ["# DocDrift evaluation", "",
           "Release replay: detection runs on the docs as they were at the older release; the maintainers' later edits are the labels.", "",
           "| Releases | API changes | Flagged | Lines maintainers edited | Flagged before they edited | Recall | Precision (lower bound) | Time |",
           "|---|---|---|---|---|---|---|---|"]
    for r in result["pairs"]:
        out.append(f"| {r['base']} → {r['head']} | {r['api_changes']} | {r['flagged']} | {r['edited_by_maintainers']} | {r['flagged_and_edited']} | "
                   f"{r['recall'] if r['recall'] is not None else '–'} | {r['precision_lower_bound'] if r['precision_lower_bound'] is not None else '–'} | {r['seconds']} s |")
    out += [f"| **All** | | {s['flagged']} | {s['edited_by_maintainers']} | {s['flagged_and_edited']} | **{s['recall']}** | **{s['precision_lower_bound']}** | {s['seconds']} s |",
            "", f"Guardrails: {result['guardrails']['bad_blocked']} unsafe edits blocked"
            + (f", {result['guardrails']['good_allowed']} correct edits allowed" if not result['guardrails']['good_allowed'].endswith("/0")
               else " (no case here has a safe automatic fix: every place is an API-reference entry or has no replacement named by the project)")
            + f" · {result['guardrails']['cases']} cases.", "", "Detection makes no model calls."]
    for r in result["pairs"]:
        if r["missed_examples"]:
            out += ["", f"Missed in {r['base']} → {r['head']}: " + ", ".join(r["missed_examples"])]
    return "\n".join(out) + "\n"
