import grammar from '../../../../protocol/mermaid-grammar/grammar.json' with { type: 'json' }

export type Recognition =
  | { error: string; ok: false; rule: string }
  | { ok: true; renderText: string; source: string; type: string }

class SourceError extends Error {
  rule: string

  constructor(rule: string, message: string) {
    super(message)
    this.rule = rule
  }
}

function scanLines(source: unknown, maxLength: number): string[] {
  if (typeof source !== 'string') throw new SourceError('input.type', 'source must be a string')
  if (source.length > maxLength) throw new SourceError('input.length', 'invalid source size')
  if (
    Array.from(source).some((character) => {
      const code = character.charCodeAt(0)
      return (code < 32 && code !== 9 && code !== 10 && code !== 13) || code === 127
    })
  )
    throw new SourceError('input.control', 'invalid control character')
  const normalized = source.replace(/\r\n/g, '\n')
  if (normalized.includes('\r')) throw new SourceError('input.control', 'invalid control character')
  return normalized.split('\n')
}

const IDENT = new RegExp(grammar.patterns.identifier)
const FLOW_IDENT = new RegExp(grammar.patterns.flowIdentifier)
const FLOW_IDENT_PREFIX = new RegExp(grammar.patterns.flowIdentifier.slice(0, -1))
const NUMBER = new RegExp(grammar.patterns.number)
const COORDINATE = new RegExp(grammar.patterns.coordinate)
const CARDINALITY = new Set(['o{', 'o|', '|o', '|{', '||', '}o', '}|'])
const FLOW_RESERVED = new Set(grammar.reserved.flowchart)
const SEQUENCE_RESERVED = new Set(grammar.reserved.sequenceDiagram)
const CLASS_RESERVED = new Set(grammar.reserved.classDiagram)
const STATE_RESERVED = new Set(grammar.reserved['stateDiagram-v2'])
const ER_RESERVED = new Set(grammar.reserved.erDiagram)
const ER_ATTRIBUTE_RESERVED = new Set(grammar.reserved.erAttribute)
const HEADERS = Object.values(grammar.headers).flat()
const FLOW_HEADER = /^(flowchart|graph)(?: (?:[tT][dD]|[tT][bB]|[bB][tT]|[lL][rR]|[rR][lL]))?$/
const INLINE_SOLID = /^--[ \t]*"([^"]*)"[ \t]*(-->|---|--o|--x)/
const INLINE_DOTTED = /^-\.[ \t]*"([^"]*)"[ \t]*(\.-?>|-?\.-)/
const INLINE_THICK = /^==[ \t]*"([^"]*)"[ \t]*(==>|===|===o|===x)/
const INLINE_QUOTED_EDGE = /^(?:--|-\.|==)[ \t]*"/
const CLASS_ID = '[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*'
const CLASS_ID_PREFIX = new RegExp('^' + CLASS_ID)
const CLASS_ARROWS = [
  '<|--',
  '*--',
  'o--',
  '-->',
  '..>',
  '..|>',
  '<|..',
  '<..',
  'o..',
  '..o',
  '--|>',
  '--*',
  '--o',
  '<--',
  '--',
]
const SEQUENCE_ARROWS = ['-->>', '->>', '-->', '->', '--)', '-)', '--x', '-x']
const CLASS_DECLARATION = new RegExp(
  `^class (${CLASS_ID})(~[^~ \\t][^~]*~)?(?:\\["([^"]+)"\\])?(<<[A-Za-z][A-Za-z0-9_-]*>>)?( \\{)?$`,
)
const CLASS_NOTE = new RegExp(`^note for (${CLASS_ID}) (.+)$`)

export function encode(value: string): string {
  const decoded = decode(value).replace(/<br[ \t]*\/?>|\\n/gi, '\n')
  return Array.from(decoded, (character) => {
    if (character === '\n') return '<br/>'
    if (/^[A-Za-z0-9 .,!?$%*+=_-]$/.test(character)) return character
    if (character === '"') return '#quot;'
    return `#${character.codePointAt(0)};`
  }).join('')
}

function bareText(value: string): string {
  const content = text(value)
  if (content.startsWith('"')) {
    if (!content.endsWith('"') || content.length < 2 || content.slice(1, -1).includes('"'))
      fail('ambiguous quoted text')
    return text(content.slice(1, -1))
  }
  if ((content.match(/"/g) ?? []).length % 2 !== 0) fail('unmatched text quote')
  return content
}

function checkDepth(stack: readonly unknown[]): void {
  if (stack.length > grammar.bounds.maxDepth) fail('maximum nesting depth exceeded', 'input.depth')
}

function classIdentifier(value: string): string {
  for (const part of value.split('.')) {
    if (!IDENT.test(part)) fail(`invalid class identifier: ${value}`)
    if (CLASS_RESERVED.has(part.toLowerCase()))
      fail(
        `reserved identifier: ${part}; choose a non-keyword class identifier`,
        'identifier.reserved',
      )
  }
  return value
}

function decode(value: string): string {
  return value.replace(/#quot;|#(\d+);/g, (entity: string, digits: string | undefined) => {
    if (entity === '#quot;') return '"'
    const code = Number(digits)
    if (
      code > 0x10ffff ||
      (code >= 0xd800 && code <= 0xdfff) ||
      (code < 0x20 && code !== 9 && code !== 10) ||
      code === 0x7f
    )
      fail('invalid character entity')
    return String.fromCodePoint(code)
  })
}

function encodedText(value: string): string {
  const content = text(value)
  if (/&[#A-Za-z0-9]/.test(content)) fail('HTML-like entity is unsupported')
  return text(encode(content))
}

function fail(message: string, rule = 'syntax.statement'): never {
  throw new SourceError(rule, message)
}

function flowIdentifier(value: string): string {
  if (!FLOW_IDENT.test(value)) fail(`invalid identifier: ${value}`)
  if (FLOW_RESERVED.has(value) || value === 'classDef' || value === 'linkStyle')
    fail(`reserved identifier: ${value}; choose a non-keyword identifier`, 'identifier.reserved')
  return value
}

function identifier(value: string, reserved?: ReadonlySet<string>): string {
  if (!IDENT.test(value)) fail(`invalid identifier: ${value}`)
  if (reserved?.has(value.toLowerCase()))
    fail(`reserved identifier: ${value}; choose a non-keyword identifier`, 'identifier.reserved')
  return value
}

function number(value: string): string {
  if (!NUMBER.test(value) || !Number.isFinite(Number(value))) fail(`invalid number: ${value}`)
  return value
}

function text(value: string): string {
  const trimmed = value.trim()
  if (!trimmed) fail('empty text slot')
  return trimmed
}

const slots = {
  bare: (value: string): string => encodedText(bareText(value)),
  c4raw: (value: string): string => {
    const content = text(value)
    if (
      /["<>]/.test(content) ||
      Array.from(content).some((character) => character.charCodeAt(0) < 32) ||
      /#(?:quot|\d+);/i.test(content) ||
      /&[#A-Za-z0-9]/.test(content)
    )
      fail('unsafe C4 raw text', 'c4.raw-text')
    return `"${content}"`
  },
  quoted: (value: string): string => `"${encodedText(value)}"`,
}

class Scanner {
  line: string
  pos: number

  constructor(line: string) {
    this.line = line
    this.pos = 0
  }
  done(): void {
    this.space()
    if (this.pos !== this.line.length) fail(`unexpected input at column ${this.pos + 1}`)
  }
  id(): string {
    return identifier(this.take(IDENT_PREFIX))
  }
  literal(value: string): string {
    this.space()
    if (!this.line.startsWith(value, this.pos)) fail(`expected ${value} at column ${this.pos + 1}`)
    this.pos += value.length
    return value
  }
  maybe(value: string): boolean {
    this.space()
    if (!this.line.startsWith(value, this.pos)) return false
    this.pos += value.length
    return true
  }
  quoted(): string {
    if (!this.maybe('"')) fail('expected a quoted label, e.g. "Label"')
    const end = this.line.indexOf('"', this.pos)
    if (end < 0) fail('unterminated quoted text')
    const value = this.line.slice(this.pos, end)
    this.pos = end + 1
    return text(value)
  }
  space(): void {
    while (this.line[this.pos] === ' ' || this.line[this.pos] === '\t') this.pos++
  }
  take(pattern: RegExp): string {
    this.space()
    const found = this.line.slice(this.pos).match(pattern)
    if (!found || found.index !== 0) fail(`expected ${pattern} at column ${this.pos + 1}`)
    this.pos += found[0].length
    return found[0]
  }
}

function checkBrokenInlineEdge(remaining: string): void {
  if (INLINE_QUOTED_EDGE.exec(remaining))
    fail('missing or invalid closing arrow for inline edge label; e.g. -- "label" -->')
}

function flow(lines: string[], header: string): string[] {
  const out = [flowHeader(header)]
  const stack: string[] = []
  const scopedEndpoints: Set<string>[] = []
  const groups = new Set<string>()
  const nodes = new Set<string>()
  let completedGroups = 0
  const addGroup = (id: string): void => {
    if (groups.has(id) || nodes.has(id))
      fail('duplicate or conflicting subgraph id', 'flow.subgraph-id-unique')
    groups.add(id)
  }
  for (const line of lines) {
    if (line.startsWith('direction ')) {
      out.push(`direction ${flowDirection(line.slice(10))}`)
      continue
    }
    const s = new Scanner(line)
    if (s.maybe('subgraph ')) {
      const group = flowGroup(s)
      if (group.id) addGroup(group.id)
      stack.push(group.id)
      scopedEndpoints.push(new Set<string>())
      checkDepth(stack)
      out.push(group.render)
    } else if (line === 'end') {
      if (!stack.length) fail('unmatched end; close each subgraph with end')
      const explicit = stack.pop()!
      const id = explicit || `subGraph${completedGroups}`
      const scoped = scopedEndpoints.pop()!
      if (scoped.has(id))
        fail('subgraph cannot contain itself as an edge endpoint', 'flow.subgraph-id-unique')
      if (!explicit) addGroup(id)
      completedGroups++
      out.push('end')
    } else {
      let result = flowNode(s)
      const firstIds: string[] = []
      for (const part of result.split(' & ')) {
        const id = part.match(FLOW_IDENT_PREFIX)![0]
        firstIds.push(id)
        if (part !== id) nodes.add(id)
      }
      s.space()
      const endpoints = [...firstIds]
      const standalone = s.pos === line.length
      while (s.pos < line.length) {
        const remaining = s.line.slice(s.pos)
        let arrow: string
        let label = ''
        let inline: null | RegExpExecArray
        if ((inline = INLINE_SOLID.exec(remaining))) {
          arrow = inline[2]
          label = `|${slots.quoted(text(inline[1]))}|`
          s.pos += inline[0].length
        } else if ((inline = INLINE_DOTTED.exec(remaining))) {
          arrow = inline[2].includes('>') ? '-.->' : '-.-'
          label = `|${slots.quoted(text(inline[1]))}|`
          s.pos += inline[0].length
        } else if ((inline = INLINE_THICK.exec(remaining))) {
          arrow = inline[2]
          label = `|${slots.quoted(text(inline[1]))}|`
          s.pos += inline[0].length
        } else {
          checkBrokenInlineEdge(remaining)
          const found = grammar.flowchartSyntax.arrows.find((candidate) =>
            s.line.startsWith(candidate, s.pos),
          )
          if (!found) {
            if (
              remaining.startsWith('--') ||
              remaining.startsWith('-.') ||
              remaining.startsWith('==')
            )
              fail('unquoted inline edge label; quote it, e.g. -- "label" -->')
            fail('expected an arrow such as -->, e.g. A --> B')
          }
          arrow = found
          s.pos += found.length
          if (s.maybe('|')) label = `|${flowLabel(s, ['|']).label}|`
        }
        const to = flowNode(s)
        for (const part of to.split(' & ')) {
          const id = part.match(FLOW_IDENT_PREFIX)![0]
          endpoints.push(id)
          if (part !== id) nodes.add(id)
        }
        result += ` ${arrow}${label} ${to}`
        s.space()
      }
      if (standalone) for (const id of firstIds) nodes.add(id)
      for (const scope of scopedEndpoints) for (const id of endpoints) scope.add(id)
      if (endpoints.some((id) => stack.includes(id)))
        fail('subgraph cannot contain itself as an edge endpoint', 'flow.subgraph-id-unique')
      out.push(result)
    }
  }
  if (stack.length) fail('unclosed subgraph; add end')
  if ([...groups].some((id) => nodes.has(id)))
    fail('subgraph id conflicts with node', 'flow.subgraph-id-unique')
  return out
}

function flowBareLabel(value: string): string {
  const label = text(value)
  if (/["[\]{}|]/.test(label)) fail('unsafe or ambiguous label; use plain text, e.g. A["API (v1)"]')
  return label
}

function flowDirection(value: string): string {
  const direction = value.trim().toUpperCase()
  if (!['BT', 'LR', 'RL', 'TB', 'TD'].includes(direction))
    fail('invalid direction; expected TD, TB, BT, LR or RL')
  return direction
}

function flowGroup(s: Scanner): { id: string; render: string } {
  s.space()
  const start = s.pos
  let title: string
  if (s.line[s.pos] === '"') {
    title = s.quoted()
    s.done()
  } else {
    if (!FLOW_IDENT_PREFIX.test(s.line.slice(s.pos)))
      fail('expected a subgraph, e.g. subgraph API["API Layer"]')
    const id = s.take(FLOW_IDENT_PREFIX)
    if (s.maybe('[')) {
      const label = flowLabel(s, [']']).label
      s.done()
      flowIdentifier(id)
      return { id, render: `subgraph ${id}[${label}]` }
    }
    s.space()
    if (s.pos === s.line.length) {
      flowIdentifier(id)
      return { id, render: `subgraph ${id}[${slots.quoted(id)}]` }
    }
    title = flowBareLabel(s.line.slice(start))
  }
  if (FLOW_IDENT.test(title)) {
    const id = flowIdentifier(title)
    return { id, render: `subgraph ${id}[${slots.quoted(title)}]` }
  }
  return { id: '', render: `subgraph ${slots.quoted(title)}` }
}

function flowHeader(header: string): string {
  const match = /^(flowchart|graph)(?: ([a-zA-Z]{2}))?$/.exec(header)
  if (!match || !match[2]) return 'flowchart'
  return `flowchart ${match[2].toUpperCase()}`
}

function flowLabel(s: Scanner, closers: readonly string[]): { close: string; label: string } {
  s.space()
  let label: string
  if (s.line[s.pos] === '"') {
    label = s.quoted()
  } else {
    const ends = closers.map((close) => s.line.indexOf(close, s.pos)).filter((index) => index >= 0)
    if (!ends.length) fail('missing label or closing shape; e.g. A["Service"] or DB[("Database")]')
    const end = Math.min(...ends)
    label = flowBareLabel(s.line.slice(s.pos, end))
    s.pos = end
  }
  s.space()
  const close = closers.find((candidate) => s.line.startsWith(candidate, s.pos))
  if (!close) fail('missing closing shape; e.g. A["Service"] or DB[("Database")]')
  s.pos += close.length
  return { close, label: slots.quoted(label) }
}

function flowNode(s: Scanner): string {
  const first = flowSingleNode(s)
  s.space()
  if (!s.maybe('&')) return first
  const parts = [first]
  do {
    parts.push(flowSingleNode(s))
    s.space()
  } while (s.maybe('&'))
  return parts.join(' & ')
}

function flowSingleNode(s: Scanner): string {
  s.space()
  if (!FLOW_IDENT_PREFIX.test(s.line.slice(s.pos))) fail('expected a node ID such as A or DB')
  const id = flowIdentifier(s.take(FLOW_IDENT_PREFIX))
  s.space()
  const shape = grammar.flowchartSyntax.nodeShapes.find(({ open }) =>
    s.line.startsWith(open, s.pos),
  )
  if (!shape) return id
  s.pos += shape.open.length
  const { close, label } = flowLabel(s, shape.closes)
  return `${id}${shape.open}${label}${close}`
}

function ignoredLine(line: string): boolean {
  return (
    line.startsWith('%%') ||
    /^style [A-Za-z0-9_.]+ .+$/.test(line) ||
    /^classDef [A-Za-z0-9_]+ .+$/.test(line) ||
    /^linkStyle .+$/.test(line)
  )
}

const IDENT_PREFIX = new RegExp(grammar.patterns.identifier.slice(0, -1))

interface BoundaryState {
  children: number
}

type Handler = (lines: string[], header: string) => string[]

interface JourneyTask {
  roles: null | string
  score: string
  text: string
}

interface SequenceMessage {
  arrow: string
  from: string
  message: string
  sign: string
  to: string
}

interface StateRef {
  alias?: string
  id: string
}

export function recognize(source: unknown): Recognition {
  try {
    const rawLines = scanLines(source, grammar.bounds.maxInputUtf16Units)
    const lines = rawLines.map((line) => line.trim()).filter(Boolean)
    const header = lines.shift()
    if (!header) fail('missing header', 'input.header')
    for (const line of lines) {
      if (line.startsWith('%%{')) fail('init directives are unsupported')
    }
    const content = lines.filter((line) => !ignoredLine(line))
    let type = header
    let handler: Handler
    if (FLOW_HEADER.test(header)) {
      type = 'flowchart'
      handler = flow
    } else if (header === 'stateDiagram') {
      type = 'stateDiagram-v2'
      handler = state
    } else if (header === 'pie' || header.startsWith('pie title ') || header === 'pie title') {
      type = 'pie'
      handler = pie
    } else if (
      header === 'journey' ||
      header.startsWith('journey title ') ||
      header === 'journey title'
    ) {
      type = 'journey'
      handler = journey
    } else if (header === 'sequenceDiagram') handler = sequence
    else if (header === 'classDiagram') handler = classDiagram
    else if (header === 'stateDiagram-v2') handler = state
    else if (header === 'erDiagram') handler = er
    else if (['C4Component', 'C4Container', 'C4Context'].includes(header)) handler = c4
    else if (header === 'quadrantChart') handler = quadrant
    else if (header === 'xychart-beta') handler = xy
    else fail(`unsupported header; supported: ${HEADERS.join(', ')}`, 'input.header')
    if (!content.length) fail('empty diagram', 'input.empty-diagram')
    return {
      ok: true,
      renderText: handler(content, header).join('\n'),
      source: source as string,
      type,
    }
  } catch (error) {
    if (!(error instanceof SourceError)) throw error
    return { error: error.message, ok: false, rule: error.rule }
  }
}

function c4(lines: string[], header: string): string[] {
  const out = [header]
  const stack: BoundaryState[] = []
  const ids = new Set<string>()
  const shapes = new Set<string>()
  const relations: [string, string][] = []
  let title = false
  const elements = new Map<string, [number, number]>([
    ['Boundary', [2, 3]],
    ['Component', [2, 4]],
    ['Component_Ext', [2, 4]],
    ['ComponentDb', [2, 4]],
    ['ComponentQueue', [2, 4]],
    ['Container', [2, 4]],
    ['Container_Boundary', [2, 2]],
    ['Container_Ext', [2, 4]],
    ['ContainerDb', [2, 4]],
    ['ContainerQueue', [2, 4]],
    ['Person', [2, 3]],
    ['Person_Ext', [2, 3]],
    ['Rel', [3, 4]],
    ['Rel_Neighbor', [3, 4]],
    ['System', [2, 3]],
    ['System_Boundary', [2, 2]],
    ['System_Ext', [2, 3]],
    ['SystemDb', [2, 4]],
    ['SystemQueue', [2, 4]],
  ])
  for (const line of lines) {
    if (line.startsWith('title ')) {
      if (title) fail('duplicate C4 title')
      title = true
      out.push(`title ${slots.bare(line.slice(6))}`)
      continue
    }
    if (line === '}') {
      const boundary = stack.pop()
      if (!boundary) fail('unmatched C4 boundary')
      if (!boundary.children) fail('empty C4 boundary', 'c4.boundary-nonempty')
      out.push('}')
      continue
    }
    const s = new Scanner(line)
    const name = s.take(/^[A-Za-z_]+/)
    const limits = elements.get(name)
    if (!limits)
      fail(
        'unsupported C4 element; allowed: Person, System, Container, Component, ContainerDb, SystemDb, ContainerQueue, SystemQueue, ComponentDb, ComponentQueue, boundaries, Rel',
      )
    s.literal('(')
    const args = [s.id()]
    const relation = name.startsWith('Rel')
    if (relation) {
      s.literal(',')
      args.push(s.id())
    }
    while (s.maybe(',')) args.push(slots.c4raw(s.quoted()))
    s.literal(')')
    const boundary = name.endsWith('Boundary') || name === 'Boundary'
    const open = s.maybe('{')
    s.done()
    if (args.length < limits[0] || args.length > limits[1] || boundary !== open)
      fail('invalid C4 arguments or boundary')
    if (relation) relations.push([args[0], args[1]])
    else {
      if (ids.has(args[0])) fail(`duplicate C4 identifier: ${args[0]}`, 'c4.id-unique')
      ids.add(args[0])
      if (!boundary) shapes.add(args[0])
    }
    if (stack.length) stack.at(-1)!.children++
    if (open) {
      stack.push({ children: 0 })
      checkDepth(stack)
    }
    out.push(`${name}(${args.join(', ')})${open ? ' {' : ''}`)
  }
  if (stack.length) fail('unclosed C4 boundary')
  for (const [from, to] of relations) {
    if (!shapes.has(from) || !shapes.has(to))
      fail(`unknown C4 relation endpoint: ${from} -> ${to}`, 'c4.endpoint-declared')
  }
  return out
}

function classDiagram(lines: string[], header: string): string[] {
  const out = [header]
  let body = false
  let member: null | string
  let relation: null | string
  for (const line of lines) {
    let match: null | RegExpMatchArray
    if (body) {
      if (line === '}') {
        body = false
        out.push('}')
        continue
      }
      if (/[{}]/.test(line)) fail('nested class body')
      const trimmed = line.trim()
      if (/^<<[A-Za-z][A-Za-z0-9_-]*>>$/.test(trimmed)) {
        out.push(trimmed)
        continue
      }
      out.push(classMember(line))
    } else if (line.startsWith('direction ')) {
      out.push(`direction ${flowDirection(line.slice(10))}`)
    } else if ((match = line.match(CLASS_DECLARATION))) {
      const generic = match[2] as string | undefined
      const annotation = match[4] as string | undefined
      body = Boolean(match[5])
      out.push(
        `class ${classIdentifier(match[1])}${generic ?? ''}${match[3] ? `[${slots.quoted(match[3])}]` : ''}${annotation ?? ''}${body ? ' {' : ''}`,
      )
    } else if ((match = line.match(CLASS_NOTE))) {
      out.push(
        `note for ${classIdentifier(match[1])} ${slots.quoted(match[2].startsWith('"') ? bareText(match[2]) : match[2])}`,
      )
    } else if ((member = classMemberStatement(line)) !== null) {
      out.push(member)
    } else if ((relation = classRelationStatement(line)) !== null) {
      out.push(relation)
    } else
      fail(
        'unsupported class statement; allowed: class ID, class ID { members }, ID : member, relation ID <arrow> ID [: label], note for ID',
      )
  }
  if (body) fail('unclosed class body')
  return out
}

function classIdEnd(line: string, start: number): number {
  const match = CLASS_ID_PREFIX.exec(line.slice(start))
  return match ? start + match[0].length : -1
}

function classMember(value: string): string {
  const content = text(value.startsWith('"') ? bareText(value) : value)
  if (/[{}]/.test(content)) fail('nested class body')
  if (/&[#A-Za-z0-9]/.test(content)) fail('HTML-like entity is unsupported')
  const decoded = decode(content).replace(/<br[ \t]*\/?>|\\n/gi, '\n')
  if (decoded.includes(';'))
    fail('ambiguous multiple statements on one line', 'text.statement-separator')
  const encoded = decoded
    .split('\n')
    .map((part) => part.trim())
    .join('')
    .replace(/[<>&"]/g, (character) =>
      character === '"' ? '#quot;' : `#${character.codePointAt(0)};`,
    )
  return text(encoded)
}

function classMemberStatement(line: string): null | string {
  const idEnd = classIdEnd(line, 0)
  if (idEnd < 0) return null
  let pos = skipAsciiSpaces(line, idEnd)
  if (line[pos] !== ':') return null
  const id = line.slice(0, idEnd)
  pos = skipAsciiSpaces(line, pos + 1)
  return `${classIdentifier(id)} : ${classMember(line.slice(pos))}`
}

function classRelationStatement(line: string): null | string {
  const leftEnd = classIdEnd(line, 0)
  if (leftEnd < 0) return null
  let pos = skipAsciiSpaces(line, leftEnd)
  if (pos === leftEnd) return null
  const leftId = line.slice(0, leftEnd)
  let leftCard: null | string = null
  if (line[pos] === '"') {
    const close = line.indexOf('"', pos + 1)
    if (close < 0) return null
    leftCard = line.slice(pos + 1, close)
    pos = skipAsciiSpaces(line, close + 1)
    if (pos === close + 1) return null
  }
  const arrow = CLASS_ARROWS.find((candidate) => line.startsWith(candidate, pos))
  if (!arrow) return null
  pos += arrow.length
  if (!isAsciiSpace(line[pos])) return null
  pos = skipAsciiSpaces(line, pos)
  let rightCard: null | string = null
  if (line[pos] === '"') {
    const close = line.indexOf('"', pos + 1)
    if (close < 0) return null
    rightCard = line.slice(pos + 1, close)
    pos = skipAsciiSpaces(line, close + 1)
    if (pos === close + 1) return null
  }
  const rightEnd = classIdEnd(line, pos)
  if (rightEnd < 0) return null
  const rightId = line.slice(pos, rightEnd)
  const colon = skipAsciiSpaces(line, rightEnd)
  let label: null | string = null
  if (line[colon] === ':') {
    label = line.slice(skipAsciiSpaces(line, colon + 1))
    if (!label) return null
  } else if (colon !== line.length) return null
  return `${classIdentifier(leftId)}${leftCard ? ` ${slots.quoted(leftCard)}` : ''} ${arrow} ${rightCard ? `${slots.quoted(rightCard)} ` : ''}${classIdentifier(rightId)}${label ? ` : ${classText(label)}` : ''}`
}

function classText(value: string): string {
  return structuralText(
    value,
    /;[ \t]*(?:(?:class|classDef|namespace|style|link|click|note|direction)\b|[A-Za-z][A-Za-z0-9_]*[^;\n]*?(?:<\|--|\*--|o--|-->|\.\.>|\.\.\|>|<\|\.\.|<\.\.|o\.\.|\.\.o|--\|>|--\*|--o|<--|--))/gi,
  )
}

function er(lines: string[], header: string): string[] {
  const out = [header]
  let body = false
  for (const line of lines) {
    const s = new Scanner(line)
    if (body) {
      if (line === '}') {
        body = false
        out.push('}')
        continue
      }
      const type = s.take(/^[A-Za-z][A-Za-z0-9_]*(?:\([0-9]+(?:,[0-9]+)?\))?(?:\[\])*/)
      identifier(type.match(/^[A-Za-z][A-Za-z0-9_]*/)![0], ER_ATTRIBUTE_RESERVED)
      const name = identifier(s.id(), ER_ATTRIBUTE_RESERVED)
      let key = ''
      let comment = ''
      s.space()
      if (s.pos < line.length && line[s.pos] !== '"') {
        key = s.take(/^(?:PK|FK|UK)(?:,[ \t]*(?:PK|FK|UK))*/)
        if (new Set(key.split(',').map((part) => part.trim())).size !== key.split(',').length)
          fail('duplicate attribute key')
      }
      s.space()
      if (s.pos < line.length) comment = ` ${slots.quoted(s.quoted())}`
      s.done()
      out.push(`${type} ${name}${key ? ` ${key}` : ''}${comment}`)
    } else {
      const from = erName(s)
      if (s.maybe('{')) {
        s.done()
        body = true
        out.push(`${from} {`)
        continue
      }
      const left = s.take(/^(?:\|\||\|o|o\||}o|o\{|}\||\|\{)/)
      s.literal('--')
      const right = s.take(/^(?:\|\||\|o|o\||}o|o\{|}\||\|\{)/)
      if (!CARDINALITY.has(left) || !CARDINALITY.has(right)) fail('invalid cardinality')
      const to = erName(s)
      s.literal(':')
      s.space()
      const isQuoted = s.line[s.pos] === '"'
      const label = isQuoted ? s.quoted() : text(s.line.slice(s.pos))
      if (!isQuoted) {
        for (const match of label.matchAll(/;/g)) {
          if (!/#(?:quot|\d+)$/.test(label.slice(0, match.index)))
            fail('ambiguous multiple statements on one line', 'text.statement-separator')
        }
        s.pos = s.line.length
      }
      s.done()
      out.push(`${from} ${left}--${right} ${to} : ${slots.quoted(label)}`)
    }
  }
  if (body) fail('unclosed ER body')
  return out
}

function erName(s: Scanner): string {
  s.space()
  if (s.line[s.pos] === '"') return slots.quoted(s.quoted())
  return identifier(s.id(), ER_RESERVED)
}

function firstUnquotedColon(value: string): number {
  let quoted = false
  for (let index = 0; index < value.length; index++) {
    const character = value[index]
    if (character === '"') quoted = !quoted
    else if (character === ':' && !quoted) return index
  }
  return -1
}

function indexOfUnquoted(value: string, token: string): number {
  let quoted = false
  for (let index = 0; index <= value.length - token.length; index++) {
    const character = value[index]
    if (character === '"') quoted = !quoted
    else if (!quoted && value.startsWith(token, index)) return index
  }
  return -1
}

function isAsciiSpace(character: string | undefined): boolean {
  return character === ' ' || character === '\t'
}

function journey(lines: string[], header: string): string[] {
  if (/^journey[ \t]+title(?:[ \t]+.*)?$/.test(header)) {
    lines = [`title ${header.slice(13).trim()}`, ...lines]
    header = 'journey'
  }
  const out = [header]
  let title = false
  let section = false
  let tasks = 0
  for (const line of lines) {
    let match: null | RegExpMatchArray
    let task: JourneyTask | null
    if ((match = line.match(/^title (.+)$/))) {
      if (title || section) fail('duplicate or misplaced journey title')
      title = true
      out.push(`title ${journeyText(match[1])}`)
    } else if ((match = line.match(/^section (.+)$/))) {
      section = true
      out.push(`section ${journeyText(match[1])}`)
    } else if ((task = journeyTask(line))) {
      if (!section) fail('journey task requires section')
      const score = Number(task.score)
      if (
        !Number.isSafeInteger(score) ||
        score < grammar.bounds.journeyScoreMin ||
        score > grammar.bounds.journeyScoreMax
      )
        fail('journey score must be 1 to 5', 'journey.score')
      const roles =
        task.roles && task.roles.trim()
          ? splitRoles(task.roles).map((role) => {
              if (decode(bareText(role)).includes(','))
                fail('comma inside journey role is unsupported')
              return journeyText(role)
            })
          : []
      tasks++
      out.push(`${journeyText(task.text)}: ${score}${roles.length ? `: ${roles.join(', ')}` : ''}`)
    } else fail('unsupported journey statement; allowed: title, section, task with score and roles')
  }
  if (!tasks) fail('journey requires a task')
  return out
}

function journeyTask(line: string): JourneyTask | null {
  const colon = firstUnquotedColon(line)
  if (colon < 0) return null
  const text = line.slice(0, colon)
  const rest = line.slice(colon + 1)
  const start = skipAsciiSpaces(rest, 0)
  let pos = start
  if (rest[pos] === '-') pos++
  const digits = pos
  while (pos < rest.length && /\d/.test(rest[pos])) pos++
  if (pos === digits) return null
  const score = rest.slice(start, pos)
  pos = skipAsciiSpaces(rest, pos)
  if (pos === rest.length) return { roles: null, score, text }
  if (rest[pos] !== ':') return null
  return { roles: rest.slice(pos + 1).trim(), score, text }
}

function journeyText(value: string): string {
  return structuralText(value, /;[ \t]*(?:(?:title|section)[ \t]|[^:;]+:[ \t]*-?\d+[ \t]*:)/g)
}

function pie(lines: string[], header: string): string[] {
  if (/^pie[ \t]+title(?:[ \t]+.*)?$/.test(header)) {
    lines = [`title ${header.slice(9).trim()}`, ...lines]
    header = 'pie'
  }
  const out = [header]
  let slices = 0
  let title = false
  for (const line of lines) {
    if (line.startsWith('title ')) {
      if (title || slices) fail('duplicate or misplaced pie title')
      title = true
      out.push(`title ${slots.bare(line.slice(6))}`)
    } else {
      const s = new Scanner(line)
      const label = s.quoted()
      s.literal(':')
      const value = number(s.take(/^-?(?:\d+(?:\.\d+)?|\.\d+)/))
      s.done()
      if (Number(value) < 0) fail('negative pie value')
      slices++
      out.push(`${slots.quoted(label)} : ${value}`)
    }
  }
  if (!slices) fail('pie requires a slice')
  return out
}

function quadrant(lines: string[], header: string): string[] {
  const out = [header]
  const captions = new Set<string>()
  let points = 0
  for (const line of lines) {
    let match: null | RegExpMatchArray
    if ((match = line.match(/^title (.+)$/))) {
      if (captions.has('title') || points) fail('duplicate or misplaced title')
      captions.add('title')
      out.push(`title ${slots.bare(match[1])}`)
    } else if ((match = line.match(/^(x-axis|y-axis) "([^"]+)" --> "([^"]+)"$/))) {
      if (captions.has(match[1]) || points) fail('duplicate or misplaced axis')
      captions.add(match[1])
      out.push(`${match[1]} ${slots.quoted(match[2])} --> ${slots.quoted(match[3])}`)
    } else if ((match = line.match(/^(quadrant-[1-4]) "([^"]+)"$/))) {
      if (captions.has(match[1]) || points) fail('duplicate or misplaced quadrant')
      captions.add(match[1])
      out.push(`${match[1]} ${slots.quoted(match[2])}`)
    } else if (
      (match = line.match(
        /^"([^"]+)": \[(-?(?:\d+(?:\.\d+)?|\.\d+)),[ \t]*(-?(?:\d+(?:\.\d+)?|\.\d+))\]$/,
      ))
    ) {
      if ([match[2], match[3]].some((x) => !COORDINATE.test(x) || !Number.isFinite(Number(x))))
        fail('invalid quadrant coordinate')
      points++
      out.push(`${slots.quoted(match[1])}: [${match[2]}, ${match[3]}]`)
    } else
      fail(
        'unsupported quadrant statement; allowed: title, x-axis/y-axis "low" --> "high", quadrant-1..4 "caption", "point": [x, y]',
      )
  }
  if (
    !['x-axis', 'y-axis', 'quadrant-1', 'quadrant-2', 'quadrant-3', 'quadrant-4'].every((key) =>
      captions.has(key),
    ) ||
    !points
  )
    fail('quadrant requires axes, four captions and a point')
  return out
}

function sequence(lines: string[], header: string): string[] {
  const out = [header]
  const stack: string[] = []
  const participants = new Set<string>()
  const declarations = new Set<string>()
  const activationReferences = new Set<string>()
  const active = new Map<string, number>()
  const activation = (id: string, delta: number): void => {
    activationReferences.add(id)
    const depth = (active.get(id) ?? 0) + delta
    if (depth < 0)
      fail(`deactivation of inactive participant: ${id}`, 'sequence.activation-balance')
    active.set(id, depth)
  }
  for (const line of lines) {
    let match: null | RegExpMatchArray
    let message: null | SequenceMessage
    if (line === 'autonumber') {
      out.push(line)
    } else if ((match = line.match(/^(participant|actor) (.+)$/))) {
      const rest = text(match[2])
      const as = rest.lastIndexOf(' as ')
      const name = as >= 0 ? sequenceIdentifier(text(rest.slice(0, as))) : sequenceName(rest)
      const alias = as >= 0 ? sequenceText(rest.slice(as + 4)) : undefined
      if (declarations.has(name))
        fail(`duplicate sequence declaration: ${name}`, 'sequence.declaration-unique')
      declarations.add(name)
      participants.add(name)
      out.push(`${match[1]} ${name}${alias ? ` as ${alias}` : ''}`)
    } else if ((match = line.match(/^rect (.+)$/))) {
      const argument = text(match[1])
      if (argument.includes(';'))
        fail('ambiguous multiple statements on one line', 'text.statement-separator')
      stack.push('rect')
      checkDepth(stack)
      out.push(`rect ${argument}`)
    } else if ((match = line.match(/^(alt|loop|opt|par) (.+)$/))) {
      stack.push(match[1])
      checkDepth(stack)
      out.push(`${match[1]} ${sequenceText(match[2])}`)
    } else if ((match = line.match(/^(else|and) (.+)$/))) {
      if (
        (match[1] === 'else' && stack.at(-1) !== 'alt') ||
        (match[1] === 'and' && stack.at(-1) !== 'par')
      )
        fail('invalid sequence branch', 'sequence.branch')
      out.push(`${match[1]} ${sequenceText(match[2])}`)
    } else if (line === 'end') {
      if (!stack.pop()) fail('unmatched end')
      out.push('end')
    } else if ((match = line.match(/^title (.+)$/))) {
      out.push(`title ${sequenceText(match[1])}`)
    } else if (line.startsWith('Note ') || line.startsWith('note ')) {
      let rest = line.slice(5)
      let side: string
      if (rest.startsWith('over ')) {
        side = 'over'
        rest = rest.slice(5)
      } else if (rest.startsWith('left of ')) {
        side = 'left of'
        rest = rest.slice(8)
      } else if (rest.startsWith('right of ')) {
        side = 'right of'
        rest = rest.slice(9)
      } else fail('invalid sequence note')
      const colon = rest.indexOf(': ')
      if (colon < 0) fail('invalid sequence note')
      const names = rest
        .slice(0, colon)
        .split(',')
        .map((part) => sequenceName(part))
      if (side !== 'over' && names.length !== 1) fail('invalid note participants')
      for (const name of names) participants.add(name)
      out.push(`Note ${side} ${names.join(',')}: ${sequenceText(rest.slice(colon + 2))}`)
    } else if ((match = line.match(/^(activate|deactivate) (.+)$/))) {
      const id = sequenceName(match[2])
      activation(id, match[1] === 'activate' ? 1 : -1)
      out.push(`${match[1]} ${id}`)
    } else if ((message = sequenceMessage(line))) {
      const from = sequenceName(message.from)
      const to = sequenceName(message.to)
      participants.add(from)
      participants.add(to)
      if (message.sign === '+') activation(to, 1)
      if (message.sign === '-') activation(from, -1)
      out.push(`${from}${message.arrow}${message.sign}${to}: ${sequenceText(message.message)}`)
    } else
      fail(
        'unsupported sequence statement; allowed: participant, actor, message, note, activate/deactivate, autonumber, alt/loop/opt/par/rect, title',
      )
  }
  if (stack.length) fail('unclosed sequence block')
  for (const id of activationReferences) {
    if (!participants.has(id))
      fail(`activation references unknown participant: ${id}`, 'sequence.activation-participant')
  }
  return out
}

function sequenceIdentifier(value: string): string {
  if (!IDENT.test(value)) fail(`invalid sequence identifier: ${value}`)
  if (SEQUENCE_RESERVED.has(value.toLowerCase()))
    fail(
      `reserved identifier: ${value}; choose a non-keyword participant identifier`,
      'identifier.reserved',
    )
  return value
}

function sequenceMessage(line: string): null | SequenceMessage {
  let best = -1
  let arrow: null | string = null
  for (const candidate of SEQUENCE_ARROWS) {
    const index = line.indexOf(candidate)
    if (
      index >= 0 &&
      (best < 0 || index < best || (index === best && candidate.length > (arrow?.length ?? 0)))
    ) {
      best = index
      arrow = candidate
    }
  }
  if (!arrow) return null
  let pos = best + arrow.length
  let sign = ''
  if (line[pos] === '+' || line[pos] === '-') {
    sign = line[pos]
    pos++
  }
  const colon = line.indexOf(': ', pos)
  if (colon < 0) return null
  const from = text(line.slice(0, best))
  const to = text(line.slice(pos, colon))
  return { arrow, from, message: text(line.slice(colon + 2)), sign, to }
}

function sequenceName(value: string): string {
  let name = text(value)
  if (name.startsWith('"')) name = bareText(name)
  if (!/^[A-Za-z0-9_]+(?: [A-Za-z0-9_]+)*$/.test(name))
    fail('invalid sequence name; use words separated by spaces, e.g. participant API as "REST API"')
  if (!name.includes(' ') && SEQUENCE_RESERVED.has(name.toLowerCase()))
    fail(
      `reserved identifier: ${name}; choose a non-keyword participant name`,
      'identifier.reserved',
    )
  return name
}

function sequenceText(value: string): string {
  return structuralText(
    value,
    /;[ \t]*(?:[A-Za-z][A-Za-z0-9_]*[ \t]*(?:-->>|->>|-->|->|--\)|-\)|--x|-x|<<--|<<-|<--|<-)|(?:participant|actor|create|destroy|alt|loop|opt|par|par_over|else|and|rect|box|critical|option|break|end|note|activate|deactivate|title|links|link|properties|details|autonumber)\b)/gi,
  )
}

function skipAsciiSpaces(line: string, position: number): number {
  let pos = position
  while (isAsciiSpace(line[pos])) pos++
  return pos
}

function splitRoles(value: string): string[] {
  const roles: string[] = []
  let start = 0
  let quoted = false
  for (let i = 0; i < value.length; i++) {
    if (value[i] === '"') quoted = !quoted
    if (value[i] === ',' && !quoted) {
      roles.push(value.slice(start, i))
      start = i + 1
    }
  }
  if (quoted) fail('unmatched role quote')
  roles.push(value.slice(start))
  return roles
}

function state(lines: string[], header: string): string[] {
  const out = [header === 'stateDiagram' ? 'stateDiagram-v2' : header]
  const states = new Set<string>()
  const aliases = new Map<string, string>()
  const composites: string[] = []
  let note: undefined | { id: string; parts: string[]; side: string }
  for (const line of lines) {
    if (note) {
      if (/^end note$/i.test(line)) {
        if (!note.parts.length) fail('empty state note')
        out.push(`note ${note.side} of ${note.id} : ${note.parts.join('<br/>')}`)
        note = undefined
      } else note.parts.push(stateText(line))
    } else if (line.startsWith('direction ')) {
      out.push(`direction ${flowDirection(line.slice(10))}`)
    } else if (line === '}') {
      if (!composites.length) fail('unmatched }')
      composites.pop()
      out.push('}')
    } else if (line.startsWith('note ') || line.startsWith('Note ')) {
      let rest = line.slice(5)
      let side: string
      if (rest.startsWith('left of ')) {
        side = 'left'
        rest = rest.slice(8)
      } else if (rest.startsWith('right of ')) {
        side = 'right'
        rest = rest.slice(9)
      } else fail('unsupported state note; use note left/right of ID')
      const colon = firstUnquotedColon(rest)
      if (colon >= 0) {
        const id = stateNoteTarget(rest.slice(0, colon), states, aliases)
        out.push(`note ${side} of ${id} : ${stateText(rest.slice(colon + 1))}`)
      } else {
        note = { id: stateNoteTarget(rest, states, aliases), parts: [], side }
      }
    } else if (
      line.startsWith('state ') &&
      (firstUnquotedColon(line.slice(6)) >= 0 || indexOfUnquoted(line, '-->') < 0)
    ) {
      const rest = line.slice(6)
      const colon = firstUnquotedColon(rest)
      if (colon >= 0) {
        const name = text(rest.slice(0, colon))
        const description = text(rest.slice(colon + 1))
        const ref = stateReference(name, states, aliases)
        if (ref.alias) out.push(ref.alias)
        out.push(`${ref.id} : ${stateText(description)}`)
      } else {
        stateDeclaration(line, states, aliases, composites, out)
      }
    } else if (indexOfUnquoted(line, '-->') >= 0) {
      const arrow = indexOfUnquoted(line, '-->')
      const leftText = text(line.slice(0, arrow))
      const right = line.slice(arrow + 3)
      const colon = firstUnquotedColon(right)
      const toText = text(colon >= 0 ? right.slice(0, colon) : right)
      const label = colon >= 0 ? right.slice(colon + 1) : undefined
      const from = stateReference(leftText, states, aliases)
      const toRef = stateReference(toText, states, aliases)
      if (from.alias) out.push(from.alias)
      if (toRef.alias) out.push(toRef.alias)
      out.push(`${from.id} --> ${toRef.id}${label !== undefined ? ` : ${stateText(label)}` : ''}`)
    } else if (firstUnquotedColon(line) >= 0) {
      const colon = firstUnquotedColon(line)
      const name = text(line.slice(0, colon))
      const description = text(line.slice(colon + 1))
      const ref = stateReference(name, states, aliases)
      if (ref.alias) out.push(ref.alias)
      out.push(`${ref.id} : ${stateText(description)}`)
    } else
      fail(
        'unsupported state statement; allowed: state ID, state "Label" as ID, state ID { ... }, ID --> ID [: label], ID : description, direction LR, note left/right of ID',
      )
  }
  if (note) fail('unclosed state note')
  if (composites.length) fail('unclosed state block')
  return out
}

function stateDeclaration(
  line: string,
  states: Set<string>,
  aliases: Map<string, string>,
  composites: string[],
  out: string[],
): void {
  let rest = text(line.slice(6))
  const open = rest.endsWith('{')
  if (open) rest = text(rest.slice(0, -1))
  const as = rest.lastIndexOf(' as ')
  const display = as >= 0 ? text(rest.slice(0, as)) : rest
  const explicitId = as >= 0 ? stateIdentifier(text(rest.slice(as + 4))) : undefined
  if (!display) fail('state declaration requires a name')
  let id: string
  let render: string
  if (explicitId) {
    id = explicitId
    const displayText = display.startsWith('"') ? bareText(display) : display
    states.add(id)
    aliases.set(displayText, id)
    render = `state ${slots.quoted(displayText)} as ${id}`
  } else if (display.startsWith('"')) {
    const displayText = bareText(display)
    const ref = stateDisplayReference(displayText, states, aliases)
    id = ref.id
    render = ref.alias ?? `state ${slots.quoted(displayText)} as ${id}`
  } else if (/^[A-Za-z0-9_]+$/.test(display)) {
    id = stateIdentifier(display)
    states.add(id)
    render = `state ${id}`
  } else {
    if (display.includes('"') || display.startsWith('state '))
      fail('invalid state display; use a bare state name or a quoted display alias')
    const ref = stateDisplayReference(display, states, aliases)
    id = ref.id
    render = ref.alias ?? `state ${slots.quoted(display)} as ${id}`
  }
  out.push(`${render}${open ? ' {' : ''}`)
  if (open) {
    composites.push(id)
    checkDepth(composites)
  }
}

function stateDisplayId(display: string, states: Set<string>): string {
  let base = display.replace(/[^A-Za-z0-9_]+/g, '_').replace(/^_+|_+$/g, '')
  if (!base) base = 'State'
  if (!/^[A-Za-z]/.test(base)) base = `State_${base}`
  let candidate = base
  let suffix = 2
  while (states.has(candidate) || stateReserved(candidate)) candidate = `${base}_${suffix++}`
  return candidate
}

function stateDisplayReference(
  display: string,
  states: Set<string>,
  aliases: Map<string, string>,
): StateRef {
  const existing = aliases.get(display)
  if (existing) {
    states.add(existing)
    return { id: existing }
  }
  const id = stateDisplayId(display, states)
  aliases.set(display, id)
  states.add(id)
  return { alias: `state ${slots.quoted(display)} as ${id}`, id }
}

function stateIdentifier(value: string): string {
  if (!/^[A-Za-z0-9_]+$/.test(value)) fail(`invalid state identifier: ${value}`)
  if (stateReserved(value))
    fail(
      `reserved identifier: ${value}; choose a non-keyword state identifier`,
      'identifier.reserved',
    )
  return value
}

function stateNoteTarget(raw: string, states: Set<string>, aliases: Map<string, string>): string {
  const value = text(raw)
  let id: string | undefined
  if (value.startsWith('"')) {
    id = aliases.get(bareText(value))
  } else if (/^[A-Za-z0-9_]+$/.test(value)) {
    if (stateReserved(value))
      fail(
        `reserved identifier: ${value}; choose a non-keyword state identifier`,
        'identifier.reserved',
      )
    id = value
  } else {
    id = aliases.get(value)
  }
  if (!id || !states.has(id))
    fail(`note references unknown or later state: ${value}`, 'state.note-after-state')
  return id
}

function stateReference(raw: string, states: Set<string>, aliases: Map<string, string>): StateRef {
  const value = text(raw)
  if (value === '[*]') return { id: value }
  if (value.includes('"') || value.startsWith('state '))
    fail(
      'invalid state reference; use an alias declaration and a bare state name, not quotes or `state ...`',
    )
  if (/^[A-Za-z0-9_]+$/.test(value)) {
    if (stateReserved(value))
      fail(
        `reserved identifier: ${value}; choose a non-keyword state identifier`,
        'identifier.reserved',
      )
    states.add(value)
    return { id: value }
  }
  return stateDisplayReference(value, states, aliases)
}

function stateReserved(value: string): boolean {
  return STATE_RESERVED.has(value.toLowerCase())
}

function stateText(value: string): string {
  return structuralText(
    value,
    /;[ \t]*(?:(?:\[\*\]|[A-Za-z][A-Za-z0-9_]*)[ \t]*-->|(?:state|note|class|classDef|direction|end)(?:[ \t]|$))/g,
  )
}

function structuralText(value: string, statements: RegExp): string {
  const content = text(value)
  if (!content.startsWith('"')) {
    for (const match of content.matchAll(statements)) {
      if (!/#(?:quot|\d+)$/.test(content.slice(0, match.index)))
        fail('ambiguous multiple statements on one line', 'text.statement-separator')
    }
  }
  return slots.bare(content)
}

function xy(lines: string[], header: string): string[] {
  const out = [header]
  let categories = 0
  let series = 0
  let title = false
  let yAxis = false
  for (const line of lines) {
    let match: null | RegExpMatchArray
    if ((match = line.match(/^title "([^"]+)"$/))) {
      if (title || categories || series || yAxis) fail('duplicate or misplaced title')
      title = true
      out.push(`title ${slots.quoted(match[1])}`)
    } else if (line.startsWith('x-axis ')) {
      if (categories || series || yAxis) fail('duplicate or misplaced x-axis')
      const s = new Scanner(line.slice(7))
      let label = ''
      s.space()
      if (s.line[s.pos] === '"') label = `${slots.quoted(s.quoted())} `
      s.literal('[')
      const values = [s.quoted()]
      while (s.maybe(',')) values.push(s.quoted())
      s.literal(']')
      s.done()
      categories = values.length
      out.push(`x-axis ${label}[${values.map(slots.quoted).join(', ')}]`)
    } else if (
      (match = line.match(
        /^y-axis "([^"]+)" (-?(?:\d+(?:\.\d+)?|\.\d+)) --> (-?(?:\d+(?:\.\d+)?|\.\d+))$/,
      ))
    ) {
      if (yAxis || series || !categories || Number(number(match[2])) >= Number(number(match[3])))
        fail('invalid y-axis')
      yAxis = true
      out.push(`y-axis ${slots.quoted(match[1])} ${match[2]} --> ${match[3]}`)
    } else if ((match = line.match(/^(bar|line) \[([^\]]+)\]$/))) {
      if (!categories) fail('series requires categories')
      const values = match[2].split(',').map((x) => number(x.trim()))
      if (values.length !== categories) fail('series length mismatch', 'xy.series-length')
      series++
      out.push(`${match[1]} [${values.join(', ')}]`)
    } else
      fail(
        'unsupported xychart statement; allowed: title, x-axis ["category"], y-axis "label" [min --> max], bar/line [values]',
      )
  }
  if (!categories || !series) fail('xychart requires categories and a series')
  return out
}
