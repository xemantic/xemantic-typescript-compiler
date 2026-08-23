export interface Deep { a: { b: { c: number } } }
export const deep: Deep = { a: { b: { c: "no" } } };
export function takesDeep(d: Deep) {}
takesDeep({ a: { b: { c: true } } });
export const fnMismatch: (a: number) => string = (a: string) => 1;
