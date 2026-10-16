#!/usr/bin/env bash
# Run scripts/check.sh now, and again every time a source file changes. Leave it running in a
# terminal while you work; stop it with Ctrl+C.
#
#   scripts/watch.sh              first run builds, then it watches
#   scripts/watch.sh --no-build   first run reuses the existing jar
#
# Only UI files changed (src/main/resources/static, scripts/)  -> quick run, no rebuild.
# Anything else changed (Java, config, pom.xml, agent files)   -> full rebuild.
# Uses plain polling, so there is nothing to install.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
INTERVAL="${WATCH_INTERVAL:-2}"
MARK="$(mktemp)"
trap 'rm -f "$MARK"' EXIT

changed_since_mark() {
  find src scripts pom.xml agents-local -type f \
    ! -name '.DS_Store' ! -name '*.swp' ! -name '*~' ! -name '*.log' \
    -newer "$MARK" 2>/dev/null | sort
}

run_check() {
  local flag="$1"
  printf '\n\033[1m── %s ── check.sh %s\033[0m\n' "$(date +%H:%M:%S)" "$flag"
  bash "$ROOT/scripts/check.sh" $flag && printf '\n\033[32mPASS\033[0m\n' || printf '\n\033[31mFAIL\033[0m\n'
  printf 'Watching for changes… (Ctrl+C to stop)\n'
}

run_check "${1:-}"    # first run: full build unless you pass --fast or --no-build

while true; do
  sleep "$INTERVAL"
  [ -n "$(changed_since_mark | head -n 1)" ] || continue
  sleep 1                                   # let the editor finish writing
  list="$(changed_since_mark)"
  NEW="$(mktemp)"; mv "$NEW" "$MARK"        # anything edited from here on triggers another run
  printf '\nChanged:\n%s\n' "$(printf '%s\n' "$list" | sed 's/^/  /' | head -n 8)"
  flag=""
  if ! printf '%s\n' "$list" | grep -qvE '^(src/main/resources/static/|scripts/)' && [ -f "$ROOT/target/agent-board.jar" ]; then
    flag="--fast"
  fi
  run_check "$flag"
done
