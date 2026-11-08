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

describe('shared Mermaid grammar integrity and coverage', () => {
  it('parses every production rule body', () => {
    const rules = parsedProductionRules()
    expect(rules.length).toBeGreaterThan(0)
    for (const rule of rules) {
      expect(rule.name).toBeTruthy()
      expect(rule.rhs).toBeTruthy()
    }
  })

  it('resolves every production nonterminal reference', () => {
    const rules = parsedProductionRules()
    const violations: string[] = []
    const commonNames = new Set(
      rules.filter((rule) => rule.scope === 'common').map((rule) => rule.name),
    )
    const c4Names = new Set(
      rules.filter((rule) => rule.scope === 'c4').map((rule) => rule.name),
    )
    const scopes = [...new Set(rules.map((rule) => rule.scope))].filter(
      (scope) => scope !== 'common',
    )
    for (const scope of scopes) {
      const defined = new Set(
        rules.filter((rule) => rule.scope === scope).map((rule) => rule.name),
      )
      for (const name of commonNames) defined.add(name)
      if (scope.startsWith('C4') || scope === 'c4') {
        for (const name of c4Names) defined.add(name)
      }
      for (const rule of rules.filter((other) => other.scope === scope)) {
        for (const token of productionNonTerminalReferences(rule.rhs)) {
          if (!defined.has(token)) {
            violations.push(`${scope}.${rule.name} references undefined ${token}`)
          }
        }
      }
    }
    expect(violations).toEqual([])
  })

  it('covers every production with a conformance case', () => {
    const caseNames = new Set(cases.map((entry) => entry.name))
    const productionKeys = parsedProductionRules().map((rule) => `${rule.scope}.${rule.name}`)
    const missing = productionKeys.filter((key) => !productionCoverage[key])
    expect(missing).toEqual([])
    for (const [ruleKey, caseName] of Object.entries(productionCoverage)) {
      expect(caseNames.has(caseName), `${ruleKey} -> ${caseName}`).toBe(true)
    }
  })
})

function parsedProductionRules(): { name: string; rhs: string; scope: string }[] {
  return Object.entries(grammar.productions).flatMap(([scope, rules]) =>
    rules.map((rule) => {
      const divider = rule.indexOf(' = ')
      if (divider < 0) throw new Error(`Invalid production: ${rule}`)
      return {
        name: rule.slice(0, divider).trim(),
        rhs: rule.slice(divider + 3),
        scope,
      }
    }),
  )
}

const PRODUCTION_PRIMITIVES = new Set([
  'annotation',
  'bare-member',
  'c4raw',
  'digit',
  'digits',
  'double-quote',
  'flow-id',
  'integer',
  'letter',
  'newline',
  'nonempty-text-without-double-quote',
  'nonzero-digit',
  'optional-space',
  'shape-close',
  'shape-open',
  'task-text',
  'title-with-spaces',
  'unwrapped-text',
])

const PRODUCTION_PROSE_WORDS = new Set([
  'a', 'arrows', 'flowchart', 'flowchartSyntax', 'from', 'of', 'token', 'yntax',
])

const STRUCTURAL_WORDS = new Set(['direction', 'role'])

const NONTERMINAL = /[a-z][a-z0-9-]*/g

function productionNonTerminalReferences(rhs: string): string[] {
  const withoutQuotedTerminals = rhs.replaceAll(/'[^']*'/g, ' ')
  const tokens: string[] = []
  for (const token of withoutQuotedTerminals.matchAll(NONTERMINAL)) {
    const next = token[0]
    if (PRODUCTION_PRIMITIVES.has(next) || STRUCTURAL_WORDS.has(next) || PRODUCTION_PROSE_WORDS.has(next)) continue
    tokens.push(next)
  }
  return tokens
}

const productionCoverage: Record<string, string> = {
  'c4.boundary': 'C4 container boundary',
  'c4.c4-statement': 'C4 context',
  'c4.element': 'C4 context',
  'c4.relation': 'C4 forward relation',
  'C4Component.statement': 'C4 component boundary',
  'C4Container.statement': 'C4 container boundary',
  'C4Context.statement': 'C4 context',
  'classDiagram.arrow': 'class reverse relations',
  'classDiagram.class-id': 'class dotted name',
  'classDiagram.statement': 'class members and relationship',
  'common.bare': 'flow unquoted HTML',
  'common.coordinate': 'quadrant coordinates',
  'common.diagram': 'flowchart common source forms',
  'common.id': 'flow numeric and case-sensitive identifiers',
  'common.number': 'pie punctuation',
  'common.quoted': 'flowchart classic shapes',
  'erDiagram.attribute-type': 'ER attributes',
  'erDiagram.cardinality': 'ER bare relation and parameterized types',
  'erDiagram.key': 'er composite keys with whitespace',
  'erDiagram.name': 'ER bare relation and parameterized types',
  'erDiagram.statement': 'ER bare relation and parameterized types',
  'flowchart.arrow': 'flowchart dotted arrow tokens',
  'flowchart.direction': 'flowchart lowercase direction',
  'flowchart.edge-label': 'flowchart common source forms',
  'flowchart.header': 'flowchart common source forms',
  'flowchart.inline-dotted-close': 'flow inline edge label dotted',
  'flowchart.inline-dotted-edge': 'flow inline edge label dotted',
  'flowchart.inline-edge': 'flow inline edge label solid',
  'flowchart.inline-solid-close': 'flow inline edge label solid',
  'flowchart.inline-solid-edge': 'flow inline edge label solid',
  'flowchart.inline-thick-close': 'flow inline edge label thick',
  'flowchart.inline-thick-edge': 'flow inline edge label thick',
  'flowchart.node': 'flowchart classic shapes',
  'flowchart.node-group': 'flow ampersand source node grouping',
  'flowchart.statement': 'flowchart common source forms',
  'flowchart.subgraph-title': 'flowchart common source forms',
  'flowchart.unquoted-label': 'flowchart classic shapes',
  'journey.header': 'journey inline header title',
  'journey.statement': 'journey sections',
  'pie.header': 'pie inline header title',
  'pie.statement': 'pie punctuation',
  'quadrantChart.statement': 'quadrant coordinates',
  'sequenceDiagram.arrow': 'sequence all supported arrows',
  'sequenceDiagram.sequence-name': 'sequence authoring wrappers',
  'sequenceDiagram.statement': 'sequence blocks',
  'stateDiagram-v2.number-start-id': 'state numeric identifier',
  'stateDiagram-v2.state-display': 'state spaced identifier',
  'stateDiagram-v2.state-display-bare': 'state spaced identifier',
  'stateDiagram-v2.state-id': 'state numeric identifier',
  'stateDiagram-v2.state-ref': 'state standalone declaration',
  'stateDiagram-v2.statement': 'state composite accepted',
  'xychart-beta.statement': 'xy categories',
}

