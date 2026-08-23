#!/usr/bin/env bash
# (INC.18) THE PARTITION GATE, BOTH ARMS.
#
#   realism      — tsc's own 78 sources. The shape a real editor session has, and
#                  the arm that has always run. Its SENSITIVITY is 1: every
#                  diagnostic it reports is netted by `checkSpine`, and 5 of its 78
#                  files carry any row at all, so it compares an essentially empty
#                  per-file population and cannot see a starved partition.
#   sensitivity  — test-fixtures/partition-gate. Many files, each carrying rows a
#                  DIFFERENT dedicated walker owns. Same comparison, and it REFUSES
#                  below its own floors rather than printing green.
#
# Neither arm replaces the other. REFUSES rather than skips when its inputs are
# absent — a gate that passes quietly where its subject is missing is round 853's
# defect (`scripts/round920-token-gate.sh` is the reference for the refusal).
#
# Usage: scripts/partition-gate.sh [realism|sensitivity|both]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
ARM="${1:-both}"

# The floors the sensitivity arm is held to. They are deliberately far below what
# the fixture measures (77 passes / 66 files at the round it landed): the point is
# to catch the fixture GOING BLIND, not to re-pin its exact content, which drifts
# with every walker that gains or loses a diagnostic.
MIN_PASSES=40
MIN_FILES_CARRYING=40

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
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/PartitionGateMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

rc=0
if [[ "$ARM" == "both" || "$ARM" == "realism" ]]; then
  echo "===== ARM: realism (tsc's own sources) ====="
  java -Xmx6g -cp "$CP" \
    com.xemantic.typescript.compiler.project.PartitionGateMainKt "$PROFILE" 0 1 || rc=$?
fi
if [[ "$ARM" == "both" || "$ARM" == "sensitivity" ]]; then
  echo "===== ARM: sensitivity (test-fixtures/partition-gate) ====="
  java -Xmx6g -cp "$CP" \
    com.xemantic.typescript.compiler.project.PartitionGateMainKt \
    "$FIXTURE" "$MIN_PASSES" "$MIN_FILES_CARRYING" || rc=$?
fi
exit $rc
