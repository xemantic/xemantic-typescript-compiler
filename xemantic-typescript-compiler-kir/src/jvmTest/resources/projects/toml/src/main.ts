/*
 * OURS, not the library's: the driver that makes `smol-toml` a test.
 *
 * It parses the document in `document.toml` — embedded here as a string,
 * because this runtime has no file I/O — and prints it as JSON, so the test has
 * one exact line to compare. The expectation beside it is produced by PYTHON's
 * `tomllib`, which is a second, independent TOML implementation: the test
 * therefore checks the compiled library against another parser rather than
 * against itself.
 */

import { parse } from './parse.ts'

const document = "title = \"TOML Example\"\nenabled = true\nratio = 0.5\nnegative = -17\nexponent = 1e3\nunderscored = 1_000_000\nhex = 0xDEADBEEF\noctal = 0o755\nbinary = 0b1010\nempty_string = \"\"\nescaped = \"line\\nbreak\\ttab \\\"quoted\\\" back\\\\slash\"\nliteral = 'C:\\Users\\nobody'\nmultiline = \"\"\"\nfirst\nsecond\"\"\"\n\n[owner]\nname = \"Tom Preston-Werner\"\ndob = 1979\n\n[database]\nports = [ 8000, 8001, 8002 ]\ndata = [ [\"delta\", \"phi\"], [3.14] ]\ntemp_targets = { cpu = 79.5, case = 72.0 }\n\n[servers.alpha]\nip = \"10.0.0.1\"\nrole = \"frontend\"\n\n[servers.beta]\nip = \"10.0.0.2\"\nrole = \"backend\"\n\n[[products]]\nname = \"Hammer\"\nsku = 738594937\n\n[[products]]\nname = \"Nail\"\nsku = 284758393\ncolor = \"gray\"\n\n[deep.nested.table]\nvalue = \"reached\"\n"

console.log(JSON.stringify(parse(document)))
