import { parse } from './parse.ts'

const document = [
    'title = "TOML Example"',
    '',
    '[owner]',
    'name = "Tom Preston-Werner"',
    'dob = 1979',
    '',
    '[database]',
    'enabled = true',
    'ports = [ 8000, 8001, 8002 ]',
    'temp_targets = { cpu = 79.5, case = 72.0 }'
].join('\n')

const parsed = parse(document)
console.log(JSON.stringify(parsed))
