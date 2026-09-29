import { toast } from 'sonner'

export async function copyToClipboard(text: string, successMessage = 'Copied to clipboard') {
  await navigator.clipboard.writeText(text)
  toast.success(successMessage)
}
