#!/usr/bin/env bash
# (API.5) round 919 — one-mistake-at-a-time ablation of the find-references
# mechanism and of (BUG.2)'s template token re-scan.
#
# Protocol, per CLAUDE.md: ONE mistake per arm (round 807), each arm dry-run for a
# REAL diff (round 902), and the tree restored from a sha256-verified SNAPSHOT
# rather than by `git checkout` (round 851 — a checkout also destroys uncommitted
# work in the ablated file). An arm that reddens nothing is recorded as
# UNDISCRIMINATED, never claimed.
set -uo pipefail
cd "$(dirname "$0")/.."

PROJECT_SRC=xemantic-typescript-compiler-project/src/commonMain/kotlin/Project.kt
INDEX_SRC=xemantic-typescript-compiler-project/src/commonMain/kotlin/SourceIndex.kt
CHECKER_SRC=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
SNAP_DIR=$(mktemp -d)
OUT=${OUT:-build/round919}
mkdir -p "$OUT"

for f in "$PROJECT_SRC" "$INDEX_SRC" "$CHECKER_SRC"; do
    cp "$f" "$SNAP_DIR/$(basename "$f")"
done
snapshot_sha() { sha256sum "$1" | cut -d' ' -f1; }
declare -A BASE
for f in "$PROJECT_SRC" "$INDEX_SRC" "$CHECKER_SRC"; do
    BASE[$f]=$(snapshot_sha "$f")
done

restore() {
    for f in "$PROJECT_SRC" "$INDEX_SRC" "$CHECKER_SRC"; do
        cp "$SNAP_DIR/$(basename "$f")" "$f"
        if [ "$(snapshot_sha "$f")" != "${BASE[$f]}" ]; then
            echo "FATAL: restore of $f did not reproduce its sha256" >&2
            exit 2
        fi
    done
}
trap restore EXIT

# patch <file> <old> <new>  — fails loudly when the anchor is not found exactly once
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
    diff_lines=$(git diff --shortstat -- "$PROJECT_SRC" "$INDEX_SRC" "$CHECKER_SRC")
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

# --- A1: identity by NAME instead of by resolved declaration set (the grep arm) ---
a1() {
    patch "$PROJECT_SRC" \
        '            if (definition.locations.none { it in seed }) continue' \
        '            if (definition.name != (caret as Identifier).text) continue'
}

# --- A2: a document highlight does not restrict to the queried file ---------------
a2() {
    patch "$PROJECT_SRC" \
        'return referencesOf(key, caret, listOf(key), restrictToQueryFile = true)' \
        'return referencesOf(key, caret, listOf(key), restrictToQueryFile = false)'
}

# --- A3: an occurrence reports the RAW Node.end rather than its real extent -------
a3() {
    patch "$PROJECT_SRC" \
        '            val end = realEnds[definition.fileName]?.get(definition.start) ?: continue' \
        '            val end = definition.end'
}

# --- A4: the caret-IS-a-declaration recovery leg removed --------------------------
a4() {
    patch "$PROJECT_SRC" \
        '        for (definition in definitions) {
            for (location in definition.locations) {' \
        '        if (definitions.isNotEmpty()) return null
        for (definition in definitions) {
            for (location in definition.locations) {'
}

# --- A5: that leg adopts the whole set the occurrence carried ---------------------
a5() {
    patch "$PROJECT_SRC" \
        '                    return setOf(location)' \
        '                    return definition.locations.toSet()'
}

# --- A6: (BUG.2) the template substitution re-scan removed ------------------------
a6() {
    patch "$INDEX_SRC" \
        '                    token == SyntaxKind.TemplateHead -> substitutions.add(0)' \
        '                    token == SyntaxKind.TemplateHead -> Unit'
}

# --- A7: the import-alias hop removed (core) --------------------------------------
a7() {
    patch "$CHECKER_SRC" \
        '        for (declaration in declarations) if (!isImportBindingDecl(declaration)) return symbol
        return resolveImportedSymbolGeneral(symbol) ?: symbol' \
        '        return symbol'
}

# --- A8: only the FIRST declaration of a symbol is recorded (core) ----------------
a8() {
    patch "$CHECKER_SRC" \
        '            for (declaration in symbol.declarations) {
                typeCaptureDeclarationLocation(declaration)?.let { locations.add(it) }
            }' \
        '            symbol.declarations.firstOrNull()?.let { declaration ->
                typeCaptureDeclarationLocation(declaration)?.let { locations.add(it) }
            }'
}

ARMS=("$@")
[ ${#ARMS[@]} -eq 0 ] && ARMS=(A1 A2 A3 A4 A5 A6 A7 A8)
for arm in "${ARMS[@]}"; do
    case "$arm" in
        A1) run_arm A1 a1 ;;
        A2) run_arm A2 a2 ;;
        A3) run_arm A3 a3 ;;
        A4) run_arm A4 a4 ;;
        A5) run_arm A5 a5 ;;
        A6) run_arm A6 a6 ;;
        A7) run_arm A7 a7 ;;
        A8) run_arm A8 a8 ;;
        *) echo "unknown arm $arm" ;;
    esac
done
restore
echo "=== ablation complete; tree restored and sha256-verified"
