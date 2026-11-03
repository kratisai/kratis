import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}))

import { toast } from 'sonner'

import { copyToClipboard } from '@/lib/clipboard'

describe('copyToClipboard', () => {
  let writeText: ReturnType<typeof vi.fn>
  let execCommand: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.clearAllMocks()
    writeText = vi.fn().mockResolvedValue(undefined)
    execCommand = vi.fn().mockReturnValue(true)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommand,
    })
  })

  afterEach(() => {
    Reflect.deleteProperty(navigator, 'clipboard')
    Reflect.deleteProperty(document, 'execCommand')
  })

  it('writes the given text to the clipboard and shows the default success toast', async () => {
    expect(await copyToClipboard('# Hello world')).toBe(true)

    expect(writeText).toHaveBeenCalledWith('# Hello world')
    expect(toast.success).toHaveBeenCalledWith('Copied to clipboard')
  })

  it('shows a custom success message when provided', async () => {
    await copyToClipboard('some text', 'Public key copied to clipboard')

    expect(toast.success).toHaveBeenCalledWith('Public key copied to clipboard')
  })

  it('falls back to a hidden textarea when the clipboard API is unavailable', async () => {
    Reflect.deleteProperty(navigator, 'clipboard')

    expect(await copyToClipboard('text')).toBe(true)

    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(toast.success).toHaveBeenCalledWith('Copied to clipboard')
    expect(document.querySelector('textarea')).not.toBeInTheDocument()
  })

  it('falls back to a hidden textarea when the clipboard API rejects', async () => {
    writeText.mockRejectedValue(new Error('denied'))

    expect(await copyToClipboard('text')).toBe(true)

    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(toast.success).toHaveBeenCalledWith('Copied to clipboard')
  })

  it('reports a failure when neither clipboard mechanism works', async () => {
    Reflect.deleteProperty(navigator, 'clipboard')
    execCommand.mockReturnValue(false)

    expect(await copyToClipboard('text')).toBe(false)

    expect(toast.error).toHaveBeenCalledWith('Failed to copy to clipboard')
    expect(toast.success).not.toHaveBeenCalled()
    expect(document.querySelector('textarea')).not.toBeInTheDocument()
  })
})
