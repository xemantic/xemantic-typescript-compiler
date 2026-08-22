#!/usr/bin/env bash
# MEASURE THE PRIZE for incremental error reporting (the (INC.*) arc).
#
# Two things no other harness here measures:
#   - the cost of a REAL keystroke (LanguageServiceCostMain dirties a file with its
#     own bytes, so the content-keyed parse cache still hits on every file);
#   - the cost of `recheckOnly` — the INV.6 partition seam `ProjectCompiler.build`
#     already takes and `Project` passes null — against a full build, WITH the
#     target file's diagnostics compared row for row.
#
# REFUSES rather than skips when its inputs are absent (CLAUDE.md rounds 853/873).
#
# Usage: scripts/incremental-cost.sh [<projectDir> [<fileSuffix> [rotations]]]
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
  echo "REFUSED: no project. Materialize one with:" >&2
  echo "  scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log" >&2
  exit 2; }

FILE="${2:-src/compiler/semver.ts}"
ROTATIONS="${3:-3}"

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/IncrementalCostMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES"
CP="$CP:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"
CP="$CP:$DEPS"

echo "project: $PROJECT  target: $FILE  rotations: $ROTATIONS"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.IncrementalCostMainKt \
  "$PROJECT" "$FILE" "$ROTATIONS"
