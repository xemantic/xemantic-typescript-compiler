import { CrossBase, CrossShape, CrossNamed, CrossEnum } from "./crossBase";
export class CrossDerived extends CrossBase {
    override method(a: number): void {}
    constructor() {
        this.shared = 2;
        super();
    }
}
export class CrossImpl implements CrossShape {}
export const crossNamed: CrossNamed = { name: 1 };
export const crossEnumBad: CrossEnum = "no";
