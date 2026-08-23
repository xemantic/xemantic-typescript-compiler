declare module "ambient-one" {
    import { rel } from "./relative-inside-ambient";
    export const a: number;
}
declare module "ambient-two" {
    export = 1;
}
export {};
