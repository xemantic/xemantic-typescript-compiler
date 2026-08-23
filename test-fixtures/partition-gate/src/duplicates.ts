export function dup(a: number): void;
export function dup(a: string): number;
export function dup(a: any): any { return a; }
let redeclared = 1;
let redeclared = 2;
export { redeclared };
loop1: for (const x of []) {
    loop1: for (const y of []) {}
}
