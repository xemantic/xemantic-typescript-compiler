#!/usr/bin/env bash
# ROUND 873 — (SERVE.2): the POSITIVE CONTROL for `scripts/serve_parity.py`.
#
# A green matrix from a blind driver is indistinguishable from parity, and this
# repo has shipped that mistake more than once (round 853's `+0.00% on all 18
# counters` from a frozen classpath; rounds 855/856's ablation driver that
# dispatched no arm and printed `complete`). So before the sweep is believed,
# each arm below reintroduces ONE deliberate divergence, rebuilds, runs the whole
# matrix, and records which cells redden — one mistake at a time (round 807),
# because a combined ablation credits pins with discrimination they do not have.
#
#   A1  the round-872 exit-code bug: a daemon-served compile reports 0
#   A2  the round-873 cwd fix removed: the server ignores request.workingDirectory
#   A3  a stale-answer daemon: the second identical request replays the first
#
# A1 and A2 are real defects this repo has actually shipped; A3 is the SEQUENCE
# axis's own control, because nothing else in the matrix can tell a driver that
# re-runs a request from one that only looks like it does.
#
# The tree is committed before this runs and every arm reverts before the next:
# an arm's edit is undone with `git checkout --`, which also destroys any
# UNCOMMITTED change in that file (round 789, round 851).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="${1:-build/serve-parity-ablate}"
ARMS=("${@:2}")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3)
mkdir -p "$OUT"

[[ -z "$(git status --porcelain)" ]] || { echo "error: tree is dirty; commit first" >&2; exit 1; }

SERVER="xemantic-typescript-compiler-daemon/src/jvmMain/kotlin/server/CompileServer.kt"
CLIENT="xemantic-typescript-compiler-daemon/src/jvmMain/kotlin/server/XtscMain.kt"

apply_arm() {
    case "$1" in
    A1) python3 - <<'PY'
import pathlib
p = pathlib.Path("xemantic-typescript-compiler-daemon/src/jvmMain/kotlin/server/XtscMain.kt")
s = p.read_text()
old = "    return response.exitCode\n"
assert s.count(old) == 1, s.count(old)
p.write_text(s.replace(old, "    return if (response.exitCode == 2) response.exitCode else 0\n"))
PY
        ;;
    A2) python3 - <<'PY'
import pathlib
p = pathlib.Path("xemantic-typescript-compiler-daemon/src/jvmMain/kotlin/server/CompileServer.kt")
s = p.read_text()
old = "            SystemVfs.workingDirectory = workingDirectory.ifEmpty { null }\n"
assert s.count(old) == 1
p.write_text(s.replace(old, "            SystemVfs.workingDirectory = null\n"))
PY
        ;;
    A3) python3 - <<'PY'
import pathlib
p = pathlib.Path("xemantic-typescript-compiler-daemon/src/jvmMain/kotlin/server/CompileServer.kt")
s = p.read_text()
old = "            compileCapturing(request.args, request.workingDirectory)\n"
assert s.count(old) == 1
new = ("            ablationStaleAnswers.getOrPut(request.args.toString()) {\n"
       "                compileCapturing(request.args, request.workingDirectory)\n"
       "            }\n")
s = s.replace(old, new)
anchor = "object CompileServer {\n"
assert s.count(anchor) == 1
s = s.replace(anchor, anchor + "\n    private val ablationStaleAnswers = HashMap<String, CompileResponse>()\n")
p.write_text(s)
PY
        ;;
    *) echo "unknown arm $1" >&2; exit 1 ;;
    esac
}

for arm in "${ARMS[@]}"; do
    echo "=== $arm ==="
    apply_arm "$arm"
    # A dry-run check that the edit is REAL: an arm that changed nothing would
    # produce a green sweep and read as "the matrix cannot see this", which is
    # the opposite of the truth.
    changed="$(git diff --shortstat)"
    [[ -n "$changed" ]] || { echo "error: arm $arm changed nothing" >&2; exit 1; }
    echo "    edit: $changed"
    if ! ./gradlew assemble > "$OUT/$arm.build.log" 2>&1; then
        echo "error: arm $arm did not build; see $OUT/$arm.build.log" >&2
        git checkout -- "$SERVER" "$CLIENT"; exit 1
    fi
    grep -q "BUILD SUCCESSFUL" "$OUT/$arm.build.log" || {
        echo "error: arm $arm build log has no BUILD SUCCESSFUL" >&2
        git checkout -- "$SERVER" "$CLIENT"; exit 1; }
    python3 -u scripts/serve_parity.py --out "build/serve-parity-$arm" --timeout 120 \
        > "$OUT/$arm.matrix.log" 2>&1 || true
    echo "    verdict: $(tail -3 "$OUT/$arm.matrix.log" | grep 'invocation pairs' || echo '(none)')"
    grep -E '^(DIFF|ERROR)' "$OUT/$arm.matrix.log" | sed 's/^/    /' || echo "    (no red cells)"
    git checkout -- "$SERVER" "$CLIENT"
done

echo "=== restoring HEAD binary ==="
./gradlew assemble > "$OUT/restore.build.log" 2>&1
grep -q "BUILD SUCCESSFUL" "$OUT/restore.build.log" || { echo "error: restore build failed" >&2; exit 1; }
git status --porcelain
echo "ablation complete; tree restored"
