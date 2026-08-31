#!/usr/bin/env bash
# (INC.56) Price the HOST'S FILESYSTEM PROMISE — `Project.trustFilesystem`.
#
# One arm per JVM (round 867: two arms in one process share a branch profile), and the
# two arms are ROTATED ACROSS PROCESSES so a linear drift of the box does not land on
# whichever ran first (round 867 / (INC.68)).
#
# REFUSES rather than skips when its inputs are absent — a measurement script that
# quietly measures nothing is round 853's defect.
#
# Usage: scripts/inc56-trusted-floor.sh [<projectDir> [rotations [batches]]]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

PROJECT="${1:-build/bench/many-small-2400-dom}"
ROTATIONS="${2:-8}"
BATCHES="${3:-2}"
DRAWS="${4:-6}"
[[ -f "$PROJECT/tsconfig.json" ]] || { echo "REFUSED: no project at '$PROJECT'" >&2; exit 2; }

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc56TrustedFloorMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT  rotations: $ROTATIONS  batches: $BATCHES  draws: $DRAWS"
for ((b = 0; b < BATCHES; b++)); do
  if (( b % 2 == 0 )); then ARMS=(plain trust); else ARMS=(trust plain); fi
  for arm in "${ARMS[@]}"; do
    echo "--- batch $b arm $arm"
    java -Xmx6g -cp "$CP" \
      com.xemantic.typescript.compiler.project.Inc56TrustedFloorMainKt \
      "$PROJECT" "$arm" "$ROTATIONS" "$DRAWS"
  done
done
