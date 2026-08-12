#!/usr/bin/env bash
# (WARM.25) round 898 — re-census + price the copy families rounds 891/892 left.
#
# Round 894 § 9 candidate (8) is "not a change; an instrument run": its census
# measures `Checker$EpochMap.<init>` at 38.1 ms/rebuild where round 891 DERIVED
# 14-24 ms for the same thing, and both numbers are on record. Candidate (6),
# `spineArgListOverlay`, has never been censused at all — only JFR-attributed.
#
# Two stages, round 851's order throughout (every gradle-invoking step BEFORE
# the daemon stop, class dirs checked non-empty in between):
#
#   setup                     — build, resolve deps, stop daemons, positive control
#   tier <tierlist> <tag> [it] — one JVM, one tier list, one log
#
# The tier lists are the AMPLIFICATION ladders (round 759): `copyampem<r>` /
# `copyampal<r>` perform `r` EXTRA copies at every censused site of exactly ONE
# family and take NO timestamp pair anywhere, so `wall(r) = base + r*C` and two
# values of `r` cancel `base`. The falsifier is arithmetic — `ampSink` must be
# exactly `r x` the armed families' entry count, printed by the census itself.
#
# ROTATION IS TWO BATCHES, MIRRORED (round 891's own law, sharpened): the FIRST
# instrumented rebuild in a process is the slowest draw and is worth up to 15%,
# so a single 6-draw ladder puts that bias entirely on whichever arm led. Round
# 891 measured 53.6 vs 14.1 ms/rep for ONE family on ONE binary from exactly
# that mistake. Run `A` and `B` and pool.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round898
mkdir -p "$OUT"
STAGE="${1:-setup}"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test

if [[ "$STAGE" == "setup" ]]; then
  date > "$OUT/started"
  ./gradlew :xemantic-typescript-compiler-core:compileKotlinJvm \
            :xemantic-typescript-compiler-core:compileTestKotlinJvm > "$OUT/build.log" 2>&1
  grep -q 'BUILD SUCCESSFUL' "$OUT/build.log" || { echo "BUILD FAILED" >> "$OUT/started"; exit 1; }
  scripts/lib/dep-classpath.sh --print > "$OUT/deps.txt" || { echo "DEPS FAILED" >> "$OUT/started"; exit 1; }
  ./gradlew --stop >> "$OUT/build.log" 2>&1
  pkill -f 'KotlinCompile[D]aemon'
  sleep 10
  free -m > "$OUT/free.txt"
  # round 853's positive control: the class dir must hold the code under test.
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

if [[ "$STAGE" == "tier" ]]; then
  T="${2:-copyamp0}"; TAG="${3:-x}"; IT="${4:-2}"
  # warmup 6, not 3: measured A/A puts two identical process medians 2.0% apart
  # at warmup 3 and 0.8% at 6, and the quantity here is a slope between arms
  # measured in DIFFERENT rebuilds of the same process.
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 6 "$IT" "$T" > "$OUT/warm-$TAG.log" 2>&1
  echo "tier $T $TAG exit=$? $(date)" >> "$OUT/started"
  exit 0
fi

echo "unknown stage: $STAGE" >&2
exit 2
