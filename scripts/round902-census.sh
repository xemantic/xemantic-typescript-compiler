#!/usr/bin/env bash
# (WARM.29) round 902 — measure the PARALLEL-ARRAY SCAN's rate, which round 901
# § 5 could only estimate ("~3-6 ns") when it priced the successor at 0.41-0.47%.
#
# CLAUDE.md's first law: the next instrument, not the next fix.  Round 901 left
# the amplifier with two arms (the real `LexicalScope.symbols` probe and the
# 64-bit proof-of-absence filter it refused); this adds the third — a linear scan
# of a `String[]` reached from the scope by one field load — and reads all three
# under one timestamp pair each, cyclically rotated so no arm owns a position.
#
# Two values of `r` cancel the ~90 ns boundary algebraically WITHIN each arm; at
# equal `r` it cancels BETWEEN them, which is the only way a FIRST-probe rate (the
# one production performs) is readable at all.
#
# The falsifier is ARITHMETIC, never timing (round 759): the sink must be an exact
# multiple of `r`, and the SCAN sink must equal the MAP sink exactly — an
# inequality would do for the filter, but a scan over the same keys answers the
# same question, so anything but equality means an arm is dead or wrong.
#
# The run order is ABBA at the RUN level, so a drift across the batch does not
# land on one `r`.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round902
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
# round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is actually in it — and round 852: the cached classpath outlives
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
  local tag="$1" r="$2"
  echo "=== $tag  r=$r ===" | tee -a "$OUT/started"
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
    --noEmit --lexLevelAmp "$r" "$PROJ" > "$OUT/$tag.txt" 2>&1
  grep -a "amplified r=" "$OUT/$tag.txt" | tee -a "$OUT/started"
}

run r4a 4
run r16a 16
run r16b 16
run r4b 4

date >> "$OUT/started"
touch "$OUT/done"
