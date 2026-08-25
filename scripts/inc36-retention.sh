#!/usr/bin/env bash
# (INC.36) ATTRIBUTE the heap a whole-program `referencesAt` RETAINS.
#
# (INC.36) records the operational fact and names the number to attack: the sweep
# peaks at 1,077-1,125 MB in G1 old gen and RETAINS 264 MB after a full GC, is green
# at -Xmx2g and OOMs at -Xmx1g. The peak is transient allocation the collector already
# handles; the retention is what a plugin's host JVM has to carry, so this decomposes
# it — a subtraction ladder over `liveAfterGc`, one row per retainer, plus a jcmd
# class histogram at peak retention and again after everything is dropped.
#
# Two processes minimum for anything quoted (CLAUDE.md: a heap/leaf reading is a
# collector's decision, not a counter), so the default is two runs.
#
# Usage: scripts/inc36-retention.sh [runs] [ladder|second|reparse] [<projectDir>]
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

RUNS="${1:-2}"
MODE="${2:-ladder}"
PROJECT="${3:-}"
if [[ -z "$PROJECT" ]]; then
  shopt -s nullglob
  for candidate in build/bench/tsc-project-*; do
    [[ -f "$candidate/tsconfig.json" ]] && PROJECT="$candidate"
  done
  shopt -u nullglob
fi
# REFUSES rather than skips when its input is absent — CLAUDE.md rounds 853/873: a
# gate that passes quietly where its artifact is missing is worse than no gate.
if [[ -z "$PROJECT" || ! -f "$PROJECT/tsconfig.json" ]]; then
  echo "REFUSED: no compiler profile under build/bench (or '$PROJECT' names no project)." >&2
  echo "         Materialize one with: scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log" >&2
  exit 2
fi

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
if [[ ! -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc36RetentionMainKt.class" ]]; then
  echo "REFUSED: the runner is not in $CLASSES — build first:" >&2
  echo "         ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2
fi

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES"
CP="$CP:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"
CP="$CP:$DEPS"

OUT="$(mktemp -t inc36-retention.XXXXXX)"
HDIR="$(mktemp -d -t inc36-hist.XXXXXX)"
HELPER=""
cleanup() {
  [[ -n "$HELPER" ]] && kill "$HELPER" 2>/dev/null
  rm -f "$OUT"
  rm -rf "$HDIR"
}
trap cleanup EXIT

# The class histogram is taken by THIS helper and never by the runner shelling out to
# `jcmd` with its own pid: a JVM attaching to itself HUNG the whole ladder for thirty
# minutes (2026-08-25), printing a plausible partial table and then nothing. The
# runner asks through $HDIR/request and gives up after 90s, so a dead helper costs a
# corroborating histogram and never the census.
export INC36_HIST_DIR="$HDIR"
(
  while [[ ! -f "$HDIR/stop" ]]; do
    if [[ -f "$HDIR/request" ]]; then
      hpid="$(head -1 "$HDIR/request" 2>/dev/null || true)"
      if [[ -n "$hpid" ]]; then
        jcmd "$hpid" GC.class_histogram > "$HDIR/answer.tmp" 2>&1 ||           echo "jcmd failed for pid $hpid" > "$HDIR/answer.tmp"
        mv "$HDIR/answer.tmp" "$HDIR/answer"
      fi
      rm -f "$HDIR/request"
    fi
    sleep 0.5
  done
) &
HELPER=$!

echo "project: $PROJECT  runs: $RUNS  mode: $MODE"
echo "commit:  $(git rev-parse --short HEAD 2>/dev/null || echo '<unknown>')  date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# -Xmx6g deliberately: a CONSTRAINED heap makes G1 collect harder and changes what a
# retention reading says, so the census runs with headroom the arm cannot exhaust.
# The -Xmx1g/2g floor question is (INC.36)'s already-measured half and is not re-asked.
for run in $(seq 1 "$RUNS"); do
  echo "=== process $run of $RUNS ==="
  set +e
  java -Xmx6g -cp "$CP" \
    com.xemantic.typescript.compiler.project.Inc36RetentionMainKt \
    "$PROJECT" "$MODE" 2>&1 | tee -a "$OUT"
  STATUS="${PIPESTATUS[0]}"
  set -e
  [[ "$STATUS" -eq 0 ]] || { echo "REFUSED: process $run exited $STATUS" >&2; exit "$STATUS"; }
done

# ---- POSITIVE CONTROL -------------------------------------------------------
# Exit 0 says the JVM finished, not that anything was measured (round 947).
fail=0
grep -aq '^LADDER 3.referencesAt ' "$OUT" || {
  echo "REFUSED: no '3.referencesAt' ladder row — the arm never ran." >&2; fail=1; }
grep -aq '^CENSUS ' "$OUT" || { echo "REFUSED: no CENSUS row." >&2; fail=1; }
grep -aq '^CONTROL positive: OK' "$OUT" || {
  echo "REFUSED: the runner's own positive control did not pass." >&2; fail=1; }
grep -aq '^CONTROL attribution: OK' "$OUT" || {
  echo "REFUSED: the runner's own attribution control did not pass." >&2; fail=1; }
# A whole-program reference answer on a 78-file program is not empty, and an empty one
# would make every heap row a measurement of an early return.
HITS="$(grep -a '^ANSWER referencesAt ' "$OUT" | head -1 | sed 's/.*hits=\([0-9]*\).*/\1/')"
if [[ -z "$HITS" || "$HITS" -lt 1 ]]; then
  echo "REFUSED: referencesAt reported '${HITS:-<absent>}' hits — the arm is vacuous." >&2
  fail=1
fi
# ...and the ladder must have as many rows as there were processes, or a run died
# silently after printing a partial table.
ROWS="$(grep -ac '^LADDER 0.baseline ' "$OUT")"
if [[ "$ROWS" -ne "$RUNS" ]]; then
  echo "REFUSED: $ROWS baseline rows for $RUNS processes — a run did not complete." >&2
  fail=1
fi
[[ "$fail" -eq 0 ]] || exit 3
echo "positive control: OK ($RUNS processes, $HITS hits on the first)"
