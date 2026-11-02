import mermaid from 'mermaid'
import { beforeAll, expect, test } from 'vitest'

import { recognize } from '@/lib/mermaid'

beforeAll(() => {
  mermaid.initialize({ securityLevel: 'loose', startOnLoad: false })
})

test('Mermaid rejects reserved identifiers and invalid numeric inputs', async () => {
  for (const source of [
    'flowchart\nend["x"] --> B',
    'sequenceDiagram\nloop->>A: x',
    'sequenceDiagram\ndeactivate A',
    'classDiagram\nclass style\nstyle --> A',
    'stateDiagram-v2\n[*] --> click',
    'erDiagram\nsubgraph ||--o{ A : "x"',
    'pie\n"A" : .5',
  ]) {
    expect(recognize(source).ok, source).toBe(false)
    await expect(mermaid.parse(source), source).rejects.toThrow()
  }
})

test('Mermaid parses but cannot render unknown C4 endpoints and state notes', async () => {
  for (const [index, source] of [
    'C4Context\nPerson(a, "A")\nRel(a, missing, "calls")',
    'stateDiagram-v2\nnote right of A : x',
  ].entries()) {
    expect(recognize(source).ok, source).toBe(false)
    expect(await mermaid.parse(source), source).not.toBe(false)
    await expect(mermaid.render(`negative-${index}`, source), source).rejects.toThrow()
  }
})
