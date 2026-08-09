#!/usr/bin/env bash
# (WARM.10) round 863 — the WHOLE-PROGRAM REGEX SWEEP.
#
# Two instruments in one block, run one at a time on a quiet box:
#
#   (1) the `rows` tier, 2 processes x 2 draws — the per-pass table warm, which
#       is what ranks the remaining tail after rounds 860 and 862 took the two
#       known offenders out of it. Round 846 measured `rows` as probe-FREE for
#       the tail rows (+0.0% warm), so its absolutes are readable.
#
#   (2) a JFR ExecutionSample recording of ONE warm process, used ONLY to
#       DISCOVER which call sites reach `java.util.regex` — never to price one
#       (round 623: a JFR leaf-frame self-% is not a wall-clock price). The
#       static census is the primary enumeration; this is the falsifier that
#       catches a site the census misread.
#
# Shape and ORDER are round 851's: every gradle-invoking step BEFORE the daemon
# stop, then the samples, and the class dir is checked non-empty in between.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round863
mkdir -p "$OUT"
date > "$OUT/started"

./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
if ! grep -q 'BUILD SUCCESSFUL' "$OUT/build.log"; then
  echo "BUILD FAILED" >> "$OUT/started"; date > "$OUT/done"; exit 1
fi

python3 scripts/cost_gate.py > "$OUT/cost-gate.log" 2>&1
echo "cost_gate exit=$?" >> "$OUT/started"
python3 scripts/huge_methods.py --fail-over 0 > "$OUT/huge-methods.log" 2>&1
echo "huge_methods exit=$?" >> "$OUT/started"

DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED" >> "$OUT/started"; exit 1; }

./gradlew --stop >> "$OUT/build.log" 2>&1
pkill -f 'KotlinCompile[D]aemon'
sleep 10
free -m > "$OUT/free.txt"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test
# Round 853's positive control: the code under test must be in the dir we load.
[[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || {
  echo "REFUSED: main class dir does not hold MainKt" >> "$OUT/started"; exit 1; }
[[ -f "$TEST/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || {
  echo "REFUSED: test class dir does not hold BenchMainKt" >> "$OUT/started"; exit 1; }
echo "main classes: $(find "$MAIN" -name '*.class' | wc -l)" >> "$OUT/started"

CPW="$MAIN:$TEST:$DEPS"
PROJ=$(ls -d build/bench/tsc-project-* | head -1)
echo "PROJ=$PROJ" >> "$OUT/started"

for i in 1 2; do
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 8 rows,rows > "$OUT/warm-rows$i.log" 2>&1
done

# The DISCOVERY run, last, and never mixed with a priced one.
java -Xmx4g -XX:StartFlightRecording=settings=profile,filename="$OUT/regex.jfr" \
     -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 6 > "$OUT/warm-jfr.log" 2>&1

date > "$OUT/done"
