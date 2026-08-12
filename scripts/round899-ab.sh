#!/usr/bin/env bash
# Round 899 — (WARM.26) — the CUMULATIVE warm A/B of rounds 895-898.
#
#   arm A = 63819970 (round 893's measurement commit, i.e. the state BEFORE 895)
#   arm B = HEAD
#
# Shape is round 893's, verbatim, because the only thing that should differ
# between the two measurements is the binary:
#   * one JVM per SAMPLE (round 867 — two arms sharing a compiled method are not
#     independent arms: a branch profiled as never-taken costs an uncommon trap
#     when the other arm takes it);
#   * WARMUP=6 / ITERS=8 (the 2026-08-10 calibration: two identical arms sit
#     3.3% apart at warm-up 2, 0.8% at 6), sample = that process's median;
#   * TWO batches with OPPOSITE leading arms, so each arm leads exactly half the
#     pairs (round 891: a rotation that leaves the leading draw's ~15% on one arm
#     manufactures a 4x disagreement).
#
# EXPECTED EFFECT IS SMALL. Rounds 895-898 bank ~82 ms of counted removed work
# on a ~5.4 s rebuild = ~1.5%, against per-arm sds of 2.21% / 3.44% at round 893.
# This run is therefore expected to be UNDERPOWERED for a point estimate; the
# reportable quantities are the sign pattern and the paired range.
#
# The class dirs are SNAPSHOTS from two separate builds; the TEST class dir is
# shared and comes from the HEAD build (ab-warm.sh's documented contract —
# BenchMain touches only ProjectCompiler/SystemVfs/ParallelCheckMode/ShareBind,
# and the CP_* tier constants it names are `const val`, i.e. inlined).
set -uo pipefail
SP="${ARMS_DIR:?set ARMS_DIR to a dir holding arms/A_main, arms/B_main, arms/TEST_head and deps.txt}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$SP/ab"
mkdir -p "$OUT"

A_MAIN="$SP/arms/A_main"
B_MAIN="$SP/arms/B_main"
TEST="$SP/arms/TEST_head"
DEPS="$(cat "$SP/deps.txt")"
PROJ="$(ls -d "$REPO"/build/bench/tsc-project-* | head -1)"

WARMUP=6
ITERS=8
PAIRS=6

# --- positive controls: the code under test really is in these dirs ---------
# (round 853: a gate reading a class DIRECTORY needs proof that the code under
#  test is in it — a run of identical answers is otherwise indistinguishable
#  from a frozen binary.)
[[ -f "$A_MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: A has no MainKt"; exit 1; }
[[ -f "$B_MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: B has no MainKt"; exit 1; }
[[ -f "$B_MAIN/com/xemantic/typescript/compiler/SourceScanFilter.class" ]] || { echo "REFUSED: B lacks SourceScanFilter (round 895)"; exit 1; }
[[ -e "$A_MAIN/com/xemantic/typescript/compiler/SourceScanFilter.class" ]] && { echo "REFUSED: A HAS SourceScanFilter — arms are not distinct"; exit 1; }
[[ -f "$B_MAIN/com/xemantic/typescript/compiler/MapCensus.class" ]] || { echo "REFUSED: B lacks MapCensus (round 896)"; exit 1; }
[[ -f "$TEST/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || { echo "REFUSED: no BenchMainKt"; exit 1; }
[[ -d "$PROJ" ]] || { echo "REFUSED: no bench project"; exit 1; }

run_one() {   # $1 = main dir, $2 = tag
    local dir="$1" tag="$2" log="$OUT/$2.jsonl"
    java -Xmx4g -cp "$dir:$TEST:$DEPS" \
        com.xemantic.typescript.compiler.bench.BenchMainKt "$PROJ" "$WARMUP" "$ITERS" \
        > "$log" 2>&1
    python3 - "$log" "$tag" <<'PY'
import json, statistics, sys
its = []
for line in open(sys.argv[1], errors="replace"):
    line = line.strip()
    if line.startswith('{"iter"'):
        try: its.append(json.loads(line))
        except ValueError: pass
if not its:
    print(f"{sys.argv[2]} FAIL"); sys.exit(0)
fe = sorted({(o["files"], o["errors"]) for o in its})
if len(fe) != 1:
    print(f"{sys.argv[2]} DRIFT " + " ".join("%d/%d" % x for x in fe)); sys.exit(0)
ms = [o["ms"] for o in its]
print("%s %.1f %d %d %d %.0f %.0f" % (sys.argv[2], statistics.median(ms),
      fe[0][0], fe[0][1], len(ms), min(ms), max(ms)))
PY
}

echo "round899 A/B started $(date)" | tee "$OUT/progress.txt"
free -m >> "$OUT/progress.txt"

for BATCH in 1 2; do
    for ((i = 1; i <= PAIRS; i++)); do
        # batch 1 leads with A on odd pairs; batch 2 leads with B on odd pairs —
        # so pooled over both batches each arm leads exactly half the pairs.
        if (( (i + BATCH) % 2 == 1 )); then order=(A B); else order=(B A); fi
        for arm in "${order[@]}"; do
            if [[ $arm == A ]]; then d="$A_MAIN"; else d="$B_MAIN"; fi
            res="$(run_one "$d" "b${BATCH}p${i}${arm}")"
            echo "$res" >> "$OUT/samples.txt"
            echo "$(date +%H:%M:%S) $res" >> "$OUT/progress.txt"
        done
    done
done
echo "round899 A/B done $(date)" >> "$OUT/progress.txt"
touch "$OUT/DONE"
