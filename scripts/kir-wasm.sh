#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
# xemantic-typescript-compiler - a conformant TypeScript compiler and type
# checker that runs on JVM, native, and WebAssembly
# Copyright (C) 2026 Kazimierz Pogoda / Xemantic
#
# Compiles a TypeScript project to WebAssembly through the KIR backend.
#
#   scripts/kir-wasm.sh <project-dir> <entry.ts> <out-dir> <name> [wasm-wasi|wasm-js]
#
# THE FOURTH BACKEND, and the one that needed no new lowering. Kotlin/Wasm is
# reached exactly as Kotlin/Native is — this module's `IrGenerationExtension`
# rides into somebody else's compiler with `-Xplugin` — but the driver is
# `kotlinc-wasm` (`org.jetbrains.kotlin.cli.js.KotlinWasmCompiler`) instead of
# konanc, so there is no konan distribution and no linker involved.
#
# TWO STAGES, and they are not optional: the Wasm compiler REFUSES to produce a
# klib and link a binary in one invocation ("it is not possible to produce a
# KLIB ... and compile the resulting JavaScript artifact ... at the same time").
# The plugin runs in stage 1 — an `IrGenerationExtension` runs during Fir2Ir,
# which is the klib-producing compilation — and stage 2 only reads what stage 1
# serialized. That is also why the plugin's re-parenting of every generated
# declaration into the SEED's own file is load-bearing here for exactly the
# reason it is on Native: klib serialization sees only the frontend's files.
#
# THE TARGET decides what the artifact is for:
#   wasm-wasi  a standalone `.wasm` for a WASI host (wasmtime, wasmer, workerd
#              with WASI) — the "instead of a container" shape.
#   wasm-js    a `.wasm` plus JS glue for a V8 isolate — the Cloudflare Worker
#              shape.
# Both need a host with the WasmGC and exception-handling proposals: Kotlin/Wasm
# emits GC types, and a host without them REFUSES THE MODULE rather than running
# it slowly.
#
set -uo pipefail

REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${KIR_WASM_WORK:-$REPO/build/kir-wasm}"

die() { echo "kir-wasm: $*" >&2; exit 2; }

[ $# -ge 4 ] || die "usage: kir-wasm.sh <project-dir> <entry.ts> <out-dir> <name> [wasm-wasi|wasm-js]"
PROJECT="$(cd -- "$1" 2>/dev/null && pwd)" || die "no project directory '$1'"
ENTRY="$2"
OUTDIR="$3"
NAME="$4"
TARGET="${5:-wasm-wasi}"
case "$TARGET" in wasm-wasi|wasm-js) ;; *) die "unknown target '$TARGET'" ;; esac

KIR_CLASSES="$REPO/xemantic-typescript-compiler-kir/build/classes/kotlin/jvm/main"
KIR_RESOURCES="$REPO/xemantic-typescript-compiler-kir/build/processedResources/jvm/main"

mkdir -p "$WORK" "$OUTDIR"

# ---- the build's own answers, never a guessed path -------------------------
# One Gradle invocation prints the KIR runtime classpath AND resolves the Wasm
# standard library for this target. Both are properties of `libs.versions.toml`,
# so asking the build is the only way that cannot go stale (CLAUDE.md, round
# 858: a cached classpath guarded against the wrong input is no guard at all).
INIT="$WORK/print-kir-wasm.init.gradle.kts"
cat > "$INIT" <<'GRADLE'
rootProject {
    findProject(":xemantic-typescript-compiler-kir")?.tasks?.register("kirPrintWasmPaths") {
        val kotlinVersion = "KOTLIN_VERSION_PLACEHOLDER"
        val wasmTarget = "WASM_TARGET_PLACEHOLDER"
        doLast {
            val tail = project.configurations.getByName("jvmRuntimeClasspath")
                .resolve().joinToString(":") { it.absolutePath }
            println("KIR_CLASSPATH=$tail")
            // The Wasm standard library is not a dependency of any module here,
            // so it is resolved into a DETACHED configuration rather than added
            // to one — this script must not change what the project depends on.
            // `wasm-wasi` -> `kotlin-stdlib-wasm-wasi`, `wasm-js` -> `...-wasm-js`.
            val notation = "org.jetbrains.kotlin:kotlin-stdlib-$wasmTarget:$kotlinVersion"
            val stdlib = project.configurations.detachedConfiguration(
                project.dependencies.create(notation)
            ).also { it.isTransitive = false }
            println("WASM_STDLIB=" + stdlib.resolve().single().absolutePath)
        }
    }
}
GRADLE
KOTLIN_VERSION="$(grep -E '^kotlin = ' "$REPO/gradle/libs.versions.toml" | head -1 | cut -d'"' -f2)"
[ -n "$KOTLIN_VERSION" ] || die "could not read the Kotlin version from gradle/libs.versions.toml"
sed -i.bak "s/KOTLIN_VERSION_PLACEHOLDER/$KOTLIN_VERSION/; s/WASM_TARGET_PLACEHOLDER/$TARGET/" "$INIT"
rm -f "$INIT.bak"

echo "kir-wasm: resolving the build's classpaths (Kotlin $KOTLIN_VERSION, $TARGET) ..." >&2
GRADLE_LOG="$WORK/paths.log"
"$REPO/gradlew" --console=plain -I "$INIT" \
    :xemantic-typescript-compiler-kir:compileKotlinJvm \
    :xemantic-typescript-compiler-kir:jvmProcessResources \
    :xemantic-typescript-compiler-kir:kirPrintWasmPaths > "$GRADLE_LOG" 2>&1
# A build is verified by its VERDICT, never by exit status alone (round 851/947).
grep -aq "BUILD SUCCESSFUL" "$GRADLE_LOG" || die "gradle did not report BUILD SUCCESSFUL — see $GRADLE_LOG"
KIR_TAIL="$(grep -a '^KIR_CLASSPATH=' "$GRADLE_LOG" | head -1 | cut -c15-)"
WASM_STDLIB="$(grep -a '^WASM_STDLIB=' "$GRADLE_LOG" | head -1 | cut -c13-)"
[ -n "$KIR_TAIL" ] || die "could not resolve the KIR classpath — see $GRADLE_LOG"
[ -f "$WASM_STDLIB" ] || die "could not resolve kotlin-stdlib-$TARGET — see $GRADLE_LOG"
# POSITIVE CONTROL that the code under test is in the directory we prepend.
[ -f "$KIR_CLASSES/com/xemantic/typescript/compiler/kir/emit/KirNativeExtension.class" ] \
    || die "no KIR plugin classes under $KIR_CLASSES"

pick() { tr ':' '\n' <<< "$KIR_TAIL" | grep -m1 -- "$1" || true; }
COMPILER="$(pick 'kotlin-compiler-embeddable')"
STDLIB_JVM="$(pick '/kotlin-stdlib-')"
COROUTINES="$(pick 'kotlinx-coroutines-core-jvm')"
[ -n "$COMPILER" ] || die "no kotlin-compiler-embeddable on the KIR classpath"
[ -n "$COROUTINES" ] || die "no kotlinx-coroutines on the KIR classpath"
# `kotlin-reflect` is on the KIR tail at an OLD version (a transitive one); the
# CLI argument parser needs the compiler's own, so it is resolved beside it.
REFLECT="${COMPILER%/kotlin-compiler-embeddable/*}/kotlin-reflect/$KOTLIN_VERSION"
REFLECT="$(find "$REFLECT" -name "kotlin-reflect-$KOTLIN_VERSION.jar" 2>/dev/null | head -1)"
[ -n "$REFLECT" ] || die "no kotlin-reflect-$KOTLIN_VERSION beside the compiler jar"

# COROUTINES AHEAD OF THE COMPILER, for the reason the native task records: the
# front end runs inside the compiler's process and 1.11.0 renamed `runBlocking`'s
# JVM entry point, so the newer jar must win.
DRIVER_CP="$COROUTINES:$COMPILER:$STDLIB_JVM:$REFLECT"
wasmc() { java -Xmx4g -cp "$DRIVER_CP" org.jetbrains.kotlin.cli.js.KotlinWasmCompiler "$@"; }

# ---- the runtime, as a klib for this target --------------------------------
# GENERATED, not forked, and generated by the NATIVE generator: everything
# Kotlin/Native lacks (java.math, java.time, java.util.regex, reflection) is
# exactly what Kotlin/Wasm lacks, and the file it produces compiles for Wasm
# unchanged. If that ever stops being true this is where it will fail, loudly.
RUNTIME_SRC="$WORK/JsRuntime.kt"
python3 "$REPO/scripts/kir_native_runtime.py" \
    "$REPO/xemantic-typescript-compiler-kir/src/jvmMain/kotlin/kir/runtime/JsRuntime.kt" \
    "$RUNTIME_SRC" > "$WORK/runtime-generate.log" 2>&1 \
    || die "the runtime generator failed — see $WORK/runtime-generate.log"

RUNTIME_KLIB="$WORK/$TARGET/jsruntime.klib"
if [ ! -f "$RUNTIME_KLIB" ] || [ "$RUNTIME_SRC" -nt "$RUNTIME_KLIB" ]; then
    echo "kir-wasm: compiling the KIR runtime for $TARGET ..." >&2
    wasmc "$RUNTIME_SRC" -Xwasm-target="$TARGET" -libraries "$WASM_STDLIB" \
        -Xir-produce-klib-file -ir-output-dir "$WORK/$TARGET" -ir-output-name jsruntime \
        > "$WORK/runtime-compile.log" 2>&1 \
        || die "the runtime did not compile for $TARGET — see $WORK/runtime-compile.log"
fi

# ---- the seed --------------------------------------------------------------
# As on Native: the entry point is resolved by the FRONTEND, which never sees the
# generated `main`, so the seed declares the `main` the compiler finds and the
# plugin gives it a body.
SEED="$WORK/seed/Seed.kt"
mkdir -p "$(dirname "$SEED")"
cat > "$SEED" <<'KOTLIN'
// The `main` the Wasm compiler resolves. Its body is replaced by the KIR plugin
// with a call to the entry point lowered from the TypeScript program.
fun main() {
}
KOTLIN

# THE PLUGIN CLASSPATH: this module's classes and RESOURCES — the registrar is
# found by ServiceLoader and its META-INF/services file is a resource, which
# Gradle stages in a different directory; omit it and the plugin is never
# discovered and the compiler exits 0 having compiled the empty seed.
PLUGIN_ARGS=()
PLUGIN_ARGS+=("-Xplugin=$KIR_CLASSES" "-Xplugin=$KIR_RESOURCES")
while IFS= read -r jar; do
    case "${jar##*/}" in
        kotlin-compiler-embeddable*|kotlin-stdlib-*|kotlin-reflect-*|\
        kotlin-script-runtime-*|kotlin-daemon-embeddable-*|kotlin-build-tools-api-*) ;;
        *) PLUGIN_ARGS+=("-Xplugin=$jar") ;;
    esac
done < <(tr ':' '\n' <<< "$KIR_TAIL")

export XTSC_KIR_PROJECT="$PROJECT"
export XTSC_KIR_ENTRY="$ENTRY"

STAGE1_LOG="$WORK/stage1.log"
echo "kir-wasm: stage 1 — check, lower, serialize the klib ..." >&2
wasmc "$SEED" -Xwasm-target="$TARGET" \
    -libraries "$WASM_STDLIB:$RUNTIME_KLIB" \
    -Xir-produce-klib-file -ir-output-dir "$OUTDIR" -ir-output-name "$NAME" \
    "${PLUGIN_ARGS[@]}" > "$STAGE1_LOG" 2>&1 \
    || { sed -n '1,40p' "$STAGE1_LOG" >&2; die "stage 1 failed — see $STAGE1_LOG"; }

# POSITIVE CONTROL. The plugin announces itself; without that line the compile
# ran WITHOUT the backend and produced a module of the empty seed, at exit 0 and
# with nothing else in the log to say so.
ANNOUNCEMENT="$(grep -a '^xtsc-kir-native:' "$STAGE1_LOG" | head -1)"
[ -n "$ANNOUNCEMENT" ] \
    || die "the KIR plugin did not run — the compiler built the seed alone. See $STAGE1_LOG"
echo "kir-wasm: $ANNOUNCEMENT" >&2

STAGE2_LOG="$WORK/stage2.log"
echo "kir-wasm: stage 2 — link -> .wasm ..." >&2
wasmc -Xwasm-target="$TARGET" \
    -libraries "$WASM_STDLIB:$RUNTIME_KLIB" \
    -Xinclude="$OUTDIR/$NAME.klib" \
    -ir-output-dir "$OUTDIR" -ir-output-name "$NAME" \
    -Xir-produce-js -Xir-dce -main call > "$STAGE2_LOG" 2>&1 \
    || { sed -n '1,40p' "$STAGE2_LOG" >&2; die "stage 2 failed — see $STAGE2_LOG"; }

[ -f "$OUTDIR/$NAME.wasm" ] || die "stage 2 produced no $OUTDIR/$NAME.wasm"
echo "kir-wasm: wrote $OUTDIR/$NAME.wasm ($(wc -c < "$OUTDIR/$NAME.wasm") bytes), target $TARGET"
