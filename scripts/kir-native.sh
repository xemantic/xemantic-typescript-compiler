#!/usr/bin/env bash
#
# Compile a TypeScript project to a Kotlin/Native BINARY through the KIR backend.
#
#   xtsc front end  ->  Kotlin IR  ->  konanc -opt  ->  a linked native executable
#
# WHY IT IS A SCRIPT AND NOT A FUNCTION CALL. The JVM path drives kotlinc's own
# phases in-process (`KotlinIrEmitter`); Kotlin/Native has no equivalent phase API
# in 2.4, so the relationship inverts: konanc is the driver and the backend rides
# in as an `IrGenerationExtension` (`KirNativePlugin.kt`). That means a second JVM,
# a plugin classpath, and a runtime klib -- which is what this file assembles.
#
# FOUR THINGS HAD TO BE SOLVED AND EACH IS LOAD-BEARING; a change here that drops
# one of them fails LATE and quietly:
#
#   1. COROUTINES. The native compiler bundles an older kotlinx-coroutines than
#      the xtsc front end links against -- 1.11.0 renamed `runBlocking`'s JVM
#      entry point to `runBlockingK` while KEEPING the old one. So 1.11.0 goes
#      AHEAD of the compiler jar on the java classpath, where it satisfies both
#      callers. A plugin classloader cannot fix this: its parent is the
#      compiler's, and parent-first means the older copy always wins.
#
#   2. FIELD VISIBILITY. Native's IR validator rejects the public fields the JVM
#      backend accepts. The plugin narrows them; see its KDoc.
#
#   3. KLIB SERIALIZATION SEES ONLY THE FRONTEND'S FILES, so a file the plugin
#      ADDS to the module fragment is dropped whole -- and the binary then LINKS
#      and dies at run time with an `IrLinkageError`. The plugin re-parents every
#      generated declaration into the seed's own file. See its KDoc.
#
#   4. THE ENTRY POINT IS RESOLVED BY THE FRONTEND, which never saw the generated
#      `main`, so `-e program.main` answers "could not find" however valid the IR
#      is. The seed declares the `main` konanc finds and the plugin gives it a
#      body calling the generated one.
#
# USE:  scripts/kir-native.sh <project-dir> <entry-file> <output-binary> [--library]
#
set -uo pipefail

REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${1:?usage: kir-native.sh <project-dir> <entry-file> <output-binary> [--library]}"
ENTRY="${2:?usage: kir-native.sh <project-dir> <entry-file> <output-binary> [--library]}"
OUTPUT="${3:?usage: kir-native.sh <project-dir> <entry-file> <output-binary> [--library]}"
PRODUCE="${4:-}"
WORK="${KIR_NATIVE_WORK:-$REPO/build/bench/kir-native}"
KONAN="${KONAN_HOME:-$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-2.4.10}"
KIR_CLASSES="$REPO/xemantic-typescript-compiler-kir/build/classes/kotlin/jvm/main"
# The registrar is found by ServiceLoader, and its META-INF/services file is a
# RESOURCE — which Gradle puts in a different directory from the classes. Omit it
# and the plugin is simply never discovered: konanc exits 0 having compiled the
# empty seed, and the binary is the size of a hello-world (round-853 shape).
KIR_RESOURCES="$REPO/xemantic-typescript-compiler-kir/build/processedResources/jvm/main"

die() { echo "kir-native: $*" >&2; exit 2; }

# REFUSE rather than skip, per CLAUDE.md rounds 853/873/895: a gate that passes
# quietly where its toolchain is absent is worse than no gate.
[ -d "$KONAN" ] || die "no Kotlin/Native distribution at '$KONAN' — set KONAN_HOME.
  Gradle downloads one for any native target; ~5.5 GB."
[ -d "$PROJECT" ] || die "no project directory '$PROJECT'"
[ -f "$KIR_CLASSES/com/xemantic/typescript/compiler/kir/emit/KirNativeRegistrar.class" ] \
    || die "the KIR plugin is not built — run
  ./gradlew :xemantic-typescript-compiler-kir:compileKotlinJvm"
[ -f "$REPO/xemantic-typescript-compiler-kir/build/processedResources/jvm/main/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar" ] \
    || die "the plugin's service resource is not staged — run
  ./gradlew :xemantic-typescript-compiler-kir:jvmProcessResources"

mkdir -p "$WORK"

# ---- the KIR classpath, resolved from the BUILD rather than hand-listed ------
CP_FILE="$WORK/kir-cp.txt"
if [ ! -s "$CP_FILE" ] || [ "$REPO/gradle/libs.versions.toml" -nt "$CP_FILE" ]; then
    init="$WORK/print-kir-classpath.init.gradle.kts"
    cat > "$init" <<'K'
rootProject {
    findProject(":xemantic-typescript-compiler-kir")?.tasks?.register("kirPrintClasspath") {
        doLast {
            val cp = project.configurations.getByName("jvmRuntimeClasspath")
                .resolve().joinToString(":") { it.absolutePath }
            println("KIR_CLASSPATH=$cp")
        }
    }
}
K
    "$REPO/gradlew" --console=plain -I "$init" \
        :xemantic-typescript-compiler-kir:kirPrintClasspath > "$WORK/cp.log" 2>&1
    grep -q "BUILD SUCCESSFUL" "$WORK/cp.log" || die "gradle did not report BUILD SUCCESSFUL — see $WORK/cp.log"
    grep -a '^KIR_CLASSPATH=' "$WORK/cp.log" | head -1 | cut -c15- > "$CP_FILE"
fi
[ -s "$CP_FILE" ] || die "could not resolve the KIR classpath"
KIR_TAIL="$(cat "$CP_FILE")"

COROUTINES="$(tr ':' '\n' <<< "$KIR_TAIL" | grep -m1 'kotlinx-coroutines-core-jvm')"
[ -n "$COROUTINES" ] || die "no kotlinx-coroutines on the KIR classpath (see note 1)"

# ---- the runtime klib, GENERATED from the JVM runtime -----------------------
RUNTIME_SRC="$WORK/JsRuntime.native.kt"
python3 "$REPO/scripts/kir_native_runtime.py" \
    "$REPO/xemantic-typescript-compiler-kir/src/jvmMain/kotlin/kir/runtime/JsRuntime.kt" \
    "$RUNTIME_SRC" > "$WORK/runtime-gen.log" 2>&1 \
    || die "could not derive the native runtime — see $WORK/runtime-gen.log"
if [ ! -f "$WORK/jsruntime.klib" ] || [ "$RUNTIME_SRC" -nt "$WORK/jsruntime.klib" ]; then
    "$KONAN/bin/konanc" "$RUNTIME_SRC" -produce library -o "$WORK/jsruntime" \
        > "$WORK/runtime-build.log" 2>&1 \
        || die "the native runtime did not compile — see $WORK/runtime-build.log"
fi

# ---- the plugin classpath ---------------------------------------------------
# NOT the kotlin compiler or the standard library: the native compiler brings its
# own, and a second copy on the plugin path is a version fight nobody wins.
PLUGIN_ARGS=(-Xplugin="$KIR_CLASSES" -Xplugin="$KIR_RESOURCES")
while IFS= read -r jar; do
    case "${jar##*/}" in
        kotlin-compiler-embeddable*|kotlin-stdlib*|kotlin-reflect*|\
        kotlin-script-runtime*|kotlin-daemon-embeddable*|kotlin-build-tools-api*) continue ;;
    esac
    PLUGIN_ARGS+=(-Xplugin="$jar")
done < <(tr ':' '\n' <<< "$KIR_TAIL")

# ---- the seed ---------------------------------------------------------------
mkdir -p "$WORK/seed"
cat > "$WORK/seed/Seed.kt" <<'K'
// The `main` konanc resolves. Its body is replaced by the KIR native plugin with
// a call to the entry point lowered from the TypeScript program (note 4 above).
fun main() {
}
K

PRODUCE_ARGS=(-opt)
[ "$PRODUCE" = "--library" ] && PRODUCE_ARGS=(-produce library)

XTSC_KIR_PROJECT="$(cd "$PROJECT" && pwd)" XTSC_KIR_ENTRY="$ENTRY" \
java -ea -Xmx6G -Dfile.encoding=UTF-8 -Dkonan.home="$KONAN" \
    -cp "$COROUTINES:$KONAN/konan/lib/kotlin-native-compiler-embeddable.jar" \
    org.jetbrains.kotlin.cli.utilities.MainKt konanc \
    "$WORK/seed/Seed.kt" "${PLUGIN_ARGS[@]}" -l "$WORK/jsruntime.klib" \
    "${PRODUCE_ARGS[@]}" -o "$OUTPUT" > "$WORK/compile.log" 2>&1
status=$?

# POSITIVE CONTROL. The plugin announces itself on stderr; without that line the
# compile ran WITHOUT the backend and produced a binary of the empty seed, at
# exit 0 and with nothing in the log to say so.
grep -a 'xtsc-kir-native:' "$WORK/compile.log" \
    || die "the KIR plugin did not run — konanc compiled the seed alone. See $WORK/compile.log"
if [ $status -ne 0 ]; then
    grep -av '^WARNING' "$WORK/compile.log" | grep -av '^	at ' | head -20 >&2
    die "the native compile failed — see $WORK/compile.log"
fi
echo "kir-native: wrote ${OUTPUT}.kexe"
