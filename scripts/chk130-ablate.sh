#!/usr/bin/env bash
# (CHK.130) ablation. ONE mistake per arm (round 807), each patch anchored on an
# EXACTLY-ONCE occurrence (round 922: `git diff --shortstat` is vacuous on a tree
# that already carries the round's work, so the arm's receipt is its own snapshot
# `cmp`, never a diff line count).
#
# Arms:
#   a0  CONTROL, comment-only                     -> must be ALL GREEN
#   a1  parenthesize EVERY union member           -> must redden the must-NOT-paren pins
#   a2  restore HEAD's resolved-SHAPE predicate   -> must redden exactly the recovered rows
#   a3  parenthesize NO union member              -> must redden the must-paren pins
set -uo pipefail
cd "$(dirname "$0")/.."
F=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SNAP=build/bench/chk130/ablate; mkdir -p "$SNAP"
ARM="${1:-}"
case "$ARM" in
  restore) cp "$SNAP/Checker.kt" "$F"; echo "restored"; exit 0;;
  snapshot) cp "$F" "$SNAP/Checker.kt"; echo "snapshotted"; exit 0;;
esac
[[ -f "$SNAP/Checker.kt" ]] || { echo "REFUSED: no snapshot; run '$0 snapshot' first"; exit 2; }
cp "$SNAP/Checker.kt" "$F"

ANCHOR='    private fun unionMemberRendersAsFunctionType(m: Type): Boolean {'
n=$(grep -cF "$ANCHOR" "$F")
[[ "$n" -eq 1 ]] || { echo "REFUSED: anchor occurs $n times, expected 1"; exit 2; }

python3 - "$F" "$ARM" <<'PY'
import sys
path, arm = sys.argv[1], sys.argv[2]
src = open(path).read()
head = "    private fun unionMemberRendersAsFunctionType(m: Type): Boolean {\n"
i = src.index(head)
j = src.index("\n    }\n", i) + len("\n    }\n")
bodies = {
  # a0: a comment inside the helper — semantics untouched.
  "a0": head + "        // ablation a0: control, comment only\n" + src[i+len(head):j],
  # a1: every union member parenthesized.
  "a1": head + "        return true\n    }\n",
  # a2: HEAD's predicate — the resolved SHAPE, with none of the prints-as-a-name arms.
  "a2": head + ("        return m is Type.Object &&\n"
                "            m.tupleElementTypes == null &&\n"
                "            m.properties.isNullOrEmpty() &&\n"
                "            ((m.callSignatures?.size ?: 0) + (m.constructSignatures?.size ?: 0)) == 1\n    }\n"),
  # a3: no union member ever parenthesized.
  "a3": head + "        return false\n    }\n",
}
if arm not in bodies:
    print("REFUSED: unknown arm " + arm); sys.exit(2)
out = src[:i] + bodies[arm] + src[j:]
if out == src:
    print("REFUSED: arm " + arm + " changed nothing"); sys.exit(2)
open(path, "w").write(out)
print("arm " + arm + " applied")
PY
rc=$?
[[ $rc -ne 0 ]] && { cp "$SNAP/Checker.kt" "$F"; exit $rc; }
if cmp -s "$F" "$SNAP/Checker.kt"; then echo "REFUSED: arm $ARM is byte-identical to the snapshot"; exit 2; fi
exit 0
