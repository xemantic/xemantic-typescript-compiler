#!/usr/bin/env python3
"""(WARM.18b) round 892 — apply ONE deliberate mistake to the cta LOCAL family.

Round 807's law: a combined ablation cannot attribute, so each arm is a single
edit and the driver reverts between arms. Every arm must COMPILE (round 808: a
build failure reports `ran 0, failed 0`, which reads exactly like "the mistake
changed nothing").

Two source files are in play and that is deliberate. The MECHANISM arms edit
`ScopeStack.kt` and are seen by `ScopeStackTest`; the WIRING arms edit
`Checker.kt` (which frame opens which scope, and where the pop is) and can only
be seen through a compile, i.e. by `CtaLocalScopePinTest`. An arm that reddens
nothing in either is reported as such — round 868: a signal with no uniquely-its
own failure is a redundant guard, not coverage.
"""
import sys

STACK = "xemantic-typescript-compiler-core/src/commonMain/kotlin/ScopeStack.kt"
CHECKER = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"

ARMS = {
    # ---- mechanism ----------------------------------------------------------
    # The mistake the whole scheme turns on: the last restore applied to a key
    # must be its FIRST record, i.e. the value the frame inherited.
    "A1": (STACK, [(
        """        var i = undoKeys.size - 1
        while (i >= mark) {
            val k = undoKeys.removeAt(i)
            val v = undoVals.removeAt(i)
            if (v == null) live.remove(k) else live[k] = v
            i--
        }""",
        """        var i = mark
        while (i < undoKeys.size) {
            val k = undoKeys[i]
            val v = undoVals[i]
            if (v == null) live.remove(k) else live[k] = v
            i++
        }
        while (undoKeys.size > mark) {
            undoKeys.removeAt(undoKeys.size - 1)
            undoVals.removeAt(undoVals.size - 1)
        }""",
    )]),
    # The map scope closes and restores nothing — every write leaks outward.
    "A2": (STACK, [(
        """            val k = undoKeys.removeAt(i)
            val v = undoVals.removeAt(i)
            if (v == null) live.remove(k) else live[k] = v
            i--""",
        """            undoKeys.removeAt(i)
            undoVals.removeAt(i)
            i--""",
    )]),
    # The pre-write state is recorded as ABSENT, so a SHADOWED key is dropped at
    # the pop instead of coming back with its outer value.
    "A3": (STACK, [(
        "        undoVals.add(live[key])",
        "        undoVals.add(null)",
    )]),
    # The SET scope closes and restores nothing.
    "A4": (STACK, [(
        """            val k = undoKeys.removeAt(i)
            val had = undoHad.removeAt(i)
            if (had) live.add(k) else live.remove(k)
            i--""",
        """            undoKeys.removeAt(i)
            undoHad.removeAt(i)
            i--""",
    )]),
    # The set records "was PRESENT" unconditionally, so an element ADDED by an
    # inner scope is re-added at the pop instead of being dropped.
    "A5": (STACK, [(
        "        undoHad.add(key in live)",
        "        undoHad.add(true)",
    )]),
    # Every scope's slice starts at 0, so one pop unwinds the whole log.
    "A6": (STACK, [(
        """    fun push() {
        marks.add(undoKeys.size)
    }

    /**
     * Close the innermost scope, restoring every key it shadowed or introduced.""",
        """    fun push() {
        marks.add(0)
    }

    /**
     * Close the innermost scope, restoring every key it shadowed or introduced.""",
    )]),
    # A write made with NO scope open is recorded, so the outermost (file-root)
    # frame's entries are undone by the first unmatched pop.
    "A7": (STACK, [(
        """        onMutate?.invoke()
        // A write made while no scope is open belongs to the outermost (file
        // root) frame, which is dropped by [reset] rather than by a pop —
        // recording it would grow a log nothing ever replays.
        if (marks.isEmpty()) return
        undoKeys.add(key)""",
        """        onMutate?.invoke()
        if (marks.isEmpty()) marks.add(0)
        undoKeys.add(key)""",
    )]),
    # `reset` keeps the entries — a CROSS-FILE leak.
    "A8": (STACK, [(
        """    fun reset() {
        live.clear()
        marks.clear()
        undoKeys.clear()
        undoVals.clear()
    }""",
        """    fun reset() {
        marks.clear()
        undoKeys.clear()
        undoVals.clear()
    }""",
    )]),
    # ---- wiring -------------------------------------------------------------
    # The NARROWING frame shares instead of scoping — round 891's refusal
    # reason (iii) made real: two disciplines over one ambient field.
    "A9": (CHECKER, [(
        """                FrontEnd.addCopy(FrontEnd.CP_CTA_LOCAL, 0)
                ctaLocalTypeScope.push()
                val lt = ctaLocalTypeScope.view""",
        """                FrontEnd.addCopy(FrontEnd.CP_CTA_LOCAL, 0)
                val lt = ctaLocalTypeScope.view""",
    ), (
        """                    varScoped = narrowVarScoped,
                    localScoped = true))""",
        """                    varScoped = narrowVarScoped,
                    localScoped = false))""",
    )]),
    # The fn-body frame opens the three scopes and the leave closes only the
    # localTypes one — declNodes/shadowedNames then leak outward.
    "A10": (CHECKER, [(
        "            if (gone.ctaFnScoped) { ctaDeclNodeScope.pop(); ctaShadowScope.pop() }",
        "            if (false && gone.ctaFnScoped) { ctaDeclNodeScope.pop(); ctaShadowScope.pop() }",
    )]),
    # The per-FILE boundary is gone: a file's locals survive into the next.
    "A11": (CHECKER, [(
        "        ctaLocalTypeScope.reset(); ctaDeclNodeScope.reset(); ctaShadowScope.reset()",
        "        ctaDeclNodeScope.reset(); ctaShadowScope.reset()",
    )]),
    # The fn-body frame opens NO localTypes scope — a body's locals leak into
    # the file scope (the `applyBodyLocalShadowing` FP class, directly).
    "A12": (CHECKER, [(
        """        FrontEnd.addCopy(FrontEnd.CP_CTA_LOCAL, 0)
        ctaLocalTypeScope.push(); ctaDeclNodeScope.push(); ctaShadowScope.push()""",
        """        FrontEnd.addCopy(FrontEnd.CP_CTA_LOCAL, 0)
        ctaDeclNodeScope.push(); ctaShadowScope.push()""",
    ), (
        "            localScoped = true, ctaFnScoped = true)",
        "            localScoped = false, ctaFnScoped = true)",
    )]),
}


def main() -> int:
    arm = sys.argv[1]
    if arm not in ARMS:
        print(f"unknown arm: {arm}", file=sys.stderr)
        return 2
    path, edits = ARMS[arm]
    src = open(path).read()
    for old, new in edits:
        if src.count(old) != 1:
            print(f"{arm}: anchor occurs {src.count(old)} times in {path} — "
                  "not exactly once, refusing", file=sys.stderr)
            return 3
        src = src.replace(old, new)
    open(path, "w").write(src)
    print(f"{arm}: applied to {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
