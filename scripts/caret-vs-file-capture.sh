#!/usr/bin/env bash
# (INC.13) The free oracle for stage 2: at a FIXED partition, does a span answer
# the same whether it is asked ALONE (one caret) or as part of its file's WHOLE
# span set? The two arms answer the same question, so any divergence is a defect in
# one of them and no baseline is needed.
#
# It is deliberately NOT `capture-equivalence.sh`, which varies the PARTITION at a
# fixed request; this varies the REQUEST at a fixed partition. Keeping them apart is
# (INC.2b)'s rule — a census that mixes two mechanisms cannot attribute either.
# REFUSES rather than skips when its inputs are absent.
#
# Usage: scripts/caret-vs-file-capture.sh [<projectDir> [maxFiles [caretsPerFile]]]
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
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/CaretVsFileCaptureMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.CaretVsFileCaptureMainKt \
  "$PROJECT" "${2:-2147483647}" "${3:-12}"
