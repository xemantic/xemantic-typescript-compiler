#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# xemantic-typescript-compiler - a conformant TypeScript compiler and type
# checker that runs on JVM, native, and WebAssembly
#
# (NARROW.2)(f) round 855 — ablation of the pre-test probe's pins, ONE MISTAKE AT
# A TIME (round 807: a combined ablation cannot attribute, and a pin can be
# credited with discrimination it does not have).
#
# The harness was COMMITTED first (round 789) so each arm's revert is a scoped
# `git checkout` that cannot destroy the round's own work. The source rewrite and
# the build run in the FOREGROUND of this script, and the script restores the
# file before moving on (round 805 — never leave an ablated source behind).
#
# Arms:
#   A1 condition-arm   drop `is FlowCondition ->` from narrowableRoots
#   A2 assignment-arm  drop `is FlowAssignment ->` from narrowableRoots
#   A3 pre-nanos       stop recording the pre-test's own span
#   A4 detailed-gate   collect the inventory unconditionally
#
# Usage:  scripts/round855-ablate.sh [arm ...]     (default: all four)

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

FLOW=xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt
CHECKER=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
RESULTS=xemantic-typescript-compiler-core/build/test-results/jvmTest

if [[ -n "$(git status --porcelain -- "$FLOW" "$CHECKER")" ]]; then
    echo "error: $FLOW / $CHECKER are dirty — commit before ablating" >&2
    exit 1
fi

apply() {
    case "$1" in
    A1) python3 - <<'PY'
p = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt"
s = open(p).read()
old = "                    is FlowCondition -> collectIdentifierTexts(fn.expression, out)\n"
assert s.count(old) == 1, "A1 anchor"
open(p, "w").write(s.replace(old, ""))
PY
        ;;
    A2) python3 - <<'PY'
p = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt"
s = open(p).read()
old = "                    is FlowAssignment -> collectIdentifierTexts(fn.node, out)\n"
assert s.count(old) == 1, "A2 anchor"
open(p, "w").write(s.replace(old, ""))
PY
        ;;
    A3) python3 - <<'PY'
p = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
s = open(p).read()
old = "            preNanos = PassTiming.nowNanos() - p0"
assert s.count(old) == 1, "A3 anchor"
open(p, "w").write(s.replace(old, "            preNanos = 0L"))
PY
        ;;
    A4) python3 - <<'PY'
p = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt"
s = open(p).read()
old = "            if (PassTiming.detailed) narrowingNodes.toList() else null,"
assert s.count(old) == 1, "A4 anchor"
s = s.replace(old, "            narrowingNodes.toList(),")
old2 = "        if (PassTiming.detailed) narrowingNodes.add(node)"
assert s.count(old2) == 1, "A4 anchor 2"
open(p, "w").write(s.replace(old2, "        narrowingNodes.add(node)"))
PY
        ;;
    *) echo "unknown arm $1" >&2; return 1 ;;
    esac
}

for arm in "${@:-A1 A2 A3 A4}"; do
    echo "===================== ARM $arm ====================="
    apply "$arm" || { echo "APPLY FAILED for $arm"; git checkout -- "$FLOW" "$CHECKER"; continue; }
    rm -rf "$RESULTS"
    ./gradlew :xemantic-typescript-compiler-core:jvmTest \
        --tests '*NarrowableRootsPreTestTest*' --tests '*NarrowedAnyCensusTest*' \
        > "build/bench/r855-ablate-$arm.log" 2>&1
    # Restore IMMEDIATELY — the window in which the tree is wrong ends here.
    git checkout -- "$FLOW" "$CHECKER"
    if ! grep -qa "BUILD SUCCESSFUL\|BUILD FAILED" "build/bench/r855-ablate-$arm.log"; then
        echo "$arm: NO BUILD VERDICT — the run died (round 808: a dead build reads as a clean ablation)"
        continue
    fi
    if grep -qa "Not enough memory to run compilation\|fallback strategy\|GC overhead limit" \
        "build/bench/r855-ablate-$arm.log"; then
        echo "$arm: BUILD ENVIRONMENT FAILURE — not an ablation result"
        continue
    fi
    python3 - "$arm" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
arm = sys.argv[1]
red = []
total = 0
for p in glob.glob("xemantic-typescript-compiler-core/build/test-results/jvmTest/*.xml"):
    r = ET.parse(p).getroot()
    for tc in r.iter("testcase"):
        total += 1
        if tc.find("failure") is not None or tc.find("error") is not None:
            red.append(tc.get("name"))
if total == 0:
    print(f"{arm}: 0 TESTS RAN — the build failed, this is not a zero")
else:
    print(f"{arm}: {len(red)} red of {total}")
    for n in sorted(red):
        print(f"    RED {n}")
PY
done

git status --porcelain -- "$FLOW" "$CHECKER" | sed 's/^/LEFTOVER: /'
echo "== ablation complete; tree restored =="
