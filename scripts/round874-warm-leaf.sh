#!/usr/bin/env bash
# (WARM.21) round 874 — the warm leaf profile RE-TAKEN a THIRD time, on the post-869/870/871 binary.
#
# Every instrument this arc owns is a PASS or a SECTION probe: it can only find
# cost that somebody thought to bracket. This one is the complement — it finds
# cost nobody bracketed, at the price of not being able to price anything it
# finds (round 623: a JFR leaf-frame self-% is NOT a wall-clock price; a leaf
# showing 5.3% of samples was eliminated and measured -0.3%). Its output is a
# CANDIDATE LIST. Every candidate is priced afterwards by an independent
# instrument or it is not a finding.
#
# WARM, not cold: the JFR window is opened by `delay` well after the warm-up
# rebuilds and closed inside the measured loop, so every sample is drawn from a
# steady state — which is the regime `--serve` ships and the one rounds 845-867
# measure in.
#
# Shape and ORDER are round 851's: every gradle-invoking step BEFORE the daemon
# stop, class dirs checked non-empty in between (round 853's positive control).
#
# Usage: round874-warm-leaf.sh setup | jfr <n> | rows
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round874
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
  sleep 10
  free -m > "$OUT/free.txt"
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

# 3 warm-up rebuilds (~26 + 12 + 9 s) then 20 measured ones at ~7 s: the window
# [60 s, 150 s] therefore lies wholly inside the measured loop, several rebuilds
# after the last cold one. Chosen by ARITHMETIC over the known warm figure
# (round 867: a warm rebuild is ~6.9 s), and checked afterwards against the
# recording's own sample span.
if [[ "$STAGE" == "jfr" ]]; then
  N="${2:-1}"
  java -Xmx4g \
     -XX:FlightRecorderOptions=stackdepth=1024 \
     -XX:StartFlightRecording=settings=profile,delay=60s,duration=90s,filename="$OUT/warm$N.jfr" \
     -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 20 > "$OUT/warm-jfr$N.log" 2>&1
  echo "jfr $N exit=$? $(date)" >> "$OUT/started"
  exit 0
fi

if [[ "$STAGE" == "tier" ]]; then
  T="${2:-frontend}"; N="${3:-1}"
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 8 "$T" > "$OUT/warm-$T-$N.log" 2>&1
  echo "tier $T $N exit=$? $(date)" >> "$OUT/started"
  exit 0
fi

echo "unknown stage: $STAGE" >&2
exit 2
