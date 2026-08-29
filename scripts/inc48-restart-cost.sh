#!/usr/bin/env bash
# (INC.48) WHAT AN IDE RESTART COSTS, with and without a persisted state.
#
# Three arms on one project, ABBA-rotated, agreeing row for row: a cold `Project.open`
# + `diagnostics()` (what every host pays today on every restart), the same with a
# snapshot restored and nothing changed on disk, and the same with one file's text
# changed since the snapshot.
#
# REFUSES rather than skips when its inputs are absent (rounds 853/873).
#
# The COLD arms are separate processes on purpose: an IDE restart is not warm, and a
# single arm with no warm-up is the only honest way to quote one.
#   scripts/inc48-restart-cost.sh "" 1 cold-open
#   scripts/inc48-restart-cost.sh "" 1 restored-clean
#
# Usage: scripts/inc48-restart-cost.sh [<projectDir> [rotations [cold-arm]]]
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
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc48RestartCostMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "commit:  $(git rev-parse --short HEAD)  date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.Inc48RestartCostMainKt \
  "$PROJECT" "${2:-3}" ${3:+"$3"}
