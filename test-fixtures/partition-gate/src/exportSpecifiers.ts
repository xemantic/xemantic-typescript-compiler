export type Declared = {};
export type { NotDeclared };
export { alsoNotDeclared };
export namespace NsExport {
    export const dupe = 1;
    export { dupe };
}
