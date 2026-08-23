export let notIterable: undefined;
export function spreads() {
    const noop = () => {};
    while (1) { notIterable = ~noop(...notIterable); }
}
export function fixedArity(a: number, b: number) {}
declare const wide: number[];
fixedArity(...wide);
export const spreadOverride = { a: 1, ...{ a: "s" } };
