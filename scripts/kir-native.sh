#!/usr/bin/env bash
#
# Compile a TypeScript project to a Kotlin/Native BINARY through the KIR backend.
#
#   xtsc front end  ->  Kotlin IR  ->  konanc -opt  ->  a linked native executable
#
# A WRAPPER, not a second implementation. Everything is in the Gradle task
# `:xemantic-typescript-compiler-kir:kirNativeCompile`, which resolves its own
# plugin classpath from the build rather than from a cached file — the classpath
# staleness this repo keeps rediscovering (CLAUDE.md rounds 852/857/858) cannot
# arise when Gradle is the one resolving it. Read that task for why konanc drives
# rather than being driven, and `docs/perf/kir-backend-levers.md` § 6 for what the
# resulting binary measures.
#
# USE:  scripts/kir-native.sh <project-dir> <entry-file> <output-binary> [--library]
#
set -uo pipefail

REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${1:?usage: kir-native.sh <project-dir> <entry-file> <output-binary> [--library]}"
ENTRY="${2:?usage: kir-native.sh <project-dir> <entry-file> <output-binary> [--library]}"
OUTPUT="${3:?usage: kir-native.sh <project-dir> <entry-file> <output-binary> [--library]}"
LIBRARY="${4:-}"

ARGS=(-PkirProject="$PROJECT" -PkirEntry="$ENTRY" -PkirOutput="$OUTPUT")
[ "$LIBRARY" = "--library" ] && ARGS+=(-PkirLibrary)

exec "$REPO/gradlew" --console=plain -q \
    :xemantic-typescript-compiler-kir:kirNativeCompile "${ARGS[@]}"
