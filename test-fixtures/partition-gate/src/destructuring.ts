export function fromNullish() {
    const {} = null;
    const [] = undefined;
}
export function defaults() {
    const { a = "s", b }: { a?: number; b: number } = { b: 1 };
    const [c = true, d]: [number?, number?] = [];
    return [a, b, c, d];
}
export let da: number, db: string;
({ da, db } = { da: undefined, db: undefined });
