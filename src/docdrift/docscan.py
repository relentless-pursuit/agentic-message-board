"""Find the places in the docs that still use an API the code has changed.

This step is deterministic and aims for high recall: it finds every place that uses the old name or parameter in
code (a code block, inline code, or an API directive). Deciding whether that place is really outdated is the
reasoning step's job, because a sentence such as "BaseCommand was removed in 8.2" mentions the old name on purpose.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass

from .api import Api
from .changes import Change

HISTORY_HINTS = re.compile(r"deprecat|removed|no longer|renamed|versionchanged|versionadded|changed in|was replaced", re.I)
# reStructuredText directives whose body is code: code-block, sourcecode, doctest, and project ones such as click:example.
CODE_DIRECTIVE = re.compile(r"^\.\.\s+[\w:-]*(code|example|run|doctest|testcode|ipython)[\w:-]*::")
DIRECTIVE = re.compile(r"(auto(function|class|method|data|module)|:(func|class|meth|obj):|\{(func|class|meth)\})")


@dataclass
class Finding:
    file: str
    line: int                # 1-based line of the reference
    change: Change
    matched: str             # the text that refers to the old API
    start: int               # first line of the excerpt (1-based, inclusive)
    end: int                 # last line of the excerpt (inclusive)
    excerpt: str
    in_code: bool
    mentions_history: bool

    @property
    def id(self) -> str:
        return hashlib.sha1(f"{self.file}:{self.line}:{self.change.id}".encode()).hexdigest()[:10]

    def to_dict(self) -> dict:
        return {"id": self.id, "file": self.file, "line": self.line, "change": self.change.to_dict(),
                "matched": self.matched, "excerpt_lines": [self.start, self.end], "excerpt": self.excerpt,
                "in_code": self.in_code, "mentions_history": self.mentions_history}


def code_lines(lines: list[str]) -> list[bool]:
    """True for lines inside a fenced block (``` or ~~~) or an indented reStructuredText literal block."""
    out, fence, rst_block = [], None, False
    for i, line in enumerate(lines):
        s = line.strip()
        if fence:
            out.append(True)
            if s.startswith(fence):
                fence = None
            continue
        if s.startswith("```") or s.startswith("~~~"):
            fence = s[:3]
            out.append(True)
            continue
        if rst_block and (not s or line.startswith((" ", "\t"))):
            out.append(bool(s))
            continue
        rst_block = False
        if s.endswith("::") and not s.startswith(".."):
            rst_block = True
        if CODE_DIRECTIVE.match(s):
            rst_block = True
        out.append(False)
    return out


def _excerpt_bounds(lines: list[str], code: list[bool], i: int) -> tuple[int, int]:
    """The code block, or the paragraph, around line i (0-based), at most 40 lines."""
    lo = hi = i
    if code[i]:
        while lo > 0 and code[lo - 1] and i - lo < 30:
            lo -= 1
        while hi + 1 < len(lines) and code[hi + 1] and hi - i < 30:
            hi += 1
    else:
        while lo > 0 and lines[lo - 1].strip() and not code[lo - 1] and i - lo < 12:
            lo -= 1
        while hi + 1 < len(lines) and lines[hi + 1].strip() and not code[hi + 1] and hi - i < 12:
            hi += 1
    return lo, hi


def _call_text(lines: list[str], i: int, col: int) -> str:
    """The text of a call that starts at lines[i][col] (at the '('), up to its closing parenthesis, at most 15 lines."""
    depth, out = 0, []
    for j in range(i, min(i + 15, len(lines))):
        seg = lines[j][col:] if j == i else lines[j]
        for ch in seg:
            out.append(ch)
            depth += ch == "("
            depth -= ch == ")"
            if depth == 0 and out and "(" in out:
                return "".join(out)
        out.append("\n")
    return "".join(out)


def _inline_code(line: str) -> str:
    return " ".join(re.findall(r"``?([^`]+)``?", line))


def scan(files: dict[str, str], changes: list[Change], new_api: Api) -> list[Finding]:
    """Every doc reference to a changed API. files maps a doc path to its text at the newer commit."""
    findings: dict[str, Finding] = {}
    for path, text in files.items():
        lines = text.splitlines()
        code = code_lines(lines)
        for ch in changes:
            for i, kind_hit in _hits(lines, code, ch, new_api):
                lo, hi = _excerpt_bounds(lines, code, i)
                excerpt = "\n".join(lines[lo:hi + 1])
                f = Finding(path, i + 1, ch, kind_hit, lo + 1, hi + 1, excerpt, code[i], bool(HISTORY_HINTS.search(excerpt)))
                findings.setdefault(f.id, f)
    return sorted(findings.values(), key=lambda f: (f.file, f.line))


def _args(call: str) -> list[str]:
    """Top-level arguments of a call text such as "(a, b=1, f(x))"."""
    inner, depth, cur, out, quote = call[1:-1] if call.endswith(")") else call[1:], 0, "", [], ""
    for ch in inner:
        # Text inside a string literal is not code: f"bar={bar}" passes no keyword.
        if quote:
            if ch == quote:
                quote = ""
            continue
        if ch in "\"'":
            quote = ch
            cur += "''"
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


def _hits(lines: list[str], code: list[bool], ch: Change, new_api: Api):
    name, owner = ch.old.name, ch.old.owner
    if ch.kind in ("removed", "renamed", "deprecated"):
        # A name the new code still has (say, another class's method) only counts when written with its class.
        ambiguous = bool(new_api.by_name(name)) or len(name) < 4
        if owner:
            pattern = rf"\b{re.escape(owner)}\.{re.escape(name)}\b" if ambiguous else rf"\.{re.escape(name)}\b"
        elif ambiguous:
            return
        else:
            pattern = rf"(?<![\w-]){re.escape(name)}\b(?!-)"
        rx = re.compile(pattern)
        for i, line in enumerate(lines):
            target = line if (code[i] or DIRECTIVE.search(line)) else _inline_code(line)
            m = rx.search(target)
            if m:
                yield i, m.group(0).lstrip(".")
        return
    # Parameter changes: find calls of the symbol, then look inside the call. A method name several classes share
    # (Context.invoke, CliRunner.invoke) is ambiguous in a call such as x.invoke(...), so it is skipped.
    if ch.old.kind == "method" and len({s.owner for s in new_api.by_name(name) if s.kind == "method"}) > 1:
        return
    call = re.compile(rf"\.{re.escape(name)}\s*\(" if ch.old.kind == "method" else rf"(?<![\w]){re.escape(name)}\s*\(")
    for i, line in enumerate(lines):
        if not code[i] and "`" not in line:
            continue
        for m in call.finditer(line):
            text = _call_text(lines, i, m.end() - 1)
            args = _args(text)
            if ch.kind in ("param_removed", "param_renamed"):
                if any(re.match(rf"{re.escape(ch.param)}\s*=(?!=)", a) for a in args):
                    yield i, f"{name}({ch.param}=…)"
            elif ch.kind == "param_required" and ch.new and ch.new_param:
                positional = [a for a in args if not re.match(r"\*{0,2}\w+\s*=(?!=)", a)]
                required_before = [p.name for p in ch.new.params if p.required and p.kind == "positional"]
                passed_by_name = any(re.match(rf"{re.escape(ch.new_param)}\s*=", a) for a in args)
                if not passed_by_name and ch.new_param in required_before and len(positional) <= required_before.index(ch.new_param):
                    yield i, f"{name}(… without {ch.new_param})"
