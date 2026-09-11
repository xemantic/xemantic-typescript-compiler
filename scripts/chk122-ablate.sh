#!/usr/bin/env bash
# (CHK.122) single-mistake ablation over InGuardFunnelConsultTest's 16 pins.
#
# ONE mistake per arm (round 807: a combined ablation cannot attribute), each
# applied by an anchored substitution that REFUSES unless it matches exactly once
# (round 922: a substitution helper without an anchor count is a second way for an
# arm to be silently dead), and each diffed against the arm's OWN snapshot rather
# than against `git diff --shortstat`, which on a dirty tree prints the same thing
# for every arm.
#
# Carries BOTH controls, which is what makes a 0-RED arm interpretable:
#   c-green  a comment-only edit               -> must be 0 RED
#   c-red    the consult always suppresses     -> must redden every twin
set -uo pipefail
cd "$(dirname "$0")/.."
SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SNAP=build/bench/chk122/ablate; mkdir -p "$SNAP"
cp "$SRC" "$SNAP/Checker.kt.orig"
trap 'cp "$SNAP/Checker.kt.orig" "$SRC"' EXIT

patch_once() {  # <find> <replace>
  python3 - "$SRC" "$1" "$2" <<'PY'
import sys
p, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
t = open(p).read()
n = t.count(old)
if n != 1:
    sys.stderr.write(f"ANCHOR MATCHED {n} TIMES, expected 1 — arm is DEAD\n"); sys.exit(1)
open(p, "w").write(t.replace(old, new))
PY
}

ARMS=("$@"); [[ ${#ARMS[@]} -eq 0 ]] && ARMS=(a1 a2 a3 a4 c-green c-red)

for arm in "${ARMS[@]}"; do
  cp "$SNAP/Checker.kt.orig" "$SRC"
  case "$arm" in
    a1) # drop the funnel consult entirely
        patch_once 'val inGuarded = cmamInGuardMayAddProperty(objectExpr, propName, refuseOnExhaustion = false)' \
                   'val inGuarded = false' ;;
    a2) # restore the REFUSING contract at the funnel
        patch_once 'cmamInGuardMayAddProperty(objectExpr, propName, refuseOnExhaustion = false)' \
                   'cmamInGuardMayAddProperty(objectExpr, propName, refuseOnExhaustion = true)' ;;
    a3) # ignore the property NAME
        patch_once 'return name == propName && getReferencePath(e.right) == path' \
                   'return getReferencePath(e.right) == path' ;;
    a4) # ignore the reference PATH
        patch_once 'return name == propName && getReferencePath(e.right) == path' \
                   'return name == propName' ;;
    c-green) patch_once '        // Check if property exists in type members' \
                        '        // Check if property exists in type members (ablation control: comment only)' ;;
    c-red)   patch_once 'val inGuarded = cmamInGuardMayAddProperty(objectExpr, propName, refuseOnExhaustion = false)' \
                        'val inGuarded = true' ;;
    *) echo "$arm: UNKNOWN ARM"; exit 1 ;;
  esac
  [[ $? -ne 0 ]] && { echo "$arm: PATCH REFUSED"; continue; }
  cmp -s "$SRC" "$SNAP/Checker.kt.orig" && { echo "$arm: DEAD — source identical to baseline"; continue; }

  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*InGuardFunnelConsultTest*' \
      > "$SNAP/$arm.log" 2>&1
  grep -q "BUILD SUCCESSFUL\|tests completed" "$SNAP/$arm.log" || \
    grep -qi "FAILED" "$SNAP/$arm.log" || { echo "$arm: BUILD DIED — see $SNAP/$arm.log"; continue; }

  red=$(python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
names = []
for p in glob.glob('*/build/test-results/jvmTest/*InGuardFunnel*.xml'):
    for tc in ET.parse(p).getroot().iter('testcase'):
        if list(tc): names.append(tc.get('name'))
print(len(names))
for n in sorted(names): print("   RED:", n)
PY
)
  echo "$arm: $(echo "$red" | head -1) RED"
  echo "$red" | tail -n +2
done
cp "$SNAP/Checker.kt.orig" "$SRC"
echo "tree restored; REBUILD before taking any further measurement (CHK.54)"
