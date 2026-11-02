import { expect, test } from 'vitest'

import { encode, recognize } from '@/lib/mermaid'

import { invalidFixtures, validFixtures } from '../../support/test-mermaid-fixtures'

function acceptance(source: string) {
  const result = recognize(source)
  if (!result.ok) throw new Error(result.error)
  return result
}

function rejection(source: unknown) {
  const result = recognize(source)
  if (result.ok) throw new Error(`expected rejection for ${JSON.stringify(source)}`)
  return result
}

for (const fixture of validFixtures) {
  test(`accepts ${fixture.name}`, () => {
    const result = acceptance(fixture.source)
    expect(result.type).toBe(fixture.type)
    expect(result.source).toBe(fixture.source)
    expect(result.renderText.length).toBeGreaterThan(0)
  })
}

for (const fixture of invalidFixtures) {
  test(`rejects ${fixture.name}`, () => {
    expect(rejection(fixture.source).error.length).toBeGreaterThan(0)
  })
}

test.each([
  'flowchart LR\nDB[(PostgreSQL)]',
  'flowchart LR\nREP --> DB[(PostgreSQL)]',
  'flowchart LR\nsubgraph External\nA\nend',
  'flowchart LR\nsubgraph API[API Layer]\nA\nend',
])('accepts reported standard flowchart source %s', (source) => {
  const result = acceptance(source)
  expect(result.source).toBe(source)
})

test('syntax errors suggest concrete supported examples', () => {
  expect(rejection('flowchart LR\nDB[(Unclosed]').error).toContain('DB[("Database")]')
  expect(rejection('flowchart LR\nA -z- B').error).toContain('A --> B')
  expect(rejection('pie\nSlice : 1').error).toContain('expected a quoted label, e.g. "Label"')
})

test('encoder decodes and encodes once, including unicode and line breaks', () => {
  for (const value of ['a; (b) & c', 'A#59;B #quot; 😀', 'a<br/>b', 'a\\nb', '<b>bold</b>']) {
    expect(encode(encode(value))).toBe(encode(value))
  }
  expect(encode('a; "😀"')).toBe('a#59; #quot;#128512;#quot;')
})

test('recognized render text is idempotent for every positive fixture', () => {
  for (const fixture of validFixtures) {
    const first = acceptance(fixture.source)
    const second = acceptance(first.renderText)
    expect(second.renderText, fixture.name).toBe(first.renderText)
  }
})

test('returns canonical slot policies without mutating source', () => {
  const source = 'sequenceDiagram\r\nparticipant A as Alice (A)\r\nA->>B: hello; #1\r\n'
  expect(recognize(source)).toEqual({
    ok: true,
    renderText: 'sequenceDiagram\nparticipant A as Alice #40;A#41;\nA->>B: hello#59; #35;1',
    source,
    type: 'sequenceDiagram',
  })
  expect(acceptance('C4Context\nPerson(a, "A (x); &")').renderText).toMatch(/"A \(x\); &"/)
})

test('removes only unambiguous bare-slot wrapper quotes', () => {
  const cases = [
    [
      'sequenceDiagram\nparticipant A as "Display Name"',
      'sequenceDiagram\nparticipant A as Display Name',
    ],
    ['sequenceDiagram\nA->>B: "Hello; world"', 'sequenceDiagram\nA->>B: Hello; world'],
    ['sequenceDiagram\nNote over A: "A note"', 'sequenceDiagram\nNote over A: A note'],
    [
      'sequenceDiagram\nloop "More work"\nA->>B: x\nend',
      'sequenceDiagram\nloop More work\nA->>B: x\nend',
    ],
    ['sequenceDiagram\ntitle "A title"', 'sequenceDiagram\ntitle A title'],
    ['classDiagram\nclass A {\n"+name: string"\n}', 'classDiagram\nclass A {\n+name: string\n}'],
    ['classDiagram\nA --> B : "Connected"', 'classDiagram\nA --> B : Connected'],
    [
      'stateDiagram-v2\n[*] --> A\nA --> [*] : "Finished"',
      'stateDiagram-v2\n[*] --> A\nA --> [*] : Finished',
    ],
    [
      'stateDiagram-v2\n[*] --> A\nnote right of A : "A note"',
      'stateDiagram-v2\n[*] --> A\nnote right of A : A note',
    ],
    ['pie\ntitle "A title"\n"Slice" : 1', 'pie\ntitle A title\n"Slice" : 1'],
    [
      'journey\ntitle "A title"\nsection Start\nTask: 3: Actor',
      'journey\ntitle A title\nsection Start\nTask: 3: Actor',
    ],
    [
      'journey\nsection "First step"\nTask: 3: Actor',
      'journey\nsection First step\nTask: 3: Actor',
    ],
    [
      'journey\nsection Start\n"First task": 3: Actor',
      'journey\nsection Start\nFirst task: 3: Actor',
    ],
    ['journey\nsection Start\nTask: 3: "An actor"', 'journey\nsection Start\nTask: 3: An actor'],
  ]
  const suffix =
    '\nx-axis "low" --> "high"\ny-axis "low" --> "high"\nquadrant-1 "one"\nquadrant-2 "two"\nquadrant-3 "three"\nquadrant-4 "four"\n"A": [0.1, 0.2]'
  cases.push([`quadrantChart\ntitle "A title"${suffix}`, `quadrantChart\ntitle A title${suffix}`])
  for (const [wrapped, bare] of cases) {
    const first = acceptance(wrapped)
    const second = acceptance(bare)
    expect(first.renderText, wrapped).toBe(second.renderText)
    expect(first.source).toBe(wrapped)
  }
})

test('preserves internal and entity-encoded literal quotes in bare text', () => {
  const result = acceptance(
    'sequenceDiagram\nA->>B: He said "yes"\nB->>A: "Reply #quot;hello#quot;"',
  )
  expect(result.renderText).toBe(
    'sequenceDiagram\nA->>B: He said #quot;yes#quot;\nB->>A: Reply #quot;hello#quot;',
  )
})

test('does not strip required quotes from quoted and C4 slots', () => {
  expect(acceptance('flowchart\nA["Label"] --> B').renderText).toBe('flowchart\nA["Label"] --> B')
  expect(acceptance('C4Context\nPerson(a, "Label")').renderText).toBe(
    'C4Context\nPerson(a, "Label")',
  )
  expect(acceptance('pie\n"Slice" : 1').renderText).toBe('pie\n"Slice" : 1')
})

test('rejects reserved identifiers in every sequence and flow position', () => {
  for (const id of ['loop', 'Loop', 'participant', 'Note', 'par_over']) {
    for (const line of [
      `participant ${id}`,
      `${id}->>A: x`,
      `A->>${id}: x`,
      `Note over ${id}: x`,
      `Note over A,${id}: x`,
      `activate ${id}`,
    ]) {
      expect(rejection(`sequenceDiagram\n${line}`).error, line).toMatch(/reserved identifier/)
    }
  }
  for (const id of ['end', 'class', 'classDef', 'linkStyle', 'graph']) {
    for (const line of [`${id}["x"] --> B`, `A --> ${id}`, `subgraph ${id}["G"]\nA\nend`]) {
      expect(rejection(`flowchart\n${line}`).error, line).toMatch(/reserved identifier/)
    }
  }
})

test('rejects parser-reserved class, state and ER identifiers', () => {
  for (const id of ['style', 'call', 'href', 'namespace', 'cssClass', 'callback', 'link']) {
    for (const source of [
      `classDiagram\nclass ${id}`,
      `classDiagram\nA --> ${id}`,
      `classDiagram\n${id} --> A`,
    ]) {
      expect(rejection(source).error, source).toMatch(/reserved identifier/)
    }
  }
  for (const id of ['style', 'click', 'default', 'stateDiagram', 'href']) {
    expect(rejection(`stateDiagram-v2\n[*] --> ${id}`).error, id).toMatch(/reserved identifier/)
  }
  for (const id of ['subgraph', 'style', 'erDiagram', 'one', 'many']) {
    for (const source of [`erDiagram\n${id} ||--o{ A : "x"`, `erDiagram\nA ||--o{ ${id} : "x"`]) {
      expect(rejection(source).error, source).toMatch(/reserved identifier/)
    }
  }
})

test('recognizes literal encoded semicolons and rejects ambiguous separators', () => {
  const quoted = acceptance('sequenceDiagram\nA->>B: "hello; B->>A: reply"')
  const encoded = acceptance('sequenceDiagram\nA->>B: hello#59; B->>A: reply')
  expect(quoted.renderText).toBe(encoded.renderText)
  for (const source of [
    'sequenceDiagram\nNote over A: hello; B->>A: bye',
    'sequenceDiagram\nparticipant A as hello; B->>A: bye',
    'stateDiagram-v2\n[*] --> A\nnote right of A : hi; B --> C',
  ]) {
    rejection(source)
  }
})

test('C4 rejects raw tags, HTML references and invalid relation endpoints', () => {
  for (const argument of [
    '<b>Text</b>',
    '&lt;script&gt;',
    '&#60;script&#62;',
    '&#x3c;script&#x3e;',
  ]) {
    expect(rejection(`C4Context\nPerson(a, "${argument}")`).error, argument).toMatch(
      /unsafe C4 raw text/,
    )
  }
  expect(acceptance('C4Context\nRel(a, b, "Calls")\nPerson(a, "A")\nSystem(b, "B")').ok).toBe(true)
  expect(rejection('C4Context\nPerson(a, "A")\nRel(a, b, "Calls")').error).toMatch(
    /unknown C4 relation endpoint/,
  )
})

test('rejects appended syntax on every fixture', () => {
  for (const fixture of validFixtures) {
    rejection(`${fixture.source}\nUNSUPPORTED ???`)
  }
})

test('rejects directives, comments, malformed input and invalid Unicode entities', () => {
  for (const source of [
    null,
    '',
    '%% comment\nflowchart\nA --> B',
    'flowchart\n%%{init: {}}%%\nA --> B',
    'pie\n"x#99999999;" : 1',
    'pie\n"x#0;" : 1',
    'pie\n"x#13;" : 1',
    'pie\n"x#127;" : 1',
    'pie\n"a\rb" : 1',
    'pie\n"x" : 1\u0000',
  ]) {
    rejection(source)
  }
})

test('encodes angle-bracket text without accepting named entities', () => {
  expect(acceptance('flowchart\nA["<valid_utf8_substring>"] --> B').renderText).toBe(
    'flowchart\nA["#60;valid_utf8_substring#62;"] --> B',
  )
  expect(acceptance('flowchart\nA[<script>alert(1)</script>]').renderText).toBe(
    'flowchart\nA["#60;script#62;alert#40;1#41;#60;#47;script#62;"]',
  )
  expect(acceptance('pie\n"<b></b>" : 1').renderText).toBe('pie\n"#60;b#62;#60;#47;b#62;" : 1')
  expect(rejection('flowchart\nA["&amp;"]').error).toMatch(/HTML-like entity/)
})

test('supports class UML members, generics, annotations and notes', () => {
  expect(
    acceptance(
      'classDiagram\nclass Repo~T, ID~ {\n<<interface>>\n+save(entity) Result\n+items List<String>\n}\nnote for Repo "Data access"',
    ).renderText,
  ).toBe(
    'classDiagram\nclass Repo~T, ID~ {\n<<interface>>\n+save(entity) Result\n+items List#60;String#62;\n}\nnote for Repo "Data access"',
  )
  expect(
    acceptance('classDiagram\nclass A {\nfoo#40;#41;\n}').renderText,
  ).toBe('classDiagram\nclass A {\nfoo()\n}')
})
