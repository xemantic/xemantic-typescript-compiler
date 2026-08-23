type Ctor<T> = new (...a: any[]) => T;
export function mixin<TBase extends Ctor<object>>(B: TBase) {
    return class extends B {
        private conflict = 1;
        extra = 2;
    };
}
export class NotCtor {}
const nc = 1;
export class ExtendsValue extends nc {}
