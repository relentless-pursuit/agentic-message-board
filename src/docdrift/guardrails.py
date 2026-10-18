"""Checks every proposed doc edit must pass before it is written. A failed check means: flag for a person, change nothing.

The model only proposes text. These checks are code, so a wrong or manipulated answer cannot reach the repository.
"""
from __future__ import annotations

import ast
import builtins
import re
from dataclasses import dataclass, field

from .api import Api
from .docscan import DIRECTIVE, Finding, scan

URL = re.compile(r"https?://[^\s)>\]\"']+")
SECRET = re.compile(r"(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY)")
CALLED = re.compile(r"(?<![\w.])(?:[a-z_][\w]*\.)*([A-Za-z_]\w*)\s*\(")
MAX_CHANGED_LINES = 12


@dataclass
class Check:
    ok: bool
    failures: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {"ok": self.ok, "failures": self.failures}


def _fences(text: str) -> int:
    return sum(1 for l in text.splitlines() if l.strip().startswith(("```", "~~~")))


def _python_blocks(text: str) -> list[str]:
    return re.findall(r"```(?:python|py|pycon)?\n(.*?)```", text, re.S)


def check(f: Finding, replacement: str, api: Api, known_builtins: set[str] | None = None) -> Check:
    old = f.excerpt
    fails = []
    if not replacement.strip():
        return Check(False, ["empty replacement"])
    if replacement == old:
        fails.append("the replacement changes nothing")
    old_lines, new_lines = old.splitlines(), replacement.splitlines()
    changed = sum(1 for a, b in zip(old_lines, new_lines) if a != b) + abs(len(old_lines) - len(new_lines))
    if changed > MAX_CHANGED_LINES:
        fails.append(f"changes {changed} lines; at most {MAX_CHANGED_LINES} are allowed for an automatic edit")
    if len(new_lines) < len(old_lines) / 2 or len(replacement.strip()) < len(old.strip()) / 2:
        fails.append("deletes more than half of the section")
    added_urls = set(URL.findall(replacement)) - set(URL.findall(old))
    if added_urls:
        fails.append(f"adds links: {', '.join(sorted(added_urls))[:200]}")
    if SECRET.search(replacement):
        fails.append("contains something that looks like a credential")
    if _fences(replacement) != _fences(old):
        fails.append("breaks a code fence")
    known = api.names | {s.owner for s in api.symbols.values()} | (known_builtins or BUILTINS)
    invented = {n for n in CALLED.findall(replacement) if n not in CALLED.findall(old) and n not in known}
    if invented:
        fails.append(f"calls names that are not in the code: {', '.join(sorted(invented))}")
    for before, after in zip(_python_blocks(old), _python_blocks(replacement)):
        if _parses(before) and not _parses(after):
            fails.append("a Python example no longer parses")
    # An API-reference entry (autoclass, autofunction...) is never edited automatically: dropping it, or adding a
    # deprecation note, is a person's decision. Rewriting it to another class documents that class twice.
    changed_lines = [a for a, b in zip(old_lines, new_lines) if a != b] + old_lines[len(new_lines):]
    if any(DIRECTIVE.search(l) for l in changed_lines):
        fails.append("edits an API-reference entry (autoclass/autofunction); a person decides")
    # A replacement may only bring in the name the project itself gives: the target of a rename, or the
    # "Use 'X' instead" of a deprecation. Any other API name is the model's guess.
    allowed = set(replacement_names(f.change))
    new_api_names = {w for w in re.findall(r"[A-Za-z_]\w*", replacement) if w in api.names} - set(re.findall(r"[A-Za-z_]\w*", old))
    guessed = new_api_names - allowed
    if guessed:
        fails.append(f"replaces {f.change.old.name} with {', '.join(sorted(guessed))}, which the project does not name as its replacement")
    # The edit must remove the outdated use it was made for.
    if scan({f.file: replacement}, [f.change], api):
        fails.append("the outdated reference is still there after the edit")
    return Check(not fails, fails)


def replacement_names(change) -> list[str]:
    """The names the project itself says replace an old API."""
    names = []
    if change.new is not None and change.kind in ("renamed", "param_renamed"):
        names.append(change.new.name)
    names += re.findall(r"Use '(?:[\w]+\.)*([\w]+)' instead", change.note or "")
    return names


def _parses(code: str) -> bool:
    try:
        ast.parse(code)
        return True
    except SyntaxError:
        return False


BUILTINS = set(dir(builtins)) | {"print", "len", "open", "range", "str", "int", "dict", "list", "set", "tuple", "super", "isinstance", "main", "cli"}
