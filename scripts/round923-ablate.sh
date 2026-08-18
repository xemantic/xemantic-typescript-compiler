#!/usr/bin/env bash
# (BUG.3) round 923 — one deliberate mistake at a time in `typeCaptureThisClass`,
# each arm restored from a sha256-VERIFIED snapshot.
#
# WHY A SNAPSHOT AND NOT `git checkout` (round 789/851): the round's own work is
# uncommitted, so a checkout would destroy it. And why the snapshot is also the
# DRY-RUN BASELINE (round 922): `git diff --shortstat` on a tree carrying the
# round's uncommitted work prints the round's WHOLE diff for every arm, so it
# cannot tell a landed edit from an unlanded one. The `patch` helper's
# ANCHOR-COUNT assertion (exactly one occurrence, or exit 3) is what carries this.
set -uo pipefail
cd "$(dirname "$0")/.."
CHK=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SNAP=build/round923/Checker.kt.snap
OUT=build/round923
mkdir -p "$OUT"
cp "$CHK" "$SNAP"
SNAP_SHA=$(sha256sum "$SNAP" | cut -d' ' -f1)
echo "snapshot sha256 $SNAP_SHA"

restore() {
  cp "$SNAP" "$CHK"
  local now; now=$(sha256sum "$CHK" | cut -d' ' -f1)
  [[ "$now" == "$SNAP_SHA" ]] || { echo "RESTORE FAILED"; exit 9; }
}

patch() {  # patch <old> <new>
  python3 - "$CHK" "$1" "$2" <<'PY'
import sys
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding='utf-8').read()
n = s.count(old)
if n != 1:
    sys.stderr.write(f"ANCHOR COUNT {n}, expected 1\n"); sys.exit(3)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
}

run_arm() {  # run_arm <name> <old> <new>
  local name="$1"; shift
  restore
  patch "$1" "$2" || { echo "$name: ANCHOR MISS"; restore; return; }
  local after; after=$(sha256sum "$CHK" | cut -d' ' -f1)
  [[ "$after" != "$SNAP_SHA" ]] || { echo "$name: EDIT DID NOT LAND"; restore; return; }
  rm -rf xemantic-typescript-compiler-project/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-project:jvmTest --tests '*ProjectThisReceiverTest*' \
      --tests '*ProjectMemberAccessibilityTest*' --tests '*ProjectCompletionTest*' \
      --tests '*ProjectDefinitionTest*' > "$OUT/$name.log" 2>&1
  python3 - "$name" "$OUT" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
name, out = sys.argv[1], sys.argv[2]
red = []
for f in glob.glob('xemantic-typescript-compiler-project/build/test-results/jvmTest/*.xml'):
    for tc in ET.parse(f).getroot().iter('testcase'):
        if tc.findall('failure') or tc.findall('error'):
            red.append(tc.get('name').replace('[jvm]', ''))
red.sort()
print(f"{name}: {len(red)} red")
for r in red:
    print(f"    {r}")
open(f"{out}/{name}.red", "w").write("\n".join(red))
PY
  restore
}

run_arm_prepatched() {  # like run_arm, but keeps an already-applied edit
  local name="$1"; shift
  patch "$1" "$2" || { echo "$name: ANCHOR MISS"; restore; return; }
  local after; after=$(sha256sum "$CHK" | cut -d' ' -f1)
  [[ "$after" != "$SNAP_SHA" ]] || { echo "$name: EDIT DID NOT LAND"; restore; return; }
  rm -rf xemantic-typescript-compiler-project/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-project:jvmTest --tests '*ProjectThisReceiverTest*' \
      --tests '*ProjectMemberAccessibilityTest*' --tests '*ProjectCompletionTest*' \
      --tests '*ProjectDefinitionTest*' > "$OUT/$name.log" 2>&1
  python3 - "$name" "$OUT" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
name, out = sys.argv[1], sys.argv[2]
red = []
for f in glob.glob('xemantic-typescript-compiler-project/build/test-results/jvmTest/*.xml'):
    for tc in ET.parse(f).getroot().iter('testcase'):
        if tc.findall('failure') or tc.findall('error'):
            red.append(tc.get('name').replace('[jvm]', ''))
red.sort()
print(f"{name}: {len(red)} red")
for r in red:
    print(f"    {r}")
open(f"{out}/{name}.red", "w").write("\n".join(red))
PY
  restore
}

ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5 A6)
for arm in "${ARMS[@]}"; do
case "$arm" in
  # A1 — an arrow is NOT transparent (the pre-fix behaviour, expressed inside the
  #      new ascent): the first arrow stops the walk.
  A1) run_arm A1 '                is ArrowFunction -> {}' '                is ArrowFunction -> return null' ;;
  # A2 — a `function` is transparent (i.e. `spineCaClassCtx` reused verbatim on
  #      its bug-compatible arm, and the same mistake for a fn EXPRESSION).
  A2) run_arm A2 '                is FunctionExpression, is FunctionDeclaration -> return null' '                is FunctionExpression, is FunctionDeclaration -> {}' ;;
  # A3 — a STATIC member answers with the instance class.
  A3) run_arm A3 '        if (isStatic) return null
        return (member as NodeBase).parent as? ClassDeclaration' '        return (member as NodeBase).parent as? ClassDeclaration' ;;
  # A4 — an OBJECT LITERAL member is treated as a class member: instead of
  #      failing the `as? ClassDeclaration` cast, keep ascending past it.
  A4) run_arm A4 '                is MethodDeclaration ->
                    return typeCaptureThisOwner(parent, ModifierFlag.Static in parent.modifiers)' '                is MethodDeclaration ->
                    typeCaptureThisOwner(parent, ModifierFlag.Static in parent.modifiers)
                        ?.let { return it }' ;;
  # A5 — the ascent does not stop at a CLASS EXPRESSION. MEASURED REDUNDANT (0 red):
  #      the member arm's `as? ClassDeclaration` cast already answers null for a
  #      class expression's method, so this guard is a second lock on one door.
  #      Recorded as a redundant guard rather than claimed as coverage; A7 is the
  #      arm that shows the door is real.
  A5) run_arm A5 '                is ClassDeclaration, is ClassExpression -> return null' '                is ClassDeclaration -> return null' ;;
  # A6 — the install is reverted to `frame.classForThis`: the whole fix off.
  A6) run_arm A6 '        currentClassForThis = typeCaptureThisClass(node)' '        currentClassForThis = frame.classForThis' ;;
  # A7 — A4 AND A5 TOGETHER, and deliberately so. Each alone reddens nothing at the
  #      class-expression pin because the two guards are MUTUALLY REDUNDANT there:
  #      with the member arm intact, `typeCaptureThisOwner`'s `as? ClassDeclaration`
  #      cast already answers null for a class EXPRESSION's method; with the
  #      ClassExpression arm intact, the ascent stops there instead. This arm is NOT
  #      an attribution (round 807 forbids that) — it is the demonstration that the
  #      pin is not vacuous, and that removing BOTH guards produces the confident
  #      wrong list the pin exists to refuse.
  A7) restore
      patch '                is MethodDeclaration ->
                    return typeCaptureThisOwner(parent, ModifierFlag.Static in parent.modifiers)' '                is MethodDeclaration ->
                    typeCaptureThisOwner(parent, ModifierFlag.Static in parent.modifiers)
                        ?.let { return it }' || echo "A7 anchor 1 miss"
      run_arm_prepatched A7 '                is ClassDeclaration, is ClassExpression -> return null' '                is ClassDeclaration -> return null' ;;
  *) echo "unknown arm $arm" ;;
esac
done
restore
echo "tree restored, sha256 $(sha256sum "$CHK" | cut -d' ' -f1)"
