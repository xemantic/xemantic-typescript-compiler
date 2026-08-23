export class A2 { constructor(public x: number) {} }
export class B2 extends A2 {
    constructor() {
        const f = () => { super(); };
        super(1);
    }
}
export class C2 {
    constructor() { super(); }
}
export class D2 extends A2 {
    constructor() { super(1); return 3; }
}
