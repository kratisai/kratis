const FENCE_RE = /^\s*(`{3,}|~{3,})[^\n]*$/gm
const INLINE_CODE_RE = /`([^`]+)`/g
const IMAGE_RE = /!\[([^\]]*)\]\([^)]*\)/g
const LINK_RE = /\[([^\]]+)\]\([^)]*\)/g
const HEADING_RE = /^\s{0,3}#{1,6}\s+/gm
const QUOTE_RE = /^\s*>\s?/gm
const BULLET_RE = /^\s*[-*+]\s+/gm
const ORDERED_RE = /^\s*\d+[.)]\s+/gm
const HR_RE = /^\s*([-*_])(?:\s*\1){2,}\s*$/gm
// Left boundary is captured instead of a lookbehind: lookbehind is a parse-time
// SyntaxError on Safari < 16.4 and Vite/esbuild does not transpile regex literals.
const UNDERSCORE_EMPHASIS_RE = /(^|[^\w])_{1,3}([^_\n]+)_{1,3}(?!\w)/g
const ASTERISK_EMPHASIS_RE = /\*{1,3}([^*\n]+)\*{1,3}/g
const STRIKE_RE = /~{1,2}([^~\n]+)~{1,2}/g

/** Collapses inline markdown to a single line for activity summaries. */
export function markdownToText(markdown: string): string {
  return markdown
    .replace(FENCE_RE, ' ')
    .replace(INLINE_CODE_RE, '$1')
    .replace(IMAGE_RE, '$1')
    .replace(LINK_RE, '$1')
    .replace(HEADING_RE, '')
    .replace(QUOTE_RE, '')
    .replace(BULLET_RE, '')
    .replace(ORDERED_RE, '')
    .replace(HR_RE, ' ')
    .replace(UNDERSCORE_EMPHASIS_RE, '$1$2')
    .replace(ASTERISK_EMPHASIS_RE, '$1')
    .replace(STRIKE_RE, '$1')
    .replace(/\s+/g, ' ')
    .trim()
}
