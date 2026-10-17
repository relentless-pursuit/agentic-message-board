#!/usr/bin/env bash
# Non-interactive start for run.yaml and CI: starts Agent Board in the background, waits until it answers,
# prints the address and exits. Running it again while a board answers on the port reuses that board.
#
#   bash ./scripts/run-background.sh --demo     start (same options as start-local.sh)
#   bash ./scripts/run-background.sh --stop     stop the board this script started
#
# For everyday local use, ./scripts/start-local.sh runs the board in the foreground instead.
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8087}"
URL="http://127.0.0.1:$PORT/"
PIDFILE=data/runtime/board.pid
LOG=data/runtime/board.log

if [ "${1:-}" = "--stop" ]; then
  if [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null; then echo "Agent Board stopped."; else echo "No background Agent Board to stop."; fi
  rm -f "$PIDFILE"
  exit 0
fi
if curl -fsS -o /dev/null "${URL}api/access" 2>/dev/null; then
  echo "Design lab (start here): ${URL}architecture-lab.html"
  echo "Agent Board is already running: $URL"
  exit 0
fi

mkdir -p data/runtime
nohup bash ./scripts/start-local.sh "$@" >"$LOG" 2>&1 &
echo $! >"$PIDFILE"
for _ in $(seq 1 180); do
  if curl -fsS -o /dev/null "${URL}api/access" 2>/dev/null; then
    echo "Design lab (start here): ${URL}architecture-lab.html"
    echo "Agent Board is running: $URL"
    echo "Log: $LOG    Stop: bash ./scripts/run-background.sh --stop"
    exit 0
  fi
  if ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "Agent Board did not start. Last lines of $LOG:" >&2
    tail -n 30 "$LOG" >&2
    exit 1
  fi
  sleep 2
done
echo "Agent Board did not answer within 6 minutes. Last lines of $LOG:" >&2
tail -n 30 "$LOG" >&2
exit 1
