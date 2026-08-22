#!/usr/bin/env bash
# (INC.2) The equivalence gate for a CAPTURE under a partition of ONE: for every
# file of a real project, do `recheckOnly = {file}` and a full build capture the
# same TYPES and DEFINITIONS at every identifier of that file?
#
# It deliberately does NOT inherit (INC.1)'s sweep: that one compared diagnostics,
# this compares first-touch type identity (interning order, `aliasDisplayMap`, the
# alias a union displays under), which a narrowed walk could plausibly change with
# every diagnostic still agreeing. REFUSES rather than skips when its inputs are
# absent.
#
# Usage: scripts/capture-equivalence.sh [<projectDir> [maxFiles]]
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
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/CaptureEquivalenceMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.CaptureEquivalenceMainKt "$PROJECT" "${2:-2147483647}"
