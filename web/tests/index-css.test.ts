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
