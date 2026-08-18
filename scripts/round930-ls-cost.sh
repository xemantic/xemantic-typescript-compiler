#!/usr/bin/env bash
# (API.13) Re-take docs/language-service.md § 14's cost table on a REAL project.
#
# The wall figures there are a property of a local artifact (tsc's own sources under
# build/bench) and of the box, so they cannot be pinned by the suite — the build
# COUNTS are, by LanguageServiceStateTest. This is the other half. It REFUSES rather
# than skips when the profile is absent (CLAUDE.md rounds 853/873: a gate that passes
# quietly where its input is missing is worse than no gate).
#
# Usage: scripts/round930-ls-cost.sh [<projectDir> [<fileSuffix> <needle> [rotations]]]
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
if [[ -z "$PROJECT" || ! -f "$PROJECT/tsconfig.json" ]]; then
  echo "REFUSED: no compiler profile under build/bench." >&2
  echo "         Materialize one with: scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log" >&2
  exit 2
fi

FILE="${2:-src/compiler/types.ts}"
NEEDLE="${3-}"
[[ -z "$NEEDLE" ]] && NEEDLE="SyntaxKind {"
ROTATIONS="${4:-3}"

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
if [[ ! -f "$CLASSES/com/xemantic/typescript/compiler/project/LanguageServiceCostMainKt.class" ]]; then
  echo "REFUSED: the runner is not in $CLASSES — build first:" >&2
  echo "         ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2
fi

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"

CP="$CLASSES"
CP="$CP:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"
CP="$CP:$DEPS"

echo "project: $PROJECT  caret: $FILE '$NEEDLE'  rotations: $ROTATIONS"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.LanguageServiceCostMainKt \
  "$PROJECT" "$FILE" "$NEEDLE" "$ROTATIONS"
