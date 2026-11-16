import { afterEach, describe, expect, it } from 'vitest'

import type { IngestionBatchLogDto } from '@/lib/repo-api'

import { downloadIngestionLogsCsv, formatIngestionLogsCsv } from '@/lib/ingestion-log-export'

import { mockDownloads, restoreDownloads } from '../support/test-download'

const logs: IngestionBatchLogDto[] = [
  {
    batchId: 'batch-1',
    createdAt: '2026-11-14T10:00:00Z',
    id: 'log-1',
    level: 'INFO',
    message: 'Starting ingestion, model: gpt-4o',
    step: 'START',
  },
  {
    batchId: 'batch-1',
    createdAt: '2026-11-14T10:00:05Z',
    id: 'log-2',
    level: 'DEBUG',
    message: 'Excluded "self-call"\nline2',
    step: 'EXTRACT_SURFACES',
  },
]

describe('formatIngestionLogsCsv', () => {
  it('formats header and RFC-4180 escaped rows including DEBUG logs', () => {
    const csv = formatIngestionLogsCsv(logs)

    expect(csv).toBe(
      [
        'created_at,level,step,message',
        '2026-11-14T10:00:00Z,INFO,START,"Starting ingestion, model: gpt-4o"',
        '2026-11-14T10:00:05Z,DEBUG,EXTRACT_SURFACES,"Excluded ""self-call""\nline2"',
      ].join('\n'),
    )
  })
})

describe('downloadIngestionLogsCsv', () => {
  afterEach(() => {
    restoreDownloads()
  })

  it('downloads CSV containing all log levels named after the repo and batch', async () => {
    const { clickedAnchors, createObjectURL, revokeObjectURL } = mockDownloads()

    downloadIngestionLogsCsv(logs, 'glark/BookingService', 'batch-1')

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    const blob = createObjectURL.mock.calls[0][0]
    expect(blob.type).toBe('text/csv;charset=utf-8')
    const content = await blob.text()
    expect(content).toContain('INFO,START')
    expect(content).toContain('DEBUG,EXTRACT_SURFACES')

    expect(clickedAnchors).toHaveLength(1)
    expect(clickedAnchors[0].download).toBe('ingestion-logs-glark-BookingService-batch-1.csv')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-download')
  })
})
