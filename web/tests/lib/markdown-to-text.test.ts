import { describe, expect, it } from 'vitest'

import { markdownToText } from '@/lib/markdown-to-text'

describe('markdownToText', () => {
  it('strips headings, emphasis and inline code', () => {
    expect(markdownToText('### Code Review: `abc123`\n\n**Title:** _done_')).toBe(
      'Code Review: abc123 Title: done',
    )
  })

  it('removes list markers, quotes, fences and rules', () => {
    expect(markdownToText('> quoted\n\n- one\n- two\n\n---\n\n```ts\nconst x = 1\n```')).toBe(
      'quoted one two const x = 1',
    )
  })

  it('preserves intraword snake_case while stripping emphasis', () => {
    expect(markdownToText('Update file_name_here and **done**')).toBe(
      'Update file_name_here and done',
    )
  })

  it('strips one- and two-tilde strikethrough', () => {
    expect(markdownToText('~old~ and ~~gone~~')).toBe('old and gone')
  })

  it('keeps link and image labels', () => {
    expect(markdownToText('See [the docs](https://example.com) ![diagram](x.png)')).toBe(
      'See the docs diagram',
    )
  })

  it('collapses whitespace to a single line', () => {
    expect(markdownToText('one\n\n two\tthree  ')).toBe('one two three')
  })
})
