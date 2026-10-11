import mermaid from 'mermaid'
import { describe, expect, it } from 'vitest'

import { getMermaidConfig } from '@/components/chat/markdown-message'

// jsdom lacks the SVG text metrics mermaid measures during layout.
if (typeof SVGElement !== 'undefined') {
  const proto = SVGElement.prototype as unknown as Record<string, unknown>
  proto.getBBox = function () {
    return { height: 12, width: 80, x: 0, y: 0 }
  }
  proto.getComputedTextLength = function () {
    return 80
  }
}

// Mermaid's base theme hard-codes ER attribute row fills to near-white and
// ignores attributeBackgroundColorOdd/Even theme variables. We override the rows
// in index.css via these class names, so guard them against mermaid drift.
const ER_ATTRIBUTE_ROW_SELECTOR = '.row-rect-odd > path:not([fill="none"])'
const ER_ATTRIBUTE_ROW_EVEN_SELECTOR = '.row-rect-even > path:not([fill="none"])'

const ER = `erDiagram
    EXECUTION_ENVIRONMENT {
        long id PK
        string envId UK
        datetime createdAt
    }`

async function renderEr(isDark: boolean) {
  mermaid.initialize({ securityLevel: 'loose', startOnLoad: false, ...getMermaidConfig(isDark) })
  const { svg } = await mermaid.render(`er-${Math.random().toString(36).slice(2)}`, ER)
  const container = document.createElement('div')
  container.innerHTML = svg
  return container.querySelector('svg') as SVGSVGElement
}

describe('ER diagram attribute rows', () => {
  it('emits the row groups our theme overrides target', async () => {
    const svg = await renderEr(true)
    expect(svg.querySelector(ER_ATTRIBUTE_ROW_SELECTOR)).not.toBeNull()
    expect(svg.querySelector(ER_ATTRIBUTE_ROW_EVEN_SELECTOR)).not.toBeNull()
  })

  it('renders near-white odd rows under the base theme in dark mode', async () => {
    const svg = await renderEr(true)
    const fill = svg.querySelector(ER_ATTRIBUTE_ROW_SELECTOR)?.getAttribute('fill') ?? ''
    // Documents the mermaid behaviour our stylesheet override corrects: the odd
    // row is not themeable via themeVariables and stays near-white.
    const lightness = Number(/(\d+(?:\.\d+)?)%\s*\)?$/.exec(fill)?.[1] ?? Number.NaN)
    expect(lightness).toBeGreaterThan(80)
  })
})
