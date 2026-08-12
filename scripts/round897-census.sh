#!/usr/bin/env bash
# (WARM.24) round 897 — price round 894's candidate (1), *scanner identifier
# interning*, BEFORE a line of fix (CLAUDE.md's first law).
#
# The census's own § 9(1) says the fix's cost is UNPRICED and that an intern
# table is itself a hash probe per identifier TOKEN — round 788's law, one
# instrument over: skipping a cached resolution MOVES work, it does not delete
# it.  This script answers three questions on one binary:
#
#   1. the TOKEN population — occurrences, distinct names, the hit rate an
#      intern table would see;
#   2. the PROBE population — how many `moduleOnlyGlobalNames` / `globals`
#      probes a rebuild pays, split by whether they HIT (only a hit can pay the
#      `String.equals` character walk interning removes);
#   3. the RECOVERY and the COST, by REPLAY of both real populations, ABBA per
#      rep, with an ARITHMETIC falsifier rather than a timing one.
#
# WARM, because the whole arc is: the `namecensus<N>` tier runs its rebuild
# after `BenchMain`'s six-iteration warm-up.  Each process is its own JVM
# (round 867), and the round-891 mirror rotation is run as a SECOND batch by
# passing `b`.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round897
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm
# round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is actually in it.
[[ -f "$CLASSES/main/com/xemantic/typescript/compiler/NameCensus.class" ]] || {
  echo "ABORT — no NameCensus.class: the class dir predates round 897" | tee -a "$OUT/started"; exit 1; }
[[ -f "$CLASSES/main/com/xemantic/typescript/compiler/MainKt.class" ]] || {
  echo "ABORT — the core class dir has no MainKt: it is not the compiler" | tee -a "$OUT/started"; exit 1; }

. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || exit 1
CP="$CLASSES/main:$CLASSES/test:$DEPS"
PROJ=$(ls -d build/bench/tsc-project-* | head -1)
echo "PROJ=$PROJ CP ok" >> "$OUT/started"

BATCH="${1:-a}"
run() {
  local tag="$1"; shift
  echo "=== $tag ===" | tee -a "$OUT/started"
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.bench.BenchMainKt \
    "$PROJ" 6 2 "$1" > "$OUT/$tag.txt" 2>&1
  grep -a "WARM.24" -A 14 "$OUT/$tag.txt" | head -20
}

if [[ "$BATCH" == "a" || "$BATCH" == "all" ]]; then
  run p1 namecensus6
  run p2 namecensus12
fi
if [[ "$BATCH" == "b" || "$BATCH" == "all" ]]; then
  run p3 namecensus6
  run p4 namecensus12
fi
date >> "$OUT/started"
touch "$OUT/done-$BATCH"
