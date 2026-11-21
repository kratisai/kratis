import { describe, expect, it } from 'vitest'

import { isSafeHttpUrl } from '@/lib/utils'

describe('isSafeHttpUrl', () => {
  it('accepts valid https and http URLs', () => {
    expect(isSafeHttpUrl('https://github.com/org/repo/pull/42')).toBe(true)
    expect(isSafeHttpUrl('http://localhost:3000/pr/1')).toBe(true)
    expect(isSafeHttpUrl('https://gitlab.example.com/group/proj/-/merge_requests/12')).toBe(true)
  })

  it('rejects unsafe schemes', () => {
    expect(isSafeHttpUrl('javascript:alert(1)')).toBe(false)
    expect(isSafeHttpUrl('data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==')).toBe(
      false,
    )
    expect(isSafeHttpUrl('file:///etc/passwd')).toBe(false)
    expect(isSafeHttpUrl('vbscript:msgbox')).toBe(false)
  })

  it('rejects relative URLs and malformed strings', () => {
    expect(isSafeHttpUrl('/pull/42')).toBe(false)
    expect(isSafeHttpUrl('pull/42')).toBe(false)
    expect(isSafeHttpUrl('')).toBe(false)
    expect(isSafeHttpUrl(undefined)).toBe(false)
    expect(isSafeHttpUrl(null)).toBe(false)
    expect(isSafeHttpUrl('not a url')).toBe(false)
  })
})
