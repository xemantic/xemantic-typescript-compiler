# shellcheck shell=bash
#
# Shared provenance logic for the guarded xtsc launcher and its AOT-cache admin
# command. Sourced by scripts/xtsc and scripts/xtsc-aot; not executable itself.
#
# WHY THIS FILE EXISTS
# --------------------
# JDK 25's AOT cache (JEP 483 class loading/linking + JEP 515 method profiles) is
# worth 1.638x on a real compile (docs/perf/aot-cache-round828.md). It also has no
# invalidation whatsoever: round 828 physically removed Checker.class from the jar,
# gave the jar a fresh mtime, and the cached run still exited 0 printing
# "OK - 0 errors" while the uncached run correctly died with NoClassDefFoundError.
# A stale cache silently runs the PREVIOUS build's bytecode, with no warning, no
# refusal and no exit code to notice it by. -XX:AOTMode=on does not help.
#
# THE CONTRACT THIS FILE IMPLEMENTS  --  FAIL SAFE, ALWAYS
# -------------------------------------------------------
# The cache is used ONLY when every byte of its provenance is positively verified
# against a recomputed manifest. Anything else - missing file, missing manifest,
# one differing field, a truncated cache, no sha256 tool, an exploded-directory
# classpath - falls back to a normal (slower) run. A false refusal costs seconds;
# a false acceptance costs a wrong answer. There is no configuration that makes an
# unverified cache usable.
#
# THE MANIFEST is plain text, two sections, and validation is a byte comparison:
#
#   <fingerprint block>        recomputed on every run; must match exactly
#   cache <size> <sha256>      the cache file's own size and digest
#
# Measured cost of the whole check on the reference box: ~80 ms (~30 ms for the 8
# classpath jars, ~50 ms for the 49 MiB cache) against the ~10,000 ms the cache
# saves - 0.8%. The guarded launcher still measures 1.68x, 3/3 pairs.

XTSC_AOT_MANIFEST_VERSION=1

# Bumped whenever the java command line the launcher builds changes in a way that
# could affect what the cache contains AND that no other field of the fingerprint
# block already records - a new -XX flag, say. A bump invalidates every existing
# cache. The MAIN CLASS is deliberately NOT such a change: it has its own
# `mainclass` field below, so round 840's swap of the launcher onto the
# server/daemon dispatcher invalidates by itself (an existing cache is no longer
# even reachable by name, so the decision is `SKIP no-cache-file` and the run is
# simply uncached). Bumping here as well would add nothing.
XTSC_AOT_LAUNCHER_VERSION=1

xtsc_die() {
  printf 'xtsc: %s\n' "$*" >&2
  exit 2
}

# sha256 of a file, bare hex, no filename. Fail-safe: a box with neither tool gets
# no cache rather than an unverified one (callers treat the empty result as a miss).
xtsc_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$1" 2>/dev/null | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "$1" 2>/dev/null | cut -d' ' -f1
  else
    printf ''
  fi
}

xtsc_sha256_of_string() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1
  else
    printf ''
  fi
}

xtsc_file_size() {
  # BSD stat and GNU stat disagree on flags; try both, then fall back to wc.
  stat -c %s -- "$1" 2>/dev/null || stat -f %z -- "$1" 2>/dev/null || wc -c <"$1" 2>/dev/null | tr -d ' '
}

# Resolve the classpath, in the order a distribution, a packager and this repo
# would each expect. Sets XTSC_RESOLVED_CP.
xtsc_resolve_classpath() {
  if [ -n "${XTSC_CP:-}" ]; then
    XTSC_RESOLVED_CP="$XTSC_CP"
    return 0
  fi
  if [ -n "${XTSC_HOME:-}" ] && [ -d "$XTSC_HOME/lib" ]; then
    local jars
    jars="$(find "$XTSC_HOME/lib" -maxdepth 1 -name '*.jar' | LC_ALL=C sort | tr '\n' ':')"
    XTSC_RESOLVED_CP="${jars%:}"
    [ -n "$XTSC_RESOLVED_CP" ] && return 0
  fi
  # Development fallback: this repo's own build outputs.
  local root jar deps
  root="$XTSC_SCRIPT_DIR/.."
  jar="$(find "$root/xemantic-typescript-compiler-core/build/libs" -maxdepth 1 -name 'xemantic-typescript-compiler-jvm-*.jar' 2>/dev/null | LC_ALL=C sort | head -1)"
  if [ -n "$jar" ] && [ -f "$root/build/bench/cp.txt" ]; then
    deps="$(cat "$root/build/bench/cp.txt")"
    XTSC_RESOLVED_CP="$jar:$deps"
    return 0
  fi
  XTSC_RESOLVED_CP=""
  return 1
}

# The JDK's identity. The `release` file is the exact per-build stamp and costs a
# read; `java -version` costs a 30 ms JVM start, so it is only the fallback. The
# JVM version-stamps its own archives too, but relying on that would mean trusting
# the same validator that demonstrably does not check the application classes.
xtsc_java_identity() {
  local javabin javahome rel
  javabin="${XTSC_JAVA:-java}"
  if command -v readlink >/dev/null 2>&1; then
    javahome="$(dirname "$(dirname "$(readlink -f "$(command -v "$javabin")" 2>/dev/null)")" 2>/dev/null)"
  fi
  rel="${javahome:-}/release"
  if [ -n "${javahome:-}" ] && [ -f "$rel" ]; then
    printf 'release %s' "$(xtsc_sha256 "$rel")"
  else
    printf 'version %s' "$("$javabin" -version 2>&1 | tr '\n' ' ')"
  fi
}

# The fingerprint block: everything whose change must invalidate the cache.
# Printed to stdout. Every classpath entry is CONTENT-hashed - size and mtime are
# not consulted anywhere, deliberately: a rebuild that happens to preserve both is
# exactly the case that produces a wrong answer.
xtsc_fingerprint_block() {
  local cp="$1" main="$2"
  printf 'xtsc-aot-manifest %s\n' "$XTSC_AOT_MANIFEST_VERSION"
  printf 'launcher %s\n' "$XTSC_AOT_LAUNCHER_VERSION"
  printf 'java %s\n' "$(xtsc_java_identity)"
  printf 'os %s\n' "$(uname -s) $(uname -m)"
  printf 'mainclass %s\n' "$main"
  local n=0 entry entries=() ok=1
  local IFS=':'
  read -r -a entries <<<"$cp"
  unset IFS
  printf 'cpcount %s\n' "${#entries[@]}"
  for entry in "${entries[@]}"; do
    [ -f "$entry" ] || ok=0
  done
  if [ "$ok" = 0 ]; then
    # A directory on the classpath cannot be trained from at all (CDS refuses a
    # non-empty directory at dump time) and cannot be hashed as one file here;
    # emit a marker that can never match a trained manifest.
    for entry in "${entries[@]}"; do
      n=$((n + 1))
      printf 'cp %s NOT-A-FILE %s\n' "$n" "${entry##*/}"
    done
    return 0
  fi
  # One process for all the digests and one for all the sizes: with 8 jars the
  # per-entry form cost ~50 ms of fork, which is most of the guard's budget.
  local shas sizes
  if command -v sha256sum >/dev/null 2>&1; then
    shas="$(sha256sum -- "${entries[@]}" 2>/dev/null | cut -d' ' -f1)"
  elif command -v shasum >/dev/null 2>&1; then
    shas="$(shasum -a 256 -- "${entries[@]}" 2>/dev/null | cut -d' ' -f1)"
  else
    shas=""
  fi
  sizes="$(stat -c %s -- "${entries[@]}" 2>/dev/null || stat -f %z -- "${entries[@]}" 2>/dev/null || true)"
  local -a shaArr=() sizeArr=()
  IFS=$'\n' read -r -d '' -a shaArr <<<"$shas" || true
  IFS=$'\n' read -r -d '' -a sizeArr <<<"$sizes" || true
  if [ "${#shaArr[@]}" -ne "${#entries[@]}" ]; then
    for entry in "${entries[@]}"; do
      n=$((n + 1))
      printf 'cp %s NO-SHA256-TOOL %s\n' "$n" "${entry##*/}"
    done
    return 0
  fi
  for entry in "${entries[@]}"; do
    printf 'cp %s %s %s %s\n' "$((n + 1))" "${shaArr[$n]}" "${sizeArr[$n]:-?}" "${entry##*/}"
    n=$((n + 1))
  done
}

# Short, stable id used in the cache FILE NAME, so that two builds never collide on
# one path and an upgrade misses by construction rather than by a deletion step.
xtsc_fingerprint_id() {
  xtsc_sha256_of_string "$1" | cut -c1-16
}

xtsc_cache_dir() {
  if [ -n "${XTSC_AOT_DIR:-}" ]; then
    printf '%s' "$XTSC_AOT_DIR"
  else
    printf '%s/xtsc' "${XDG_CACHE_HOME:-$HOME/.cache}"
  fi
}

# THE DECISION. Prints one line: "USE <cachefile>" or "SKIP <reason>".
# Callers must treat anything that is not USE as "run without the cache".
xtsc_aot_decide() {
  local cp="$1" main="$2"
  if [ "${XTSC_AOT:-on}" = "off" ]; then
    printf 'SKIP disabled\n'
    return 0
  fi
  local block id dir cache manifest
  block="$(xtsc_fingerprint_block "$cp" "$main")"
  case "$block" in
    *NO-SHA256-TOOL*) printf 'SKIP no-sha256-tool\n'; return 0 ;;
    *NOT-A-FILE*)     printf 'SKIP classpath-not-jar-only\n'; return 0 ;;
  esac
  id="$(xtsc_fingerprint_id "$block")"
  [ -n "$id" ] || { printf 'SKIP no-sha256-tool\n'; return 0; }
  dir="$(xtsc_cache_dir)"
  cache="$dir/xtsc-$id.aot"
  manifest="$cache.manifest"
  [ -f "$cache" ] || { printf 'SKIP no-cache-file\n'; return 0; }
  [ -f "$manifest" ] || { printf 'SKIP no-manifest\n'; return 0; }

  local stored_block stored_cache_line actual_size actual_sha
  stored_block="$(sed -n '/^cache /q;p' -- "$manifest")"
  stored_cache_line="$(grep -m1 '^cache ' -- "$manifest" || true)"
  if [ "$stored_block" != "$block" ]; then
    printf 'SKIP manifest-mismatch\n'
    return 0
  fi
  [ -n "$stored_cache_line" ] || { printf 'SKIP manifest-mismatch\n'; return 0; }
  actual_size="$(xtsc_file_size "$cache")"
  actual_sha="$(xtsc_sha256 "$cache")"
  if [ -z "$actual_sha" ] || [ "$stored_cache_line" != "cache $actual_size $actual_sha" ]; then
    printf 'SKIP cache-corrupt\n'
    return 0
  fi
  printf 'USE %s\n' "$cache"
}

# JVM flags for a cached run.
#
# -XX:AOTCache alone means AOTMode=auto: if the JVM cannot use the file it warns
# and runs normally. That is the fail-safe direction and is why -XX:AOTMode=on
# (cache mandatory) is deliberately NOT used.
#
# The two -Xlog lines deal with the warnings a cached run prints:
#   [warning][aot,codecache,stubs] Saved blob's name 'LLLLLLIILLLL' is different ...
#   [warning][aot] Failed to link AdapterHandlerEntry (fp=...) to its code ...
# Round 828 called them a stderr cosmetic. They are NOT on stderr - they are on
# STDOUT, interleaved with the diagnostics, so anything parsing xtsc's output sees
# them. `aot*=off:stdout` removes JVM AOT logging from the diagnostics stream
# entirely, and `aot*=error:stderr` re-homes genuine AOT errors on stderr, where
# they still print: verified against a deliberately truncated cache, which keeps
# all three `[error][aot]` lines and still compiles correctly. Only WARNINGS are
# lost, and XTSC_AOT_VERBOSE=1 brings the whole log back at info level.
xtsc_aot_jvm_flags() {
  printf -- '-XX:AOTCache=%s\n-Xlog:aot*=off:stdout\n' "$1"
  if [ -n "${XTSC_AOT_VERBOSE:-}" ]; then
    printf -- '-Xlog:aot*=info:stderr\n'
  else
    printf -- '-Xlog:aot*=error:stderr\n'
  fi
}
