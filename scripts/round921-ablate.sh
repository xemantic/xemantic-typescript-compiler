#!/usr/bin/env bash
# (API.6) round 921 — one-mistake-at-a-time ablation of signature help.
#
# Protocol, per CLAUDE.md: ONE mistake per arm (round 807), each arm dry-run for a
# REAL diff (round 902), and the tree restored from a sha256-verified SNAPSHOT
# rather than by `git checkout` (round 851 — a checkout also destroys uncommitted
# work in the ablated file). An arm that reddens nothing is recorded as
# UNDISCRIMINATED, never claimed.
set -uo pipefail
cd "$(dirname "$0")/.."

INDEX_SRC=xemantic-typescript-compiler-project/src/commonMain/kotlin/SourceIndex.kt
CHECKER_SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SNAP_DIR=$(mktemp -d)
OUT=${OUT:-build/round921}
mkdir -p "$OUT"

for f in "$INDEX_SRC" "$CHECKER_SRC"; do
    cp "$f" "$SNAP_DIR/$(basename "$f")"
done
snapshot_sha() { sha256sum "$1" | cut -d' ' -f1; }
declare -A BASE
for f in "$INDEX_SRC" "$CHECKER_SRC"; do
    BASE[$f]=$(snapshot_sha "$f")
done

restore() {
    for f in "$INDEX_SRC" "$CHECKER_SRC"; do
        cp "$SNAP_DIR/$(basename "$f")" "$f"
        if [ "$(snapshot_sha "$f")" != "${BASE[$f]}" ]; then
            echo "FATAL: restore of $f did not reproduce its sha256" >&2
            exit 2
        fi
    done
}
trap restore EXIT

patch() {
    python3 - "$1" "$2" "$3" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
n = s.count(old)
if n != 1:
    sys.stderr.write("ANCHOR COUNT %d (expected 1) in %s\n" % (n, path))
    sys.exit(3)
open(path, "w").write(s.replace(old, new))
PY
}

run_arm() {
    local name="$1"; shift
    echo "=== ARM $name"
    restore
    "$@" || { echo "ARM $name: patch failed"; return; }
    local diff_lines
    diff_lines=$(git diff --shortstat -- "$INDEX_SRC" "$CHECKER_SRC")
    echo "ARM $name diff: $diff_lines"
    if [ -z "$diff_lines" ]; then
        echo "ARM $name: NO DIFF — the edit did not land"; return
    fi
    rm -rf xemantic-typescript-compiler-project/build/test-results/jvmTest
    ./gradlew :xemantic-typescript-compiler-project:jvmTest > "$OUT/$name.log" 2>&1
    if ! grep -qa "BUILD SUCCESSFUL\|BUILD FAILED" "$OUT/$name.log"; then
        echo "ARM $name: BUILD DID NOT COMPLETE — see $OUT/$name.log"; return
    fi
    if grep -qa "^e: " "$OUT/$name.log"; then
        echo "ARM $name: DID NOT COMPILE"; grep -a "^e: " "$OUT/$name.log" | head -3; return
    fi
    python3 - "$name" "$OUT" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
name, out = sys.argv[1], sys.argv[2]
red = []
for p in glob.glob('xemantic-typescript-compiler-project/build/test-results/jvmTest/*.xml'):
    for case in ET.parse(p).getroot().iter('testcase'):
        if case.find('failure') is not None or case.find('error') is not None:
            red.append(case.get('classname').split('.')[-1] + ' :: ' + case.get('name'))
red.sort()
print("ARM %s RED %d" % (name, len(red)))
for r in red:
    print("    " + r)
open("%s/%s.red" % (out, name), "w").write("\n".join(red) + "\n")
PY
}

# --- A1: the anchor keeps the OUTERMOST call instead of the innermost -------------
a1() {
    patch "$INDEX_SRC" \
        'if (best != null && start <= best.argumentListStart) continue' \
        'if (best != null && start >= best.argumentListStart) continue'
}

# --- A2: only the FIRST overload is reported --------------------------------------
a2() {
    patch "$CHECKER_SRC" \
        '            val rendered = signatures.map {' \
        '            val rendered = signatures.take(1).map {'
}

# --- A3: the rest-parameter clamp removed -----------------------------------------
a3() {
    patch "$CHECKER_SRC" \
        'parameters.lastOrNull()?.isRest == true -> parameters.size - 1' \
        'parameters.lastOrNull()?.isRest == true -> -1'
}

# --- A4: the RECEIVER path removed — only a bare name resolves a callee ------------
a4() {
    patch "$CHECKER_SRC" \
        '        return getCallSignaturesOfType(getCalleeType(callee))' \
        '        if (callee !is Identifier) return emptyList()
        return getCallSignaturesOfType(getCalleeType(callee))'
}

# --- A5: the EXPORT-TABLE leg removed ---------------------------------------------
a5() {
    patch "$CHECKER_SRC" \
        '        if (callee is PropertyAccessExpression) {
            val exported = typeCaptureExportedMember(callee.expression, callee.name.text)' \
        '        if (callee is PropertyAccessExpression && false) {
            val exported = typeCaptureExportedMember(callee.expression, callee.name.text)'
}

# --- A6: the active signature is always 0 -----------------------------------------
a6() {
    patch "$CHECKER_SRC" \
        '        val completed = arguments.take(activeArgument.coerceAtLeast(0))' \
        '        if (signatures.isNotEmpty()) return 0
        val completed = arguments.take(activeArgument.coerceAtLeast(0))'
}

# --- A7: the active signature is chosen by ARITY alone ----------------------------
a7() {
    patch "$CHECKER_SRC" \
        '            if (!signatureAcceptsArgs(sig, completed)) continue' \
        '            if (false && !signatureAcceptsArgs(sig, completed)) continue'
}

# --- A8: every comma in the region counts, not only this list's separators ---------
a8() {
    patch "$INDEX_SRC" \
        'if (arguments.none { at >= it.pos && at < realEndOf(it) }) count++' \
        'if (at >= 0) count++'
}

# --- A9: the region is the call's own real end, not the bracket-matched close ------
a9() {
    patch "$INDEX_SRC" \
        '            val end = argumentListEndFrom(openParen)' \
        '            val end = realEndOf(node)'
}

# --- A10: the declaration render for a dropped binding-pattern parameter removed ---
a10() {
    patch "$CHECKER_SRC" \
        'if (declared != null && declared.size > sig.parameters.size) {' \
        'if (declared != null && declared.size < sig.parameters.size) {'
}

# --- A11: a parameter's label range does not follow the label ---------------------
a11() {
    patch "$CHECKER_SRC" \
        '                    labelStart = start,' \
        '                    labelStart = 0,'
}

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11)
for arm in "${ARMS[@]}"; do
    case "$arm" in
        a1|a2|a3|a4|a5|a6|a7|a8|a9|a10|a11) run_arm "$arm" "$arm" ;;
        *) echo "unknown arm: $arm" ;;
    esac
done
restore
echo "complete; tree restored"
