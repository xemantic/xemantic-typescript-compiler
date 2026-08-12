#!/usr/bin/env bash
# Round 899 — (WARM.26) stage 2: the SIXTH warm leaf profile, on the CURRENT
# binary (HEAD, i.e. after rounds 895-898).
#
# Recipe IDENTICAL to scripts/round888-warm-leaf.sh and round893-profile.sh part
# (b) — stackdepth=1024, delay=60s/duration=90s, `jfr print --stack-depth 512`,
# two processes — so the only variable against rounds 888/893 is the binary.
#
# The three laws this recipe exists to honour, restated because they are what
# makes the dumps comparable at all:
#   * round 868 — `jfr print` truncates to the top 5 frames by DEFAULT and the
#     aggregation refuses a dump whose max depth is <= 5;
#   * round 870 — a JFR share is a share of WALL TIME in a fixed window, so
#     every cross-round number must be multiplied by THAT round's own median
#     rebuild before it is read (the aggregators do this);
#   * round 874 — read by FAMILY, not by row.
set -uo pipefail
SP="${ARMS_DIR:?set ARMS_DIR to a dir holding arms/B_main, arms/TEST_head and deps.txt}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/build/bench/round899"
mkdir -p "$OUT"
DEPS="$(cat "$SP/deps.txt")"
TEST="$SP/arms/TEST_head"
PROJ="$(ls -d "$REPO"/build/bench/tsc-project-* | head -1)"

# positive control (round 853): the binary under the profile is the one this
# round is about, not a leftover class dir.
[[ -f "$SP/arms/B_main/com/xemantic/typescript/compiler/SourceScanFilter.class" ]] \
    || { echo "REFUSED: B_main lacks SourceScanFilter (round 895)"; exit 1; }

echo "profile started $(date)" > "$OUT/profile-started"
for N in 1 2; do
  java -Xmx4g \
    -XX:FlightRecorderOptions=stackdepth=1024 \
    -XX:StartFlightRecording=settings=profile,delay=60s,duration=90s,filename="$OUT/warm$N.jfr" \
    -cp "$SP/arms/B_main:$TEST:$DEPS" \
    com.xemantic.typescript.compiler.bench.BenchMainKt "$PROJ" 3 20 \
    > "$OUT/warm-jfr$N.log" 2>&1
  echo "jfr $N exit=$? $(date)" >> "$OUT/profile-started"
  jfr print --stack-depth 512 --events jdk.ExecutionSample "$OUT/warm$N.jfr" \
    > "$OUT/deep$N.txt" 2> "$OUT/deep$N.err"
  echo "print $N exit=$? lines=$(wc -l < "$OUT/deep$N.txt") $(date)" >> "$OUT/profile-started"
done
echo "profile done $(date)" >> "$OUT/profile-started"
touch "$OUT/PROFILE_DONE"
