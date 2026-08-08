#!/usr/bin/env bash
# Shared resolver for the CORE module's `jvmRuntimeClasspath` dependency tail —
# the jars every `MainKt` / `BenchMain` measurement links against.
#
# WHY THIS FILE EXISTS (round 858). Three consecutive rounds found an instrument
# silently loading something other than the code under test, exiting 0, and
# printing a plausible number:
#   * round 853 — `grid838.sh` / `cost_gate.py` / `huge_methods.py` resolved a
#     STALE pre-module-split class dir, so a `+0.00%` counter streak was really a
#     frozen binary;
#   * round 857 — `aot-corpus-suite.sh` built an AOT prefix that was no longer a
#     prefix of the trained classpath, so both arms would have run uncached,
#     agreed, and proved nothing;
#   * round 858 — `build/bench/cp.txt` was a HAND-FROZEN Jul-8 file still naming
#     kotlin-stdlib 2.4.0 / kotlinx-io 0.9.0 / serialization 1.9.0 after the
#     build had moved to 2.4.10 / 0.9.1 / 1.11.0. Every jar it named still
#     existed on disk and the compiler still LINKED against them (verified), so
#     nothing anywhere failed — a reader just measured a dependency tail that is
#     not the shipping one.
#
# THE INVARIANT THIS FILE ENFORCES: a cached classpath file is a claim about a
# BUILD, and it is usable only while it is newer than every build-definition
# input AND every jar it names still exists. Note which input matters — the
# versions live in `gradle/libs.versions.toml`, NOT in the module's
# `build.gradle.kts`, so a guard watching only the latter (which is what
# `ab-warm.sh` had) is blind to exactly the bump that produced the stale file.
#
# USE, sourced:
#     . "$REPO_ROOT/scripts/lib/dep-classpath.sh"
#     CP_TAIL="$(xtsc_dep_classpath)"          # ':'-joined, no leading/trailing ':'
#
# USE, standalone (this is the shape the pin drives):
#     scripts/lib/dep-classpath.sh --validate <cache-file>     # exit 0 = usable
#     scripts/lib/dep-classpath.sh --print                     # resolve + echo
#
# Override wholesale with XTSC_CP=<deps>. Override the repo root (the pin does,
# to drive an ABLATED copy against a fixture tree) with XTSC_DEP_ROOT.

# Resolve the repo root from this file's own location unless told otherwise.
xtsc_dep_root() {
    if [ -n "${XTSC_DEP_ROOT:-}" ]; then printf '%s' "$XTSC_DEP_ROOT"; return; fi
    (cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
}

# The build-definition files whose change invalidates a cached classpath.
# `libs.versions.toml` is the load-bearing one: it is where a version bump lands.
xtsc_dep_cache_inputs() {
    local root; root="$(xtsc_dep_root)"
    printf '%s\n' \
        "$root/gradle/libs.versions.toml" \
        "$root/build.gradle.kts" \
        "$root/settings.gradle.kts" \
        "$root/gradle.properties" \
        "$root/xemantic-typescript-compiler-core/build.gradle.kts"
}

# xtsc_dep_validate <cache-file>
# Exit 0 iff the cache may be used. Every refusal prints WHY on stderr — a guard
# that fails quietly is the thing this file exists to prevent.
xtsc_dep_validate() {
    local cache="$1" input entry

    # (1) EXISTS AND IS NON-EMPTY. An empty cache joined into a `-cp` string
    #     yields a classpath that still starts with the class dir, so the run
    #     proceeds and dies (or does not) far from here.
    if [ ! -s "$cache" ]; then
        echo "dep-classpath: refusing '$cache' — missing or empty" >&2
        return 1
    fi

    # (2) NEWER THAN EVERY BUILD-DEFINITION INPUT. This is the check that would
    #     have caught build/bench/cp.txt: a version bump in libs.versions.toml
    #     leaves the module build file untouched.
    while IFS= read -r input; do
        [ -e "$input" ] || continue
        if [ "$input" -nt "$cache" ]; then
            echo "dep-classpath: refusing '$cache' — older than $input (re-resolve)" >&2
            return 1
        fi
    done <<EOF
$(xtsc_dep_cache_inputs)
EOF

    # (3) EVERY NAMED JAR STILL EXISTS. A pruned Gradle cache otherwise turns
    #     into a silently short classpath.
    while IFS= read -r entry; do
        [ -n "$entry" ] || continue
        if [ ! -e "$entry" ]; then
            echo "dep-classpath: refusing '$cache' — names a missing entry: $entry" >&2
            return 1
        fi
    done <<EOF
$(tr ':\n' '\n\n' < "$cache" | sed '/^$/d')
EOF

    return 0
}

# Resolve the tail fresh through Gradle, using the same init script as every
# other reader. Refuses a PRE-SPLIT init script (round MOD.3: it registers the
# task under `allprojects`, so `-api` prints a second, wrong line that `head -1`
# may pick).
xtsc_dep_resolve_fresh() {
    local root init cp
    root="$(xtsc_dep_root)"
    init="$root/build/bench/print-classpath.init.gradle.kts"
    [ -f "$init" ] || {
        echo "dep-classpath: $init missing — run scripts/bench-compile-tsc.sh once" >&2
        return 1
    }
    grep -q 'xemantic-typescript-compiler-core' "$init" || {
        echo "dep-classpath: $init predates the module split — re-run bench-compile-tsc.sh" >&2
        return 1
    }
    echo "dep-classpath: resolving jvmRuntimeClasspath (gradle) ..." >&2
    cp="$("$root/gradlew" -q --console=plain -I "$init" xtscPrintJvmRuntimeClasspath 2>/dev/null \
        | sed -n 's/^XTSC_CLASSPATH=//p' | head -1)"
    [ -n "$cp" ] || { echo "dep-classpath: could not resolve jvmRuntimeClasspath" >&2; return 1; }
    printf '%s' "$cp"
}

# xtsc_dep_classpath [cache-file]
# Echoes the ':'-joined dependency tail, or fails loudly. Never echoes a tail it
# has not validated.
xtsc_dep_classpath() {
    local root cache cp
    root="$(xtsc_dep_root)"
    cache="${1:-$root/build/bench/cp-warm.txt}"

    if [ -n "${XTSC_CP:-}" ]; then printf '%s' "$XTSC_CP"; return 0; fi

    if xtsc_dep_validate "$cache" 2>/dev/null; then
        tr '\n' ':' < "$cache" | sed 's/:*$//'
        return 0
    fi

    cp="$(xtsc_dep_resolve_fresh)" || return 1
    mkdir -p "$(dirname -- "$cache")"
    printf '%s' "$cp" > "$cache"
    # Re-validate what we just wrote: a resolution that produced a jar path that
    # does not exist must not become the next run's trusted cache.
    xtsc_dep_validate "$cache" || return 1
    printf '%s' "$cp"
}

# Standalone entry point, so the guard is drivable (and ablatable) on its own.
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    case "${1:-}" in
        --validate) shift; xtsc_dep_validate "${1:?usage: --validate <cache-file>}" ;;
        --print)    shift; xtsc_dep_classpath "${1:-}" ;;
        --inputs)   xtsc_dep_cache_inputs ;;
        *) echo "usage: $0 --validate <cache-file> | --print [cache-file] | --inputs" >&2; exit 2 ;;
    esac
fi
