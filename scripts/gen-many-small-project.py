#!/usr/bin/env python3
"""(INC.56) Generate a MANY-SMALL-FILES TypeScript project — the shape an
application has, as opposed to the 78 huge sources of the tsc profile.

The (INC.56) queue entry demands the crawl's read cost be re-measured on this
shape, because the per-FILE overhead (a Vfs read, a UTF-8 decode, a flags
computation, a cache probe) is what dominates there and is invisible on a
profile whose mean file is 128 KB.

Layout: `layers` layers of `perLayer` modules; each module imports two modules
from the layer below, declares a handful of types and functions, and exports
them. One file carries a deliberate type error, because
`FloorDecompositionMain` REFUSES a project whose full build is silent (a floor
of zero would then be no evidence that the checker was narrowed).

usage: gen-many-small-project.py <out> [layers] [perLayer] [--lib a,b] [--edits <dir>]

`--lib` replaces `"lib": ["es2020"]` (an unset `lib` would mean `dom`, ~2,242 globals
against ~185 — a front-end figure is a claim about this line too). `--edits` writes the
incremental benches' edit variants of `src/layer00/m0_0.ts` (the deepest dependency) to
<dir>: `orig.ts`, `body.ts` (one line inside a function body, no exported signature
moves), `sig.ts` (an export appended), `sigtop.ts` (the same export prepended).

**Arm B** of docs/perf/incremental-vs-tsgo.md, `build/bench/many-small-2400-dom` with its
variants in `build/bench/inc90-edits-app`, is exactly:

    scripts/gen-many-small-project.py build/bench/many-small-2400-dom 48 50 \
        --lib es2020,dom --edits build/bench/inc90-edits-app
"""
import json, os, sys, shutil

args = sys.argv[1:]
lib = ["es2020"]
edits = None
for flag in ("--lib", "--edits"):
    if flag in args:
        i = args.index(flag)
        if i + 1 >= len(args):
            sys.exit(f"REFUSED: {flag} needs a value")
        if flag == "--lib":
            lib = args[i + 1].split(",")
        else:
            edits = args[i + 1]
        del args[i:i + 2]
out = args[0]
layers = int(args[1]) if len(args) > 1 else 24
per_layer = int(args[2]) if len(args) > 2 else 50

shutil.rmtree(out, ignore_errors=True)
src = os.path.join(out, "src")
os.makedirs(src)

with open(os.path.join(out, "tsconfig.json"), "w") as f:
    f.write("""{
  "compilerOptions": {
    "rootDir": "src",
    "outDir": "dist",
    "lib": LIB,
    "target": "es2020",
    "module": "esnext",
    "moduleResolution": "bundler",
    "strict": true,
    "skipLibCheck": true,
    "types": []
  },
  "include": ["src/**/*"]
}
""".replace("LIB", json.dumps(lib)))

def mod(layer, idx):
    return f"m{layer}_{idx}"

for layer in range(layers):
    d = os.path.join(src, f"layer{layer:02d}")
    os.makedirs(d, exist_ok=True)
    for idx in range(per_layer):
        name = mod(layer, idx)
        lines = []
        deps = []
        if layer > 0:
            for k in (idx, (idx * 7 + 3) % per_layer):
                deps.append((layer - 1, k))
        for (dl, di) in deps:
            dn = mod(dl, di)
            lines.append(f'import {{ {dn}Value, type {dn}Shape }} from "../layer{dl:02d}/{dn}";')
        if deps:
            lines.append("")
        lines.append(f"export interface {name}Shape {{")
        lines.append("  id: string;")
        lines.append("  count: number;")
        lines.append("  tags: readonly string[];")
        for (dl, di) in deps:
            lines.append(f"  from_{mod(dl, di)}: {mod(dl, di)}Shape;")
        lines.append("}")
        lines.append("")
        lines.append(f"export type {name}Key = keyof {name}Shape;")
        lines.append("")
        lines.append(f"export const {name}Value: {name}Shape = {{")
        lines.append(f'  id: "{name}",')
        lines.append(f"  count: {idx},")
        lines.append('  tags: ["a", "b"],')
        for (dl, di) in deps:
            lines.append(f"  from_{mod(dl, di)}: {mod(dl, di)}Value,")
        lines.append("};")
        lines.append("")
        lines.append(f"export function {name}Describe(input: {name}Shape): string {{")
        lines.append("  const parts: string[] = [input.id];")
        lines.append("  for (const tag of input.tags) {")
        lines.append("    parts.push(tag.toUpperCase());")
        lines.append("  }")
        lines.append("  return parts.join(\"/\") + String(input.count);")
        lines.append("}")
        lines.append("")
        lines.append(f"export function {name}Pick<K extends {name}Key>(s: {name}Shape, k: K): {name}Shape[K] {{")
        lines.append("  return s[k];")
        lines.append("}")
        lines.append("")
        lines.append(f"export class {name}Holder {{")
        lines.append(f"  constructor(private readonly shape: {name}Shape) {{}}")
        lines.append(f"  get describe(): string {{ return {name}Describe(this.shape); }}")
        lines.append("  get size(): number { return this.shape.tags.length + this.shape.count; }")
        lines.append("}")
        with open(os.path.join(d, name + ".ts"), "w") as f:
            f.write("\n".join(lines) + "\n")

# One deliberate error, so the FULL build is not silent: FloorDecompositionMain
# REFUSES a project whose full build reports nothing, because then the floor
# build's own zero would be no evidence that the checker had been narrowed.
with open(os.path.join(src, "faulty.ts"), "w") as f:
    f.write('import { m0_0Value } from "./layer00/m0_0";\n\n'
            'export const broken: number = m0_0Value.id;\n')

if edits is not None:
    shutil.rmtree(edits, ignore_errors=True)
    os.makedirs(edits)
    with open(os.path.join(src, "layer00", "m0_0.ts")) as f:
        orig = f.read()
    anchor = "    parts.push(tag.toUpperCase());\n"
    extra = "export const m0_0Extra: number = 1;\n"
    assert orig.count(anchor) == 1, "the body edit's anchor moved"
    variants = {
        "orig": orig,
        "body": orig.replace(anchor, anchor + "    if (parts.length > 9000) parts.pop();\n"),
        "sig": orig + "\n" + extra,
        "sigtop": extra + "\n" + orig,
    }
    for v, text in variants.items():
        with open(os.path.join(edits, v + ".ts"), "w") as f:
            f.write(text)

n = sum(len(files) for _, _, files in os.walk(src))
total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(src) for f in fs)
print(f"generated {n} files, {total} bytes, mean {total // n} bytes/file at {out}")
