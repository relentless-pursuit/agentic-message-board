"""Docs served differently: one usage card per public API, built from the code as it is now.

A card answers what a developer actually needs: the real signature today, examples taken from the project's own docs
and tests that are checked against that signature, the examples in the docs that no longer work, and when the code
last changed. Because the cards are rebuilt from the code, they cannot drift the way hand-written pages do.
"""
from __future__ import annotations

import html
import json
import re
from collections import Counter
from math import log

from .api import Api, Symbol
from .docscan import _args, _call_text, code_lines
from .gitrepo import Repo, is_doc


def _examples(sym: Symbol, sources: dict[str, str], limit: int = 4) -> tuple[list[dict], list[dict]]:
    """Calls of sym in docs and tests: (current examples, stale examples)."""
    call = re.compile(rf"\.{re.escape(sym.name)}\s*\(" if sym.kind == "method" else rf"(?<![\w]){re.escape(sym.name)}\s*\(")
    good, stale = [], []
    allowed = sym.param_names()
    for path, text in sources.items():
        lines = text.splitlines()
        code = code_lines(lines) if not path.endswith(".py") else [True] * len(lines)
        for i, line in enumerate(lines):
            if not code[i] or line.lstrip().startswith(("def ", "class ", "#")):
                continue
            for m in call.finditer(line):
                text_call = _call_text(lines, i, m.end() - 1)
                if text_call.count("\n") > 5:
                    continue
                keywords = [a.split("=")[0].strip() for a in _args(text_call) if re.match(r"\w+\s*=(?!=)", a)]
                unknown = [k for k in keywords if k not in allowed]
                snippet = (line[:m.start()].lstrip() + line[m.start():m.end() - 1] + text_call).strip()
                item = {"code": snippet[:400], "where": f"{path}:{i + 1}"}
                if unknown and not sym.accepts_any_keyword():
                    stale.append({**item, "problem": f"uses {', '.join(unknown)}, which {sym.short} no longer accepts"})
                elif len(good) < limit and snippet not in {g["code"] for g in good}:
                    good.append(item)
    good.sort(key=lambda e: (e["where"].startswith("tests/"), len(e["code"])))
    return good[:limit], stale


def build(repo: Repo, ref: str = "HEAD") -> list[dict]:
    api = Api.at(repo, ref)
    files = repo.files(ref)
    docs = {f: repo.show(ref, f) or "" for f in files if is_doc(f)}
    tests = {f: repo.show(ref, f) or "" for f in files if f.endswith(".py") and ("tests/" in f or f.split("/")[-1].startswith("test_"))}
    public = [s for s in api.symbols.values() if s.exported_as] or [s for s in api.symbols.values() if s.kind != "method"]
    changed: dict[str, str] = {}
    cards = []
    for s in sorted(public, key=lambda s: s.exported_as[0] if s.exported_as else s.qualname):
        good, stale = _examples(s, {**docs, **tests})
        if s.file not in changed:
            changed[s.file] = repo.last_change(ref, s.file)
        methods = [m for m in api.symbols.values() if m.owner == s.name and m.file == s.file] if s.kind == "class" else []
        pages = sorted({p for p, t in docs.items() if re.search(rf"\b{re.escape(s.name)}\b", t)})
        cards.append({"name": (s.exported_as or [s.qualname])[0], "kind": s.kind, "signature": s.signature(),
                      "summary": (s.doc.strip().split("\n\n")[0].replace("\n", " ") if s.doc else ""),
                      "defined_in": f"{s.file}:{s.line}", "last_changed": changed[s.file],
                      "examples": good, "stale_examples": stale, "doc_pages": pages[:8],
                      "methods": [m.signature() for m in methods][:20]})
    return cards


def _tokens(text: str) -> list[str]:
    return [t for t in re.findall(r"[a-z][a-z0-9]+", text.lower().replace("_", " ")) if len(t) > 2]


def search(cards: list[dict], question: str, k: int = 3) -> list[dict]:
    """BM25 over each card's name, summary, signature and examples. No model, no network."""
    docs = [_tokens(" ".join([c["name"], c["name"], c["summary"], c["signature"], *(e["code"] for e in c["examples"])])) for c in cards]
    n, avg = len(docs), (sum(map(len, docs)) / max(1, len(docs)))
    df = Counter(t for d in docs for t in set(d))
    q = _tokens(question)
    scores = []
    for c, d in zip(cards, docs):
        tf = Counter(d)
        s = sum(log(1 + (n - df[t] + .5) / (df[t] + .5)) * tf[t] * 2.2 / (tf[t] + 1.2 * (.25 + .75 * len(d) / avg)) for t in q if t in tf)
        # A question that names an API ("option") should find that API first, before longer names containing it.
        if c["name"].split(".")[-1].lower() in q:
            s += 5
        scores.append((s, c))
    return [c for s, c in sorted(scores, key=lambda x: -x[0]) if s > 0][:k]


def answer_text(question: str, hits: list[dict]) -> str:
    if not hits:
        return "No public API matches that question."
    out = [f"Closest APIs for: {question}", ""]
    for c in hits:
        out += [f"{c['name']}  —  {c['signature']}", f"  {c['summary'][:200]}" if c["summary"] else "", f"  defined in {c['defined_in']}"]
        if c["examples"]:
            out += [f"  example ({c['examples'][0]['where']}):", "    " + c["examples"][0]["code"].replace("\n", "\n    ")]
        out.append("")
    return "\n".join(l for l in out if l is not None)


def site(cards: list[dict], title: str) -> str:
    data = json.dumps(cards).replace("</", "<\\/")
    return PAGE.replace("{{TITLE}}", html.escape(title)).replace("{{DATA}}", data)


PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{TITLE}}</title><style>
:root{--bg:#fafaf8;--ink:#23261f;--muted:#6f7469;--line:#e5e7df;--card:#fff;--ok:#247e50;--warn:#a2581f;--code:#f4f5f0}
@media(prefers-color-scheme:dark){:root{--bg:#16181a;--ink:#e8e9e4;--muted:#a0a59a;--line:#2c3033;--card:#1e2124;--ok:#6cc59a;--warn:#e0a26d;--code:#25292c}}
body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
main{max-width:860px;margin:auto;padding:28px 16px}h1{font-size:22px;margin:0 0 4px}p.sub{color:var(--muted);margin:0 0 18px}
input{width:100%;box-sizing:border-box;padding:11px 13px;border:1px solid var(--line);border-radius:10px;background:var(--card);color:var(--ink);font:inherit;margin-bottom:16px}
article{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 18px;margin-bottom:10px}
h2{font-size:15px;margin:0}code,pre{font-family:ui-monospace,Menlo,monospace;font-size:12.5px}pre{background:var(--code);padding:9px 11px;border-radius:8px;overflow-x:auto;margin:6px 0}
.sig{color:var(--muted);margin:3px 0 6px;overflow-wrap:anywhere}.meta{color:var(--muted);font-size:12px}.ok{color:var(--ok)}.warn{color:var(--warn)}
details summary{cursor:pointer;color:var(--muted);font-size:12px}h3{font-size:12px;margin:10px 0 2px;text-transform:uppercase;letter-spacing:.06em;color:var(--muted)}
</style></head><body><main><h1>{{TITLE}}</h1><p class="sub">One card per public API, rebuilt from the code. Examples are checked against today's signature.</p>
<input id="q" type="search" placeholder="Search: an API name or what you want to do" autofocus><div id="list"></div></main>
<script>const cards={{DATA}};const list=document.getElementById('list');const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function card(c){let h=`<article><h2>${esc(c.name)} <span class="meta">${c.kind}</span></h2><div class="sig"><code>${esc(c.signature)}</code></div>`;
if(c.summary)h+=`<div>${esc(c.summary)}</div>`;
if(c.examples.length){h+=`<h3 class="ok">✓ Works today</h3>`;for(const e of c.examples)h+=`<pre>${esc(e.code)}</pre><div class="meta">${esc(e.where)}</div>`;}
if(c.stale_examples.length){h+=`<h3 class="warn">⚠ The docs still show</h3>`;for(const e of c.stale_examples)h+=`<pre>${esc(e.code)}</pre><div class="meta warn">${esc(e.where)} · ${esc(e.problem)}</div>`;}
if(c.methods.length)h+=`<details><summary>${c.methods.length} methods</summary><pre>${esc(c.methods.join('\\n'))}</pre></details>`;
h+=`<div class="meta">Defined in ${esc(c.defined_in)} · last changed ${esc(c.last_changed)}${c.doc_pages.length?' · docs: '+esc(c.doc_pages.join(', ')):''}</div></article>`;return h;}
function show(){const q=document.getElementById('q').value.toLowerCase().trim();const words=q.split(/\\s+/).filter(Boolean);
const hits=cards.filter(c=>{const t=(c.name+' '+c.summary+' '+c.signature).toLowerCase();return words.every(w=>t.includes(w));});
list.innerHTML=hits.slice(0,80).map(card).join('')||'<p class="sub">No match.</p>';}
document.getElementById('q').addEventListener('input',show);show();</script></body></html>"""
