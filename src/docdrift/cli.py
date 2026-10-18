"""DocDrift: find documentation that a code change made outdated, fix what is safe to fix, and serve docs
that are rebuilt from the code.

    python3 src/docdrift example                      clone the example repo (pallets/click) into data/click
    python3 src/docdrift check  --repo data/click --base 8.1.8 --head 8.2.0 [--post-to-board http://127.0.0.1:8087]
    python3 src/docdrift cards  --repo data/click     usage-card site in out/docdrift/site/index.html
    python3 src/docdrift ask    --repo data/click "how do I add an option with a default"
    python3 src/docdrift eval   --repo data/click     replay real releases and score detection
    python3 src/docdrift serve  --repo data/click     webhook receiver + usage cards on http://127.0.0.1:8099/
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from . import board, evaluate, report, usage
from .gitrepo import GitError, Repo, open_repo
from .pipeline import run_check
from .reasoner import load_env, pick

EXAMPLE_URL = "https://github.com/pallets/click.git"
EXAMPLE_TAGS = ["7.1.2", "8.0.0", "8.0.4", "8.1.0", "8.1.8", "8.2.0"]


def cmd_example(a) -> int:
    dest = Path(a.dest)
    if not (dest / ".git").exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "--quiet", "--no-checkout", EXAMPLE_URL, str(dest)], check=True)
    else:
        subprocess.run(["git", "-C", str(dest), "fetch", "--quiet", "--tags", "origin"], check=True)
    repo = Repo(dest)
    missing = [t for t in EXAMPLE_TAGS if subprocess.run(["git", "-C", str(dest), "rev-parse", "-q", "--verify", f"{t}^{{commit}}"],
                                                         capture_output=True).returncode]
    if missing:
        print(f"Missing tags: {missing}", file=sys.stderr)
        return 1
    print(f"Example repository ready: {repo.path} (pallets/click, tags {', '.join(EXAMPLE_TAGS)})")
    return 0


def cmd_check(a) -> int:
    repo = open_repo(a.repo)
    base, head = repo.find(a.base), repo.find(a.head)
    reasoner = pick(a.reasoner, repo.path.name.split("__")[-1], a.model)
    res = run_check(repo, base, head, reasoner, max_reviews=a.max_reviews)
    res.base, res.head = a.base, a.head
    paths = report.write(res, Path(a.out))
    if a.post_to_board:
        print(board.post(a.post_to_board, res))
    if a.github_annotations:
        print(report.annotations(res))
    s = res.summary()
    print(f"{s['api_changes']} API changes · {s['doc_references']} doc references · {s['outdated']} outdated "
          f"({s['auto_fixed']} fixed, {s['needs_person']} need a person) · reasoner: {s['reasoner']} · {s['seconds']} s")
    print(f"Report: {paths['markdown']}  ·  JSON: {paths['json']}  ·  Patch: {paths['patch']}")
    if a.apply and res.patch:
        done = subprocess.run(["git", "-C", str(repo.path), "apply", "-"], input=res.patch, text=True)
        print("Applied the patch to the working tree." if done.returncode == 0 else "The patch did not apply.")
    return 1 if (a.fail_on_outdated and s["outdated"]) else 0


def cmd_cards(a) -> int:
    repo = Repo(a.repo)
    cards = usage.build(repo, a.ref)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "cards.json").write_text(json.dumps(cards, indent=1))
    (out / "index.html").write_text(usage.site(cards, f"{repo.path.name} · usage cards"))
    stale = sum(len(c["stale_examples"]) for c in cards)
    print(f"{len(cards)} usage cards · {sum(len(c['examples']) for c in cards)} verified examples · {stale} stale examples in the docs")
    print(f"Open {out / 'index.html'} in a browser (or: python3 src/docdrift serve --repo {a.repo})")
    return 0


def cmd_ask(a) -> int:
    repo = Repo(a.repo)
    cards = usage.build(repo, a.ref)
    print(usage.answer_text(a.question, usage.search(cards, a.question)))
    return 0


def cmd_eval(a) -> int:
    repo = Repo(a.repo)
    result = evaluate.run(repo)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "eval.json").write_text(json.dumps(result, indent=2))
    (out / "eval.md").write_text(evaluate.markdown(result))
    s = result["summary"]
    print(f"Release replay: recall {s['recall']} · precision (lower bound) {s['precision_lower_bound']} · "
          f"{s['flagged_and_edited']}/{s['edited_by_maintainers']} maintainer edits flagged in advance · {s['seconds']} s")
    g = result["guardrails"]
    print(f"Guardrails: {g['bad_blocked']} unsafe edits blocked" + ("" if g["good_allowed"].endswith("/0") else f" · {g['good_allowed']} correct edits allowed"))
    print(f"Details: {out / 'eval.md'}")
    return 0


def cmd_serve(a) -> int:
    from docdrift.server import serve
    serve(Repo(a.repo), a.host, a.port, a.reasoner)
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="python3 src/docdrift", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("example", help="clone the example repository (pallets/click)")
    e.add_argument("--dest", default="data/click")
    e.set_defaults(fn=cmd_example)

    c = sub.add_parser("check", help="find docs made outdated by code changes between two refs")
    c.add_argument("--repo", required=True, help="a local git folder, or a GitHub repository such as pallets/click")
    c.add_argument("--base", required=True, help="older commit, tag or branch")
    c.add_argument("--head", default="HEAD", help="newer commit, tag or branch")
    c.add_argument("--out", default="out/docdrift/check")
    c.add_argument("--reasoner", choices=["auto", "openai", "claude", "rules"], default="auto",
                   help="auto: OpenAI when OPENAI_API_KEY is set, else claude -p when CLAUDE_KEY is set, else rules")
    c.add_argument("--model", default=None, help="OpenAI model (default gpt-5.4-mini) or Claude model (default haiku)")
    c.add_argument("--post-to-board", metavar="URL", help="post the result to an Agent Board feed, e.g. http://127.0.0.1:8087")
    c.add_argument("--max-reviews", type=int, default=25, help="at most this many model reviews per run (cost cap)")
    c.add_argument("--apply", action="store_true", help="apply the guarded fixes to the repository's working tree")
    c.add_argument("--github-annotations", action="store_true", help="print GitHub Actions warnings")
    c.add_argument("--fail-on-outdated", action="store_true", help="exit 1 when outdated docs are found (for CI)")
    c.set_defaults(fn=cmd_check)

    for name, fn, help_ in (("cards", cmd_cards, "build the usage-card site"), ("ask", cmd_ask, "find the API for a question")):
        s = sub.add_parser(name, help=help_)
        s.add_argument("--repo", required=True)
        s.add_argument("--ref", default="HEAD")
        if name == "cards":
            s.add_argument("--out", default="out/docdrift/site")
        else:
            s.add_argument("question")
        s.set_defaults(fn=fn)

    v = sub.add_parser("eval", help="replay real releases and score detection and guardrails")
    v.add_argument("--repo", required=True)
    v.add_argument("--out", default="out/docdrift/eval")
    v.set_defaults(fn=cmd_eval)

    s = sub.add_parser("serve", help="webhook receiver and usage cards over HTTP")
    s.add_argument("--repo", required=True)
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8099)
    s.add_argument("--reasoner", choices=["auto", "openai", "claude", "rules"], default="auto")
    s.set_defaults(fn=cmd_serve)

    load_env()
    a = p.parse_args(argv)
    try:
        return a.fn(a)
    except GitError as err:
        print(f"error: {err}", file=sys.stderr)
        return 2

