#!/usr/bin/env bash
# Agent Board: one script to build, start and stop the board on this computer.
#
#   bash start.sh          build if needed, start in the background with the ten-agent demo, and print the links
#   bash start.sh stop     stop the board
#   PORT=8090 bash start.sh   use another port (default 8087)
#
# Settings live in .env (git-ignored). The first start copies .env.example to .env and, in a terminal,
# asks for your OpenAI key. Edit it later with:  open -e .env  (macOS)  or  nano .env
set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd)"

PORT="${PORT:-8087}"
URL="http://127.0.0.1:$PORT/"
PIDFILE=data/runtime/board.pid
LOG=data/runtime/board.log
JAR=target/agent-board.jar

case "${1:-}" in
  stop)
    if [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null; then echo "Agent Board stopped."; else echo "No Agent Board started by this script is running."; fi
    rm -f "$PIDFILE"; exit 0 ;;
  ""|start) ;;
  -h|--help|help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown option: $1 (try: bash start.sh help)" >&2; exit 2 ;;
esac

links() {
  echo "Design lab (start here): ${URL}architecture-lab.html"
  echo "Ten-agent demo:          ${URL}#demo"
  echo "Agent Board:             $URL"
}
if curl -fsS -o /dev/null "${URL}api/access" 2>/dev/null; then
  echo "Agent Board is already running."; links; exit 0
fi

# 1. Settings: .env, created from .env.example on the first start --------------------------------
if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example (git-ignored)."
fi
setting() { sed -n "s/^$1=//p" .env | tail -n 1 | sed 's/^"//; s/"$//'; }
save_setting() {  # replace or add KEY=value in .env without printing the value
  local tmp; tmp="$(mktemp)"
  grep -v "^$1=" .env >"$tmp" || true
  printf '%s=%s\n' "$1" "$2" >>"$tmp"
  mv "$tmp" .env
}
if [ -t 0 ] && [ -z "${OPENAI_API_KEY:-}" ] && [ -z "$(setting OPENAI_API_KEY)" ]; then
  read -r -s -p "OpenAI API key (needed to run agents; Enter to skip): " key; echo
  [ -n "$key" ] && save_setting OPENAI_API_KEY "$key" && echo "Saved to .env."
fi
if [ -t 0 ] && [ ! -f data/runtime/agent-board.mv.db ] && [ -z "${GITHUB_TOKEN:-}" ] && [ -z "$(setting GITHUB_TOKEN)" ]; then
  read -r -s -p "GitHub token for the demo's GitHub connector (read-only is enough; Enter to skip): " key; echo
  [ -n "$key" ] && save_setting GITHUB_TOKEN "$key" && echo "Saved to .env."
fi
# Values already set in the shell win over .env.
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in ''|\#*) continue ;; esac
  key="${line%%=*}"; value="${line#*=}"
  [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
  value="${value%\"}"; value="${value#\"}"
  if [ -n "$value" ] && [ -z "${!key:-}" ]; then export "$key=$value"; fi
done < .env
export PORT
export BOARD_EXAMPLE_AGENTS_DIR="${BOARD_EXAMPLE_AGENTS_DIR:-examples/agents}" BOARD_WORKFLOWS_DIR="${BOARD_WORKFLOWS_DIR:-examples/workflows}"

# 2. Java 17+, and nothing else on the port ------------------------------------------------------
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
major="$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
if [ -z "$major" ] || [ "$major" -lt 17 ]; then
  echo "Java 17 or newer is required. Install it, or set JAVA_HOME to a JDK 17+." >&2; exit 1
fi
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  echo "Port $PORT is used by another program. Stop it, or start on another port: PORT=8090 bash start.sh" >&2; exit 1
fi

# 3. Build when the jar is missing or older than the source --------------------------------------
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -type f -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "Building Agent Board (first start or the source changed; the first build downloads dependencies)…"
  sh ./mvnw -B -ntp -q -DskipTests package
fi

# 4. Start in the background and wait until it answers -------------------------------------------
mkdir -p agents-local data/runtime
[ -z "${OPENAI_API_KEY:-}" ] && echo "Note: no OpenAI key yet, so runs stay disabled. Add it to .env (open -e .env, or nano .env), then: bash start.sh stop && bash start.sh"
command -v uvx >/dev/null 2>&1 || echo "Note: uv is not installed, so the Web fetch connector is skipped (https://docs.astral.sh/uv/)."
nohup "$JAVA_BIN" -jar "$JAR" --spring.profiles.active=local >"$LOG" 2>&1 &
echo $! >"$PIDFILE"
for _ in $(seq 1 180); do
  if curl -fsS -o /dev/null "${URL}api/access" 2>/dev/null; then
    echo "Agent Board is running."; links
    echo "Log: $LOG    Stop: bash start.sh stop"
    if [ -t 1 ]; then  # in a terminal, open the design lab and the demo
      for page in "${URL}architecture-lab.html" "${URL}#demo"; do
        if command -v open >/dev/null 2>&1; then open "$page"; elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$page" >/dev/null 2>&1 || true; fi
      done
    fi
    exit 0
  fi
  if ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "Agent Board did not start. Last lines of $LOG:" >&2; tail -n 30 "$LOG" >&2; exit 1
  fi
  sleep 2
done
echo "Agent Board did not answer within 6 minutes. Last lines of $LOG:" >&2; tail -n 30 "$LOG" >&2; exit 1
