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
"""
import os, sys, shutil

out = sys.argv[1]
layers = int(sys.argv[2]) if len(sys.argv) > 2 else 24
per_layer = int(sys.argv[3]) if len(sys.argv) > 3 else 50

shutil.rmtree(out, ignore_errors=True)
src = os.path.join(out, "src")
os.makedirs(src)

with open(os.path.join(out, "tsconfig.json"), "w") as f:
    f.write("""{
  "compilerOptions": {
    "rootDir": "src",
    "outDir": "dist",
    "lib": ["es2020"],
    "target": "es2020",
    "module": "esnext",
    "moduleResolution": "bundler",
    "strict": true,
    "skipLibCheck": true,
    "types": []
  },
  "include": ["src/**/*"]
}
""")

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

# One deliberate error, so the FULL build is not silent.
with open(os.path.join(src, "faulty.ts"), "w") as f:
    f.write('import { m00_0Value } from "./layer00/m0_0";\n\n'
            'export const broken: number = m00_0Value.id;\n')
# fix the import path/name
with open(os.path.join(src, "faulty.ts"), "w") as f:
    f.write('import { m0_0Value } from "./layer00/m0_0";\n\n'
            'export const broken: number = m0_0Value.id;\n')

n = sum(len(files) for _, _, files in os.walk(src))
total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(src) for f in fs)
print(f"generated {n} files, {total} bytes, mean {total // n} bytes/file at {out}")
