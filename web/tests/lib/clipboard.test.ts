import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('sonner', () => ({
  toast: { success: vi.fn() },
}))

import { toast } from 'sonner'

import { copyToClipboard } from '@/lib/clipboard'

describe('copyToClipboard', () => {
  let writeText: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.clearAllMocks()
    writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
  })

  afterEach(() => {
    Reflect.deleteProperty(navigator, 'clipboard')
  })

  it('writes the given text to the clipboard and shows the default success toast', async () => {
    await copyToClipboard('# Hello world')

    expect(writeText).toHaveBeenCalledWith('# Hello world')
    expect(toast.success).toHaveBeenCalledWith('Copied to clipboard')
  })

  it('shows a custom success message when provided', async () => {
    await copyToClipboard('some text', 'Public key copied to clipboard')

    expect(toast.success).toHaveBeenCalledWith('Public key copied to clipboard')
  })

  it('propagates clipboard write failures without showing a toast', async () => {
    writeText.mockRejectedValue(new Error('denied'))

    await expect(copyToClipboard('text')).rejects.toThrow('denied')
    expect(toast.success).not.toHaveBeenCalled()
  })
})
