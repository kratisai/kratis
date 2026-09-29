/// <reference types="node" />

import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const WEB_ROOT = process.cwd()
const SRC_DIR = join(WEB_ROOT, 'src')
const ID_HELPER = join(SRC_DIR, 'lib', 'id.ts')

function* sourceFiles(dir: string): Generator<string> {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      yield* sourceFiles(full)
    } else if (/\.tsx?$/.test(entry)) {
      yield full
    }
  }
}

describe('secure-context-only Web Crypto APIs', () => {
  it('crypto.randomUUID is only used inside the uuid() helper', () => {
    const offenders: string[] = []

    for (const file of sourceFiles(SRC_DIR)) {
      if (file === ID_HELPER || /\.test\.tsx?$/.test(file)) continue
      if (readFileSync(file, 'utf8').includes('crypto.randomUUID')) {
        offenders.push(file)
      }
    }

    expect(
      offenders,
      `crypto.randomUUID() throws outside secure contexts (plain-HTTP LAN on a phone); use uuid() from @/lib/id instead. Offending files: ${offenders.join(', ')}`,
    ).toEqual([])
  })
})
