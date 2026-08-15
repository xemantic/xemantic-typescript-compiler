#!/usr/bin/env bash
# (SPINE.1) round 908 — the gates, re-run after the census's mode test moved to
# the CALL SITE (round 900: a callee's `if (off) return` cannot protect its own
# arguments, and `sec >= 0` is true in production at both install sites).
#
# The census values cannot move — the guard is the identity when the probe is ON
# — so this run is a GATE on the guard, not a re-measurement.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round908d
mkdir -p "$OUT"
date > "$OUT/started"

rm -rf ./*/build/test-results/jvmTest build/test-results/jvmTest
./gradlew jvmTest > "$OUT/suite.log" 2>&1
echo "jvmTest=$?" >> "$OUT/started"
python3 scripts/cost_gate.py > "$OUT/cost-gate.log" 2>&1
echo "cost_gate=$?" >> "$OUT/started"
python3 scripts/huge_methods.py --fail-over 0 > "$OUT/huge.log" 2>&1
echo "huge=$?" >> "$OUT/started"

date > "$OUT/done"
