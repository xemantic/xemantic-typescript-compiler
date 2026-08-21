#!/usr/bin/env bash
# A SCREENING harness for a runtime-only change on the compiled toml program.
#
# Not a replacement for scripts/kir-bench.sh: it has no equivalence gate, no
# Node arms and one library. What it is good for is deciding whether a candidate
# is worth a 25-minute three-arm run, and it holds everything but the runtime
# jar fixed -- the compiled program under build/bench/kir-bench/jvm-toml is the
# one the benchmark timed.
#
# ITS BAND IS ~6% AND THAT IS DRIFT BETWEEN BATCHES, NOT SPREAD WITHIN ONE.
# Measured 2026-08-21: two n=5 batches of the SAME BYTES read 692 ms and 735 ms.
# So a candidate within ~6% of the baseline has NOT been measured by this
# harness -- it has been shown not to be a large win, which is usually the
# question. Re-run the baseline in the SAME batch as the candidate, never
# against a number from an earlier one (CLAUDE.md round 858), and take anything
# that survives to scripts/kir-bench.sh before believing it.
#
# USE:  scripts/kir-screen.sh <label> [processes]
set -uo pipefail
REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LABEL="${1:?usage: kir-screen.sh <label> [processes]}"
N="${2:-5}"
KIRC="$REPO/xemantic-typescript-compiler-kir/build/classes/kotlin/jvm/main"
# The stdlib comes from the shared guard, which refuses a cache older than
# `gradle/libs.versions.toml` (CLAUDE.md round 858) rather than naming a version.
# shellcheck source=scripts/lib/dep-classpath.sh
. "$REPO/scripts/lib/dep-classpath.sh"
STD="$(xtsc_dep_classpath | tr ':' '\n' | grep -m1 'kotlin-stdlib')"
[ -n "$STD" ] || { echo "kir-screen: no kotlin-stdlib on the classpath" >&2; exit 2; }
[ -d "$REPO/build/bench/kir-bench/jvm-toml" ] || {
    echo "kir-screen: no compiled program — run scripts/kir-bench.sh first" >&2; exit 2; }
OUT="${KIR_BENCH_WORK:-$REPO/build/bench/kir-bench}/screen-$LABEL.txt"
: > "$OUT"
for i in $(seq 1 "$N"); do
    java -cp "$REPO/build/bench/kir-bench/jvm-toml:$STD:$KIRC" program.MainKt >> "$OUT" 2>&1
done
python3 - "$OUT" "$LABEL" <<'PY'
import re, statistics, sys
text = open(sys.argv[1]).read()
best = [int(m) for m in re.findall(r"best_ms=(\d+)", text)]
sinks = set(re.findall(r"sink=(-?\d+)", text))
if len(sinks) != 1:
    raise SystemExit(f"the runs disagree on the sink: {sinks}")
best.sort()
print(f"{sys.argv[2]:>14}  median {statistics.median(best):7.1f} ms  "
      f"[{best[0]}..{best[-1]}]  n={len(best)}  sink={sinks.pop()}")
PY
