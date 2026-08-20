import { Doc, Alias, N } from './nodes'
export function crossFileClasses(x: Alias | Doc)   { if (x instanceof Doc) console.log(x.contents) }
export function crossFileAlias(x: N | Doc)         { if (x instanceof Doc) console.log(x.contents) }
export function crossFileNullable(x: N | Doc | null){ if (x instanceof Doc) console.log(x.contents) }
