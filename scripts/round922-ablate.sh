#!/usr/bin/env bash
# (API.7) round 922 — one-mistake-at-a-time ablation of the syntactic-role
# mechanism and of the three refusals it cashed.
#
# Protocol, per CLAUDE.md: ONE mistake per arm (round 807), each arm dry-run for a
# REAL diff (round 902), and the tree restored from a sha256-verified SNAPSHOT
# rather than by `git checkout` (round 851). An arm that reddens nothing is
# recorded as UNDISCRIMINATED, never claimed.
set -uo pipefail
cd "$(dirname "$0")/.."

ROLES_SRC=xemantic-typescript-compiler-project/src/commonMain/kotlin/SyntaxRoles.kt
INDEX_SRC=xemantic-typescript-compiler-project/src/commonMain/kotlin/SourceIndex.kt
CHECKER_SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
FILES=("$ROLES_SRC" "$INDEX_SRC" "$CHECKER_SRC")
SNAP_DIR=$(mktemp -d)
OUT=${OUT:-build/round922}
mkdir -p "$OUT"

for f in "${FILES[@]}"; do cp "$f" "$SNAP_DIR/$(basename "$f")"; done
snapshot_sha() { sha256sum "$1" | cut -d' ' -f1; }
declare -A BASE
for f in "${FILES[@]}"; do BASE[$f]=$(snapshot_sha "$f"); done

restore() {
    for f in "${FILES[@]}"; do
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
    # Round 855's dry-run check, against the SNAPSHOT rather than against git.
    # `git diff --shortstat` is VACUOUS on a tree that already carries the round's own
    # uncommitted work: it reports the whole round's diff, identically, for every arm,
    # so it cannot tell an arm that landed from one that did not. The snapshot is the
    # only baseline that is a property of the ARM.
    local changed=0
    for f in "${FILES[@]}"; do
        if ! cmp -s "$f" "$SNAP_DIR/$(basename "$f")"; then changed=$((changed + 1)); fi
    done
    echo "ARM $name diff: $changed file(s) differ from the snapshot"
    if [ "$changed" -eq 0 ]; then
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

# --- A1: an array literal is not a destructuring pass-through ---------------------
a1() {
    patch "$ROLES_SRC" \
        'parent is ArrayLiteralExpression && containsIdentical(parent.elements, current) ->
                    current = parent' \
        'parent is ArrayLiteralExpression && false ->
                    current = parent'
}

# --- A2: an object literal is not a destructuring pass-through --------------------
a2() {
    patch "$ROLES_SRC" \
        'parent is ObjectLiteralExpression && containsIdentical(parent.properties, current) ->
                    current = parent' \
        'parent is ObjectLiteralExpression && false ->
                    current = parent'
}

# --- A3: a `for (x of/in …)` head is not a write ----------------------------------
a3() {
    patch "$ROLES_SRC" \
        'parent is ForOfStatement && parent.initializer === current -> return ReferenceUse.WRITE' \
        'parent is ForOfStatement && false -> return ReferenceUse.WRITE'
}

# --- A4: a member name does not ascend to its access ------------------------------
a4() {
    patch "$ROLES_SRC" \
        'parent is PropertyAccessExpression && parent.name === current -> current = parent' \
        'parent is PropertyAccessExpression && false -> current = parent'
}

# --- A5: a declaration name that binds no storage is read as a value --------------
a5() {
    patch "$ROLES_SRC" \
        '        if (isNonStorageDeclarationName(parent, node)) return ReferenceUse.UNCLASSIFIED' \
        '        if (false) return ReferenceUse.UNCLASSIFIED'
}

# --- A6: a type-position name is read as a value ----------------------------------
a6() {
    patch "$ROLES_SRC" \
        '        typePositionUse(node, parent)?.let { return it }' \
        '        if (false) typePositionUse(node, parent)?.let { return it }'
}

# --- A7: every free caret is a STATEMENT position (the unconditional list) ---------
a7() {
    patch "$ROLES_SRC" \
        '''        if (path.isEmpty()) return GrammarPosition.STATEMENT
        val innermost = path[path.size - 1]''' \
        '''        if (path.isEmpty()) return GrammarPosition.STATEMENT
        if (true) return GrammarPosition.STATEMENT
        val innermost = path[path.size - 1]'''
}

# --- A8: `await` and `yield` are not gated on the enclosing function ---------------
a8() {
    patch "$ROLES_SRC" \
        '''                if (isAsyncFunctionLike(fn)) keywords.add("await")
                if (isGeneratorFunctionLike(fn)) keywords.add("yield")''' \
        '''                keywords.add("await")
                keywords.add("yield")'''
}

# --- A9: the module-level declaration starters are offered everywhere --------------
a9() {
    patch "$ROLES_SRC" \
        'if (atModuleLevel(path)) keywords.addAll(MODULE_LEVEL_KEYWORDS)' \
        'keywords.addAll(MODULE_LEVEL_KEYWORDS)'
}

# --- A10: `break` / `continue` are not gated on an enclosing loop or switch --------
a10() {
    patch "$ROLES_SRC" \
        '''                if (jump != Jump.NONE) keywords.add("break")
                if (jump == Jump.LOOP) keywords.add("continue")''' \
        '''                keywords.add("break")
                keywords.add("continue")'''
}

# --- A11: keywords are computed for a caret whose word is absent only --------------
#   (the anchor reads the CARET rather than the word's own start)
a11() {
    patch "$INDEX_SRC" \
        'keywords = SyntaxRoles.keywordsFor(pathAt(if (word >= 0) anchorStart else offset)),' \
        'keywords = SyntaxRoles.keywordsFor(pathAt(offset)),'
}

# --- A12: accessibility hides whenever the caret is inside ANY class ---------------
a12() {
    patch "$CHECKER_SRC" \
        '''            if (enclosingClass === declaring) return false
            if (item.accessibility == "private") continue''' \
        '''            if (enclosingClass != null) return false
            if (item.accessibility == "private") continue'''
}

# --- A13: the enclosing-class ascent stops at the first function-like --------------
a13() {
    patch "$CHECKER_SRC" \
        '''            if (current is ClassDeclaration || current is ClassExpression) return current
            current = (current as NodeBase).parent''' \
        '''            if (current is ClassDeclaration || current is ClassExpression) return current
            if (current is ArrowFunction) return null
            current = (current as NodeBase).parent'''
}

# --- A14: no filter at all — the pre-(API.7) behaviour -----------------------------
a14() {
    patch "$CHECKER_SRC" \
        'if (typeCaptureMemberInaccessible(item, symbols, enclosingClass)) continue' \
        'if (false) continue'
}

ALL=(a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14)
ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=("${ALL[@]}")
for arm in "${ARMS[@]}"; do
    if [[ " ${ALL[*]} " == *" $arm "* ]]; then run_arm "$arm" "$arm"; else echo "unknown arm: $arm"; fi
done
restore
echo "complete; tree restored"
