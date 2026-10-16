#!/usr/bin/env bash
# Start Agent Board on this computer at http://127.0.0.1:${PORT:-8087}/ (no board token needed).
#
#   ./scripts/start-local.sh             build if the source changed, then start
#   ./scripts/start-local.sh --demo      also load the ten example agents and the ten-agent workflow (examples/)
#   PORT=8090 ./scripts/start-local.sh   use another port
#
# Settings come from your shell or from a .env file in this folder (see .env.example).
# A value already set in your shell wins over .env.
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

for arg in "$@"; do
  case "$arg" in
    --demo) export AGENTS_DIR="${AGENTS_DIR:-examples/agents}" BOARD_WORKFLOWS_DIR="${BOARD_WORKFLOWS_DIR:-examples/workflows}" ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# 1. Settings from .env (non-empty values only, never overriding the shell) ----------------------
if [ -f .env ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|\#*) continue ;; esac
    key="${line%%=*}"; value="${line#*=}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${value%\"}"; value="${value#\"}"
    if [ -n "$value" ] && [ -z "${!key:-}" ]; then export "$key=$value"; fi
  done < .env
fi
export PORT="${PORT:-8087}"

# 2. Nothing else may already use the port or the database --------------------------------------
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  echo "Port $PORT is already in use."
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/api/access" 2>/dev/null; then
    echo "An Agent Board is already running there: open http://127.0.0.1:$PORT/ or stop it first."
  fi
  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -nP -t -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ')"
    [ -n "$pids" ] && echo "Stop it with:  kill $pids"
  fi
  echo "Or start on another port:  PORT=8090 ./scripts/start-local.sh"
  exit 1
fi
DB_FILE="data/runtime/agent-board.mv.db"
if [ -z "${DB_URL:-}" ] && [ -f "$DB_FILE" ] && command -v lsof >/dev/null 2>&1; then
  pids="$(lsof -t "$DB_FILE" 2>/dev/null | tr '\n' ' ')"
  if [ -n "$pids" ]; then
    echo "The board database ($DB_FILE) is open in another process: $pids"
    echo "That is usually an older Agent Board. Stop it with:  kill $pids"
    exit 1
  fi
fi

# 3. Build when the jar is missing or older than the source --------------------------------------
JAR=target/agent-board.jar
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -type f -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "Building Agent Board (first start or the source changed)…"
  sh ./mvnw -B -ntp -q -DskipTests package
fi

# 4. Start ---------------------------------------------------------------------------------------
mkdir -p agents-local data/runtime
if [ -z "${OPENAI_API_KEY:-}" ] && [ ! -s data/secrets/openai-api-key ]; then
  echo "Note: OPENAI_API_KEY is not set. You can build agents and workflows; running them needs the key (put it in .env)."
fi
exec sh ./scripts/start.sh --spring.profiles.active=local
