#!/usr/bin/env bash
# (INC.18) THE PIN ABLATION — the same injected mistakes as
# `scripts/partition-gate-ablate.sh`, graded by the two `commonTest` pins instead
# of by the gate scripts, so a pin recorded as discriminating has been SEEN to fail.
#
# A pin that has never failed is a claim, not a pin. Arms are applied ONE AT A TIME
# (a combined ablation cannot attribute), each patch asserts its anchor occurs
# exactly once, and each arm's edit is compared against the ARM'S OWN SNAPSHOT —
# `git diff --shortstat` is vacuous on a tree that already carries the round's work
# (CLAUDE.md round 922).
#
# Usage: scripts/partition-gate-ablate-pins.sh [arm …]     (default: a1 a3 a4 a5 a6)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
CHK="$ROOT/xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
PT="$ROOT/xemantic-typescript-compiler-core/src/commonMain/kotlin/PassTiming.kt"
WORK="$ROOT/build/inc18-ablate-pins"
mkdir -p "$WORK"

for f in "$CHK" "$PT"; do
  if [[ -n "$(git status --porcelain -- "$f")" ]]; then
    echo "REFUSED: $(basename "$f") has uncommitted changes; this driver restores by copy" >&2
    exit 2
  fi
done

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(a1 a3 a4 a5 a6)

cp "$CHK" "$WORK/Checker.kt.snapshot"
cp "$PT" "$WORK/PassTiming.kt.snapshot"
trap 'cp "$WORK/Checker.kt.snapshot" "$CHK"; cp "$WORK/PassTiming.kt.snapshot" "$PT"; echo "tree restored"' EXIT

patch_arm() {
python3 - "$1" "$CHK" "$PT" <<'PY'
import sys
arm, chk, pt = sys.argv[1], sys.argv[2], sys.argv[3]
NOOP = "\n        // ABLATION: this pass produces nothing once the checker is narrowed.\n        if (assignedFileNames != null) return"
CHK_ARMS = {
    "a1": ("    private fun checkMissingImplementations() {",
           "    private fun checkMissingImplementations() {" + NOOP),
    "a2": ("    private fun checkConflictMarkers() {",
           "    private fun checkConflictMarkers() {" + NOOP),
    "a3": ("        if (FltmCensus.on) FltmCensus.beginSetup()\n        for (result in binderResults) {",
           "        if (FltmCensus.on) FltmCensus.beginSetup()\n        // ABLATION: round 609 — a collector gated on the partition.\n        for (result in checkedResults) {"),
    "a4": ("    private fun checkCloduleTest2() {",
           "    private fun checkCloduleTest2() {" + NOOP),
    "a5": ("    private fun checkSpine() {",
           "    private fun checkSpine() {" + NOOP),
}
# a6 — the receipt's own instrument: make the SIGNED accumulator clamp like the
# positive-only twin, which is how a RETRACTING pass would vanish from the count.
PT_ARMS = {
    "a6": ("""        if (d1 != d0) {
            PassTiming.diagNetByPass[name] =""",
           """        if (d1 > d0) { // ABLATION: clamp the signed accumulator
            PassTiming.diagNetByPass[name] ="""),
}
if arm in CHK_ARMS:
    path, (anchor, repl) = chk, CHK_ARMS[arm]
elif arm in PT_ARMS:
    path, (anchor, repl) = pt, PT_ARMS[arm]
else:
    sys.exit("unknown arm %r" % arm)
s = open(path).read()
n = s.count(anchor)
if n != 1:
    sys.exit("REFUSED: arm %s anchor occurs %d times, expected exactly 1" % (arm, n))
open(path, "w").write(s.replace(anchor, repl))
print("arm %s patched (%s)" % (arm, path.rsplit("/", 1)[-1]))
PY
}

verdict() { # <xml dir> <class-name-fragment>
  python3 - "$1" "$2" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
d, frag = sys.argv[1], sys.argv[2]
total = failed = 0
for f in glob.glob(d + "/*.xml"):
    for tc in ET.parse(f).getroot().iter("testcase"):
        if frag not in (tc.get("classname") or ""):
            continue
        total += 1
        if tc.find("failure") is not None or tc.find("error") is not None:
            failed += 1
print(("RED(%d/%d)" % (failed, total)) if failed else
      ("GREEN(%d)" % total if total else "NO-TESTS-RAN"))
PY
}

for arm in "${ARMS[@]}"; do
  echo "===== ARM $arm ====="
  cp "$WORK/Checker.kt.snapshot" "$CHK"
  cp "$WORK/PassTiming.kt.snapshot" "$PT"
  patch_arm "$arm"
  if cmp -s "$CHK" "$WORK/Checker.kt.snapshot" && cmp -s "$PT" "$WORK/PassTiming.kt.snapshot"; then
    echo "REFUSED: arm $arm produced no diff against its own snapshot" >&2; exit 3
  fi
  XML="$ROOT/xemantic-typescript-compiler-core/build/test-results/jvmTest"
  rm -rf "$XML"
  ./gradlew :xemantic-typescript-compiler-core:jvmTest \
    --tests '*PartitionSensitivityTest*' --tests '*PassDiagNetSignTest*' \
    --console=plain > "$WORK/test-$arm.log" 2>&1 || true
  if ! grep -qE "BUILD (SUCCESSFUL|FAILED)" "$WORK/test-$arm.log"; then
    echo "REFUSED: arm $arm never reached a build verdict — see $WORK/test-$arm.log" >&2
    exit 4
  fi
  if grep -q "^e: " "$WORK/test-$arm.log"; then
    echo "REFUSED: arm $arm did not COMPILE — see $WORK/test-$arm.log" >&2
    tail -20 "$WORK/test-$arm.log" >&2
    exit 4
  fi
  echo "RESULT $arm  PartitionSensitivityTest=$(verdict "$XML" PartitionSensitivityTest)" \
       " PassDiagNetSignTest=$(verdict "$XML" PassDiagNetSignTest)"
done

cp "$WORK/Checker.kt.snapshot" "$CHK"
cp "$WORK/PassTiming.kt.snapshot" "$PT"
echo "restored"
