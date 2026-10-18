"""Compare the public API at two commits and keep only the changes that can break what the docs tell people to write."""
from __future__ import annotations

from dataclasses import dataclass

from .api import UNKNOWN, Api, Symbol


@dataclass
class Change:
    kind: str                  # removed | deprecated | renamed | param_removed | param_renamed | param_required
    old: Symbol
    new: Symbol | None = None
    param: str | None = None   # the old parameter name
    new_param: str | None = None
    note: str = ""             # the project's own deprecation message, when there is one

    @property
    def id(self) -> str:
        return f"{self.kind}:{self.old.qualname}:{self.param or ''}"

    def describe(self) -> str:
        s = self.old.short
        return {
            "removed": f"{s} was removed",
            "deprecated": f"{s} is deprecated: {self.note}",
            "renamed": f"{s} was renamed to {self.new.short if self.new else '?'}",
            "param_removed": f"{s} no longer accepts the parameter '{self.param}'",
            "param_renamed": f"{s}: parameter '{self.param}' is now '{self.new_param}'",
            "param_required": f"{s} now requires the parameter '{self.new_param}'",
        }[self.kind]

    def to_dict(self) -> dict:
        return {"id": self.id, "kind": self.kind, "symbol": self.old.qualname, "short": self.old.short,
                "file": self.old.file, "param": self.param, "new_param": self.new_param,
                "new_symbol": self.new.qualname if self.new else None, "description": self.describe(),
                "old_signature": self.old.signature(), "new_signature": self.new.signature() if self.new else None}


def compare(old: Api, new: Api) -> list[Change]:
    changes: list[Change] = []
    gone = [s for q, s in old.symbols.items() if q not in new.symbols]
    added = [s for q, s in new.symbols.items() if q not in old.symbols]
    for s in gone:
        # Moved to another module under the same name and exports: not a change a doc reader can see.
        same = [n for n in new.symbols.values() if n.short == s.short and n.kind == s.kind]
        if same:
            changes += _param_changes(s, same[0])
            continue
        twin = next((a for a in added if a.kind == s.kind and a.owner == s.owner and a.file == s.file
                     and a.params == s.params and a.params and a.short != s.short), None)
        if twin:
            changes.append(Change("renamed", s, twin))
        elif s.name in new.deprecated or (s.owner and s.owner in new.deprecated):
            changes.append(Change("deprecated", s, note=new.deprecated.get(s.name) or new.deprecated[s.owner]))
        else:
            changes.append(Change("removed", s))
    for q, s in old.symbols.items():
        if q in new.symbols:
            changes += _param_changes(s, new.symbols[q])
    return changes


def _param_changes(old: Symbol, new: Symbol) -> list[Change]:
    # Parameters starting with "_" (such as __self) are internal: callers never pass them by name.
    named = lambda sym: [p for p in sym.params if p.kind in ("positional", "keyword") and not p.name.startswith("_")]
    if UNKNOWN in (old.params, new.params):
        return []   # a constructor we could not read: do not guess
    before, after = named(old), named(new)
    lost = [p for p in before if p.name not in {q.name for q in after}]
    fresh = [p for p in after if p.name not in {q.name for q in before}]
    out = []
    if len(lost) == 1 and len(fresh) == 1 and before.index(lost[0]) == after.index(fresh[0]):
        return [Change("param_renamed", old, new, lost[0].name, fresh[0].name)]
    out += [Change("param_removed", old, new, p.name) for p in lost]
    out += [Change("param_required", old, new, None, p.name) for p in fresh if p.required]
    return out
