#!/usr/bin/env bash
# (INC.2b) The equivalence gate for the OTHER THREE capture channels: for every file
# of a real project, do `recheckOnly = {file}` and a full build report the same
# MEMBERS (completion after a `.`), the same SCOPE NAMES (free-name completion) and
# the same SIGNATURES (signature help) at the same carets?
#
# `capture-equivalence.sh` sweeps captured TYPES and DEFINITIONS, which is what a
# hover, a go-to-definition and a semantic sweep read; it says nothing about these
# three, two of which render type text and carry the same first-touch identity risk.
# The caret populations are SAMPLED at an even stride per file — see the runner.
# REFUSES rather than skips when its inputs are absent.
#
# THE VERDICT IS THE CENSUS, NOT THE EXIT CODE. Like `capture-equivalence.sh`, this
# exits 1 while any known divergence stands, and known divergences stand: as of
# 2026-08-22, 286 rows of 21,507 captures in 49 of 76 files, which the run's own
# "mechanisms" table resolves into FIVE causes — see (INC.2b)'s session note. Read
# that table, not the status.
#
# Usage: scripts/capture-channel-equivalence.sh [<projectDir> [maxFiles [perChannel [fileSuffix]]]]
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
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/CaptureChannelEquivalenceMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.CaptureChannelEquivalenceMainKt \
  "$PROJECT" "${2:-2147483647}" "${3:-150}" "${4:-}"
