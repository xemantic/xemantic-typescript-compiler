export interface Target { a: number; b?: string }
export const excess: Target = { a: 1, c: true };
export const uni: { k: "a"; v: number } | { k: "b"; s: string } = { k: "a", v: 1, s: "x" };
export const accessors = {
    get g() { return 1; },
    set g(v: string) {},
};
export const spread = { ...excess, a: "override" };
