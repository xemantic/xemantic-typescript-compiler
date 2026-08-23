import { crossOverload, CrossBase } from "./crossBase";
crossOverload(true);
export class CrossOv extends CrossBase {
    method(a: string): void;
    method(a: string, b: number): void;
    method(a: number): void {}
}
export const crossAlias: import("./crossBase").CrossNamed = { name: 2 };
