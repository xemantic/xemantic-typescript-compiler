#!/usr/bin/env bash
# (INC.17) step 2 — THE REPLAY-vs-FRESH-BUILD DIFFERENTIAL, both arms.
#
# The re-entrant recheck's whole correctness claim is that for a file the checker
# was NOT originally asked about, the replayed answer equals a fresh narrowed
# build's answer. This compares them per file over diagnostics AND captured
# types/definitions — the second channel because (INC.18)'s arm a3 is a recorded
# NEGATIVE: a starved collector is invisible to a diagnostics comparison and shows
# up as a wrong TYPE.
#
#   realism      — tsc's own 78 sources. Many capture spans, few diagnostics.
#   sensitivity  — test-fixtures/partition-gate. Many files carrying rows from many
#                  distinct passes (78 of them, against the profile's 1), which is
#                  the resolution the DIAGNOSTIC channel needs.
#
# Neither arm replaces the other, and this REFUSES rather than skips when its
# inputs are absent (round 853: a gate that passes quietly where its subject is
# missing is worth nothing).
#
# Usage: scripts/replay-differential.sh [realism|sensitivity|both] [maxFiles]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
ARM="${1:-both}"
MAX="${2:-0}"
# `all` re-enters EVERY pass — the attribution arm, not a gate. A divergence that
# survives it is the seed build's cache order, not a classification defect.
ALL="${3:-}"
# An optional comma-separated list of file suffixes to compare (attribution only).
ONLY="${4:-}"

FIXTURE="$ROOT/test-fixtures/partition-gate"
[[ -f "$FIXTURE/tsconfig.json" ]] || {
  echo "REFUSED: no fixture at $FIXTURE" >&2; exit 2; }

PROFILE=""
shopt -s nullglob
for candidate in build/bench/tsc-project-*; do
  [[ -f "$candidate/tsconfig.json" ]] && PROFILE="$candidate"
done
shopt -u nullglob
if [[ "$ARM" != "sensitivity" ]]; then
  [[ -n "$PROFILE" ]] || {
    echo "REFUSED: no tsc profile at build/bench/tsc-project-* — create it with" \
         "scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log" >&2
    exit 2; }
fi

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/ReplayDifferentialMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

run() {
  java -Xmx6g -cp "$CP" \
    com.xemantic.typescript.compiler.project.ReplayDifferentialMainKt "$1" "$MAX" "$ALL" "$ONLY"
}

rc=0
if [[ "$ARM" == "both" || "$ARM" == "sensitivity" ]]; then
  echo "===== ARM: sensitivity (test-fixtures/partition-gate) ====="
  run "$FIXTURE" || rc=$?
fi
if [[ "$ARM" == "both" || "$ARM" == "realism" ]]; then
  echo "===== ARM: realism (tsc's own sources) ====="
  run "$PROFILE" || rc=$?
fi
exit $rc
