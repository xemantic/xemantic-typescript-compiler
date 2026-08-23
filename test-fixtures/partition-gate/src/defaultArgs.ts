export const fnExpr = function (a: number = "no", b = a) { return b; };
export function optionalArith(a?: number) { return a + 1; }
export const arrowDefault = (x: number = {}) => x;
