export interface Contra<in T> { take(t: T): void }
export interface Co<out T> { give(): T }
export interface WrongIn<in T> { give(): T }
export interface WrongOut<out T> { take(t: T): void }
declare const contraNum: Contra<number>;
export const contraAny: Contra<string> = contraNum;
