#!/usr/bin/env bash
# (INC.14) THE ORDER-DEPENDENCE DIFFERENTIAL — run this BEFORE any checker surgery.
#
# Two arms that must agree, needing no recorded baseline (`capture-equivalence.sh`'s
# shape): a COLD arm answering one query per build, and a SHARED arm answering
# `groupSize` queries per build — i.e. `groupSize` queries served by ONE `Checker`,
# which is exactly what (INC.14) proposes and what makes WHICH QUERY RAN FIRST
# observable. Compares captured TYPES, captured DEFINITIONS and DIAGNOSTICS, per
# file. Its wall ratio is the (INC.14) prize measured directly.
#
# REFUSES rather than skips when its inputs are absent — a differential that quietly
# compares nothing is round 853's defect.
#
# Usage: scripts/checker-reuse-differential.sh [<projectDir> [groupSize [maxFiles [dumpFile]]]]
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
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/CheckerReuseDifferentialMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.CheckerReuseDifferentialMainKt \
  "$PROJECT" "${2:-8}" "${3:-2147483647}" ${4:+"$4"}
