#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# xemantic-typescript-compiler - a conformant TypeScript compiler and type
# checker that runs on JVM, native, and WebAssembly
#
# (NARROW.2)(e) round 854 — price round 852's +79% narrowing walks.
#
# Runs the compiler profile under `--passTiming` from a GIVEN main class dir and
# prints the narrowing rows: the walk-cost distribution (which decides whether
# the added walks are the cheap head or the round-735 tail), the memo split
# (cold vs served — round 788's "did the work MOVE or was it ADDED"), and
# `narrowWalks=<ms>`, which is the object being priced.
#
# The class dir is an ARGUMENT so the two arms are two BUILDS of the same tree
# and neither is the tree's current state: the ablated arm is built, copied out,
# and the source restored before anything is measured (round 805 — never leave a
# rewritten source behind a detached script), and both arms are then measured
# from /tmp copies that no later build can move under us (round 842).
#
# The dependency tail comes from build/bench/xtsc-classpath.txt, whose FIRST
# entry (the compiler's own class dir) is replaced by the arm. That file is
# refused if it is a pre-module-split one, exactly as grid838.sh refuses it
# (round MOD.3 / 852) — a stale file names the root project's leftover class
# dir, which since the split is a frozen compiler that loads with no tell.
#
# Usage:  scripts/round854-narrow-price.sh <mainClassDir> <label> [reps]

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ARM_DIR="${1:?usage: round854-narrow-price.sh <mainClassDir> <label> [reps]}"
LABEL="${2:?usage: round854-narrow-price.sh <mainClassDir> <label> [reps]}"
REPS="${3:-2}"
HEAP="${HEAP:-4g}"

CP_FILE="$REPO_ROOT/build/bench/xtsc-classpath.txt"
[[ -f "$CP_FILE" ]] || { echo "error: no $CP_FILE (run scripts/cost_gate.py once)" >&2; exit 1; }
grep -q "xemantic-typescript-compiler-core" "$CP_FILE" || {
    echo "error: $CP_FILE is stale (pre-module-split) — regenerate it" >&2; exit 1; }
[[ -d "$ARM_DIR" ]] || { echo "error: arm class dir '$ARM_DIR' does not exist" >&2; exit 1; }
# Positive control on the BINARY, not on the path: the arm must be a compiler
# this round could have built. `AnyReceiverNarrowingTest`'s subject is round
# 852's helper, which lives in Checker; the cheapest whole-binary check is that
# the dir carries the classes the current tree declares.
[[ -f "$ARM_DIR/com/xemantic/typescript/compiler/MainKt.class" ]] || {
    echo "error: '$ARM_DIR' has no MainKt — not a compiler class dir" >&2; exit 1; }

DEPS="$(cut -d: -f2- "$CP_FILE")"
PROJ_DIR="${PROJ_DIR:-$(ls -d "$REPO_ROOT"/build/bench/tsc-project-* 2>/dev/null | head -1)}"
[[ -n "$PROJ_DIR" && -d "$PROJ_DIR" ]] || { echo "error: no bench project" >&2; exit 1; }

OUT_DIR="$REPO_ROOT/build/bench"
mkdir -p "$OUT_DIR"

for ((i = 1; i <= REPS; i++)); do
    LOG="$OUT_DIR/r854-$LABEL-$i.log"
    echo "== $LABEL rep $i -> $LOG"
    # Exit 1 is EXPECTED: the profile has 46 errors and the CLI adopted tsc's
    # exit semantics at d5ed6276. The run is judged by its counter block.
    java -Xmx"$HEAP" -cp "$ARM_DIR:$DEPS" \
        com.xemantic.typescript.compiler.MainKt --noEmit --passTiming "$PROJ_DIR" \
        > "$LOG" 2>&1 || true
    grep -aq "^== counters ==" "$LOG" || { echo "error: no counter block in $LOG" >&2; exit 1; }
    grep -a "^diagnostics:" "$LOG" || true
    grep -a "narrowWalk cost distribution\|LIVE walkMemo served\|walkMiss split\|^time split" "$LOG"
done
