import mermaid from 'mermaid'
import { beforeAll, describe, expect, it } from 'vitest'

import { recognize } from '@/lib/mermaid'

import cases from '../../../../protocol/mermaid-grammar/cases.json'
import grammar from '../../../../protocol/mermaid-grammar/grammar.json'
import rules from '../../../../protocol/mermaid-grammar/rules.json'
import { invalidFixtures, validFixtures } from '../../support/test-mermaid-fixtures'

beforeAll(() => {
  mermaid.initialize({ securityLevel: 'loose', startOnLoad: false })
})

describe('shared Mermaid grammar contract', () => {
  for (const fixture of cases) {
    it(`conforms to ${fixture.name}`, async () => {
      const result = recognize(fixture.source)
      expect(result.ok).toBe(fixture.ok)
      if (fixture.ok) {
        if (!result.ok) throw new Error(result.error)
        expect(result.type).toBe(fixture.type)
        expect(result.source).toBe(fixture.source)
        expect(result.renderText).toBe(fixture.renderText)
        expect(await mermaid.parse(result.renderText)).not.toBe(false)
      } else {
        if (result.ok) throw new Error('Expected rejection')
        expect(result.rule).toBe(fixture.rule)
        expect(result.error).toBeTruthy()
      }
    })
  }

  it('orders shared flowchart tokens longest before their prefixes', () => {
    const tokenSets = [
      grammar.flowchartSyntax.arrows,
      grammar.flowchartSyntax.nodeShapes.map(({ open }) => open),
    ]
    for (const tokens of tokenSets) {
      expect(new Set(tokens).size).toBe(tokens.length)
      for (const [index, token] of tokens.entries()) {
        for (const prefix of tokens.slice(index + 1)) {
          if (prefix.startsWith(token)) throw new Error(`${prefix} must precede ${token}`)
        }
      }
    }
  })

  it('covers every rule and only emits declared error ids', () => {
    const ids = rules.map((rule) => rule.id)
    const errors = new Set(rules.map((rule) => rule.error))
    expect(new Set(ids).size).toBe(ids.length)
    expect(errors).toEqual(new Set(ids))
    const emitted = new Set<string>()
    for (const fixture of cases) {
      const result = recognize(fixture.source)
      if (!result.ok) {
        expect(errors.has(result.rule), fixture.name).toBe(true)
        emitted.add(result.rule)
      }
    }
    expect(emitted).toEqual(errors)
  })

  it('includes every scanner fixture and supported type', () => {
    const names = cases.map((fixture) => fixture.name)
    expect(new Set(names).size).toBe(names.length)
    for (const fixture of [...validFixtures, ...invalidFixtures]) {
      expect(
        cases.some((entry) => entry.name === fixture.name && entry.source === fixture.source),
      ).toBe(true)
    }
    expect(new Set(cases.filter((entry) => entry.ok).map((entry) => entry.type))).toEqual(
      new Set(Object.keys(grammar.headers)),
    )
  })
})
