export enum Fwd {
    A = B,
    B = 1,
}
export enum WithThis {
    X = 1,
    Y = this.X,
}
export const enum Nominal1 { N = 1 }
export const enum Nominal2 { N = 1 }
const bad: Nominal1 = Nominal2.N;
export { bad };
