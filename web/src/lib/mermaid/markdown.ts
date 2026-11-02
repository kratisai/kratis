import { recognize } from './index'

interface FenceLine {
  content: string
  eol: string
}

interface OpenFence {
  body: FenceLine[]
  candidate: boolean
  eol: string
  length: number
  marker: string
}

const OPENING_FENCE = /^( {0,3})(`{3,}|~{3,})([^\r\n]*)$/
const CLOSING_FENCE = /^ {0,3}(`{3,}|~{3,})[ \t]*$/
const LIST_MARKER = /^( {0,3})([-+*]|\d+[.)])([ \t]+)/

export function canonicalizeMermaidMarkdown(markdown: string): string {
  const output: string[] = []
  const lists: number[] = []
  let open: null | OpenFence = null

  for (const line of splitLines(markdown)) {
    if (open) {
      const closing = CLOSING_FENCE.exec(line.content)
      if (closing && closing[1][0] === open.marker && closing[1].length >= open.length) {
        output.push(...renderBody(open))
        output.push(line.content + line.eol)
        open = null
      } else {
        open.body.push(line)
      }
      continue
    }

    const indent = indentation(line.content)
    const list = LIST_MARKER.exec(line.content)
    if (line.content.trim() !== '') {
      while (lists.length > 0 && indent < lists.at(-1)!) lists.pop()
    }
    if (list) {
      const contentIndent = list[1].length + list[2].length + list[3].replace(/\t/g, '    ').length
      while (lists.length > 0 && contentIndent <= lists.at(-1)!) lists.pop()
      lists.push(contentIndent)
    }

    const fenceLine = list ? line.content.slice(list[0].length) : line.content
    const opening = OPENING_FENCE.exec(fenceLine)
    if (!opening) {
      output.push(line.content + line.eol)
      continue
    }
    const marker = opening[2][0]
    const info = opening[3].trim().split(/\s+/)[0] ?? ''
    if (marker === '`' && opening[3].includes('`')) {
      output.push(line.content + line.eol)
      continue
    }
    output.push(line.content + line.eol)
    open = {
      body: [],
      candidate: info === 'mermaid' && list === null && lists.length === 0,
      eol: line.eol,
      length: opening[2].length,
      marker,
    }
  }

  if (open) output.push(...open.body.map((line) => line.content + line.eol))
  return output.join('')
}

function indentation(value: string): number {
  let width = 0
  for (const character of value) {
    if (character === ' ') width++
    else if (character === '\t') width += 4 - (width % 4)
    else break
  }
  return width
}

function renderBody(open: OpenFence): string[] {
  if (!open.candidate || open.body.length === 0)
    return open.body.map((line) => line.content + line.eol)

  const eol = open.body.find((line) => line.eol)?.eol ?? (open.eol || '\n')
  const source = open.body.map((line) => line.content).join('\n')
  const recognition = recognize(source)
  if (!recognition.ok) return open.body.map((line) => line.content + line.eol)

  const indents = open.body
    .filter((line) => line.content.trim() !== '')
    .map((line) => indentation(line.content))
  const prefix = ' '.repeat(indents.length === 0 ? 0 : Math.min(...indents))
  const replacement = recognition.renderText
    .split('\n')
    .map((line) => (line === '' ? line : prefix + line))
    .join(eol)
  return [`${replacement}${open.body.at(-1)?.eol ? eol : ''}`]
}

function splitLines(value: string): FenceLine[] {
  const lines: FenceLine[] = []
  let start = 0
  while (start < value.length) {
    const newline = value.indexOf('\n', start)
    if (newline < 0) {
      lines.push({ content: value.slice(start), eol: '' })
      break
    }
    const crlf = newline > start && value[newline - 1] === '\r'
    lines.push({
      content: value.slice(start, crlf ? newline - 1 : newline),
      eol: crlf ? '\r\n' : '\n',
    })
    start = newline + 1
  }
  return lines
}
