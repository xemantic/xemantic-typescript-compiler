#!/usr/bin/env bash
# ROUND 872 — single-mistake ablation for the client arm and the exit code.
#
# ONE mistake per invocation, each reverted before the next (round 807: six
# injected together read as full coverage and one of them turned out to be a
# redundant guard). The tree must be COMMITTED first, because undoing a fault is
# `git checkout --` and that also deletes any uncommitted work in the same file
# (round 789).
#
# The arm default is an ARRAY, never `"${@:-A1 A2}"` — that expands as ONE word,
# hits the `unknown arm` branch, and still prints a clean summary, which is
# indistinguishable from "every guard is redundant" (rounds 855/856).
#
#   scripts/round872-ablate.sh --dry        # show each arm's diff, revert it
#   scripts/round872-ablate.sh A1 A3        # run those arms
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LAUNCHER="scripts/xtsc"
XTSCMAIN="xemantic-typescript-compiler-daemon/src/jvmMain/kotlin/server/XtscMain.kt"
PINS=(--tests '*XtscClientExitCodeTest*' --tests '*LauncherClientArmTest*')

apply() {
  case "$1" in
    # the defect this round fixed, restated: the served code is dropped
    A1) python3 - "$XTSCMAIN" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old="""    print(response.output)
    System.out.flush()
    return response.exitCode"""
new="""    print(response.output)
    System.out.flush()
    return 0"""
assert s.count(old)==1, "anchor"
open(p,'w').write(s.replace(old,new))
PY
      ;;
    # fall back on ANY non-zero code, not only "the request never ran"
    A2) sed -i 's/if \[ "\$xtsc_client_code" -ne 3 \]; then/if [ "$xtsc_client_code" -eq 0 ]; then/' "$LAUNCHER" ;;
    # the arm stops being restricted to --daemon requests
    A3) sed -i 's/if \[ "\$xtsc_is_daemon_request" -eq 1 \] && \[ -z "\${XTSC_AOT_DECIDE_ONLY:-}" \] && xtsc_resolve_client; then/if xtsc_resolve_client; then/' "$LAUNCHER" ;;
    # XTSC_SOCKET is no longer named explicitly, so the two arms can disagree
    A4) sed -i 's|\*) \[ -n "\${XTSC_SOCKET:-}" \] \&\& xtsc_client_args=(--socket "\$XTSC_SOCKET" \${xtsc_client_args\[@\]+"\${xtsc_client_args\[@\]}"}) ;;|*) ;;|' "$LAUNCHER" ;;
    # the client is allowed to start a daemon, silently replacing an in-process
    # compile with a long-lived JVM the user never asked for
    A5) python3 - "$LAUNCHER" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='  xtsc_client_args=(--no-spawn)'
new='  xtsc_client_args=()'
assert s.count(old)==1, "anchor"
open(p,'w').write(s.replace(old,new))
PY
      ;;
    # --daemon is forwarded to the client, where it means nothing
    A6) python3 - "$LAUNCHER" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='    [ "$arg" = "--daemon" ] || xtsc_client_args+=("$arg")'
new='    xtsc_client_args+=("$arg")'
assert s.count(old)==1, "anchor"
open(p,'w').write(s.replace(old,new))
PY
      ;;
    # the client's stderr is replayed even when the launcher falls back
    A7) sed -i 's|^  \[ -n "\${XTSC_CLIENT_VERBOSE:-}" \] \&\& cat "\$xtsc_client_err" >\&2$|  cat "$xtsc_client_err" >\&2|' "$LAUNCHER" ;;
    # the AOT probe is answered from the native arm
    A8) sed -i 's/ \&\& \[ -z "\${XTSC_AOT_DECIDE_ONLY:-}" \] \&\& xtsc_resolve_client; then/ \&\& xtsc_resolve_client; then/' "$LAUNCHER" ;;
    *) echo "unknown arm: $1" >&2; return 1 ;;
  esac
}

revert() { git checkout -- "$LAUNCHER" "$XTSCMAIN"; }

[ -z "$(git status --porcelain -- "$LAUNCHER" "$XTSCMAIN")" ] || {
  echo "error: commit $LAUNCHER and $XTSCMAIN first — the revert would delete uncommitted work" >&2
  exit 1
}

ARMS=("$@")
DRY=0
if [ "${ARMS[0]:-}" = "--dry" ]; then DRY=1; ARMS=(A1 A2 A3 A4 A5 A6 A7 A8); fi
[ "${#ARMS[@]}" -eq 0 ] && ARMS=(A1 A2 A3 A4 A5 A6 A7 A8)

for arm in "${ARMS[@]}"; do
  echo "=== $arm ==="
  apply "$arm"
  stat="$(git diff --shortstat -- "$LAUNCHER" "$XTSCMAIN")"
  if [ -z "$stat" ]; then
    echo "  !! NO DIFF — the anchor did not match; this arm tests NOTHING"
    revert; continue
  fi
  echo "  diff: $stat"
  if [ "$DRY" -eq 1 ]; then git --no-pager diff --unified=0 -- "$LAUNCHER" "$XTSCMAIN" | sed -n '4,20p'; revert; continue; fi

  rm -rf xemantic-typescript-compiler-daemon/build/test-results/jvmTest
  ./gradlew :xemantic-typescript-compiler-daemon:jvmTest --rerun "${PINS[@]}" \
      > "/tmp/r872-ablate-$arm.log" 2>&1 || true
  grep -q "BUILD SUCCESSFUL\|tests completed" "/tmp/r872-ablate-$arm.log" || \
    echo "  (note: build did not report success — check /tmp/r872-ablate-$arm.log)"
  python3 - "$arm" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
red=[]
tot=0
for f in glob.glob('xemantic-typescript-compiler-daemon/build/test-results/jvmTest/*.xml'):
    r=ET.parse(f).getroot(); tot+=int(r.get('tests') or 0)
    for tc in r.iter('testcase'):
        if any(c.tag in ('failure','error') for c in tc):
            red.append(tc.get('name'))
print(f"  {sys.argv[1]}: {len(red)} red of {tot}")
for n in sorted(red): print("    -", n)
if not red: print("    !! NO PIN FAILED — redundant guard, or a blind pin")
PY
  revert
done
echo "complete; tree restored"
git status --porcelain -- "$LAUNCHER" "$XTSCMAIN"
