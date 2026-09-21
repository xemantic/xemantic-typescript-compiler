#!/usr/bin/env bash
# (CHK.33) single-mistake ablation over the round's six signals.
#
# ONE MISTAKE PER ARM (round 807): a combined ablation credits a pin with discrimination it
# does not have.  Each arm is a single `python3` substitution with an ANCHOR-COUNT assertion
# (exactly one occurrence, else the arm exits non-zero) — a substitution helper without one
# is a silently DEAD arm, and round 922 measured that `git diff --shortstat` cannot tell a
# landed edit from an unlanded one on a tree that already carries the round's own work, so
# every arm additionally `cmp`s the file against its OWN snapshot.
#
# RUN IT ON A COMMITTED TREE (round 789): the arm's undo is `git checkout --`, which also
# destroys any uncommitted change in the same file.
#
# Each arm: patch -> build -> run the pin class + DestructuredParamArityTest -> restore.
# It also runs `scripts/corpus-screen.sh --errors` per arm, because round (P18.149) measured
# an arm that read 0 RED on the pins and 1 on the SCREEN: a guard held by a baseline rather
# than a redundant one.
set -uo pipefail
cd "$(dirname "$0")/.."
SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
OUT=build/bench/chk33/ablate; mkdir -p "$OUT"
SNAP="$OUT/Checker.kt.snapshot"
cp "$SRC" "$SNAP"
[[ -z "$(git status --porcelain -- "$SRC")" ]] || { echo "REFUSED: $SRC is dirty — commit first (round 789)"; exit 1; }

ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(a1 a2 a3 a4 a5 a6)

patch_arm() {
python3 - "$1" <<'PYEOF'
import sys
arm = sys.argv[1]
p = 'xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s = open(p, encoding='utf-8', newline='').read()
subs = {
  # a1 — the MAXIMUM goes back to the symbol list (the (CHK.33) defect itself).
  'a1': ("val maxParams = sigs.indices.maxOf { maxOf(sigs[it].parameters.size, declared[it]?.maxParams ?: 0) }",
         "val maxParams = sigs.maxOf { it.parameters.size }"),
  # a2 — the TS2555 arm removed: a rest-bearing combined signature back to TS2554.
  'a2': ("if (anyRest) emitTS2555TooFew(minParams, args.size, calleeExpr, source, fileName)\n            else emitTS2554TooFew(minParams, maxParams, args.size, calleeExpr, source, fileName)",
         "emitTS2554TooFew(minParams, maxParams, args.size, calleeExpr, source, fileName)"),
  # a3 — the shared helper answers nothing: BOTH readers fall back.
  'a3': ("    private fun signatureDeclaredArity(sig: Signature): FuncParamInfo? =\n        when (val d = sig.declaration) {",
         "    private fun signatureDeclaredArity(sig: Signature): FuncParamInfo? =\n        if (true) null else when (val d = sig.declaration) {"),
  # a4 — the `maxOf` barrier dropped (is `parameters.size` ever the larger?).
  'a4': ("val maxParams = sigs.indices.maxOf { maxOf(sigs[it].parameters.size, declared[it]?.maxParams ?: 0) }",
         "val maxParams = sigs.indices.maxOf { declared[it]?.maxParams ?: sigs[it].parameters.size }"),
  # a5 — the declaration's own `hasRest` dropped from `anyRest`.
  'a5': ("val anyRest = sigs.indices.any { sigHasRestParameter(sigs[it]) || declared[it]?.hasRest == true }",
         "val anyRest = sigs.indices.any { sigHasRestParameter(sigs[it]) }"),
  # a6 — the function-TYPE arms removed from the shared helper.
  'a6': ("            is FunctionType -> d.parameters\n            is ConstructorType -> d.parameters\n", ""),
}
old, new = subs[arm]
n = s.count(old)
if n != 1:
    sys.stderr.write("ANCHOR COUNT %d for %s — arm is DEAD\n" % (n, arm)); sys.exit(2)
open(p, 'w', encoding='utf-8', newline='').write(s.replace(old, new))
PYEOF
}

for arm in "${ARMS[@]}"; do
  echo "=== $arm ==="
  patch_arm "$arm" || { echo "$arm: REFUSED (anchor)"; git checkout -- "$SRC"; continue; }
  if cmp -s "$SRC" "$SNAP"; then echo "$arm: REFUSED — file identical to the snapshot"; git checkout -- "$SRC"; continue; fi
  ./gradlew :xemantic-typescript-compiler-core:compileKotlinJvm \
            :xemantic-typescript-compiler-core:compileTestKotlinJvm \
            > "$OUT/$arm.build.log" 2>&1
  if ! grep -q "BUILD SUCCESSFUL" "$OUT/$arm.build.log"; then
    echo "$arm: BUILD FAILED (round 808: a daemon OOM reads exactly like a clean ablation)"
    git checkout -- "$SRC"; continue
  fi
  rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest \
      --tests '*SignatureDeclaredArityTest*' --tests '*DestructuredParamArityTest*' \
      > "$OUT/$arm.test.log" 2>&1
  red=$(python3 - <<'PY'
import glob,xml.etree.ElementTree as ET
f=0
for p in glob.glob('xemantic-typescript-compiler-core/build/test-results/jvmTest/*.xml'):
    r=ET.parse(p).getroot(); f+=int(r.get('failures',0))+int(r.get('errors',0))
print(f)
PY
)
  scr=$(scripts/corpus-screen.sh --errors > "$OUT/$arm.screen.log" 2>&1; echo $?)
  scrn=$(grep -oE '[0-9]+ mismatch' "$OUT/$arm.screen.log" | head -1)
  echo "$arm: pins RED=$red  screen exit=$scr ($scrn)"
  git checkout -- "$SRC"
done
./gradlew :xemantic-typescript-compiler-core:compileKotlinJvm \
          :xemantic-typescript-compiler-core:compileTestKotlinJvm > "$OUT/restore.build.log" 2>&1
echo "restored; rebuilt: $(grep -c 'BUILD SUCCESSFUL' "$OUT/restore.build.log")"
md5sum xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main/com/xemantic/typescript/compiler/Checker.class
