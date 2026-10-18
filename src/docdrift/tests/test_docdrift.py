"""Offline tests (standard library unittest): a tiny git repository built in a temp folder, a fake `claude` command,
and fake OpenAI and Agent Board servers. No network, no key.

Run: python3 -m unittest discover -s src/docdrift/tests
"""
import hashlib
import hmac
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from docdrift import board, usage  # noqa: E402
from docdrift.api import Api, extract  # noqa: E402
from docdrift.changes import compare  # noqa: E402
from docdrift.docscan import code_lines, scan  # noqa: E402
from docdrift.gitrepo import Repo  # noqa: E402
from docdrift.guardrails import check  # noqa: E402
from docdrift.pipeline import run_check  # noqa: E402
from docdrift.reasoner import ClaudeReasoner, OpenAIReasoner, RulesReasoner, parse  # noqa: E402
from docdrift.server import refs_from_event, signature  # noqa: E402

V1 = '''
def connect(host, port=80, timeout=10):
    """Open a connection."""
def fetch(url, retries=3):
    """Download a page."""
def legacy_ping():
    pass
class _Base:
    def __init__(self, name, color="red"):
        pass
class Widget(_Base):
    def draw(self, size):
        pass
'''
V2 = '''
def connect(host, port=80, deadline=10):
    """Open a connection."""
def download(url, retries=3):
    """Download a page."""
class _Base:
    def __init__(self, name, color="red"):
        pass
class Widget(_Base):
    def draw(self, size):
        pass
'''
DOCS = '''# Guide

Call `connect` like this:

```python
conn = mylib.connect("db", timeout=5)
page = mylib.fetch("https://example.com")
mylib.legacy_ping()
w = mylib.Widget("w", color="blue")
print(f"timeout={conn}")
```

Note: `legacy_ping` was removed in 2.0.
'''


FAKE_CLAUDE = r'''
import json, os, sys
prompt = sys.stdin.read()
assert "--tools" in sys.argv and sys.argv[sys.argv.index("--tools") + 1] == ""
assert os.environ.get("CLAUDE_CODE_OAUTH_TOKEN") == "test-oauth-token"
assert "<<<DOC" in prompt
if "legacy_ping" in prompt and "removed in 2.0" in prompt:
    out = {"outdated": False, "confidence": 0.9, "reason": "history", "replacement": ""}
else:
    out = {"outdated": True, "confidence": 0.8, "reason": "uses old API", "replacement": ""}
print(json.dumps({"type": "result", "is_error": False, "structured_output": out, "total_cost_usd": 0.001}))
'''



def git(cwd, *args):
    subprocess.run(["git", "-C", str(cwd), *args], check=True, capture_output=True)


def make_repo(tmp: Path) -> Repo:
    tmp.mkdir(parents=True)
    git(tmp, "init", "-q")
    git(tmp, "config", "user.email", "t@example.com")
    git(tmp, "config", "user.name", "t")
    (tmp / "src" / "mylib").mkdir(parents=True)
    (tmp / "docs").mkdir()
    (tmp / "src/mylib/__init__.py").write_text("from .core import connect as connect\nfrom .core import Widget as Widget\n")
    (tmp / "src/mylib/core.py").write_text(V1)
    (tmp / "docs/guide.md").write_text(DOCS)
    (tmp / "tests").mkdir()
    (tmp / "tests/test_core.py").write_text("def test_it():\n    mylib.connect('h', port=1)\n")
    git(tmp, "add", "-A")
    git(tmp, "commit", "-qm", "v1")
    git(tmp, "tag", "v1")
    (tmp / "src/mylib/core.py").write_text(V2)
    git(tmp, "commit", "-qam", "v2")
    git(tmp, "tag", "v2")
    return Repo(tmp)


class FakeServer:
    """A local HTTP server that answers every request with `reply(path, body)` and records the requests."""
    def __init__(self, reply):
        self.requests = []
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _handle(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                outer.requests.append((self.command, self.path, dict(self.headers), body))
                code, data = reply(self.command, self.path, body)
                raw = json.dumps(data).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            do_GET = do_POST = _handle

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.srv.server_port}"
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def close(self):
        self.srv.shutdown()
        self.srv.server_close()


class DocDriftTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = make_repo(Path(self.tmp.name) / "repo")
        self.env = mock.patch.dict(os.environ, {}, clear=False)
        self.env.start()
        for k in ("OPENAI_API_KEY", "CLAUDE_KEY", "AGENT_BOARD_TOKEN"):
            os.environ.pop(k, None)

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    def test_api_reads_signatures_and_follows_private_base_init(self):
        api = extract(V1, "src/mylib/core.py")
        self.assertEqual(api["mylib.core.connect"].signature(), "connect(host, port=…, timeout=…)")
        self.assertEqual([p.name for p in api["mylib.core.Widget"].params], ["name", "color"])
        self.assertNotIn("mylib.core._Base", api)

    def test_compare_finds_breaking_changes(self):
        kinds = {(c.kind, c.old.name, c.param) for c in compare(Api.at(self.repo, "v1"), Api.at(self.repo, "v2"))}
        self.assertIn(("param_renamed", "connect", "timeout"), kinds)
        self.assertIn(("renamed", "fetch", None), kinds)
        self.assertIn(("removed", "legacy_ping", None), kinds)
        self.assertFalse(any(name == "Widget" for _, name, _ in kinds))

    def test_scan_finds_code_uses_and_ignores_strings(self):
        new = Api.at(self.repo, "v2")
        found = scan({"docs/guide.md": DOCS}, compare(Api.at(self.repo, "v1"), new), new)
        lines = {(f.line, f.change.kind) for f in found}
        self.assertIn((6, "param_renamed"), lines)       # connect(..., timeout=5)
        self.assertIn((7, "renamed"), lines)             # fetch(...)
        self.assertIn((8, "removed"), lines)             # legacy_ping()
        self.assertFalse(any(f.line == 10 for f in found))  # timeout= inside an f-string is not a keyword
        history = [f for f in found if f.line == 13]
        self.assertTrue(history and history[0].mentions_history)

    def test_code_lines_understands_rst_directives(self):
        text = "Intro\n\n.. click:example::\n\n    import click\n    click.echo('x')\n\nAfter"
        self.assertEqual(code_lines(text.splitlines()), [False, False, False, False, True, True, False, False])

    def test_rules_fix_renames_and_patch_applies(self):
        res = run_check(self.repo, "v1", "v2", RulesReasoner())
        s = res.summary()
        self.assertTrue(s["outdated"] >= 3 and s["not_outdated"] >= 1 and s["auto_fixed"] >= 1, s)
        self.assertIn("deadline=5", res.patch)
        done = subprocess.run(["git", "-C", str(self.repo.path), "apply", "--check", "-"], input=res.patch, text=True, capture_output=True)
        self.assertEqual(done.returncode, 0, done.stderr)

    def test_guardrails_block_unsafe_edits(self):
        new = Api.at(self.repo, "v2")
        f = next(f for f in scan({"docs/guide.md": DOCS}, compare(Api.at(self.repo, "v1"), new), new) if f.change.kind == "renamed")
        good = f.excerpt.replace("mylib.fetch(", "mylib.download(")
        self.assertTrue(check(f, good, new).ok)
        bad = {
            "link": good + "\nhttps://evil.example",
            "secret": good + "\nkey = 'ghp_" + "x" * 36 + "'",
            "invented api": good.replace("mylib.download(", "mylib.magic_download("),
            "still outdated": f.excerpt + "\n",
            "deleted": "x",
            "broken fence": good.replace("```python", ""),
        }
        for label, text in bad.items():
            self.assertFalse(check(f, text, new).ok, label)

    def test_openai_reasoner_uses_strict_schema_and_no_tools(self):
        def reply(method, path, body):
            req = json.loads(body)
            assert req["response_format"]["json_schema"]["strict"] is True and "tools" not in req
            prompt = req["messages"][1]["content"]
            hist = "legacy_ping" in prompt and "removed in 2.0" in prompt
            verdict = {"outdated": not hist, "confidence": 0.9, "reason": "r", "replacement": ""}
            return 200, {"choices": [{"message": {"content": json.dumps(verdict)}}], "usage": {"prompt_tokens": 1000, "completion_tokens": 100}}
        fake = FakeServer(reply)
        try:
            os.environ["OPENAI_API_KEY"] = "sk-test"
            r = OpenAIReasoner("mylib")
            with mock.patch.object(OpenAIReasoner, "URL", fake.url + "/v1/chat/completions"):
                res = run_check(self.repo, "v1", "v2", r)
            self.assertEqual(r.calls, len(res.findings))
            self.assertTrue(all(v.source == "openai" and not v.error for v in res.verdicts.values()))
            self.assertGreaterEqual(res.summary()["not_outdated"], 1)
            self.assertGreater(r.cost_usd, 0)
            self.assertEqual(fake.requests[0][2].get("Authorization"), "Bearer sk-test")
        finally:
            fake.close()

    def test_openai_failure_flags_for_a_person(self):
        fake = FakeServer(lambda m, p, b: (500, {"error": "boom"}))
        try:
            os.environ["OPENAI_API_KEY"] = "sk-test"
            with mock.patch.object(OpenAIReasoner, "URL", fake.url):
                res = run_check(self.repo, "v1", "v2", OpenAIReasoner("mylib"))
            self.assertTrue(all(v.outdated and v.confidence == 0 and v.error for v in res.verdicts.values()))
            self.assertEqual(res.patch, "")
        finally:
            fake.close()

    def test_rejected_openai_key_falls_back_to_rules_after_one_call(self):
        fake = FakeServer(lambda m, p, b: (401, {"error": "bad key"}))
        try:
            os.environ["OPENAI_API_KEY"] = "sk-revoked"
            r = OpenAIReasoner("mylib")
            with mock.patch.object(OpenAIReasoner, "URL", fake.url):
                res = run_check(self.repo, "v1", "v2", r)
            self.assertEqual(r.calls, 1)
            self.assertTrue(all("OpenAI unavailable" in v.reason for v in res.verdicts.values()))
            self.assertGreaterEqual(res.summary()["auto_fixed"], 1)
        finally:
            fake.close()

    def test_claude_reasoner_runs_cli_without_tools(self):
        fake = Path(self.tmp.name) / "fake_claude.py"
        fake.write_text(FAKE_CLAUDE)
        os.environ["CLAUDE_KEY"] = "test-oauth-token"
        r = ClaudeReasoner("mylib", command=[sys.executable, str(fake)])
        res = run_check(self.repo, "v1", "v2", r)
        self.assertTrue(r.calls == len(res.findings) and r.calls > 0)
        self.assertTrue(all(v.source == "claude" and not v.error for v in res.verdicts.values()))

    def test_parse_accepts_result_text_fallback(self):
        out = parse(json.dumps({"result": 'Here: {"outdated": true, "confidence": 1, "reason": "r", "replacement": ""}'}))
        self.assertIs(out["outdated"], True)

    def test_posts_result_to_agent_board(self):
        def reply(method, path, body):
            if path == "/api/agents/docs-drift":
                return 404, {}
            return 201, {"id": 7}
        fake = FakeServer(reply)
        try:
            res = run_check(self.repo, "v1", "v2", RulesReasoner())
            out = board.post(fake.url, res)
            self.assertIn("post #7", out)
            calls = [(m, p) for m, p, _, _ in fake.requests]
            self.assertEqual(calls, [("GET", "/api/agents/docs-drift"), ("POST", "/api/agents"), ("POST", "/api/posts")])
            post = json.loads(fake.requests[-1][3])
            self.assertEqual(post["agent"], "docs-drift")
            self.assertIn(post["severity"], ("low", "medium", "high", "critical"))
            self.assertIn("docs/guide.md", post["body"])
        finally:
            fake.close()

    def test_board_unreachable_is_reported_not_raised(self):
        res = run_check(self.repo, "v1", "v2", RulesReasoner())
        self.assertIn("not reachable", board.post("http://127.0.0.1:9", res))

    def test_webhook_signature_and_refs(self):
        body = b'{"before": "' + b"a" * 40 + b'", "after": "' + b"b" * 40 + b'"}'
        self.assertEqual(signature("s3cret", body), "sha256=" + hmac.new(b"s3cret", body, hashlib.sha256).hexdigest())
        self.assertEqual(refs_from_event("push", json.loads(body)), ("a" * 40, "b" * 40))
        self.assertIsNone(refs_from_event("push", {"before": "0" * 40, "after": "b" * 40}))
        self.assertIsNone(refs_from_event("push", {"before": "main; rm -rf /", "after": "b" * 40}))
        self.assertIsNone(refs_from_event("issues", {}))

    def test_usage_cards_verify_examples(self):
        cards = {c["name"]: c for c in usage.build(self.repo, "v2")}
        connect = cards["mylib.connect"]
        self.assertEqual(connect["signature"], "connect(host, port=…, deadline=…)")
        self.assertTrue(any("timeout" in s["problem"] for s in connect["stale_examples"]))
        self.assertTrue(any("port=1" in e["code"] for e in connect["examples"]))
        self.assertEqual(usage.search(list(cards.values()), "open a connection")[0]["name"], "mylib.connect")


if __name__ == "__main__":
    unittest.main()
