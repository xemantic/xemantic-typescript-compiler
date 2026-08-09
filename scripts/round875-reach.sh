#!/usr/bin/env bash
# (WARM.22) round 875 — the INV.4 REACH MACHINERY as ONE population.
#
# Round 874 § 29 closed the leaf-profile arc with a successor that is a DESIGN
# question rather than a candidate hunt: the reach machinery is ~338 ms of a
# 6.6 s warm rebuild spread over 43 classifiers of which the largest is 0.86%,
# so no per-classifier fix can clear the 1% bar and only a change to the SHARED
# mechanism can. Deciding between the candidate mechanisms needs one number
# nobody has ever counted — how many EDGE EVALUATIONS the family performs per
# rebuild — and round 801's law says that census comes before any timing.
#
#   census — `reach`, counters only, no timestamp anywhere. DETERMINISTIC, so
#            two rebuilds that disagree mean a broken probe, not a varying
#            compile: the harness runs several and the reader diffs them.
#   arms   — `plain` vs `plain` ABBA against a converted binary is NOT how this
#            is priced (round 840(c)): the effect is ~1% and the arm sd is ~3%.
#            The price is an amplification (round 759/867) taken by
#            `round875-edge-amp.sh`, and the wall is never quoted.
#
# Round 851's order throughout: every gradle-invoking step BEFORE the daemon
# stop, class dirs positively controlled in between (round 853).
#
# Usage: round875-reach.sh setup | census <n>
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round875
mkdir -p "$OUT"
STAGE="${1:-setup}"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test

if [[ "$STAGE" == "setup" ]]; then
  date > "$OUT/started"
  ./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
  grep -q 'BUILD SUCCESSFUL' "$OUT/build.log" || { echo "BUILD FAILED" >> "$OUT/started"; exit 1; }
  scripts/lib/dep-classpath.sh --print > "$OUT/deps.txt" || { echo "DEPS FAILED" >> "$OUT/started"; exit 1; }
  ./gradlew --stop >> "$OUT/build.log" 2>&1
  pkill -f 'KotlinCompile[D]aemon'
  sleep 8
  free -m > "$OUT/free.txt"
  # Round 853: a gate reading a class DIRECTORY needs a positive control that
  # the code under test is in it — the module dir, never the stale root one.
  [[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || {
    echo "REFUSED: main class dir does not hold MainKt" >> "$OUT/started"; exit 1; }
  [[ -f "$TEST/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || {
    echo "REFUSED: test class dir does not hold BenchMainKt" >> "$OUT/started"; exit 1; }
  echo "main classes: $(find "$MAIN" -name '*.class' | wc -l)" >> "$OUT/started"
  ls -d build/bench/tsc-project-* | head -1 > "$OUT/proj.txt"
  echo "setup ok" >> "$OUT/started"
  exit 0
fi

DEPS="$(cat "$OUT/deps.txt")"
CPW="$MAIN:$TEST:$DEPS"
PROJ="$(cat "$OUT/proj.txt")"

if [[ "$STAGE" == "census" ]]; then
  N="${2:-1}"
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 2 4 reach > "$OUT/census$N.log" 2>&1
  echo "census $N exit=$? $(date)" >> "$OUT/started"
  exit 0
fi

echo "unknown stage: $STAGE" >&2
exit 2
