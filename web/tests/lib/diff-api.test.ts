import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchDiffSummary, fetchFileDiff, fetchFileSlice } from '@/lib/diff-api'
import { useAuthStore } from '@/store/auth-store'

describe('diff-api', () => {
  beforeEach(() => {
    useAuthStore.setState({ accessToken: 'test-token' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    useAuthStore.setState({ accessToken: null })
  })

  it('fetches the diff summary without a baseBranch query param', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ files: [] }), { status: 200 })),
    )

    await fetchDiffSummary('chat-1', 'exec-1')

    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/chats/chat-1/executions/exec-1/diff/summary',
      expect.any(Object),
    )
  })

  it('fetches a file diff with only the path param', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ patch: '' }), { status: 200 })),
    )

    await fetchFileDiff('chat-1', 'exec-1', 'src/a.ts')

    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/chats/chat-1/executions/exec-1/diff/file?path=src%2Fa.ts',
      expect.any(Object),
    )
  })

  it('fetches a file slice with start/end params', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ lines: [] }), { status: 200 })),
    )

    await fetchFileSlice('chat-1', 'exec-1', 'src/a.ts', 1, 10)

    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/diff/context?endLine=10&path=src%2Fa.ts&startLine=1'),
      expect.any(Object),
    )
  })
})
