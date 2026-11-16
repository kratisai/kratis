import type { IngestionBatchLogDto } from './repo-api'

export function downloadIngestionLogsCsv(
  logs: IngestionBatchLogDto[],
  repoName: string,
  batchId: string,
): void {
  const safeRepo = repoName.replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'repo'
  const blob = new Blob([formatIngestionLogsCsv(logs)], {
    type: 'text/csv;charset=utf-8',
  })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `ingestion-logs-${safeRepo}-${batchId}.csv`
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(url)
}

export function formatIngestionLogsCsv(logs: IngestionBatchLogDto[]): string {
  const rows = ['created_at,level,step,message']
  for (const log of logs) {
    rows.push(
      [
        escapeCsvCell(log.createdAt),
        escapeCsvCell(log.level),
        escapeCsvCell(log.step),
        escapeCsvCell(log.message),
      ].join(','),
    )
  }
  return rows.join('\n')
}

function escapeCsvCell(value: string): string {
  const safe = /^[=+\-@\t\r]/.test(value) ? `'${value}` : value
  if (/[",\r\n]/.test(safe)) {
    return `"${safe.replace(/"/g, '""')}"`
  }
  return safe
}
