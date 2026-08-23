#!/usr/bin/env bash
# (INC.18) THE PROOF THAT THE RE-ARMED GATE CAN FAIL.
#
# A gate nobody has shown can fail has not been re-armed; it has merely been
# re-run. This injects, ONE AT A TIME, the deliberate mistakes (INC.17) refuses to
# land a re-entrant checker without — a partition-dependent pass that produces
# nothing when the checker is narrowed, and round 609's collector starvation — and
# reports each arm's RED/GREEN split ACROSS BOTH GATE ARMS.
#
# The expected reading is a SPLIT: the sensitivity arm reddens where the realism
# arm stays green, because on tsc's own sources one pass nets every diagnostic and
# 5 of 78 files carry any row.
#
# Protocol (CLAUDE.md rounds 789/805/851/855/922):
#  * COMMIT the harness before running this — the restore is `cp` from a snapshot,
#    and any uncommitted edit to Checker.kt is destroyed by it.
#  * Each arm is applied ALONE (a combined ablation cannot attribute).
#  * Each patch asserts its anchor occurs EXACTLY ONCE and the driver refuses an
#    arm whose edit does not differ from its own snapshot — `git diff --shortstat`
#    is vacuous on a tree that already carries the round's work.
#  * The rewrite+build stage runs in the FOREGROUND; a killed detached run leaves
#    an ablated Checker.kt in the tree with no marker.
#
# Usage: scripts/partition-gate-ablate.sh [arm …]      (default: all)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
CHK="$ROOT/xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
WORK="$ROOT/build/inc18-ablate"
mkdir -p "$WORK"
SNAP="$WORK/Checker.kt.snapshot"

if [[ -n "$(git status --porcelain -- "$CHK")" ]]; then
  echo "REFUSED: Checker.kt has uncommitted changes; this driver restores by copy" >&2
  exit 2
fi

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(a1 a2 a3 a4 a5)

patch_arm() {
python3 - "$1" "$CHK" <<'PY'
import sys
arm, path = sys.argv[1], sys.argv[2]
s = open(path).read()

# (anchor, replacement) per arm. Each anchor must occur EXACTLY once.
NOOP = "\n        // ABLATION: this pass produces nothing once the checker is narrowed.\n        if (assignedFileNames != null) return"
ARMS = {
    # a1/a2 — the (INC.17) fear itself: a partition-DEPENDENT tail walker that a
    # replay skipped, so the newly asked file gets none of its rows.
    "a1": ("    private fun checkMissingImplementations() {",
           "    private fun checkMissingImplementations() {" + NOOP),
    "a2": ("    private fun checkConflictMarkers() {",
           "    private fun checkConflictMarkers() {" + NOOP),
    # a3 — round 609: a program-wide COLLECTOR gated on the partition, so the
    # narrowed build resolves its per-file type maps for the assigned file only.
    "a3": ("        if (FltmCensus.on) FltmCensus.beginSetup()\n        for (result in binderResults) {",
           "        if (FltmCensus.on) FltmCensus.beginSetup()\n        // ABLATION: round 609 — a collector gated on the partition.\n        for (result in checkedResults) {"),
    # a4 — the CONTROL: a pass that nets a diagnostic on NEITHER project, so both
    # arms must stay GREEN. Without it, redness elsewhere is not attributable.
    "a4": ("    private fun checkCloduleTest2() {",
           "    private fun checkCloduleTest2() {" + NOOP),
    # a5 — the OTHER control: the one pass that nets every diagnostic tsc's own
    # sources report, so BOTH arms must redden. It is what shows the realism arm
    # is not permanently green.
    "a5": ("    private fun checkSpine() {",
           "    private fun checkSpine() {" + NOOP),
}
if arm not in ARMS:
    sys.exit("unknown arm %r" % arm)
anchor, repl = ARMS[arm]
n = s.count(anchor)
if n != 1:
    sys.exit("REFUSED: arm %s anchor occurs %d times, expected exactly 1" % (arm, n))
open(path, "w").write(s.replace(anchor, repl))
print("arm %s patched" % arm)
PY
}

run_arm_gate() {
  local which="$1" log="$2"
  set +e
  bash scripts/partition-gate.sh "$which" > "$log" 2>&1
  local rc=$?
  set -e
  if grep -q "^EQUIVALENT" "$log"; then echo GREEN
  elif grep -q "^DIVERGED" "$log"; then echo "RED($(grep -c '^DISAGREE' "$log") files)"
  else echo "REFUSED(rc=$rc)"; fi
}

cp "$CHK" "$SNAP"
trap 'cp "$SNAP" "$CHK"; echo "tree restored"' EXIT

for arm in "${ARMS[@]}"; do
  echo "===== ARM $arm ====="
  cp "$SNAP" "$CHK"
  patch_arm "$arm"
  # THE LANDED-EDIT CONTROL: against the ARM'S OWN snapshot, never `git diff`.
  if cmp -s "$CHK" "$SNAP"; then
    echo "REFUSED: arm $arm produced no diff against its own snapshot" >&2; exit 3
  fi
  ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm --console=plain \
    > "$WORK/build-$arm.log" 2>&1 || true
  if ! grep -q "BUILD SUCCESSFUL" "$WORK/build-$arm.log"; then
    echo "REFUSED: arm $arm did not build — see $WORK/build-$arm.log" >&2
    tail -20 "$WORK/build-$arm.log" >&2
    exit 4
  fi
  realism="$(run_arm_gate realism "$WORK/realism-$arm.log")"
  sensitivity="$(run_arm_gate sensitivity "$WORK/sensitivity-$arm.log")"
  echo "RESULT $arm  realism=$realism  sensitivity=$sensitivity"
done

cp "$SNAP" "$CHK"
./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm --console=plain \
  > "$WORK/build-restore.log" 2>&1 || true
grep -q "BUILD SUCCESSFUL" "$WORK/build-restore.log" \
  || { echo "REFUSED: restore build failed" >&2; exit 5; }
echo "restored and rebuilt"
