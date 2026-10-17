#!/usr/bin/env bash
# Build Agent Board, start it on a free port with a throwaway database, drive the UI in a real
# browser (scripts/smoke-ui.cjs), then stop it. Your own data/ folder is never touched, and
# OpenAI/GitHub keys are removed from the test server's environment, so no model request is made.
#
#   scripts/check.sh            build, start, test, stop
#   scripts/check.sh --fast     reuse target/agent-board.jar and serve the UI files straight from
#                               src/main/resources/static (right after a UI-only change)
#   scripts/check.sh --no-build reuse the jar exactly as it is
#   scripts/check.sh --full     also run the Java tests (./mvnw test) first
#   scripts/check.sh --token    test the board as deployed for others (BOARD_AUTH=token)
#   scripts/check.sh --demo     test the example team (ten agents + workflow, as start-local.sh --demo)
#
# Environment: JAR (jar path), CHROME_PATH (browser), SMOKE_KEEP=1 (keep the temp folder)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE=build
FULL=0
AUTH=open
DEMO=0
for arg in "$@"; do
  case "$arg" in
    --fast) MODE=fast ;;
    --no-build) MODE=none ;;
    --full) FULL=1 ;;
    --token) AUTH=token ;;
    --demo) DEMO=1 ;;
    -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

JAR="${JAR:-$ROOT/target/agent-board.jar}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUT="$ROOT/target/smoke"
START=$(date +%s)
say() { printf '\n== %s\n' "$*"; }
die() { printf '\n✗ %s\n' "$*" >&2; exit 1; }

# 1. Prerequisites -------------------------------------------------------------------------
command -v "$JAVA" >/dev/null 2>&1 || die "Java 17+ is required (set JAVA_HOME)."
major=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
[ "${major:-0}" -ge 17 ] || die "Java 17+ is required, found ${major:-unknown}."
command -v node >/dev/null 2>&1 || die "Node.js is required for the browser test."
command -v curl >/dev/null 2>&1 || die "curl is required."
GLOBAL_MODULES="$(npm root -g 2>/dev/null || true)"
export NODE_PATH="$ROOT/node_modules${GLOBAL_MODULES:+:$GLOBAL_MODULES}${NODE_PATH:+:$NODE_PATH}"
node -e "try{require('playwright-core')}catch(e){try{require('playwright')}catch(e2){process.exit(1)}}" 2>/dev/null \
  || die "Playwright is missing. Run once in $ROOT:  npm install --no-save playwright-core"

# 2. Build ---------------------------------------------------------------------------------
if [ "$FULL" = 1 ]; then
  say "Java tests"
  sh "$ROOT/mvnw" -B -ntp -f "$ROOT/pom.xml" test || die "Java tests failed."
fi
if [ "$MODE" = build ]; then
  say "Build"
  sh "$ROOT/mvnw" -B -ntp -q -f "$ROOT/pom.xml" -DskipTests package || die "Build failed."
fi
[ -f "$JAR" ] || die "No jar at $JAR. Run without --fast/--no-build first."

# 3. Start a throwaway server --------------------------------------------------------------
TMP="$(mktemp -d "${TMPDIR:-/tmp}/agent-board-check.XXXXXX")"
PID=""
cleanup() {
  [ -n "$PID" ] && kill "$PID" 2>/dev/null && wait "$PID" 2>/dev/null || true
  if [ "${SMOKE_KEEP:-0}" = 1 ]; then echo "Kept $TMP"; else rm -rf "$TMP"; fi
}
trap cleanup EXIT INT TERM

PORT="$(node -e "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>{console.log(s.address().port);s.close()})")"
BASE="http://127.0.0.1:$PORT"
EXTRA=("--board.auth=$AUTH")
SCRIPT=smoke-ui.cjs
if [ "$DEMO" = 1 ]; then
  EXTRA+=("--board.agents-dir=$TMP/agents" "--board.example-agents-dir=$ROOT/examples/agents" "--board.workflows-dir=$ROOT/examples/workflows")
  SCRIPT=smoke-demo.cjs
fi
if [ "$MODE" = fast ]; then
  EXTRA+=("--spring.web.resources.static-locations=file:$ROOT/src/main/resources/static/")
fi

say "Start on $BASE (data in a temporary folder)"
mkdir -p "$OUT"
(
  cd "$TMP"
  exec env -u OPENAI_API_KEY -u GITHUB_TOKEN -u BOARD_ADMIN_TOKEN -u BOARD_AUTH -u DB_URL -u AGENTS_DIR -u BOARD_EXAMPLE_AGENTS_DIR \
    PORT="$PORT" "$JAVA" -jar "$JAR" --spring.profiles.active=local ${EXTRA[@]+"${EXTRA[@]}"}
) >"$TMP/server.log" 2>&1 &
PID=$!

ready=0
for _ in $(seq 1 120); do
  kill -0 "$PID" 2>/dev/null || break
  if curl -fsS -o /dev/null "$BASE/" 2>/dev/null; then ready=1; break; fi
  sleep 1
done
if [ "$ready" != 1 ]; then
  cp "$TMP/server.log" "$OUT/server.log"
  tail -n 25 "$TMP/server.log" >&2
  die "The server did not start. Full log: $OUT/server.log"
fi
# Only a board started with BOARD_AUTH=token writes a token; the local default needs none.
TOKEN_FILE="$TMP/data/runtime/secrets/initial-admin-token"
if [ "$AUTH" = token ]; then
  for _ in $(seq 1 20); do [ -s "$TOKEN_FILE" ] && break; sleep 0.5; done
  [ -s "$TOKEN_FILE" ] || die "The board token was not created."
fi

# 4. Browser test --------------------------------------------------------------------------
say "Browser checks"
set +e
BOARD_TEST_URL="$BASE" BOARD_TEST_TOKEN_FILE="$TOKEN_FILE" SMOKE_OUT="$OUT" node "$ROOT/scripts/$SCRIPT"
rc=$?
set -e

SECS=$(( $(date +%s) - START ))
if [ "$rc" -eq 0 ]; then
  printf '\n✔ All good (%ss)\n' "$SECS"
else
  cp "$TMP/server.log" "$OUT/server.log"
  printf '\n✗ Checks failed (%ss). Server log: %s\n' "$SECS" "$OUT/server.log" >&2
  grep -E "ERROR|Exception" "$TMP/server.log" | tail -n 5 >&2 || true
fi
exit "$rc"
