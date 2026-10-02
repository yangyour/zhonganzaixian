#!/usr/bin/env bash
# Run from the repository root. All children and the interactive QA shell share one namespace.
# This starts existing prepared components only; it never creates credentials or migrates.
set -euo pipefail
# Parse the runner body once; editing the repository copy cannot alter a running shell mid-function.
main() {
MODE=${1:-}
[[ $# -le 1 && ( -z "$MODE" || "$MODE" == '--verify-owned-api-crash' ) ]] || { echo 'Use no argument or the explicit --verify-owned-api-crash mode'; exit 2; }
ROOT=$(pwd)
BASE="$ROOT/../mop-toolchain/components"
LOCAL="$ROOT/.local/niko-test"
[[ -f "$LOCAL/data/postgres/PG_VERSION" && -f "$LOCAL/private/runtime.json" ]] || { echo 'Prepare the explicitly approved local environment first'; exit 1; }
if [[ "$MODE" == '--verify-owned-api-crash' ]]; then python tools/niko-test/api-crash-snapshot.py --check-new-run; fi
export TZ=UTC LD_LIBRARY_PATH="$BASE/pg/usr/lib/x86_64-linux-gnu"
PG_PID='' KC_PID='' API_PID=''
owned_child() {
  [[ -n "$1" && -r "/proc/$1/stat" ]] || return 1
  [[ "$(awk '{print $4}' "/proc/$1/stat")" == "$$" ]]
}
cleanup() {
  # Only this launch's direct children. Never discover/stop unrelated processes by port/name.
  for pid in "$API_PID" "$KC_PID"; do
    if owned_child "$pid"; then kill -TERM "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  done
  if owned_child "$PG_PID"; then kill -INT "$PG_PID"; wait "$PG_PID" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM
"$BASE/pg/usr/lib/postgresql/17/bin/postgres" -D "$LOCAL/data/postgres" -h 127.0.0.1 -p 25432 -c unix_socket_directories='' -c max_connections=60 -c shared_buffers=128MB -c timezone=UTC -c log_timezone=UTC -c log_statement=none -c log_min_error_statement=panic >> "$LOCAL/logs/postgres.log" 2>&1 &
PG_PID=$!
python tools/niko-test/start-keycloak.py >> "$LOCAL/logs/keycloak.log" 2>&1 &
KC_PID=$!
python tools/niko-test/wait-components.py
python tools/niko-test/start-api.py >> "$LOCAL/logs/api.log" 2>&1 &
API_PID=$!
python tools/niko-test/owned_api.py --wait-ready "$API_PID" "$$"
export PATH="$ROOT/../mop-toolchain/node_modules/.bin:$PATH"
if [[ "$MODE" == '--verify-owned-api-crash' ]]; then
  python tools/niko-test/capture-http-sessions.py > "$LOCAL/logs/api-crash-sessions-before.json"
  node tests/niko-api-crash-http.mjs before
  python tools/niko-test/api-crash-snapshot.py --record
  # The signal target comes only from this launch; validate parent and exact JVM command again.
  owned_child "$API_PID"
  python tools/niko-test/owned_api.py --require-listener "$API_PID" "$$"
  kill -KILL "$API_PID"
  if wait "$API_PID"; then API_EXIT=0; else API_EXIT=$?; fi
  [[ "$API_EXIT" == 137 ]] || { echo 'Unexpected API exit; crash proof is incomplete'; exit 1; }
  API_PID=''
  python tools/niko-test/api-crash-snapshot.py --api-unavailable
  python tools/niko-test/start-api.py >> "$LOCAL/logs/api.log" 2>&1 &
  API_PID=$!
  python tools/niko-test/owned_api.py --wait-ready "$API_PID" "$$"
  # Compare before logging in again, because real OIDC login intentionally adds audit entries.
  python tools/niko-test/api-crash-snapshot.py --verify
  python tools/niko-test/capture-http-sessions.py > "$LOCAL/logs/api-crash-sessions-after.json"
  node tests/niko-api-crash-http.mjs after
  exit 0
fi
echo 'niko-local-test: use this shell for local HTTP and database QA; exit stops owned components'
bash --noprofile --norc

}
main "$@"
