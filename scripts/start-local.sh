#!/usr/bin/env sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
mkdir -p agents-local data/runtime
export PORT="${PORT:-8087}"
exec ./scripts/start.sh --spring.profiles.active=local
