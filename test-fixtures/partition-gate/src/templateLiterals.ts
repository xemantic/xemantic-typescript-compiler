export type Tpl<T extends string> = `prefix-${T}`;
export const tplBad: Tpl<number> = "x";
export function tag(strings: TemplateStringsArray, ...values: number[]) {}
tag`a${"not a number"}b`;
export type BigUnion = `${"a" | "b" | "c"}-${"d" | "e" | "f"}-${"g" | "h"}`;
export const bu: BigUnion = "z";
