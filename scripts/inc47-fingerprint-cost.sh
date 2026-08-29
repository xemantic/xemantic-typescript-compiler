#!/usr/bin/env bash
# (INC.47) The WHOLE-PROGRAM fingerprint census: cost, escape set, and the two
# controls (identical-text stability, narrowed-vs-whole partition agreement).
#
# It re-takes what scripts/inc46-fingerprint-cost.sh measured, but through
# ProjectCompiler rather than Project.diagnostics() — which (INC.46) step (3) made
# INCREMENTAL, so that script now fingerprints ONE file per rotation and prints
# `of 1 files` / `escapes: []` / `PARTITION-AGREEMENT 1/24`. A decayed instrument
# that reads like a clean bill of health; this one cannot decay the same way.
#
# The row (INC.47) exists to move is `types.ts`, a node-budget STOP and therefore an
# ESCAPE — which makes every edit touching it fall back to a whole-program build.
#
# REFUSES rather than skips when its inputs are absent (rounds 853/873).
#
# Usage: scripts/inc47-fingerprint-cost.sh [<projectDir> [rotations]]
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
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc47FingerprintCostMainKt.class" ]] || {
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
  com.xemantic.typescript.compiler.project.Inc47FingerprintCostMainKt \
  "$PROJECT" "${2:-3}"
