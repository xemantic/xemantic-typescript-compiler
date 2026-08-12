#!/usr/bin/env bash
# Round 893 — the CUMULATIVE warm A/B of rounds 887-892.
#
#   arm A = 6a4e3612 (parent of round 887)   arm B = abf184ee (HEAD)
#
# One JVM per SAMPLE (round 867: two arms that share a compiled method are not
# independent arms). WARMUP=6 (the 2026-08-10 calibration). Two BATCHES with
# OPPOSITE leading arms, 6 pairs each = 12 draws per arm pooled.
#
# The class dirs are SNAPSHOTS taken from two separate builds; the TEST class dir
# is shared and comes from the HEAD build (ab-warm.sh's documented contract —
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
[[ -f "$A_MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: A has no MainKt"; exit 1; }
[[ -f "$B_MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: B has no MainKt"; exit 1; }
[[ -f "$B_MAIN/com/xemantic/typescript/compiler/MapScopeStack.class" ]] || { echo "REFUSED: B lacks MapScopeStack (round 892)"; exit 1; }
[[ -e "$A_MAIN/com/xemantic/typescript/compiler/MapScopeStack.class" ]] && { echo "REFUSED: A HAS MapScopeStack — arms are not distinct"; exit 1; }
[[ -f "$B_MAIN/com/xemantic/typescript/compiler/SpineMask.class" ]] || { echo "REFUSED: B lacks SpineMask (round 888)"; exit 1; }
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

echo "round893 A/B started $(date)" | tee "$OUT/progress.txt"
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
echo "round893 A/B done $(date)" >> "$OUT/progress.txt"
touch "$OUT/DONE"
