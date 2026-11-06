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
const INLINE_SOLID = /^--\s*"([^"]*)"\s*(-->|---|--o|--x)/
const INLINE_DOTTED = /^-\.\s*"([^"]*)"\s*(\.-?>|-?\.-)/
const INLINE_THICK = /^==\s*"([^"]*)"\s*(==>|===|===o|===x)/
const BROKEN_INLINE_SOLID = /^--\s*([^->\n]+?)\s*(-->|---|--o|--x)/
const BROKEN_INLINE_DOTTED = /^-\.\s*([^-.\n]+?)\s*(\.-?>|-?\.-)/
const BROKEN_INLINE_THICK = /^==\s*([^=>\n]+?)\s*(==>|===|===o|===x)/
const INLINE_QUOTED_EDGE = /^(?:--|-\.|==)\s*"/

export function encode(value: string): string {
  const decoded = decode(value).replace(/<br\s*\/?>|\\n/gi, '\n')
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
    fail(`reserved identifier: ${value}`, 'identifier.reserved')
  return value
}

function identifier(value: string, reserved?: ReadonlySet<string>): string {
  if (!IDENT.test(value)) fail(`invalid identifier: ${value}`)
  if (reserved?.has(value.toLowerCase()))
    fail(`reserved identifier: ${value}`, 'identifier.reserved')
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
  if (
    BROKEN_INLINE_SOLID.exec(remaining) ||
    BROKEN_INLINE_DOTTED.exec(remaining) ||
    BROKEN_INLINE_THICK.exec(remaining)
  )
    fail(
      'unquoted inline edge label; edge labels must be quoted, e.g. -- "label" --> or -->|"label"|',
    )
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
              fail('invalid inline edge; expected quoted label, e.g. -- "label" -->')
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

const IDENT_PREFIX = new RegExp(grammar.patterns.identifier.slice(0, -1))

interface BoundaryState {
  children: number
}

type Handler = (lines: string[], header: string) => string[]

export function recognize(source: unknown): Recognition {
  try {
    const rawLines = scanLines(source, grammar.bounds.maxInputUtf16Units)
    const lines = rawLines.map((line) => line.trim()).filter(Boolean)
    const header = lines.shift()
    if (!header) fail('missing header', 'input.header')
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
    if (!lines.length) fail('empty diagram', 'input.empty-diagram')
    return {
      ok: true,
      renderText: handler(lines, header).join('\n'),
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
    ['Container', [2, 4]],
    ['Container_Boundary', [2, 2]],
    ['Container_Ext', [2, 4]],
    ['Person', [2, 3]],
    ['Person_Ext', [2, 3]],
    ['Rel', [3, 4]],
    ['Rel_Neighbor', [3, 4]],
    ['System', [2, 3]],
    ['System_Boundary', [2, 2]],
    ['System_Ext', [2, 3]],
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
      fail('unsupported C4 element; allowed: Person, System, Container, Component, boundaries, Rel')
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
    } else if (
      (match = line.match(
        /^class ([A-Za-z][A-Za-z0-9_]*)(~[^~\s][^~]*~)?(?:\["([^"]+)"\])?(<<[A-Za-z][A-Za-z0-9_-]*>>)?( \{)?$/,
      ))
    ) {
      const generic = match[2] as string | undefined
      const annotation = match[4] as string | undefined
      body = Boolean(match[5])
      out.push(
        `class ${identifier(match[1], CLASS_RESERVED)}${generic ?? ''}${match[3] ? `[${slots.quoted(match[3])}]` : ''}${annotation ?? ''}${body ? ' {' : ''}`,
      )
    } else if ((match = line.match(/^note for ([A-Za-z][A-Za-z0-9_]*) (.+)$/))) {
      out.push(
        `note for ${identifier(match[1], CLASS_RESERVED)} ${slots.quoted(match[2].startsWith('"') ? bareText(match[2]) : match[2])}`,
      )
    } else if (
      (match = line.match(
        /^([A-Za-z][A-Za-z0-9_]*)(?: "([^"]+)")? (<\|--|\*--|o--|-->|\.\.>|\.\.\|>|<\|\.\.|<\.\.|o\.\.|\.\.o|--) (?:"([^"]+)" )?([A-Za-z][A-Za-z0-9_]*)(?: : (.+))?$/,
      ))
    ) {
      out.push(
        `${identifier(match[1], CLASS_RESERVED)}${match[2] ? ` ${slots.quoted(match[2])}` : ''} ${match[3]} ${match[4] ? `${slots.quoted(match[4])} ` : ''}${identifier(match[5], CLASS_RESERVED)}${match[6] ? ` : ${classText(match[6])}` : ''}`,
      )
    } else
      fail(
        'unsupported class statement; allowed: class declaration/body, relation with <|-- *-- o-- --> ..> ..|> -- <|.. <.. o.. ..o, note for',
      )
  }
  if (body) fail('unclosed class body')
  return out
}

function classMember(value: string): string {
  const content = text(value.startsWith('"') ? bareText(value) : value)
  if (/[{}]/.test(content)) fail('nested class body')
  if (/&[#A-Za-z0-9]/.test(content)) fail('HTML-like entity is unsupported')
  const decoded = decode(content).replace(/<br\s*\/?>|\\n/gi, '\n')
  if (decoded.includes(';'))
    fail('ambiguous multiple statements on one line', 'text.statement-separator')
  return decoded
    .split('\n')
    .map((part) => part.trim())
    .join('')
    .replace(/[<>&"]/g, (character) =>
      character === '"' ? '#quot;' : `#${character.codePointAt(0)};`,
    )
}

function classText(value: string): string {
  return structuralText(
    value,
    /;\s*(?:(?:class|classDef|namespace|style|link|click|note|direction)\b|[A-Za-z][A-Za-z0-9_]*[^;\n]*?(?:<\|--|\*--|o--|-->|\.\.>|\.\.\|>|<\|\.\.|<\.\.|o\.\.|\.\.o|--))/gi,
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
        key = s.take(/^(?:PK|FK|UK)(?:,\s*(?:PK|FK|UK))*/)
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

function journey(lines: string[], header: string): string[] {
  if (/^journey\s+title(?:\s+.*)?$/.test(header)) {
    lines = [`title ${header.slice(13).trim()}`, ...lines]
    header = 'journey'
  }
  const out = [header]
  let title = false
  let section = false
  let tasks = 0
  for (const line of lines) {
    let match: null | RegExpMatchArray
    if ((match = line.match(/^title (.+)$/))) {
      if (title || section) fail('duplicate or misplaced journey title')
      title = true
      out.push(`title ${journeyText(match[1])}`)
    } else if ((match = line.match(/^section (.+)$/))) {
      section = true
      out.push(`section ${journeyText(match[1])}`)
    } else if ((match = line.match(/^("[^"]*"|[^:]+):\s*(-?\d+)\s*:\s*(.+)$/))) {
      if (!section) fail('journey task requires section')
      const score = Number(match[2])
      if (
        !Number.isSafeInteger(score) ||
        score < grammar.bounds.journeyScoreMin ||
        score > grammar.bounds.journeyScoreMax
      )
        fail('journey score must be 1 to 5', 'journey.score')
      const roles = splitRoles(match[3]).map((role) => {
        if (decode(bareText(role)).includes(',')) fail('comma inside journey role is unsupported')
        return journeyText(role)
      })
      tasks++
      out.push(`${journeyText(match[1])}: ${score}: ${roles.join(', ')}`)
    } else fail('unsupported journey statement; allowed: title, section, task with score and roles')
  }
  if (!tasks) fail('journey requires a task')
  return out
}

function journeyText(value: string): string {
  return structuralText(value, /;\s*(?:(?:title|section)\s|[^:;]+:\s*-?\d+\s*:)/g)
}

function pie(lines: string[], header: string): string[] {
  if (/^pie\s+title(?:\s+.*)?$/.test(header)) {
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
        /^"([^"]+)": \[(-?(?:\d+(?:\.\d+)?|\.\d+)),\s*(-?(?:\d+(?:\.\d+)?|\.\d+))\]$/,
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
  const seqId = (value: string): string => identifier(value, SEQUENCE_RESERVED)
  const activation = (id: string, delta: number): void => {
    activationReferences.add(id)
    const depth = (active.get(id) ?? 0) + delta
    if (depth < 0)
      fail(`deactivation of inactive participant: ${id}`, 'sequence.activation-balance')
    active.set(id, depth)
  }
  for (const line of lines) {
    let match: null | RegExpMatchArray
    if (line === 'autonumber') {
      out.push(line)
    } else if ((match = line.match(/^(participant|actor) ([A-Za-z][A-Za-z0-9_]*)(?: as (.+))?$/))) {
      const id = seqId(match[2])
      if (declarations.has(id))
        fail(`duplicate sequence declaration: ${id}`, 'sequence.declaration-unique')
      declarations.add(id)
      participants.add(id)
      out.push(`${match[1]} ${id}${match[3] ? ` as ${sequenceText(match[3])}` : ''}`)
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
    } else if (
      (match = line.match(
        /^(?:Note|note) (over|left of|right of) ([A-Za-z][A-Za-z0-9_]*)(?:,([A-Za-z][A-Za-z0-9_]*))?: (.+)$/,
      ))
    ) {
      if (match[3] && match[1] !== 'over') fail('invalid note participants')
      const first = seqId(match[2])
      const second = match[3] ? seqId(match[3]) : undefined
      participants.add(first)
      if (second) participants.add(second)
      out.push(`Note ${match[1]} ${first}${second ? `,${second}` : ''}: ${sequenceText(match[4])}`)
    } else if ((match = line.match(/^(activate|deactivate) ([A-Za-z][A-Za-z0-9_]*)$/))) {
      const id = seqId(match[2])
      activation(id, match[1] === 'activate' ? 1 : -1)
      out.push(`${match[1]} ${id}`)
    } else if (
      (match = line.match(
        /^([A-Za-z][A-Za-z0-9_]*)(-->>|->>|-->|->|--\)|-\)|--x|-x)([+-]?)([A-Za-z][A-Za-z0-9_]*): (.+)$/,
      ))
    ) {
      const from = seqId(match[1])
      const to = seqId(match[4])
      participants.add(from)
      participants.add(to)
      if (match[3] === '+') activation(to, 1)
      if (match[3] === '-') activation(from, -1)
      out.push(`${from}${match[2]}${match[3]}${to}: ${sequenceText(match[5])}`)
    } else
      fail(
        'unsupported sequence statement; allowed: participant, actor, message, note, activate/deactivate, autonumber, alt/loop/opt/par, title',
      )
  }
  if (stack.length) fail('unclosed sequence block')
  for (const id of activationReferences) {
    if (!participants.has(id))
      fail(`activation references unknown participant: ${id}`, 'sequence.activation-participant')
  }
  return out
}

function sequenceText(value: string): string {
  return structuralText(
    value,
    /;\s*(?:[A-Za-z][A-Za-z0-9_]*\s*(?:-->>|->>|-->|->|--\)|-\)|--x|-x|<<--|<<-|<--|<-)|(?:participant|actor|create|destroy|alt|loop|opt|par|par_over|else|and|rect|box|critical|option|break|end|note|activate|deactivate|title|links|link|properties|details|autonumber)\b)/gi,
  )
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
  let note: undefined | { id: string; parts: string[]; side: string }
  for (const line of lines) {
    let match: null | RegExpMatchArray
    if (note) {
      if (/^end note$/i.test(line)) {
        if (!note.parts.length) fail('empty state note')
        out.push(`note ${note.side} of ${note.id} : ${note.parts.join('<br/>')}`)
        note = undefined
      } else note.parts.push(stateText(line))
    } else if ((match = line.match(/^(?:note|Note) (left|right) of ([A-Za-z][A-Za-z0-9_]*)$/))) {
      const id = identifier(match[2], STATE_RESERVED)
      if (!states.has(id))
        fail(`note references unknown or later state: ${id}`, 'state.note-after-state')
      note = { id, parts: [], side: match[1] }
    } else if ((match = line.match(/^state "([^"]+)" as ([A-Za-z][A-Za-z0-9_]*)$/))) {
      const id = identifier(match[2], STATE_RESERVED)
      states.add(id)
      out.push(`state ${slots.quoted(match[1])} as ${id}`)
    } else if (
      (match = line.match(
        /^(\[\*\]|[A-Za-z][A-Za-z0-9_]*)\s*-->\s*(\[\*\]|[A-Za-z][A-Za-z0-9_]*)(?:\s*:\s*(.+))?$/,
      ))
    ) {
      for (const id of [match[1], match[2]]) {
        if (id !== '[*]') states.add(identifier(id, STATE_RESERVED))
      }
      out.push(`${match[1]} --> ${match[2]}${match[3] ? ` : ${stateText(match[3])}` : ''}`)
    } else if (
      (match = line.match(/^(?:note|Note) (left|right) of ([A-Za-z][A-Za-z0-9_]*) : (.+)$/))
    ) {
      const id = identifier(match[2], STATE_RESERVED)
      if (!states.has(id))
        fail(`note references unknown or later state: ${id}`, 'state.note-after-state')
      out.push(`note ${match[1]} of ${id} : ${stateText(match[3])}`)
    } else
      fail(
        'unsupported state statement; allowed: state alias, ID --> ID[: label], note left/right of ID',
      )
  }
  if (note) fail('unclosed state note')
  return out
}

function stateText(value: string): string {
  return structuralText(
    value,
    /;\s*(?:(?:\[\*\]|[A-Za-z][A-Za-z0-9_]*)\s*-->|(?:state|note|class|classDef|direction|end)(?:\s|$))/g,
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
