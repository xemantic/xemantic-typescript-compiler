#!/usr/bin/env bash
# (GATE.2) Run the SourceIndex invariant gate over a LOCAL tree of real TypeScript.
#
# The hermetic half of the gate is `TokenIndexGateTest`, which the suite runs
# everywhere. This is the other half: the same `TokenIndexInvariants` pointed at
# `build/bench/tsc-project-*` — tsc's own 78 sources, which is where (BUG.2)
# actually showed and which no fresh checkout and no CI machine has.
#
# It REFUSES rather than skips when the tree is absent. A gate reading a local
# artifact that passes quietly where the artifact is missing is the failure mode
# this repo has paid for twice (CLAUDE.md rounds 853 and 873).
#
# Usage:
#   scripts/round920-token-gate.sh                 # every tsc profile under build/bench
#   scripts/round920-token-gate.sh <dir-or-file>…  # anything else
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

TARGETS=("$@")
if [[ ${#TARGETS[@]} -eq 0 ]]; then
  shopt -s nullglob
  for profile in build/bench/tsc-project-* build/bench/tsc-*-*; do
    [[ -d "$profile/src" ]] && TARGETS+=("$profile/src")
  done
  shopt -u nullglob
fi

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  echo "REFUSED: no local TypeScript tree found under build/bench." >&2
  echo "         Materialize one with: scripts/bench-compile-tsc.sh --project all --no-emit --no-log" >&2
  exit 2
fi

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
if [[ ! -d "$CLASSES" ]]; then
  echo "REFUSED: $CLASSES is missing — build first:" >&2
  echo "         ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2
fi
# A gate that reads a class DIRECTORY needs a positive control that the code under
# test is actually in it (CLAUDE.md round 853).
if [[ ! -f "$CLASSES/com/xemantic/typescript/compiler/project/RealSourceTokenGateMainKt.class" ]]; then
  echo "REFUSED: the runner is not in $CLASSES — the build is stale." >&2
  exit 2
fi

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"

CP="$CLASSES"
CP="$CP:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"
CP="$CP:$DEPS"

echo "targets: ${TARGETS[*]}"
exec java -Xmx4g -cp "$CP" \
  com.xemantic.typescript.compiler.project.RealSourceTokenGateMainKt "${TARGETS[@]}"
