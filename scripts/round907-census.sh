#!/usr/bin/env bash
# (WARM.34) round 907 — the COUNT question rounds 901/902 left open for
# `lexLevelHasName`: how much of the 737,591-probe stream is REDUNDANT, and what
# an ASCENT memo could therefore recover.
#
# The rate is NOT re-measured: two independent instruments already put a first
# probe of a level at 36.6 ns (round 901's JFR row) and 33-37 ns (round 901's
# amplifier), agreeing to 0.5%.  What is unpriced is the POPULATION, and a
# population is a counter — deterministic, one build, no clock (round 906).
#
# Three identical runs, because the census's own falsifier is that a counter
# reproduces to the digit across processes.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round907
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
# round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is actually in it — and round 852: a cached classpath outlives
# the thing it describes.
[[ -f "$CLASSES/com/xemantic/typescript/compiler/MainKt.class" ]] || {
  echo "ABORT — the core class dir has no MainKt: it is not the compiler" | tee -a "$OUT/started"; exit 1; }
[[ "$CLASSES/com/xemantic/typescript/compiler/MapCensus.class" -nt \
   xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt ]] || {
  echo "ABORT — MapCensus.class is older than MapCensus.kt: stale build" | tee -a "$OUT/started"; exit 1; }

. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || exit 1
CP="$CLASSES:$DEPS"
PROJ=$(ls -d build/bench/tsc-project-* | head -1)
echo "PROJ=$PROJ CP ok" >> "$OUT/started"

run() {
  local tag="$1"
  echo "=== $tag ===" | tee -a "$OUT/started"
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
    --noEmit --mapCensus "$PROJ" > "$OUT/$tag.txt" 2>&1
  command grep -a "WARM.34\|WARM.28\|error TS" "$OUT/$tag.txt" | tail -20 | tee -a "$OUT/started"
  command grep -ac "error TS" "$OUT/$tag.txt" | tee -a "$OUT/started"
}

run a
run b
run c

date >> "$OUT/started"
touch "$OUT/done"
