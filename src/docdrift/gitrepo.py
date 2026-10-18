"""Read a git repository at any commit, without checking it out.

Every call passes arguments as a list (never through a shell), so a file name or ref cannot inject a command.
"""
from __future__ import annotations

import re
import subprocess
from functools import lru_cache
from pathlib import Path

DOC_SUFFIXES = (".md", ".rst", ".txt")
# Files that are supposed to mention old names: the history of the project, not instructions for today.
HISTORY_WORDS = ("change", "history", "release", "upgrade", "migration", "news")


class GitError(RuntimeError):
    pass


GITHUB = re.compile(r"^(?:https://github\.com/)?([A-Za-z0-9][\w.-]{0,99})/([A-Za-z0-9][\w.-]{0,99}?)(?:\.git)?/?$")
REF = re.compile(r"^[A-Za-z0-9][\w./-]{0,99}$")   # a tag, branch or commit; never starts with "-" (no git options)


def check_ref(ref: str) -> str:
    if not REF.match(ref) or ".." in ref:
        raise GitError(f"'{ref}' is not a valid tag, branch or commit")
    return ref


def open_repo(spec: str, cache: str = "data/docdrift/repos") -> "Repo":
    """A local git folder, or a public GitHub repository (owner/repo or its URL), downloaded on first use."""
    local = Path(spec)
    if (local / ".git").exists():
        return Repo(local)
    m = GITHUB.match(spec.strip())
    if not m:
        raise GitError(f"'{spec}' is neither a local git repository nor a GitHub repository such as pallets/click")
    owner, name = m.group(1), m.group(2)
    dest = Path(cache) / f"{owner}__{name}"
    url = f"https://github.com/{owner}/{name}.git"
    if not (dest / ".git").exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        done = subprocess.run(["git", "clone", "--quiet", "--no-checkout", url, str(dest)], capture_output=True, text=True)
        if done.returncode != 0:
            raise GitError(f"could not download {owner}/{name}: {done.stderr.strip()[:200]}")
    else:
        subprocess.run(["git", "-C", str(dest), "fetch", "--quiet", "--tags", "origin"], capture_output=True)
    return Repo(dest)


class Repo:
    def __init__(self, path: str | Path):
        self.path = Path(path).resolve()
        if not (self.path / ".git").exists():
            raise GitError(f"{self.path} is not a git repository. Run: python3 src/docdrift example")

    def git(self, *args: str) -> str:
        done = subprocess.run(["git", "-C", str(self.path), *args], capture_output=True, text=True)
        if done.returncode != 0:
            raise GitError(f"git {' '.join(args)}: {done.stderr.strip()}")
        return done.stdout

    def resolve(self, ref: str) -> str:
        return self.git("rev-parse", "--verify", f"{ref}^{{commit}}").strip()

    def find(self, ref: str) -> str:
        """The commit for a tag, commit or branch name; a branch such as "main" also matches origin/main."""
        check_ref(ref)
        for candidate in (ref, f"origin/{ref}"):
            done = subprocess.run(["git", "-C", str(self.path), "rev-parse", "--verify", "-q", f"{candidate}^{{commit}}"],
                                  capture_output=True, text=True)
            if done.returncode == 0:
                return done.stdout.strip()
        raise GitError(f"'{ref}' is not a tag, branch or commit in {self.path.name}")

    @lru_cache(maxsize=4096)
    def show(self, ref: str, path: str) -> str | None:
        """The text of a file at a commit, or None when the file does not exist there."""
        done = subprocess.run(["git", "-C", str(self.path), "show", f"{ref}:{path}"], capture_output=True)
        if done.returncode != 0:
            return None
        return done.stdout.decode("utf-8", errors="replace")

    @lru_cache(maxsize=256)
    def files(self, ref: str) -> tuple[str, ...]:
        return tuple(self.git("ls-tree", "-r", "--name-only", ref).splitlines())

    def changed(self, base: str, head: str) -> list[str]:
        return self.git("diff", "--name-only", base, head).splitlines()

    def commits(self, base: str, head: str) -> list[str]:
        return self.git("rev-list", "--reverse", "--first-parent", f"{base}..{head}").splitlines()

    def subject(self, ref: str) -> str:
        return self.git("log", "-1", "--format=%h %s", ref).strip()

    def last_change(self, ref: str, path: str) -> str:
        return self.git("log", "-1", "--format=%h %as", ref, "--", path).strip()


def is_code(path: str) -> bool:
    parts = path.split("/")
    return path.endswith(".py") and not any(p in ("tests", "test", "docs", "examples", "scripts") for p in parts[:-1]) \
        and not parts[-1].startswith("test_") and parts[-1] not in ("conftest.py", "setup.py", "noxfile.py")


def is_history(path: str) -> bool:
    name = path.rsplit("/", 1)[-1].lower()
    return any(w in name for w in HISTORY_WORDS)


def is_doc(path: str) -> bool:
    """Documentation a developer follows today: README and docs/ pages, not changelogs or upgrade notes."""
    name = path.rsplit("/", 1)[-1]
    in_docs = path.startswith("docs/") or path.startswith("doc/")
    readme = "/" not in path and name.upper().startswith("README")
    return (in_docs or readme) and path.endswith(DOC_SUFFIXES) and not is_history(path)
