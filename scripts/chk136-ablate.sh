#!/usr/bin/env bash
# (CHK.136) single-mistake ablation. ONE arm at a time (round 807: a combined ablation
# cannot attribute), each arm diffed against its OWN snapshot (round 922: `git diff
# --shortstat` on a dirty tree prints the whole round's diff identically for every arm and
# so cannot tell a landed edit from an unlanded one), and every substitution asserted to
# match EXACTLY ONCE (an anchor that matches zero times is a silently dead arm).
#
# Run from a COMMITTED tree — the restore is `git checkout --`, which destroys any
# uncommitted edit in the ablated file (round 789).
#
# Arms:
#   a1  the general arm never runs                 -> the whole pin class must redden
#   a2  the array-literal/TUPLE refusal removed    -> pin + the 8-profile grid (builder.ts:1423)
#   a3  the UNION-receiver refusal removed         -> corpus: divergentAccessorsTypes8 (prop3, a DATA member)
#   a4  the literal-key ACCESSOR refusal removed   -> corpus: divergentAccessorsTypes8 (box['value'], NON-union receiver)
#   a5  the one-row-per-site dedupe removed        -> corpus: widenedTypes (`t[3] = ""`, strict:false)
#   a6  the narrow this-rooted walker stops claiming a PASSING site -> control for the extraction
set -uo pipefail
cd "$(dirname "$0")/.."
K=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SNAP=build/bench/chk136/ablate; mkdir -p "$SNAP"
ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(a1 a2 a3 a4 a5 a6)

patch() { # patch <file> <old> <new>
  python3 - "$1" "$2" "$3" <<'PY'
import io,sys
p,old,new=sys.argv[1],sys.argv[2],sys.argv[3]
s=io.open(p,encoding='utf-8').read()
n=s.count(old)
if n!=1:
    sys.stderr.write("ANCHOR MATCHED %d TIMES — arm is DEAD\n"%n); sys.exit(2)
io.open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
}

for arm in "${ARMS[@]}"; do
  echo "=========== $arm ==========="
  git checkout -- "$K" || exit 1
  cp "$K" "$SNAP/$arm.orig"
  case "$arm" in
    a1) patch "$K" '        if (target.questionDotToken) return
        // A UNION receiver needs' '        if (target.questionDotToken) return
        if (true) return
        // A UNION receiver needs' ;;
    a2) patch "$K" '        if (value is ArrayLiteralExpression && cheaSlotMentionsTuple(slot)) return' '        if (false && value is ArrayLiteralExpression && cheaSlotMentionsTuple(slot)) return' ;;
    a3) patch "$K" '        if (recvType is Type.Union) return' '        if (false && recvType is Type.Union) return' ;;
    a4) patch "$K" '        if (cheaLiteralKeyNamesAccessor(target, recvType)) return' '        if (false && cheaLiteralKeyNamesAccessor(target, recvType)) return' ;;
    a5) patch "$K" '        if (diagnostics.any {
                it.code == 2322 && it.fileName == fileName && it.line == line && it.character == character
            }
        ) return' '        if (false) return' ;;
    a6) patch "$K" 'if (checkTypeRelatedTo(rhsType, indexValueType, assignableRelation)) return true' 'if (checkTypeRelatedTo(rhsType, indexValueType, assignableRelation)) return false' ;;
    *)  echo "unknown arm $arm"; exit 2 ;;
  esac
  [[ $? -eq 0 ]] || { echo "$arm: PATCH REFUSED"; git checkout -- "$K"; continue; }
  cmp -s "$K" "$SNAP/$arm.orig" && { echo "$arm: DEAD — file unchanged"; git checkout -- "$K"; continue; }
  ./gradlew :xemantic-typescript-compiler-core:compileKotlinJvm -q > "$SNAP/$arm.build.log" 2>&1
  if ! grep -q . <(echo ok); then :; fi
  if [[ ! -f xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main/com/xemantic/typescript/compiler/Checker.class ]]; then
    echo "$arm: BUILD PRODUCED NO CLASS"; git checkout -- "$K"; continue
  fi
  echo "$arm md5 $(md5sum xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main/com/xemantic/typescript/compiler/Checker.class | cut -d' ' -f1)"
  rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*ElementAccessWriteCheckTest*' > "$SNAP/$arm.pins.log" 2>&1
  pins=$(python3 -c "
import glob,xml.etree.ElementTree as ET
f=0
for p in glob.glob('xemantic-typescript-compiler-core/build/test-results/jvmTest/*ElementAccessWriteCheck*.xml'):
    r=ET.parse(p).getroot(); f+=int(r.get('failures',0))+int(r.get('errors',0))
print(f)")
  screen=$(bash scripts/corpus-screen.sh 2>&1 | grep -a 'TOTAL' | sed 's/.*— //;s/ mismatch.*//')
  echo "$arm: pins RED=$pins  screen mismatches=$screen"
  git checkout -- "$K"
done
git checkout -- "$K"
./gradlew :xemantic-typescript-compiler-core:compileKotlinJvm -q > "$SNAP/restore.build.log" 2>&1
echo "restored; Checker.class md5 $(md5sum xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main/com/xemantic/typescript/compiler/Checker.class | cut -d' ' -f1)"
