/**
 * Formats a model context window token count for compact display.
 * Examples: 1048576 -> "1M", 128000 -> "128K", 8192 -> "8K".
 */
export function formatContextWindow(tokens: number): string {
  if (!Number.isFinite(tokens) || tokens <= 0) return ''
  if (tokens >= 1_000_000) {
    const millions = Math.round((tokens / 1_000_000) * 10) / 10
    return `${millions}M`
  }
  if (tokens >= 1_000) {
    return `${Math.round(tokens / 1_000)}K`
  }
  return `${tokens}`
}
