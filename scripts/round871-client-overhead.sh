#!/usr/bin/env bash
# ROUND 871 — (WARM.19) part 2: isolate the CLIENT-SIDE cost of a `--serve`
# request from the compile itself.
#
# The ladder in `round871-serve-ladder.sh` measures `client - server` at ~290 ms
# on a ~6,900 ms request. That difference is a SUM of three things — the client
# JVM's own start, the Unix-socket round trip, and the JSON encode/decode of the
# request and the response — and nothing in the ladder separates them from a
# tail of server work that happens outside `CompileResponse.elapsedMs`.
#
# This isolates it with a request the server REFUSES in constant time: `--watch`
# is answered by `CompileServer.respondTo` with `elapsedMs = 0` before any
# compile is attempted (it would wedge the single request thread forever). So a
# refused request's client wall IS the client-side overhead, measured through
# exactly the shipping path, with the compile subtracted by construction rather
# than by arithmetic.
#
# Second arm: `--bare` times the launcher reaching a JVM `main` and returning
# with no socket involved at all (`XTSC_AOT_DECIDE_ONLY=1`, which the launcher
# answers before it execs java) — the bash + AOT-decision part of the same
# figure, so the JVM's own share is the difference.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: round871-client-overhead.sh <outdir> [n]}"
N="${2:-10}"
mkdir -p "$OUT"

SOCK="/tmp/xtsc-r871c-$$.sock"
SRVLOG="$OUT/overhead.server.log"
DAEMON_PID=""
cleanup() { [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null || true; rm -f "$SOCK"; }
trap cleanup EXIT

export XTSC_AOT=off
# Round 872 gave the launcher a NATIVE client arm for `--daemon` requests, which
# is a different measurement from the one this script's header describes. Pin the
# arm rather than silently re-purposing the script: `scripts/round872-client-arms.sh`
# is where the arms are compared.
export XTSC_CLIENT=off
"$ROOT/scripts/xtsc" --serve --socket "$SOCK" > "$SRVLOG" 2>&1 &
DAEMON_PID=$!
for _ in $(seq 1 200); do grep -q "listening on" "$SRVLOG" 2>/dev/null && break; sleep 0.25; done
grep -q "listening on" "$SRVLOG" || { echo "error: daemon never bound" >&2; exit 1; }

echo "arm=refused  (client JVM + socket + JSON; server answers in 0 ms)"
for i in $(seq 1 "$N"); do
  t0=$(date +%s%N)
  "$ROOT/scripts/xtsc" --daemon --socket "$SOCK" --watch . > "$OUT/refused.$i.txt" 2>&1 || true
  t1=$(date +%s%N)
  echo "  refused $i $(( (t1 - t0) / 1000000 )) ms"
done

echo "arm=decideonly  (bash launcher + AOT decision, no JVM, no socket)"
for i in $(seq 1 "$N"); do
  t0=$(date +%s%N)
  XTSC_AOT_DECIDE_ONLY=1 "$ROOT/scripts/xtsc" --daemon --socket "$SOCK" --watch . > /dev/null 2>&1 || true
  t1=$(date +%s%N)
  echo "  decideonly $i $(( (t1 - t0) / 1000000 )) ms"
done

echo "one refused response body (must say 'not supported'):"
head -2 "$OUT/refused.1.txt"
