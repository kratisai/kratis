import { describe, expect, it } from 'vitest'

import { canonicalizeMermaidMarkdown } from '@/lib/mermaid/markdown'

describe('canonicalizeMermaidMarkdown', () => {
  it('replaces a recognized diagram body with its render text', () => {
    const source = '# Diagram\n\n```mermaid\nflowchart LR\n  A["Start & go"] --> B\n```\n\nAfter.'
    expect(canonicalizeMermaidMarkdown(source)).toBe(
      '# Diagram\n\n```mermaid\nflowchart LR\nA["Start #38; go"] --> B\n```\n\nAfter.',
    )
  })

  it('canonicalizes every recognized top-level block and leaves the rest intact', () => {
    const source = [
      'Intro',
      '',
      '```mermaid',
      'flowchart TD',
      'A["One & two"] --> B',
      '```',
      '',
      'Middle',
      '',
      '~~~mermaid title',
      'sequenceDiagram',
      'A->>B: hello; world',
      '~~~',
      '',
      'End',
    ].join('\n')
    expect(canonicalizeMermaidMarkdown(source)).toBe(
      [
        'Intro',
        '',
        '```mermaid',
        'flowchart TD',
        'A["One #38; two"] --> B',
        '```',
        '',
        'Middle',
        '',
        '~~~mermaid title',
        'sequenceDiagram',
        'A->>B: hello#59; world',
        '~~~',
        '',
        'End',
      ].join('\n'),
    )
  })

  it('leaves malformed and incomplete blocks unchanged', () => {
    const malformed = 'flowchart LR\nA --> B\nclick A "https://example.com"'
    const source = `\`\`\`mermaid\n${malformed}\n\`\`\`\n\n\`\`\`mermaid\nflowchart TD\nA --> B`

    expect(canonicalizeMermaidMarkdown(source)).toBe(source)
  })

  it('canonicalizes valid blocks while leaving malformed blocks unchanged', () => {
    const malformed = 'flowchart LR\nA --> B\nclick A "https://example.com"'
    const source = `\`\`\`mermaid\nflowchart TD\nA["One & two"] --> B\n\`\`\`\n\n\`\`\`mermaid\n${malformed}\n\`\`\``
    expect(canonicalizeMermaidMarkdown(source)).toBe(
      `\`\`\`mermaid\nflowchart TD\nA["One #38; two"] --> B\n\`\`\`\n\n\`\`\`mermaid\n${malformed}\n\`\`\``,
    )
  })

  it('canonicalizes a top-level diagram indented up to three spaces and preserves its body indentation', () => {
    const source = '  ```mermaid\n  flowchart TD\n  A["One & two"] --> B\n  ```'
    expect(canonicalizeMermaidMarkdown(source)).toBe(
      '  ```mermaid\n  flowchart TD\n  A["One #38; two"] --> B\n  ```',
    )
  })

  it('ignores Mermaid fences nested in lists and blockquotes', () => {
    const list = '- item\n  ```mermaid\n  flowchart TD\n  A["One & two"] --> B\n  ```'
    const wideList =
      '-  item\n    ```mermaid\n    flowchart TD\n    A["One & two"] --> B\n    ```'
    const inlineList = '- ```mermaid\n  flowchart TD\n  A["One & two"] --> B\n  ```'
    const quote = '> ```mermaid\n> flowchart TD\n> A["One & two"] --> B\n> ```'

    expect(canonicalizeMermaidMarkdown(list)).toBe(list)
    expect(canonicalizeMermaidMarkdown(wideList)).toBe(wideList)
    expect(canonicalizeMermaidMarkdown(inlineList)).toBe(inlineList)
    expect(canonicalizeMermaidMarkdown(quote)).toBe(quote)
  })

  it('ignores Mermaid-looking text inside another fenced block', () => {
    const source = '```text\n```mermaid\nflowchart TD\nA["One & two"] --> B\n```\n```'
    expect(canonicalizeMermaidMarkdown(source)).toBe(source)
  })

  it('preserves CRLF line endings around and inside a diagram', () => {
    const source =
      '# Title\r\n\r\n```mermaid\r\nflowchart TD\r\nA["Café"] --> B\r\n```\r\n\r\nEnd\r\n'
    expect(canonicalizeMermaidMarkdown(source)).toBe(
      '# Title\r\n\r\n```mermaid\r\nflowchart TD\r\nA["Caf#233;"] --> B\r\n```\r\n\r\nEnd\r\n',
    )
  })

  it('canonicalizes Unicode labels', () => {
    const source = '```mermaid\nflowchart LR\nA["café 🚀"] --> B\n```'
    expect(canonicalizeMermaidMarkdown(source)).toBe(
      '```mermaid\nflowchart LR\nA["caf#233; #128640;"] --> B\n```',
    )
  })

  it('returns Markdown without Mermaid fences unchanged', () => {
    const source = '# Hello\n\n- one\n- two\n'
    expect(canonicalizeMermaidMarkdown(source)).toBe(source)
  })
})
