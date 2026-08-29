#!/usr/bin/env bash
# (INC.46) STEP 1 — the COST of the exported-signature fingerprint on a FULL build,
# which the queue entry left as an argument and demanded be measured before
# anything downstream of it is built.
#
# The queue's own refusal threshold: "if it is not single-digit ms on types.ts's
# 874 exports, stop." The runner prints that cell, the whole-program total, the
# walk's own population, the whole-program ESCAPE set, and — the control a cost
# figure cannot supply — whether two builds of IDENTICAL text produce IDENTICAL
# fingerprints, which is the id-freedom claim under test.
#
# REFUSES rather than skips when its inputs are absent (rounds 853/873).
#
# Usage: scripts/inc46-fingerprint-cost.sh [<projectDir> [rotations]]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

PROJECT="${1:-}"
if [[ -z "$PROJECT" ]]; then
  shopt -s nullglob
  for candidate in build/bench/tsc-project-*; do
    [[ -f "$candidate/tsconfig.json" ]] && PROJECT="$candidate"
  done
  shopt -u nullglob
fi
[[ -n "$PROJECT" && -f "$PROJECT/tsconfig.json" ]] || {
  echo "REFUSED: no project at '${1:-build/bench/tsc-project-*}' — materialize one with" \
       "scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log" >&2
  exit 2; }

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc46FingerprintCostMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT"
echo "commit:  $(git rev-parse --short HEAD 2>/dev/null || echo '<unknown>')  date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.Inc46FingerprintCostMainKt \
  "$PROJECT" "${2:-3}"
