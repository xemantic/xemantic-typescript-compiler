#!/usr/bin/env bash
# corpus-screen.sh — a ~70-second SCREEN over every ACTIVE generated corpus subtest of
# BOTH baseline channels — `.errors.txt` (3,046 active) and `.js` EMIT (5,658 active) —
# outside Gradle, in one JVM.
#
# **IT IS NOT THE GATE.** `./gradlew jvmTest` remains the commit gate and always will:
# this screen sees no hand-written pin in `src/commonTest` — and round (P18.92) measured
# a real defect that ONLY a hand-written negative control saw, on a run where the whole
# corpus was clean — nor the `.types`/`.symbols` channels, the `-project` module, the
# externals module or the KIR backend. What it is FOR is the question a (LEGACY.0b)
# family round has to answer before it writes any code: *how many currently-green
# baselines would this rule move?* Round (P18.91) could only argue that; with this it is
# one command against a THROWAWAY build.
#
# WHY THE EMIT CHANNEL EXISTS ((P18.95)). `--noEmit` skips `Transformer.transform` and
# `Emitter.emit` entirely (round 738's `skipEmitOutputs` gate), so the 8-profile grid,
# `cost_gate.py` and every other `--noEmit` instrument in this repo is STRUCTURALLY BLIND
# to a change in emitted bytes. Until this channel existed the JS-emit family of
# (LEGACY.0b) had no blast-radius instrument at all, which is why it was deferred five
# rounds running. (The emit channel is also nearly TWICE the size of the errors one —
# the "~3,100 .js subtests" this file used to quote was an under-count.)
#
# WHY IT IS COMMITTED. (P18.92) built the errors half, used it, and left it in a scratch
# directory, so the next round would have had to rebuild it — including rediscovering the
# traps below, which is exactly what its first run got wrong.
#
# THE TRAPS, all now structural rather than remembered:
#   * the LIVE generated tree is `xemantic-typescript-compiler-core/build/generated/…`;
#     the one at the REPO ROOT is a frozen pre-module-split leftover carrying ZERO
#     `@Ignore` lines, so a census off it reads a plausible-but-wrong corpus. The driver
#     REFUSES a zero-`@Ignore` tree.
#   * the case and baseline files must be read through the suite's own `Path.readText()`,
#     which decodes UTF-16 BOMs; re-implementing it reported two false regressions. The
#     driver calls that function, and the suite's own `errorsMatchBaseline` /
#     `toBaseline` + `sameAs`, directly — so both comparisons (CRLF normalisation,
#     `stripDtsSection`, the conformance `casesDir` provenance header) are the suite's by
#     construction.
#   * a shrunken population reads exactly like a clean run, so the driver refuses below a
#     floor — PER CHANNEL (2800 errors / 5200 emit), because one combined number is met
#     by a healthy channel while its sibling has collapsed to nothing.
#
# USAGE
#     scripts/corpus-screen.sh                       # both channels, whole active corpus
#     scripts/corpus-screen.sh --emit                # the emit channel only
#     scripts/corpus-screen.sh --errors              # the diagnostics channel only
#     scripts/corpus-screen.sh --filter duplicate    # one family (floors waived)
#     scripts/corpus-screen.sh --include Identifier  # also run @Ignore'd matching rows
#     scripts/corpus-screen.sh --diff 3              # print the first 3 diffs
#     XTSC_CLASSES=<dir> scripts/corpus-screen.sh    # screen a THROWAWAY build's classes
#
# Exit 0 only when every selected channel meets its floor and nothing mismatched.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 3

MAIN_CLASSES="${XTSC_CLASSES:-$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main}"
TEST_CLASSES="${XTSC_TEST_CLASSES:-$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test}"
CACHE="$ROOT/build/bench/cp-test.txt"
INIT="$ROOT/build/bench/print-test-classpath.init.gradle.kts"

# (1) POSITIVE CONTROL that the code under test is in the class dirs (round 853: a gate
#     reading a class DIRECTORY needs one, or it can measure a frozen binary forever).
[ -f "$MAIN_CLASSES/com/xemantic/typescript/compiler/Checker.class" ] || {
    echo "corpus-screen: REFUSED — no Checker.class under $MAIN_CLASSES" >&2
    echo "corpus-screen: build first (./gradlew :xemantic-typescript-compiler-core:compileTestKotlinJvm)" >&2
    exit 3
}
[ -f "$TEST_CLASSES/com/xemantic/typescript/compiler/corpus/CorpusScreenMainKt.class" ] || {
    echo "corpus-screen: REFUSED — no CorpusScreenMainKt.class under $TEST_CLASSES" >&2
    echo "corpus-screen: the driver itself is not compiled; run compileTestKotlinJvm" >&2
    exit 3
}

# (2) THE DEPENDENCY TAIL. `jvmTestRuntimeClasspath`, not `jvmRuntimeClasspath` — the
#     driver links against kotlin-test, com.xemantic.kotlin.test AND the power-assert
#     runtime, which is a SEPARATE artifact from the stdlib and whose absence kills
#     thousands of subtests identically in both arms of any comparison (round 839).
#     Validation is delegated to the one home for it, `lib/dep-classpath.sh`, which
#     refuses a cache older than `gradle/libs.versions.toml` or naming a missing jar.
if [ ! -f "$INIT" ]; then
    mkdir -p "$(dirname -- "$INIT")"
    cat > "$INIT" <<'GEOF'
// Written by scripts/corpus-screen.sh. Registered on the CORE project only:
// an `allprojects` registration would also hit the root and `-api`, and a second
// `XTSC_TEST_CLASSPATH=` line is a silently wrong classpath (round MOD.3).
rootProject {
    afterEvaluate {
        findProject(":xemantic-typescript-compiler-core")?.tasks?.register("xtscPrintJvmTestRuntimeClasspath") {
            doLast {
                val cp = project.configurations.getByName("jvmTestRuntimeClasspath")
                    .resolve().joinToString(":") { it.absolutePath }
                println("XTSC_TEST_CLASSPATH=$cp")
            }
        }
    }
}
GEOF
fi

if ! bash "$ROOT/scripts/lib/dep-classpath.sh" --validate "$CACHE" 2>/dev/null; then
    echo "corpus-screen: resolving jvmTestRuntimeClasspath (gradle) ..." >&2
    CP="$("$ROOT/gradlew" -q --console=plain -I "$INIT" \
        :xemantic-typescript-compiler-core:xtscPrintJvmTestRuntimeClasspath 2>/dev/null \
        | sed -n 's/^XTSC_TEST_CLASSPATH=//p')"
    n=$(printf '%s\n' "$CP" | grep -c .)
    [ "$n" = "1" ] || {
        echo "corpus-screen: REFUSED — expected exactly one XTSC_TEST_CLASSPATH line, got $n" >&2
        exit 3
    }
    #     The resolved configuration also names THIS repo's own build outputs. They are
    #     stripped, so the cache is a pure DEPENDENCY tail — which is what the validator's
    #     "every named entry still exists" rule is right for. One of ours legitimately does
    #     NOT exist (`build/classes/java/jvmMain`: the module is Kotlin-only, so that task
    #     is NO-SOURCE), and caching it would make every later run refuse. We supply our
    #     own outputs explicitly below instead, where a positive control already guards them.
    printf '%s' "$CP" | tr ':' '\n' | grep -v "^$ROOT/" | grep . | tr '\n' ':' > "$CACHE"
    bash "$ROOT/scripts/lib/dep-classpath.sh" --validate "$CACHE" || exit 3
fi
DEPS="$(tr '\n' ':' < "$CACHE" | sed 's/:*$//')"

# The module's own resources (the embedded real-lib `.d.ts` snapshots live here).
RES="$ROOT/xemantic-typescript-compiler-core/build/processedResources/jvm/main"

# The class DIRS must win over any jar of the same classes on the tail.
echo "corpus-screen: Checker.class md5 $(md5sum "$MAIN_CLASSES/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
exec java -Xmx4g -cp "$TEST_CLASSES:$MAIN_CLASSES:$RES:$DEPS" \
    com.xemantic.typescript.compiler.corpus.CorpusScreenMainKt "$@"
