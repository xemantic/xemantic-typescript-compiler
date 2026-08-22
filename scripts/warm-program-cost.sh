#!/usr/bin/env bash
# (INC.12) step 1 — PRICE THE WARM PROGRAM. Drives the public `Project` API in the
# order an editor would (a query, a second query with NOTHING changed, a query after
# ONE buffer changed) and prints a FrontEnd phase table for a narrowed build beside
# it. REFUSES rather than skips when its inputs are absent — a cost script that
# quietly measures nothing is round 853's defect, and its symptom is a plausible
# table.
#
# Usage: scripts/warm-program-cost.sh [<projectDir> [<fileA> [<fileB> [rotations]]]]
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
  echo "REFUSED: no project at '${1:-build/bench/tsc-project-*}'" >&2; exit 2; }

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/WarmProgramCostMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.WarmProgramCostMainKt \
  "$PROJECT" "${2:-src/compiler/checker.ts}" "${3:-src/compiler/binder.ts}" "${4:-3}"
