import mermaid from 'mermaid'
import { beforeAll, describe, expect, it } from 'vitest'

import { recognize } from '@/lib/mermaid'

import { invalidFixtures, validFixtures } from '../../support/test-mermaid-fixtures'

beforeAll(() => {
  const proto = SVGElement.prototype as unknown as Record<string, unknown>
  proto.getBBox = () => ({ height: 12, width: 80, x: 0, y: 0 })
  proto.getComputedTextLength = () => 80
  mermaid.initialize({ securityLevel: 'loose', startOnLoad: false })
})

async function flowTopology(source: string) {
  const diagram = await mermaid.mermaidAPI.getDiagramFromText(source)
  const db = diagram.db as unknown as {
    getEdges: () => { end: string; length: number; start: string; stroke: string; type: string }[]
    getSubGraphs: () => { id: string; nodes: string[] }[]
    getVertices: () => Map<string, { id: string; type: string }>
  }
  return {
    edges: db
      .getEdges()
      .map(({ end, length, start, stroke, type }) => ({ end, length, start, stroke, type })),
    groups: db.getSubGraphs().map(({ id, nodes }) => ({ id, nodes })),
    vertices: [...db.getVertices().values()].map(({ id, type }) => ({ id, type })),
  }
}

async function renderText(source: string, id: string) {
  const parsed = await mermaid.parse(source)
  expect(parsed).not.toBe(false)
  const { svg } = await mermaid.render(id, source)
  const container = document.createElement('div')
  container.innerHTML = svg
  const root = container.querySelector('svg')
  expect(root).not.toBeNull()
  return Array.from(
    root?.querySelectorAll('text, foreignObject') ?? [],
    (element) => element.textContent,
  )
    .join(' ')
    .replaceAll(/\s+/g, ' ')
}

describe('Mermaid subset scanner render proof', () => {
  it('covers every supported section 6.2 type', () => {
    expect(new Set(validFixtures.map((fixture) => fixture.type))).toEqual(
      new Set([
        'C4Component',
        'C4Container',
        'C4Context',
        'classDiagram',
        'erDiagram',
        'flowchart',
        'journey',
        'pie',
        'quadrantChart',
        'sequenceDiagram',
        'stateDiagram-v2',
        'xychart-beta',
      ]),
    )
  })

  for (const [index, fixture] of validFixtures.entries()) {
    it(`renders ${fixture.name}`, async () => {
      const result = recognize(fixture.source)
      expect(result.ok, result.ok ? '' : result.error).toBe(true)
      if (!result.ok) throw new Error(result.error)
      expect(result.type).toBe(fixture.type)
      const text = await renderText(result.renderText, `subset-proof-${index}`)
      for (const visible of fixture.visible) {
        expect(text).toContain(visible)
        expect(text).not.toContain(`"${visible}"`)
      }
    })
  }

  for (const fixture of invalidFixtures) {
    it(`rejects ${fixture.name}`, () => {
      expect(recognize(fixture.source).ok).toBe(false)
    })
  }

  it.each([
    ['flowchart', 'flowchart LR\nA["Edge; (group) & / 😀"] --> B["end"]', 'Edge; (group) & / 😀'],
    [
      'sequence',
      'sequenceDiagram\nparticipant A as Alice (team)\nA->>B: hello; world #1',
      'hello; world #1',
    ],
    ['state', 'stateDiagram-v2\n[*] --> A\nA --> [*] : done; next', 'done; next'],
    ['class', 'classDiagram\nclass User {\n+name: string\n}', '+name: string'],
    ['ER', 'erDiagram\nUSER ||--o{ TEAM : "owns; all"', 'owns; all'],
    ['C4 raw', 'C4Context\nPerson(user, "User (A); x & y", "Uses system")', 'User (A); x & y'],
    [
      'C4 arrow and pipe',
      'C4Context\nPerson(user, "Client --o|read (fast)| Server")',
      'Client --o|read (fast)| Server',
    ],
    ['pie title', 'pie\ntitle Sales; total\n"North (Q1)" : 42', 'Sales; total'],
    ['xy category', 'xychart-beta\ntitle "Revenue (Q1)"\nx-axis ["Jan; 1"]\nbar [20]', 'Jan; 1'],
    [
      'quadrant point',
      'quadrantChart\ntitle Impact; cost\nx-axis "Low (cost)" --> "High cost"\ny-axis "Low value" --> "High value"\nquadrant-1 "Plan (now)"\nquadrant-2 "Try"\nquadrant-3 "Skip"\nquadrant-4 "Later"\n"Item; A": [0.3, 0.7]',
      'Item; A',
    ],
    [
      'journey section',
      'journey\nsection Arrival: early\nFind door; enter: 4: Guide',
      'Arrival: early',
    ],
  ])(
    'renders independently checked %s text without wrapper quotes',
    async (_name, source, visible) => {
      const result = recognize(source)
      expect(result.ok, result.ok ? '' : result.error).toBe(true)
      if (!result.ok) throw new Error(result.error)
      const text = await renderText(result.renderText, `independent-${_name.replaceAll(' ', '-')}`)
      expect(text).toContain(visible)
      expect(text).not.toContain(`"${visible}"`)
    },
  )

  it.each([
    [
      'sequence alias',
      'sequenceDiagram\nparticipant A as "Alice (team)"\nA->>B: hello',
      ['Alice (team)'],
    ],
    ['sequence message', 'sequenceDiagram\nA->>B: "hello (team)"', ['hello (team)']],
    ['sequence note', 'sequenceDiagram\nA->>B: hello\nNote over A: "note (team)"', ['note (team)']],
    ['sequence block', 'sequenceDiagram\nalt "ready (now)"\nA->>B: hello\nend', ['ready (now)']],
    [
      'journey',
      'journey\ntitle "Visit (shop)"\nsection "Arrival (early)"\n"Find (door)": 4: "User (A)"',
      ['Visit (shop)', 'Arrival (early)', 'Find (door)', 'User (A)'],
    ],
    ['state transition', 'stateDiagram-v2\n[*] --> A\nA --> [*] : "done (now)"', ['done (now)']],
    ['state note', 'stateDiagram-v2\n[*] --> A\nnote right of A : "note (now)"', ['note (now)']],
  ])('renders wrapper-quoted %s without visible quotes', async (name, source, labels) => {
    const result = recognize(source)
    expect(result.ok, result.ok ? '' : result.error).toBe(true)
    if (!result.ok) throw new Error(result.error)
    const text = await renderText(result.renderText, `wrapper-${name.replaceAll(' ', '-')}`)
    for (const label of labels) {
      expect(text).toContain(label)
      expect(text).not.toContain(`"${label}"`)
    }
  })

  it.each([
    ['bare database', 'DB[(PostgreSQL)]', 'DB[("PostgreSQL")]', 'PostgreSQL'],
    ['database target', 'REP --> DB[(PostgreSQL)]', 'REP --> DB[("PostgreSQL")]', 'PostgreSQL'],
    [
      'bare subgraph',
      'subgraph External\nA\nend',
      'subgraph External["External"]\nA\nend',
      'External',
    ],
    [
      'subgraph title',
      'subgraph API[API Layer]\nA\nend',
      'subgraph API["API Layer"]\nA\nend',
      'API Layer',
    ],
    [
      'implicit subgraph title',
      'subgraph External Services\nA\nend',
      'subgraph "External Services"\nA\nend',
      'External Services',
    ],
    [
      'quoted implicit title',
      'subgraph "External Services"\nA\nend',
      'subgraph "External Services"\nA\nend',
      'External Services',
    ],
    ['square', 'A[API Layer]', 'A["API Layer"]', 'API Layer'],
    ['round', 'A(API Layer)', 'A("API Layer")', 'API Layer'],
    ['stadium', 'A([Start])', 'A(["Start"])', 'Start'],
    ['subroutine', 'A[[Routine]]', 'A[["Routine"]]', 'Routine'],
    ['circle', 'A((Circle))', 'A(("Circle"))', 'Circle'],
    ['double circle', 'A(((Circle)))', 'A((("Circle")))', 'Circle'],
    ['rhombus', 'A{Decision}', 'A{"Decision"}', 'Decision'],
    ['hexagon', 'A{{Prepare}}', 'A{{"Prepare"}}', 'Prepare'],
    ['asymmetric', 'A>Asymmetric]', 'A>"Asymmetric"]', 'Asymmetric'],
    ['parallelogram', 'A[/Data/]', 'A[/"Data"/]', 'Data'],
    ['reverse parallelogram', 'A[\\Data\\]', 'A[\\"Data"\\]', 'Data'],
    ['trapezoid', 'A[/Shape\\]', 'A[/"Shape"\\]', 'Shape'],
    ['reverse trapezoid', 'A[\\Shape/]', 'A[\\"Shape"/]', 'Shape'],
    ['bare edge label', 'A-->|Read records|B', 'A -->|"Read records"| B', 'Read records'],
    ['chain', 'A-->B-->C', 'A --> B --> C', 'C'],
    ['long circle', 'A---oB', 'A ---o B', 'B'],
    ['long cross', 'A---xB', 'A ---x B', 'B'],
    ['thick circle', 'A===oB', 'A ===o B', 'B'],
    ['thick cross', 'A===xB', 'A ===x B', 'B'],
    ['dotted circle', 'A-.-oB', 'A -.-o B', 'B'],
    ['dotted cross', 'A-.-xB', 'A -.-x B', 'B'],
  ])(
    'real Mermaid parses and renders source and canonical form: %s',
    async (name, source, canonical, visible) => {
      const diagram = `flowchart LR\n${source}`
      const expected = `flowchart LR\n${canonical}`
      const result = recognize(diagram)
      expect(result.ok, result.ok ? '' : result.error).toBe(true)
      if (!result.ok) throw new Error(result.error)
      expect(result.source).toBe(diagram)
      expect(result.renderText).toBe(expected)
      const second = recognize(result.renderText)
      expect(second.ok, second.ok ? '' : second.error).toBe(true)
      if (!second.ok) throw new Error(second.error)
      expect(second.renderText).toBe(expected)
      expect(await flowTopology(expected)).toEqual(await flowTopology(diagram))
      const rawText = await renderText(diagram, `raw-flow-${name.replaceAll(' ', '-')}`)
      const canonicalText = await renderText(
        expected,
        `canonical-flow-${name.replaceAll(' ', '-')}`,
      )
      expect(rawText).toContain(visible)
      expect(canonicalText).toContain(visible)
    },
  )

  it('renders directive-like bare label text without an executable link', async () => {
    const source = 'flowchart LR\nA[Start; click B] --> B[Ordinary]'
    const result = recognize(source)
    expect(result.ok, result.ok ? '' : result.error).toBe(true)
    if (!result.ok) throw new Error(result.error)
    expect(result.source).toBe(source)
    expect(result.renderText).toContain('Start#59; click B')
    const { svg } = await mermaid.render('flow-literal-click', result.renderText)
    const container = document.createElement('div')
    container.innerHTML = svg
    expect(container.textContent).toContain('Start; click B')
    expect(container.querySelector('script, a')).toBeNull()
    for (const element of container.querySelectorAll('*')) {
      expect([...element.attributes].some(({ name }) => /^on/i.test(name))).toBe(false)
    }
  })

  it('renders balanced participant activation', async () => {
    const result = recognize(
      'sequenceDiagram\nA->>B: begin\nactivate A\nA->>B: working\ndeactivate A',
    )
    expect(result.ok, result.ok ? '' : result.error).toBe(true)
    if (!result.ok) throw new Error(result.error)
    const text = await renderText(result.renderText, 'balanced-activation')
    expect(text).toContain('working')
  })

  it.each([
    ['inactive participant', 'sequenceDiagram\ndeactivate A'],
    ['inline inactive participant', 'sequenceDiagram\nA->>-B: hello'],
    [
      'repeated deactivation',
      'sequenceDiagram\nA->>B: hello\nactivate A\ndeactivate A\ndeactivate A',
    ],
    ['unknown state note alone', 'stateDiagram-v2\nnote right of A : hello'],
    ['unknown state note', 'stateDiagram-v2\nA --> B\nnote right of C : note'],
    ['reserved sequence identifier', 'sequenceDiagram\nloop->>A: hello'],
    ['reserved flowchart identifier', 'flowchart LR\nend["x"] --> B'],
  ])('rejects %s independently of shared fixtures', (_name, source) => {
    expect(recognize(source).ok).toBe(false)
  })

  it('preserves C4 dynamic raw relation text in Mermaid', async () => {
    const text = await renderText(
      'C4Dynamic\nPerson(user, "User")\nSystem(app, "App")\nRel(user, app, "calls; async", "HTTPS")',
      'c4-dynamic-raw',
    )
    expect(text).toContain('calls; async')
    expect(text).not.toContain('"calls; async"')
  })

  it('rejects malformed Mermaid at the real parser', async () => {
    await expect(mermaid.parse('flowchart TD\nA -->')).rejects.toThrow()
  })

  it('exposes quote leakage in bare journey titles', async () => {
    const text = await renderText(
      'journey\ntitle "Visible title"\nsection Group\nTask: 3: Actor',
      'journey-quote-negative',
    )
    expect(text).toContain('"Visible title"')
  })
})
