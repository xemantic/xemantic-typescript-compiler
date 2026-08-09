#!/usr/bin/env bash
# (WARM.11) round 864 — the one-mistake-at-a-time ablation (round 807).
#
# Each arm injects ONE deliberate fault into the committed tree, rebuilds, runs
# the round's pins plus the neighbouring INV.2(b) ones, records which FAIL, and
# reverts. A combined ablation cannot attribute; six failures from six faults
# read as full coverage and prove nothing.
#
# Round 855/856: an array default, and a per-arm `git diff --shortstat` printed
# BEFORE the build, so a no-op edit cannot masquerade as "the guard is
# redundant". Round 808: the build log is grepped for BUILD SUCCESSFUL before a
# zero is believed. Round 851: the tree must be COMMITTED before this runs — the
# revert is `git checkout --`, which deletes uncommitted work in the same file.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="${OUT:-build/bench/r864-ablate}"
mkdir -p "$OUT"

FLOW=xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(M1 M2 M3 M4)

if [[ -n "$(git status --porcelain)" ]]; then
  echo "REFUSED: working tree is dirty — the revert would destroy it" >&2
  exit 1
fi

apply() {
  case "$1" in
    # M1 — the arm is INERT: `--flowIndexLegacy` selects nothing, so both
    # "arms" are the recorded-node fill. This is round 807's blind-pin
    # mechanism, and the non-vacuity pin is the only thing that can see it.
    M1) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt'
s=open(p).read()
a="if (recordedNodes != null && !FlowIndex.legacy) {"
assert s.count(a)==1
open(p,'w').write(s.replace(a,"if (recordedNodes != null) {",1))
EOF
      ;;
    # M2 — the fill reads the WRONG KEY, so a recorded node's slot is filled
    # with the map's answer for an extent nothing wrote. Every recorded node
    # then answers null while still passing the identity check, i.e. a WRONG
    # non-answer rather than a slower one.
    M2) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt'
s=open(p).read()
a="""                if (id in 0 until count) {
                    nodeById[id] = node
                    val f = nodeToFlow[nodeKey(node)]
                    flowById[id] = f
                    if (FrontEnd.mode == FrontEnd.ON) {
                        visited++
                        if (f != null) answered++
                    }
                }
            }
        } else if (sourceFile != null && count > 0) {"""
assert s.count(a)==1
b=a.replace("nodeToFlow[nodeKey(node)]","nodeToFlow[nodeKey(node.pos, node.pos)]",1)
open(p,'w').write(s.replace(a,b,1))
EOF
      ;;
    # M3 — the fill ADDS a node nobody recorded: it also claims the recorded
    # node's PARENT, which the whole-tree walk would have answered from the
    # parent's own extent. This is the one genuinely dangerous direction — an
    # entry that is present and wrong, where an entry that is merely missing
    # degrades to the map.
    M3) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt'
s=open(p).read()
a="""                    val f = nodeToFlow[nodeKey(node)]
                    flowById[id] = f
                    if (FrontEnd.mode == FrontEnd.ON) {
                        visited++
                        if (f != null) answered++
                    }
                }
            }
        } else if (sourceFile != null && count > 0) {"""
assert s.count(a)==1
b="""                    val f = nodeToFlow[nodeKey(node)]
                    flowById[id] = f
                    val par = node.parent
                    val pid = (par as? NodeBase)?.nodeId ?: -1
                    if (pid in 0 until count) {
                        nodeById[pid] = par
                        flowById[pid] = f
                    }
                    if (FrontEnd.mode == FrontEnd.ON) {
                        visited++
                        if (f != null) answered++
                    }
                }
            }
        } else if (sourceFile != null && count > 0) {"""
open(p,'w').write(s.replace(a,b,1))
EOF
      ;;
    # M4 — `recordFlow` stops feeding the list for Identifier nodes, the most
    # plausible "trim the list" slip. It is EXPECTED to leave every pin green:
    # a missing entry degrades to `flowAt`'s map fallback, which is the safety
    # property the whole design rests on. The arm exists to demonstrate that
    # property rather than assert it.
    M4) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Flow.kt'
s=open(p).read()
a="        recordedNodes.add(node)"
assert s.count(a)==1
open(p,'w').write(s.replace(a,"        if (node !is Identifier) recordedNodes.add(node)",1))
EOF
      ;;
    *) echo "unknown arm $1" >&2; return 1 ;;
  esac
}

for ARM in "${ARMS[@]}"; do
  echo "== $ARM =="
  apply "$ARM" || { git checkout -- "$FLOW"; continue; }
  DIFF=$(git diff --shortstat)
  echo "  edit: ${DIFF:-NONE}"
  if [[ -z "$DIFF" ]]; then
    echo "  REFUSED: the edit changed nothing" >&2
    git checkout -- "$FLOW"; continue
  fi
  ./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/$ARM-build.log" 2>&1
  if ! grep -q 'BUILD SUCCESSFUL' "$OUT/$ARM-build.log"; then
    echo "  BUILD FAILED (a compile error is a result too — see the log)"
    git checkout -- "$FLOW"; continue
  fi
  rm -rf xemantic-typescript-compiler-core/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --rerun \
      --tests '*FlowIndexEquivalenceTest*' --tests '*Inv2FlowLookupTest*' \
      --tests '*CliModeRestoreTest*' --tests '*NarrowableRootsPreTestTest*' \
      --tests '*ClosureIndexEquivalenceTest*' --tests '*FlowScanEquivalenceTest*' \
      > "$OUT/$ARM-test.log" 2>&1
  python3 - "$ARM" "$OUT" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
arm, out = sys.argv[1], sys.argv[2]
tot = 0; red = []
for p in glob.glob('*/build/test-results/jvmTest/*.xml'):
    r = ET.parse(p).getroot()
    for tc in r.iter('testcase'):
        tot += 1
        if tc.find('failure') is not None or tc.find('error') is not None:
            red.append(f"{r.get('name')}.{tc.get('name')}")
print(f"  ran {tot} pins, RED {len(red)}")
for r_ in sorted(red):
    print(f"    {r_}")
open(f"{out}/{arm}-red.txt", 'w').write("\n".join(sorted(red)) + "\n")
EOF
  git checkout -- "$FLOW"
done

echo "ablation complete; tree restored: $(git status --porcelain | wc -l) modified files"
