#!/usr/bin/env bash
# (TSGO.3) the second emit receipt (docs/goport-emit-oracle.md § 5): a whole PROJECT emitted by the ported
# compiler (EmitProjectMain, -tsgo jvmTest) against the tsgo 7.0.2 binary, the two output trees `diff -r`ed.
#
#   scripts/tsgo-emit-project.sh [--project DIR] [--out DIR] [--no-build]
#
# Default project: tsc's own 78 sources (build/bench/tsc-project-*). Both compilers get the same tsconfig
# with `--outDir` on the command line. Exit 0 only when the trees are identical AND non-empty, and the
# file counts agree. The classpath is printed through a throwaway init script and every non-cache entry is
# SNAPSHOT (CLAUDE.md: a class dir read mid-build is empty), so a later build cannot change the run.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT=""
OUT="$REPO/build/goport/emit-project"
BUILD=1
GRADLE_LOCK="${GRADLE_LOCK:-/tmp/claude-1000/-home-claude-git-xemantic-typescript-compiler/1f6f3793-7d44-451b-8154-8c3e87e48d6a/scratchpad/gradle.lock}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --project) PROJECT="$2"; shift 2;;
    --out) OUT="$2"; shift 2;;
    --no-build) BUILD=0; shift;;
    *) echo "unknown arg $1" >&2; exit 2;;
  esac
done
if [[ -z "$PROJECT" ]]; then
  mapfile -t P < <(ls -d "$REPO"/build/bench/tsc-project-* 2>/dev/null)
  [[ ${#P[@]} -eq 1 ]] || { echo "expected exactly one build/bench/tsc-project-* (scripts/bench-compile-tsc.sh makes it), found ${#P[@]}" >&2; exit 2; }
  PROJECT="${P[0]}"
fi
PROJECT="$(cd "$PROJECT" && pwd)"
[[ -f "$PROJECT/tsconfig.json" ]] || { echo "no $PROJECT/tsconfig.json" >&2; exit 2; }
TSGO="$REPO/tools/tsgo-7.0.2/lib/tsc"
[[ -x "$TSGO" ]] || { echo "no tsgo binary at $TSGO" >&2; exit 2; }
mkdir -p "$OUT"

if [[ $BUILD -eq 1 ]]; then
  INIT="$OUT/print-cp.init.gradle.kts"
  cat > "$INIT" <<'G'
rootProject {
    findProject(":xemantic-typescript-compiler-tsgo")?.let { p ->
        p.tasks.register("printEmitProjectClasspath") {
            dependsOn("jvmTestClasses")
            val cp = p.configurations.getByName("jvmTestRuntimeClasspath")
            doLast {
                val dirs = listOf("main", "test").map { p.layout.buildDirectory.dir("classes/kotlin/jvm/$it").get().asFile }
                println("EMITPROJECT_CP=" + (dirs + cp.files).joinToString(":"))
            }
        }
    }
}
G
  mkdir -p "$(dirname "$GRADLE_LOCK")"
  (cd "$REPO" && flock "$GRADLE_LOCK" ./gradlew -I "$INIT" :xemantic-typescript-compiler-tsgo:printEmitProjectClasspath) > "$OUT/gradle.log" 2>&1 \
    || { tail -40 "$OUT/gradle.log"; exit 1; }
  CP_LINES=$(grep -ac '^EMITPROJECT_CP=' "$OUT/gradle.log" || true)
  [[ "$CP_LINES" == 1 ]] || { echo "expected exactly one EMITPROJECT_CP line, got $CP_LINES" >&2; exit 1; }
  RAW_CP=$(grep -a '^EMITPROJECT_CP=' "$OUT/gradle.log" | sed 's/^EMITPROJECT_CP=//')
  rm -rf "$OUT/cp"; mkdir -p "$OUT/cp"
  SNAP=(); i=0
  IFS=: read -ra ENTRIES <<< "$RAW_CP"
  for e in "${ENTRIES[@]}"; do
    [[ -e "$e" ]] || continue
    case "$e" in
      "$HOME"/.gradle/*|"$HOME"/.m2/*) SNAP+=("$e");;
      *) i=$((i+1)); dst="$OUT/cp/$i-$(basename "$e")"; cp -a "$e" "$dst"; SNAP+=("$dst");;
    esac
  done
  (IFS=:; echo "${SNAP[*]}") > "$OUT/classpath.txt"
fi
CP=$(cat "$OUT/classpath.txt")
ls "$OUT"/cp/*/com/xemantic/typescript/tsgo/EmitProjectMainKt.class >/dev/null 2>&1 \
  || { echo "classpath snapshot lacks EmitProjectMainKt (positive control)" >&2; exit 1; }

rm -rf "$OUT/tsgo" "$OUT/kotlin"
set +e
"$TSGO" -p "$PROJECT" --outDir "$OUT/tsgo" --pretty false > "$OUT/tsgo.log" 2>&1
TSGO_RC=$?
java -Xss64m -Xmx6g -cp "$CP" com.xemantic.typescript.tsgo.EmitProjectMainKt "$PROJECT/tsconfig.json" "$OUT/kotlin" > "$OUT/kotlin.log" 2>&1
KT_RC=$?
set -e
[[ $KT_RC -eq 0 ]] || { tail -30 "$OUT/kotlin.log"; echo "EmitProjectMain failed ($KT_RC)" >&2; exit 1; }
N_TSGO=$(find "$OUT/tsgo" -type f 2>/dev/null | wc -l)
N_KT=$(find "$OUT/kotlin" -type f 2>/dev/null | wc -l)
D_TSGO=$(grep -ac 'error TS' "$OUT/tsgo.log" || true)
echo "project: $PROJECT"
echo "tsgo 7.0.2: exit $TSGO_RC, $D_TSGO diagnostics, $N_TSGO files"
echo "ported:     $(grep -a '^EmitProjectMain:' "$OUT/kotlin.log")"
[[ $N_TSGO -gt 0 ]] || { echo "tsgo emitted nothing: the comparison would be vacuous" >&2; exit 1; }
if diff -r "$OUT/tsgo" "$OUT/kotlin" > "$OUT/diff.txt"; then
  echo "diff -r: IDENTICAL ($N_KT files, $(du -sb "$OUT/kotlin" | cut -f1) bytes)"
else
  echo "diff -r: $(grep -c '^diff\|^Only in' "$OUT/diff.txt") differing files (see $OUT/diff.txt)"
  head -40 "$OUT/diff.txt"
  exit 1
fi
