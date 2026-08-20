class C {
  static readonly A = 'A'
  readonly b = 'B'
}
const viaConst = 'A'
let fromConst: 'A' = viaConst          // control: the const rule
let fromStatic: 'A' = C.A              // static readonly + literal initializer
let fromInstance: 'B' = new C().b      // instance readonly + literal initializer
console.log(fromConst, fromStatic, fromInstance)
