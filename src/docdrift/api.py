"""The public Python API of a repository at one commit, read with the ast module (the code is never imported or run)."""
from __future__ import annotations

import ast
from dataclasses import dataclass, field

from .gitrepo import Repo, is_code


@dataclass(frozen=True)
class Param:
    name: str
    kind: str              # positional | keyword | var (*args) | varkw (**kwargs)
    required: bool


@dataclass
class Symbol:
    qualname: str          # click.core.Command.get_help
    name: str              # get_help
    kind: str              # function | class | method
    params: tuple[Param, ...]
    file: str
    line: int
    doc: str = ""
    owner: str = ""        # class name for a method
    exported_as: list[str] = field(default_factory=list)  # click.Command when re-exported by the package

    @property
    def short(self) -> str:
        return f"{self.owner}.{self.name}" if self.owner else self.name

    def signature(self) -> str:
        out = []
        for p in self.params:
            prefix = {"var": "*", "varkw": "**"}.get(p.kind, "")
            out.append(prefix + p.name + ("" if p.required or p.kind in ("var", "varkw") else "=…"))
        return f"{self.short}({', '.join(out)})"

    def param_names(self) -> set[str]:
        return {p.name for p in self.params}

    def accepts_any_keyword(self) -> bool:
        return any(p.kind == "varkw" for p in self.params)


UNKNOWN = (Param("args", "var", False), Param("kwargs", "varkw", False))


def module_name(path: str) -> str:
    parts = path[:-3].split("/")
    if parts[0] in ("src", "lib"):
        parts = parts[1:]
    if parts[-1] == "__init__":
        parts = parts[:-1]
    return ".".join(parts)


def _params(fn: ast.FunctionDef | ast.AsyncFunctionDef, drop_self: bool) -> tuple[Param, ...]:
    a = fn.args
    positional = a.posonlyargs + a.args
    defaults = [None] * (len(positional) - len(a.defaults)) + list(a.defaults)
    out = [Param(p.arg, "positional", d is None) for p, d in zip(positional, defaults)]
    if drop_self and out and out[0].name in ("self", "cls"):
        out = out[1:]
    if a.vararg:
        out.append(Param(a.vararg.arg, "var", False))
    out += [Param(p.arg, "keyword", d is None) for p, d in zip(a.kwonlyargs, a.kw_defaults)]
    if a.kwarg:
        out.append(Param(a.kwarg.arg, "varkw", False))
    return tuple(out)


def _overload(fn: ast.AST) -> bool:
    return any(isinstance(d, (ast.Name, ast.Attribute)) and getattr(d, "id", getattr(d, "attr", "")) == "overload"
               for d in getattr(fn, "decorator_list", []))


def extract(source: str, path: str) -> dict[str, Symbol]:
    """Public module-level functions and classes, and the public methods of public classes."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return {}
    mod = module_name(path)
    out: dict[str, Symbol] = {}
    classes = {n.name: n for n in tree.body if isinstance(n, ast.ClassDef)}

    def init_params(cls: ast.ClassDef, seen: set[str]) -> tuple[Param, ...]:
        """The constructor parameters, following base classes defined in the same module (often private ones)."""
        init = next((b for b in cls.body if isinstance(b, ast.FunctionDef) and b.name == "__init__"), None)
        if init:
            return _params(init, True)
        for base in cls.bases:
            base = base.value if isinstance(base, ast.Subscript) else base   # _NumberRangeBase[int]
            name = getattr(base, "id", None)
            if name in classes and name not in seen:
                return init_params(classes[name], seen | {name})
        # Unknown (inherited from another module, or a dataclass): accept anything rather than guess.
        return UNKNOWN

    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and not node.name.startswith("_") and not _overload(node):
            out[f"{mod}.{node.name}"] = Symbol(f"{mod}.{node.name}", node.name, "function", _params(node, False),
                                               path, node.lineno, ast.get_docstring(node) or "")
        elif isinstance(node, ast.ClassDef) and not node.name.startswith("_"):
            params = init_params(node, {node.name})
            out[f"{mod}.{node.name}"] = Symbol(f"{mod}.{node.name}", node.name, "class", params, path, node.lineno,
                                               ast.get_docstring(node) or "")
            for b in node.body:
                if isinstance(b, (ast.FunctionDef, ast.AsyncFunctionDef)) and not b.name.startswith("_") and not _overload(b):
                    static = any(getattr(d, "id", "") == "staticmethod" for d in b.decorator_list)
                    q = f"{mod}.{node.name}.{b.name}"
                    out[q] = Symbol(q, b.name, "method", _params(b, not static), path, b.lineno,
                                    ast.get_docstring(b) or "", owner=node.name)
    return out


def exports(source: str, path: str) -> dict[str, str]:
    """Names a package __init__ re-exports: {public name: module.qualname}, e.g. {"click.Command": "click.core.Command"}."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return {}
    pkg = module_name(path)
    out = {}
    for node in tree.body:
        if isinstance(node, ast.ImportFrom) and node.level >= 1:
            base = pkg.rsplit(".", node.level - 1)[0] if node.level > 1 else pkg
            src = f"{base}.{node.module}" if node.module else base
            for alias in node.names:
                public = alias.asname or alias.name
                if not public.startswith("_"):
                    out[f"{pkg}.{public}"] = f"{src}.{alias.name}"
    return out


def deprecated_aliases(source: str) -> dict[str, str]:
    """Names a module keeps only as deprecated aliases through a module __getattr__, with the warning text.

    For example click 8.2: `if name == "BaseCommand": warnings.warn("'BaseCommand' is deprecated ... Use 'Command' instead.")`.
    """
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return {}
    out = {}
    fn = next((n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "__getattr__"), None)
    for node in ast.walk(fn) if fn else []:
        if isinstance(node, ast.If) and isinstance(node.test, ast.Compare) and isinstance(node.test.comparators[0], ast.Constant):
            name = node.test.comparators[0].value
            if not isinstance(name, str) or name.startswith("_"):
                continue
            for call in ast.walk(node):
                if isinstance(call, ast.Call) and getattr(call.func, "attr", getattr(call.func, "id", "")) == "warn" and call.args:
                    arg = call.args[0]
                    if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                        out[name] = arg.value
                        break
    return out


class Api:
    """All public symbols at one commit, plus the names under which the package exports them."""

    def __init__(self, symbols: dict[str, Symbol], deprecated: dict[str, str] | None = None):
        self.symbols = symbols
        self.names = {s.name for s in symbols.values()}
        self.deprecated = deprecated or {}

    @classmethod
    def at(cls, repo: Repo, ref: str, only: list[str] | None = None) -> "Api":
        files = [f for f in repo.files(ref) if is_code(f)]
        if only is not None:
            files = [f for f in files if f in set(only)]
        symbols: dict[str, Symbol] = {}
        for f in files:
            text = repo.show(ref, f)
            if text is not None:
                symbols.update(extract(text, f))
        deprecated: dict[str, str] = {}
        for f in [f for f in repo.files(ref) if is_code(f) and f.endswith("__init__.py")]:
            text = repo.show(ref, f) or ""
            for public, target in exports(text, f).items():
                if target in symbols:
                    symbols[target].exported_as.append(public)
            deprecated.update(deprecated_aliases(text))
        # A public name in a private module (click/_termui_impl.py) is only public when the package exports it.
        private = lambda s: any(part.startswith("_") and part != "__init__.py" for part in s.file.split("/"))
        exported_classes = {s.name for s in symbols.values() if s.kind == "class" and s.exported_as}
        return cls({q: s for q, s in symbols.items()
                    if not private(s) or s.exported_as or (s.kind == "method" and s.owner in exported_classes)}, deprecated)

    @classmethod
    def from_sources(cls, sources: dict[str, str]) -> "Api":
        symbols: dict[str, Symbol] = {}
        for f, text in sources.items():
            symbols.update(extract(text, f))
        return cls(symbols)

    def by_name(self, name: str) -> list[Symbol]:
        return [s for s in self.symbols.values() if s.name == name or s.short == name]
