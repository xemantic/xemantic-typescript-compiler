#!/usr/bin/env bash
# (SPINE.1) round 908 — RE-TAKE the warm per-handler spine table on today's
# binary, and re-take the three intra-handler probes that decide whether any of
# the six handlers' cost is BOOKKEEPING rather than the migrated passes' own
# checking work.
#
# Round 847's per-handler ms are against an 8,095 ms rebuild and round 850/851's
# intra-handler ms against 7,076 / 7,369 ms ones; today's is ~5,250 ms. Neither
# an absolute nor a SHARE travels across that (CLAUDE.md round 830: a share
# rises when the REST of the function gets faster with the region's own cost
# unchanged), so every number this round quotes is measured here.
#
# Round 800's trap is why the daemon stop lives INSIDE the script, between the
# build and the first sample.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round908
mkdir -p "$OUT"
date > "$OUT/started"

./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
if ! grep -q 'BUILD SUCCESSFUL' "$OUT/build.log"; then
  echo "BUILD FAILED" >> "$OUT/started"; date > "$OUT/done"; exit 1
fi

./gradlew --stop >> "$OUT/build.log" 2>&1
pkill -f 'KotlinCompile[D]aemon'
sleep 10
free -m > "$OUT/free.txt"

PROJ=$(ls -d build/bench/tsc-project-* | head -1)
. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || { date > "$OUT/done"; exit 1; }
CPW=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test:$DEPS
echo "PROJ=$PROJ" >> "$OUT/started"

# The classpath must name the CORE module's classes, never the root's leftover
# pre-split ones (round 852) — a run against those is an old compiler at exit 0.
echo "$CPW" | grep -q 'xemantic-typescript-compiler-core/build/classes' || {
  echo "CLASSPATH REFUSED" >> "$OUT/started"; date > "$OUT/done"; exit 1; }

run() {  # run <logname> <tiers>
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 6 6 "$2" > "$OUT/$1.log" 2>&1
  echo "$1 done $(date)" >> "$OUT/started"
}

# --- (1) the per-HANDLER table. Two processes, two draws of each tier each:
#     round 846 measured the PROBE's own code cold on its first instrumented
#     rebuild, so a tier list must give it two draws before a number is quoted.
run disp1 spine,dispatch,spine,dispatch
run disp2 spine,dispatch,spine,dispatch

# --- (2) the INTRA-handler probes, ON and COARSE inside one process so the
#     boundary is priced differentially (round 734) and nesting-aware (round 850).
run intra1 cta,ctacoarse,cpa,cpacoarse
run intra2 cta,ctacoarse,cpa,cpacoarse

# --- (3) the largest handler's payload, `checkSingleCallExpressionTypes`.
run call1 call,callcoarse,call,callcoarse
run call2 call,callcoarse,call,callcoarse

date > "$OUT/done"
