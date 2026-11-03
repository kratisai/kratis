import { toast } from 'sonner'

export async function copyToClipboard(
  text: string,
  successMessage = 'Copied to clipboard',
): Promise<boolean> {
  if (!(await writeText(text))) {
    toast.error('Failed to copy to clipboard')
    return false
  }
  toast.success(successMessage)
  return true
}

function legacyWriteText(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '0'
  textarea.style.opacity = '0'
  document.body.append(textarea)
  textarea.select()
  textarea.setSelectionRange(0, text.length)
  try {
    return typeof document.execCommand === 'function' && document.execCommand('copy')
  } finally {
    textarea.remove()
  }
}

async function writeText(text: string): Promise<boolean> {
  // Insecure contexts (plain HTTP, non-localhost) do not expose navigator.clipboard.
  const clipboard = navigator.clipboard as Clipboard | undefined
  if (clipboard && typeof clipboard.writeText === 'function') {
    try {
      await clipboard.writeText(text)
      return true
    } catch {
      // Fall through to the legacy path when the modern API is blocked.
    }
  }
  return legacyWriteText(text)
}
