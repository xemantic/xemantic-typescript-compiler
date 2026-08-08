#!/usr/bin/env bash
# (WARM.3) + (WARM.4)(b) round 849 — the WARM INTRA-handler attribution, and the
# lib-type re-derivation prize.
#
# Round 847 established that a handler's warm SHARE is not its cold share: the
# top two spine handlers swap between regimes, and a probe boundary is ~1.85x
# more expensive cold than warm. Every intra-handler section table on record
# (`CtaSections`, `CpaSections`, `ArgSections`) was taken in a COLD one-shot JVM,
# so none of them can be read as a warm attribution without being re-taken. This
# script re-takes all three warm, each with its COARSE twin so the boundary is
# priced DIFFERENTIALLY (round 734 — never by an empty-span loop), inside ONE
# warm process so nothing else varies.
#
# The daemon stop lives INSIDE the script, between the build and the first
# sample: round 800's trap is that a batch which builds and then immediately
# measures reads every row ~270x too large, and no internal consistency check
# can see it.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round849
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
CP=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$(cat build/bench/cp.txt)
CPW=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test:$(tr '\n' ':' < build/bench/cp-warm.txt)
echo "PROJ=$PROJ" >> "$OUT/started"

# --- WARM. Two independent processes. Each tier is drawn TWICE inside its
#     process because round 846 measured the PROBE's own code being cold on its
#     first instrumented rebuild; only the second draw is quoted.
for i in 1 2; do
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 8 libtypes,libtypes,cta,ctacoarse,cta,ctacoarse \
       > "$OUT/warm-cta$i.log" 2>&1
done
for i in 1 2; do
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 8 cpa,cpacoarse,cpa,cpacoarse,arg,argcoarse,arg,argcoarse \
       > "$OUT/warm-cpaarg$i.log" 2>&1
done

# --- COLD, SAME binary: the cross-regime comparison the round-847 finding
#     demands before any cold section table is quoted as a warm share.
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --libTypeCensus "$PROJ" > "$OUT/cold-libtypes.log" 2>&1
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --ctaSections "$PROJ" > "$OUT/cold-cta.log" 2>&1
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --cpaSections "$PROJ" > "$OUT/cold-cpa.log" 2>&1
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --argSections "$PROJ" > "$OUT/cold-arg.log" 2>&1

date > "$OUT/done"
