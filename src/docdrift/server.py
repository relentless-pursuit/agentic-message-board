"""Open hooks: a small HTTP service other systems can call.

    POST /hooks/github      GitHub push or pull_request webhook (signed with X-Hub-Signature-256) -> starts a check
    GET  /api/reports/<id>  the check's report (JSON)
    GET  /api/cards         usage cards (JSON);  GET /api/ask?q=...  closest APIs for a question
    GET  /                  the usage-card site

Every finished check is also POSTed to DOCDRIFT_NOTIFY_URL (Slack, Teams, a ticketing system...) when it is set,
signed with the same secret so the receiver can verify it came from here.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import threading
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from .gitrepo import Repo
from .pipeline import run_check
from .reasoner import pick
from . import usage

SHA = re.compile(r"^[0-9a-f]{7,40}$")
MAX_BODY = 1_000_000


def signature(secret: str, body: bytes) -> str:
    return "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()


def refs_from_event(event: str, payload: dict) -> tuple[str, str] | None:
    if event == "push":
        before, after = payload.get("before", ""), payload.get("after", "")
    elif event == "pull_request":
        pr = payload.get("pull_request") or {}
        before, after = (pr.get("base") or {}).get("sha", ""), (pr.get("head") or {}).get("sha", "")
    else:
        return None
    if not (SHA.match(before) and SHA.match(after)) or set(before) == {"0"}:
        return None
    return before, after


class App:
    def __init__(self, repo: Repo, secret: str, notify_url: str = "", reasoner: str = "auto", fetch: bool = True):
        self.repo, self.secret, self.notify_url, self.reasoner, self.fetch = repo, secret, notify_url, reasoner, fetch
        self.reports: dict[str, dict] = {}
        self.cards = usage.build(repo, "HEAD")
        self.page = usage.site(self.cards, f"{repo.path.name} · usage cards")

    def start_check(self, base: str, head: str) -> str:
        job = uuid.uuid4().hex[:12]
        self.reports[job] = {"status": "running", "base": base, "head": head}
        threading.Thread(target=self._check, args=(job, base, head), daemon=True).start()
        return job

    def _check(self, job: str, base: str, head: str) -> None:
        try:
            if self.fetch:
                self.repo.git("fetch", "--quiet", "origin", base, head)
            res = run_check(self.repo, base, head, pick(self.reasoner, self.repo.path.name))
            self.reports[job] = {"status": "done", **res.to_dict(), "patch": res.patch}
        except Exception as e:  # report the failure instead of losing it
            self.reports[job] = {"status": "failed", "base": base, "head": head, "error": str(e)[:500]}
        self.notify(job)

    def notify(self, job: str) -> None:
        if not self.notify_url:
            return
        body = json.dumps({"job": job, **{k: v for k, v in self.reports[job].items() if k != "patch"}}).encode()
        req = urllib.request.Request(self.notify_url, data=body, method="POST", headers={
            "Content-Type": "application/json", "X-DocDrift-Signature": signature(self.secret, body)})
        try:
            urllib.request.urlopen(req, timeout=10).close()
        except OSError:
            self.reports[job]["notify_error"] = "could not reach DOCDRIFT_NOTIFY_URL"


def handler(app: App):
    class H(BaseHTTPRequestHandler):
        def _send(self, code: int, body, ctype="application/json"):
            data = body if isinstance(body, bytes) else (body if isinstance(body, str) else json.dumps(body)).encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *a):
            pass

        def do_GET(self):
            u = urlparse(self.path)
            if u.path == "/":
                return self._send(200, app.page, "text/html; charset=utf-8")
            if u.path == "/api/cards":
                return self._send(200, app.cards)
            if u.path == "/api/ask":
                q = (parse_qs(u.query).get("q") or [""])[0][:300]
                return self._send(200, {"question": q, "results": usage.search(app.cards, q)})
            m = re.fullmatch(r"/api/reports/([0-9a-f]{12})", u.path)
            if m and m.group(1) in app.reports:
                return self._send(200, app.reports[m.group(1)])
            if u.path == "/health":
                return self._send(200, {"ok": True})
            self._send(404, {"error": "not found"})

        def do_POST(self):
            if urlparse(self.path).path != "/hooks/github":
                return self._send(404, {"error": "not found"})
            if not app.secret:
                return self._send(503, {"error": "DOCDRIFT_WEBHOOK_SECRET is not set; webhooks are refused"})
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                return self._send(413, {"error": "payload too large"})
            body = self.rfile.read(length)
            if not hmac.compare_digest(signature(app.secret, body), self.headers.get("X-Hub-Signature-256", "")):
                return self._send(401, {"error": "bad signature"})
            event = self.headers.get("X-GitHub-Event", "")
            try:
                refs = refs_from_event(event, json.loads(body or b"{}"))
            except ValueError:
                return self._send(400, {"error": "body is not JSON"})
            if not refs:
                return self._send(202, {"ignored": f"nothing to check for event '{event}'"})
            return self._send(202, {"job": app.start_check(*refs)})
    return H


def serve(repo: Repo, host: str, port: int, reasoner: str = "auto") -> None:
    app = App(repo, os.environ.get("DOCDRIFT_WEBHOOK_SECRET", ""), os.environ.get("DOCDRIFT_NOTIFY_URL", ""), reasoner)
    srv = ThreadingHTTPServer((host, port), handler(app))
    print(f"DocDrift on http://{host}:{port}/  ·  webhook: POST /hooks/github" + ("" if app.secret else "  (disabled: set DOCDRIFT_WEBHOOK_SECRET)"))
    srv.serve_forever()
