#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# xemantic-typescript-compiler - a conformant TypeScript compiler and type
# checker that runs on JVM, native, and WebAssembly
#
# (NARROW.2)(e) round 854 — ONE MISTAKE AT A TIME against `NarrowedAnyCensusTest`.
#
# A combined ablation cannot attribute (round 807): six seam mistakes injected
# together read as full coverage, and re-run alone one of them turned out to be
# decided by a later guard. So each arm below injects exactly one fault, builds,
# runs the pin class, records the failing set, and RESTORES the source before the
# next arm.
#
# The harness it ablates is COMMITTED (round 789): `git checkout` is the undo
# here, and an uncommitted probe living in the same file would go with it.
# The rewrite+build+restore runs in the FOREGROUND of this script (round 805) and
# an EXIT trap restores the source if the script is killed mid-arm — the failure
# mode being a build that silently compiles the ablated code forever after.
#
# Usage:  scripts/round854-ablate.sh [arm ...]      (default: all)

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CHECKER=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
PT=xemantic-typescript-compiler-core/src/commonMain/kotlin/PassTiming.kt
OUT=build/bench

restore() { git checkout -- "$CHECKER" "$PT" 2>/dev/null || true; }
trap restore EXIT

[[ -z "$(git status --porcelain -- "$CHECKER" "$PT")" ]] || {
    echo "error: $CHECKER / $PT are dirty — commit before ablating (round 789)" >&2; exit 1; }

ARMS=("$@")
[[ ${#ARMS[@]} -gt 0 ]] || ARMS=(A1 A2 A3)

inject() {
    case "$1" in
        # The `accepted` seam: the CONSUMED side of the census stops being
        # recorded. Only the pin that asserts a receiver type was produced can
        # see it.
        A1) python3 - "$CHECKER" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "        if (PassTiming.detailed) PassTiming.cmamAnyAccepted++\n"
assert s.count(old) == 1, s.count(old)
open(p, 'w').write(s.replace(old, ""))
PY
            ;;
        # The whole span: the opening runs exactly as before but records
        # nothing. Every pin that reads a counter must redden.
        A2) python3 - "$CHECKER" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = """        if (PassTiming.detailed) {
            PassTiming.noteCmamAnyOpening(
                PassTiming.nowNanos() - probeT0,
                PassTiming.narrowWalkNanos - probeW0,
                narrowed !== rawType,
            )
        }
"""
assert s.count(old) == 1, s.count(old)
open(p, 'w').write(s.replace(old, ""))
PY
            ;;
        # The PRODUCED seam, inverted: an opening is recorded as having narrowed
        # exactly when it did not. The two population pins disagree in opposite
        # directions, which is what makes them two pins rather than one.
        A3) python3 - "$CHECKER" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = "                narrowed !== rawType,\n"
assert s.count(old) == 1, s.count(old)
open(p, 'w').write(s.replace(old, "                narrowed === rawType,\n"))
PY
            ;;
        *) echo "unknown arm $1" >&2; return 1 ;;
    esac
}

for arm in "${ARMS[@]}"; do
    echo "=== arm $arm"
    restore
    inject "$arm" || { echo "arm $arm: injection failed"; continue; }
    ./gradlew --console=plain :xemantic-typescript-compiler-core:compileKotlinJvm \
        > "$OUT/r854-abl-$arm-build.log" 2>&1
    if ! grep -aq "BUILD SUCCESSFUL" "$OUT/r854-abl-$arm-build.log"; then
        echo "arm $arm: BUILD FAILED — see $OUT/r854-abl-$arm-build.log"
        continue
    fi
    rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
    ./gradlew --console=plain :xemantic-typescript-compiler-core:jvmTest \
        --tests '*NarrowedAnyCensusTest*' > "$OUT/r854-abl-$arm-test.log" 2>&1
    # A build that never ran the tests is not a green arm (round 808).
    python3 - "$arm" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
arm = sys.argv[1]
ran = failed = []
ran, failed = [], []
for p in glob.glob('xemantic-typescript-compiler-core/build/test-results/jvmTest/*.xml'):
    r = ET.parse(p).getroot()
    for tc in r.iter('testcase'):
        ran.append(tc.get('name'))
        if tc.find('failure') is not None or tc.find('error') is not None:
            failed.append(tc.get('name'))
print("arm %s: ran %d, FAILED %d" % (arm, len(ran), len(failed)))
for n in sorted(failed):
    print("   RED  %s" % n)
if not ran:
    print("   !! NO TESTS RAN — this arm proves nothing")
PY
done
restore
echo "=== source restored"
git status --porcelain -- "$CHECKER" "$PT"
