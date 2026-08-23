return 1;
export function bodyless(): number;
export function noReturnType(a: number) { return a; }
export class WithCtorReturn {
    constructor() { return "not an object"; }
}
export function* gen() { yield; }
