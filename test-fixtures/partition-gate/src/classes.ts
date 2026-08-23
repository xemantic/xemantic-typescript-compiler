// Class heritage, member overriding and constructor rules.
export class Base {
    protected p: number = 1;
    m(a: string): void {}
}
export class Derived extends Base {
    override m(a: number): void {}
    constructor() {
        this.p = 2;
        super();
    }
}
export class NoSuper extends Base {
    constructor() {}
}
export abstract class HasAbstract {
    abstract mustImplement(): void;
}
export class Incomplete extends HasAbstract {}
export interface Shape { area(): number; name: string }
export class NotAShape implements Shape {}
