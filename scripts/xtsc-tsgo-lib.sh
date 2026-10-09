# shellcheck shell=bash
#
# Shared by scripts/xtsc-tsgo and scripts/xtsc-tsgo-aot: the ported CLI's main
# class, classpath and cache directory, on top of the guard in xtsc-aot-lib.sh
# (which this file sources — the provenance contract is that file's, unchanged).

# shellcheck source=scripts/xtsc-aot-lib.sh
. "$XTSC_SCRIPT_DIR/xtsc-aot-lib.sh"

# Inside the fingerprint (`mainclass`), so a cache trained for one entry point is
# never used for another.
XTSC_TSGO_MAIN_CLASS="com.xemantic.typescript.tsgo.cli.TsgoMainKt"

# A cache directory of its own: scripts/xtsc-aot's `train` and `clean` prune every
# OTHER cache in theirs, and the two launchers must not delete each other's.
XTSC_AOT_DIR="${XTSC_TSGO_AOT_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/xtsc-tsgo}"
export XTSC_AOT_DIR

# The dev staging directory (`scripts/xtsc-tsgo-aot stage`): the -tsgo jar and its
# runtime dependency jars, the shape of an installed XTSC_TSGO_HOME/lib.
XTSC_TSGO_STAGE="$XTSC_SCRIPT_DIR/../build/xtsc-tsgo/lib"

# Sets XTSC_RESOLVED_CP to a jar-only classpath (the JVM refuses to dump an AOT
# cache from a directory, and the guard refuses to verify one).
xtsc_tsgo_resolve_classpath() {
  if [ -n "${XTSC_TSGO_CP:-}" ]; then
    XTSC_RESOLVED_CP="$XTSC_TSGO_CP"
    return 0
  fi
  local lib jars
  for lib in "${XTSC_TSGO_HOME:+${XTSC_TSGO_HOME%/}/lib}" "$XTSC_TSGO_STAGE"; do
    [ -n "$lib" ] && [ -d "$lib" ] || continue
    jars="$(find "$lib" -maxdepth 1 -name '*.jar' | LC_ALL=C sort | tr '\n' ':')"
    XTSC_RESOLVED_CP="${jars%:}"
    [ -n "$XTSC_RESOLVED_CP" ] && return 0
  done
  XTSC_RESOLVED_CP=""
  return 1
}
