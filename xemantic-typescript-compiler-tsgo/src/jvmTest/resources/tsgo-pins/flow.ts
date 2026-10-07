export function explicit(x: number): number {
    if (x > 0) return x;
    throw new Error("negative");
    console.log("unreachable");
}
function implicit(x?: string) {
    if (x) { return x.length; }
}
export async function later(): Promise<void> {
    await Promise.resolve(1);
}
namespace N { export const v = 1; export namespace M { export let w = v; } }
declare namespace D { function f(): void; export let g: number; }
