#!/usr/bin/env bash
# (WARM.13) round 866 — the ROW-level, THREE-ARM decomposition of the GATED A/B.
#
# The wall A/B (`round866-warm-gated.sh`) is noise-dominated: the table can only
# move `checkSpine`, and the wall additionally carries the front end and the ~416
# tail passes — ~34% of a warm rebuild that is, for this question, pure drift.
#
#   rows       control: the pass probe, production dispatch.
#   gatedrows  the pass probe + the DERIVED table.  Delta = G - R.
#   gatedfull  the pass probe + a table holding EVERY handler for every kind, so
#              the same machinery runs and skips nothing.  Delta = G alone.
#
# Two equations, and the second is what round 732 never had: its single cold
# GATED run measured `G - R` and could not say which term it was seeing.
#
# All three arms arm the pass probe IDENTICALLY, so its boundaries cancel
# (round 793).  Every process runs four tier rebuilds as two adjacent pairs, and
# the ORDER is rotated across processes so neither arm always holds the first —
# and therefore coldest — instrumented slot (round 846, 4/4 across this arc).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round866rows
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm
if [[ ! -f "$CLASSES/test/com/xemantic/typescript/compiler/bench/BenchGatedTierTest.class" ]]; then
  echo "ABORT — BenchGatedTierTest.class absent: the class dir predates round 866" | tee -a "$OUT/started"
  exit 1
fi
if [[ ! -f "$CLASSES/main/com/xemantic/typescript/compiler/MainKt.class" ]]; then
  echo "ABORT — the core class dir has no MainKt: it is not the compiler" | tee -a "$OUT/started"
  exit 1
fi

. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || exit 1
CPW="$CLASSES/main:$CLASSES/test:$DEPS"
PROJ=$(ls -d build/bench/tsc-project-* | head -1)
echo "PROJ=$PROJ" >> "$OUT/started"

i=0
for order in \
    "gatedrows,rows,gatedfull,rows" \
    "rows,gatedrows,rows,gatedfull" \
    "gatedfull,rows,gatedrows,rows" \
    "rows,gatedfull,rows,gatedrows" ; do
  i=$((i + 1))
  tag="p$i"
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 6 "$order" > "$OUT/$tag.log" 2>&1
  echo "$tag [$order] done $(date +%T)" >> "$OUT/started"
done

date > "$OUT/done"
