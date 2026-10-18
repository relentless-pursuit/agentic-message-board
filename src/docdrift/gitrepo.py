"""Read a git repository at any commit, without checking it out.

Every call passes arguments as a list (never through a shell), so a file name or ref cannot inject a command.
"""
from __future__ import annotations

import subprocess
from functools import lru_cache
from pathlib import Path

DOC_SUFFIXES = (".md", ".rst", ".txt")
# Files that are supposed to mention old names: the history of the project, not instructions for today.
HISTORY_WORDS = ("change", "history", "release", "upgrade", "migration", "news")


class GitError(RuntimeError):
    pass


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
