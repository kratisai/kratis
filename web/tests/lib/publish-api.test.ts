import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { downloadPatchFile } from '@/lib/publish-api'
import { useAuthStore } from '@/store/auth-store'

describe('downloadPatchFile', () => {
  const originalCreateObjectURL = URL.createObjectURL
  const originalRevokeObjectURL = URL.revokeObjectURL

  beforeEach(() => {
    useAuthStore.setState({ accessToken: 'test-token' })
    URL.createObjectURL = vi.fn(() => 'blob:mock')
    URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })

  afterEach(() => {
    URL.createObjectURL = originalCreateObjectURL
    URL.revokeObjectURL = originalRevokeObjectURL
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    useAuthStore.setState({ accessToken: null })
  })

  it('downloads the patch from the export endpoint without a baseBranch param', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('diff --git a/f.ts b/f.ts', { status: 200 })),
    )

    await downloadPatchFile('chat-1', 'exec-1')

    expect(fetch).toHaveBeenCalledWith(
      '/api/v1/chats/chat-1/executions/exec-1/diff/export',
      expect.objectContaining({ headers: expect.objectContaining({ Accept: 'text/plain' }) }),
    )
  })
})
