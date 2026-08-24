#!/usr/bin/env bash
# (INC.37) step 1 — DECOMPOSE ONE FILE'S OWN CHECKING. Runs
# `FileCheckDecompositionMain` over a real project and prints, per target file,
# the per-pass table for a NARROWED build (`recheckOnly = {file}`) and for the
# FLOOR (`recheckOnly` naming a file the program does not contain). The file's
# own checking is the DIFFERENCE, per pass and per wall.
#
# REFUSES rather than skips when its inputs are absent — a decomposition script
# that quietly measures nothing is round 853's defect, and its symptom is a
# plausible table.
#
# Usage: scripts/file-check-decomposition.sh [<projectDir> [warmups]]
#   XTSC_FCD_FILES=a.ts,b.ts   override the size ladder (suffix match)
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
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/FileCheckDecompositionMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.FileCheckDecompositionMainKt "$PROJECT" "${2:-6}"
