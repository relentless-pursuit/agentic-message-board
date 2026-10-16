#!/usr/bin/env sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN=java
fi
major=$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
if [ -z "$major" ] || [ "$major" -lt 17 ]; then
  echo 'Java 17 or newer is required. Set JAVA_HOME to a compatible JDK.' >&2
  exit 1
fi
if [ ! -f target/agent-board.jar ]; then
  echo 'Build first: mvn -Dmaven.test.skip=false -DskipTests=false package' >&2
  exit 1
fi
printf 'Starting Agent Board at http://127.0.0.1:%s\n' "${PORT:-8080}"
exec "$JAVA_BIN" -jar target/agent-board.jar "$@"
