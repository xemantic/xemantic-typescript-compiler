#!/usr/bin/env bash
# (INC.44) The equivalence gate for the SPELLING-NARROWED RENAME: at every
# drawn caret of a real project, does `renameAt` with the narrowing ON produce the
# same PLAN — edits, refusal and conflicts — as the whole-program sweep before it?
#
# A differential — both arms are one binary with `Project.narrowReferenceSweeps`
# flipped — so it needs no baseline and cannot go stale. READ `narrowed=` AND
# `applicable=` AS WELL AS `diverged=`: a caret whose spellings cannot be bounded
# falls back and agrees with itself, and a rename that REFUSES in both arms agrees
# trivially, so a run with no applicable plan in it has compared two empty edit lists.
#
# REFUSES (exit 2) rather than skipping when its inputs are absent — a gate that
# passes quietly where its artifact is missing is rounds 853/873 all over again.
#
# Usage: scripts/rename-narrowing-differential.sh [<projectDir> [caretCount]]
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
RUNNER="RenameNarrowingDifferentialMainKt"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/$RUNNER.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT"
exec java -Xmx6g -cp "$CP" \
  "com.xemantic.typescript.compiler.project.$RUNNER" "$PROJECT" "${2:-25}"
