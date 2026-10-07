class C {
    f = () => this.g();
    g() { return function (this: unknown) { return this; }; }
}
let broken = (1 + ;
label: for (const i of [1, 2]) { if (i) continue label; else break label; }
