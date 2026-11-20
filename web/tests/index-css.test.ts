import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const indexCss = readFileSync(resolve(import.meta.dirname, '../src/index.css'), 'utf8')

describe('index.css app-shell lock', () => {
  it('locks html, body and #root to the viewport height on desktop (>= md) only', () => {
    expect(indexCss).toMatch(
      /@media \(min-width: 48rem\) \{\s*html,\s*\n\s*body,\s*\n\s*#root\s*\{\s*height:\s*100%/,
    )
  })

  it('prevents the document from scrolling or rubber-banding on desktop only', () => {
    expect(indexCss).toMatch(
      /@media \(min-width: 48rem\) \{[\s\S]*?body \{[^}]*overflow:\s*hidden;/,
    )
    expect(indexCss).toMatch(
      /@media \(min-width: 48rem\) \{[\s\S]*?body \{[^}]*overscroll-behavior:\s*none;/,
    )
  })

  it('lets the document scroll below the md breakpoint so the browser chrome collapses', () => {
    const mobileBlock = indexCss.split('@media (min-width: 48rem)')[0]
    expect(mobileBlock).not.toMatch(/body\s*\{[^}]*overflow:\s*hidden/)
  })
})

describe('index.css prose density', () => {
  function scaleFor(selector: string): number {
    const rule = indexCss.match(new RegExp(`\\.${selector}\\s*\\{[^}]*\\}`))
    if (!rule) throw new Error(`No rule found for .${selector}`)
    const scale = /--md-scale:\s*([\d.]+)/.exec(rule[0])
    if (!scale) throw new Error(`No --md-scale declared for .${selector}`)
    return Number(scale[1])
  }

  it('keeps canvas document prose tighter than editorial wiki prose', () => {
    expect(scaleFor('prose-document')).toBeLessThan(scaleFor('prose-wiki'))
  })

  it('caps chat headings at the former prose-xs heading scale', () => {
    expect(indexCss).toMatch(/\.prose-chat\s*\{[^}]*--md-heading-max-size:\s*1\.4em/s)
  })

  it('uses an activity tone override through shared markdown rules', () => {
    expect(indexCss).toContain('--md-tone-override: currentColor;')
    expect(indexCss).toContain('var(--md-tone-override, var(--md-heading))')
    expect(indexCss).toContain('--md-surface-mix: 6%;')
    expect(indexCss).toContain('--md-pre-max-height: 16em;')
    expect(indexCss).not.toMatch(/\.prose-activity\s+(:where|pre|blockquote|thead)/)
  })

  it('derives font size and rhythm from a single scale multiplier', () => {
    expect(indexCss).toMatch(/\.prose\s*\{[^}]*font-size:\s*calc\(1rem \* var\(--md-scale\)\)/s)
    expect(indexCss).toMatch(/\.prose\s*\{[^}]*line-height:\s*1\.5/s)
  })
})
