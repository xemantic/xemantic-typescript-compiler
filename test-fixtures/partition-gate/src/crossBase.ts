// Declarations the OTHER cross*.ts files depend on. A tail walker that judges a
// file against these is reading another file's type maps, which is the round-609
// starvation shape a program of independent modules cannot express.
export class CrossBase {
    protected shared: number = 1;
    method(a: string): void {}
}
export interface CrossShape {
    area(): number;
    label: string;
}
export type CrossNamed = { readonly name: string };
export enum CrossEnum { First = 1, Second = 2 }
export declare function crossOverload(a: number): void;
export declare function crossOverload(a: string): void;
