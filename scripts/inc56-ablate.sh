#!/usr/bin/env bash
# (INC.56) ABLATION — one deliberate mistake at a time (round 807), against a SNAPSHOT
# rather than `git checkout` (the tree carries this round's own work, so a checkout
# would destroy it — rounds 789/851), with each arm's edit verified against that
# SNAPSHOT rather than by `git diff --shortstat`, which prints the whole round's diff
# identically for every arm (round 922). Every patch asserts its anchor occurs EXACTLY
# once, so a dead arm refuses instead of reading as a redundant guard (round 902).
#
# Usage: scripts/inc56-ablate.sh [a1 a2 a3 a4 a5]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
OVERLAY="xemantic-typescript-compiler-project/src/commonMain/kotlin/OverlayVfs.kt"
SNAP="$(mktemp -d)"
cp "$OVERLAY" "$SNAP/OverlayVfs.kt"
restore() { cp "$SNAP/OverlayVfs.kt" "$OVERLAY"; }
trap restore EXIT

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(a1 a2 a3 a4 a5)

for arm in "${ARMS[@]}"; do
  restore
  ARM="$arm" python3 - "$OVERLAY" <<'PY'
import os, sys
arm = os.environ["ARM"]
p = sys.argv[1]
s = open(p).read()

SERVE_READ = """        retained[k]?.let {
            retainedServes++
            return it
        }
        return delegate.readText(k)"""
SERVE_RESIDENT = """        return retained[k]?.also {
            retainedServes++
            residentServes++
        }"""
CONTENTS_RESIDENT = """        contents[k]?.let {
            residentServes++
            return it
        }
        if (!trustFilesystem) return null
"""

def sub(old, new):
    global s
    assert s.count(old) == 1, f"{arm}: anchor occurs {s.count(old)} times"
    s = s.replace(old, new)

if arm == "a1":       # the promise never serves anything retained
    sub(SERVE_READ, "        return delegate.readText(k)")
    sub(SERVE_RESIDENT, "        return null")
elif arm == "a2":     # `.json` is retained and served like any source file
    sub("        if (k.endsWith(\".json\") || k in deleted || k in contents) return\n",
        "        if (k in deleted || k in contents) return\n")
    sub("        if (!trustFilesystem || json) return delegate.readText(k)\n",
        "        if (!trustFilesystem) return delegate.readText(k)\n")
    sub("        if (k.endsWith(\".json\") || k in deleted) return null\n",
        "        if (k in deleted) return null\n")
elif arm == "a3":     # the promise is not asked for — retention is unconditional
    sub("    override fun retainRead(path: String, text: String) {\n        if (!trustFilesystem) return\n",
        "    override fun retainRead(path: String, text: String) {\n")
    sub("        if (!trustFilesystem || json) return delegate.readText(k)\n",
        "        if (json) return delegate.readText(k)\n")
    sub(CONTENTS_RESIDENT, """        contents[k]?.let {
            residentServes++
            return it
        }
""")
elif arm == "a4":     # a retained read shadows an unsaved buffer
    sub(CONTENTS_RESIDENT, """        if (!trustFilesystem) return contents[k]?.also { residentServes++ }
        retained[k]?.let {
            retainedServes++
            residentServes++
            return it
        }
        contents[k]?.let {
            residentServes++
            return it
        }
""")
elif arm == "a5":     # nothing is ever resident: the crawl pays the handoff again
    sub("    override fun readTextIfResident(path: String): String? {\n        val k = key(path)\n",
        "    override fun readTextIfResident(path: String): String? {\n        if (true) return null\n        val k = key(path)\n")
else:
    raise SystemExit(f"unknown arm {arm}")

open(p, "w").write(s)
PY

  if cmp -s "$OVERLAY" "$SNAP/OverlayVfs.kt"; then
    echo "ARM $arm: DEAD — the edit did not change the file"; exit 5
  fi

  echo "=== ARM $arm ==="
  rm -rf xemantic-typescript-compiler-project/build/test-results/jvmTest
  set +e
  ./gradlew :xemantic-typescript-compiler-project:jvmTest \
    --tests '*ProjectTrustedFilesystemTest*' > "/tmp/inc56-$arm.log" 2>&1
  set -e
  if ! grep -aq "BUILD SUCCESSFUL\|BUILD FAILED" "/tmp/inc56-$arm.log"; then
    echo "  BUILD DID NOT COMPLETE — see /tmp/inc56-$arm.log"; continue
  fi
  if grep -aq "^e: " "/tmp/inc56-$arm.log"; then
    echo "  ARM DID NOT COMPILE — see /tmp/inc56-$arm.log"; continue
  fi
  python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
red, total = [], 0
for p in glob.glob('xemantic-typescript-compiler-project/build/test-results/jvmTest/*.xml'):
    r = ET.parse(p).getroot()
    total += int(r.get('tests', 0))
    for tc in r.iter('testcase'):
        if tc.findall('failure') or tc.findall('error'):
            red.append(tc.get('name'))
print(f"  {len(red)} RED of {total}")
for n in sorted(red):
    print("   -", n)
PY
done
restore
echo "ablation complete; source restored"
echo "NOTE: rebuild before any further measurement — the class dir holds the last arm"
