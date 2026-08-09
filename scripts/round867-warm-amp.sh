#!/usr/bin/env bash
# (WARM.14) round 867 — the AMPLIFIED price of one rejecting handler consultation.
#
# `docs/perf/dispatch-table.md` § 8.5 reduces the per-kind-dispatch-table
# question to one number: `s_p`, what production pays for a consultation that is
# entered and immediately declines.  `R = 32.0 M x s_p`, so the 1% warm floor is
# cleared at `s_p >= 2.2 ns` — an order of magnitude below a warm probe boundary
# (97-202 ns, round 850), which is why this is round 759's amplification and not
# a timestamp pair.
#
#   amp<N>   REAL arm: N extra passes per node over exactly the consultations
#            the derived table would SKIP, all under ONE bracket.
#   ampc<N>  CONTROL arm: the identical loop with every consultation suppressed.
#
#   p(r)  = boundary + r * (skeleton + S * s_p)      [real]
#   pc(r) = boundary + r * skeleton                  [control]
#
# Two values of `r` cancel the boundary; the two arms cancel the skeleton; `S` is
# MEASURED by the probe (consults / (nodes * r)) rather than assumed.  Three
# amplification factors give three independent slope pairs per arm, which is the
# agreement round 759 requires before a slope may be quoted.
#
# Both arms in EVERY process, order rotated so neither ever always holds the
# first — and therefore coldest — instrumented slot.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round867amp
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm
# Positive control that the class dir under test is the one this round built
# (round 853: a gate reading a class DIRECTORY needs one, or it can be green and
# blind for fourteen rounds).
if [[ ! -f "$CLASSES/test/com/xemantic/typescript/compiler/bench/BenchAmpTierTest.class" ]]; then
  echo "ABORT — BenchAmpTierTest.class absent: the class dir predates round 867" | tee -a "$OUT/started"
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
    "amp8,ampc8,amp16,ampc16,amp32,ampc32" \
    "ampc32,amp32,ampc16,amp16,ampc8,amp8" \
    "amp16,ampc16,amp32,ampc32,amp8,ampc8" \
    "ampc8,amp8,ampc32,amp32,ampc16,amp16" ; do
  i=$((i + 1))
  tag="p$i"
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 3 "$order" > "$OUT/$tag.log" 2>&1
  echo "$tag [$order] done $(date +%T)" >> "$OUT/started"
done

date > "$OUT/done"
