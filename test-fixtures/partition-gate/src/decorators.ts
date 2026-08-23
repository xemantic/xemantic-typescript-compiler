@usedBeforeDeclared
export class Decorated {
    @param(1) method(@bad p: number) {}
}
function usedBeforeDeclared(c: unknown) {}
function param(n: number) { return (a: unknown, b: unknown) => {}; }
declare function bad(a: unknown, b: unknown, c: unknown): void;
