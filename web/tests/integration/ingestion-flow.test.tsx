import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { mockDownloads, restoreDownloads } from '../support/test-download'
import {
  createMockRepository,
  createMockTeam,
} from '../support/test-factories'
import {
  addFetchHandler,
  jsonResponse,
  mockListRepositories,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderWithRouter,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'

describe('Ingestion Flow', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  function mockDrilldownExtras() {
    addFetchHandler((url) => {
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse([])
      }
      if (url.includes('/batches/') && url.includes('/stats')) {
        return jsonResponse(null, 404)
      }
      if (url.includes('/batches')) {
        return jsonResponse([])
      }
      return null
    })
  }

  it('renders "Ask Kratis" on each repository card and does not show Ingest Now', async () => {
    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: null,
        lastIngestedAt: null,
        name: 'my-repo',
      }),
    ])

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('my-repo')).toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: /ask kratis/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /ingest now/i })).not.toBeInTheDocument()
  })

  it('clicking "Ingest Now" on the drilldown calls the API', async () => {
    let ingestCalled = false

    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: null,
        lastIngestedAt: null,
        name: 'my-repo',
      }),
    ])
    mockDrilldownExtras()

    addFetchHandler((url, options) => {
      if (url.includes('/repositories/repo-1/ingest') && options.method === 'POST') {
        ingestCalled = true
        return jsonResponse({ batchId: 'batch-1', status: 'PENDING' }, 202)
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('my-repo')).toBeInTheDocument()
    })

    const ingestButton = screen.getByRole('button', { name: /ingest now/i })
    await user.click(ingestButton)

    expect(ingestCalled).toBe(true)
  })

  it('shows "Not Ingested" badge when no ingestion has occurred', async () => {
    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: null,
        lastIngestedAt: null,
        name: 'my-repo',
      }),
    ])

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos'])

    await waitFor(() => {
      expect(screen.getByText('my-repo')).toBeInTheDocument()
    })

    expect(screen.getByText('Not Ingested')).toBeInTheDocument()
  })

  it('handles API error gracefully when ingestion fails', async () => {
    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: null,
        lastIngestedAt: null,
        name: 'my-repo',
      }),
    ])
    mockDrilldownExtras()

    addFetchHandler((url, options) => {
      if (url.includes('/repositories/repo-1/ingest') && options.method === 'POST') {
        return jsonResponse({ message: 'Ingestion failed' }, 500)
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('my-repo')).toBeInTheDocument()
    })

    const ingestButton = screen.getByRole('button', { name: /ingest now/i })
    await user.click(ingestButton)

    await waitFor(() => {
      expect(screen.getByText('Ingestion failed')).toBeInTheDocument()
    })
  })

  it('refreshes repository queries when ingestion fails', async () => {
    mockListTeams([createMockTeam()])

    let listCalls = 0
    addFetchHandler((url) => {
      const match = url.match(/\/api\/v1\/teams\/[^/]+\/repositories$/)
      if (match) {
        listCalls++
        return jsonResponse([
          createMockRepository({
            ingestionStatus: null,
            lastIngestedAt: null,
            name: 'my-repo',
          }),
        ])
      }
      return null
    })
    mockDrilldownExtras()

    addFetchHandler((url, options) => {
      if (url.includes('/repositories/repo-1/ingest') && options.method === 'POST') {
        return jsonResponse({ message: 'Ingestion failed' }, 500)
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('my-repo')).toBeInTheDocument()
    })
    const callsBeforeIngest = listCalls

    await user.click(screen.getByRole('button', { name: /ingest now/i }))

    await waitFor(() => {
      expect(screen.getByText('Ingestion failed')).toBeInTheDocument()
    })

    // The failure invalidates the repository/ingestion caches so the UI can
    // reconcile any partial server-side state (e.g. a batch created before the error).
    await waitFor(() => {
      expect(listCalls).toBeGreaterThan(callsBeforeIngest)
    })
  })

  afterEach(() => {
    restoreDownloads()
  })

  it('enables "Ingest Now" button when ingestion status is FAILED', async () => {
    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: 'FAILED',
        lastIngestedAt: '2024-01-01T00:00:00Z',
        name: 'my-repo',
      }),
    ])
    mockDrilldownExtras()

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('my-repo')).toBeInTheDocument()
    })

    const ingestButton = screen.getByRole('button', { name: /ingest now/i })
    expect(ingestButton).not.toBeDisabled()
  })

  it('hides DEBUG logs by default, toggles them on, and exports all levels to CSV', async () => {
    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: 'SUCCESS',
        latestBatchId: 'batch-1',
        name: 'my-repo',
      }),
    ])
    addFetchHandler((url) => {
      if (url.includes('/batches/batch-1/logs')) {
        return jsonResponse([
          {
            batchId: 'batch-1',
            createdAt: '2026-11-14T10:00:00Z',
            id: 'log-1',
            level: 'INFO',
            message: 'CLONE completed in 1.2s',
            step: 'CLONE',
          },
          {
            batchId: 'batch-1',
            createdAt: '2026-11-14T10:00:01Z',
            id: 'log-2',
            level: 'DEBUG',
            message: 'Excluded self-call HTTP_CALLS /health',
            step: 'EXTRACT_SURFACES',
          },
        ])
      }
      if (url.includes('/batches/') && url.includes('/stats')) {
        return jsonResponse(null, 404)
      }
      if (url.includes('/batches')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('CLONE completed in 1.2s')).toBeInTheDocument()
    })
    expect(screen.queryByText('Excluded self-call HTTP_CALLS /health')).not.toBeInTheDocument()

    const toggle = screen.getByRole('button', { name: /show debug \(1\)/i })
    await user.click(toggle)
    expect(screen.getByText('Excluded self-call HTTP_CALLS /health')).toBeInTheDocument()

    const { createObjectURL } = mockDownloads()
    await user.click(screen.getByRole('button', { name: /download csv/i }))
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    const csvText = await createObjectURL.mock.calls[0][0].text()
    expect(csvText).toContain('CLONE completed in 1.2s')
    expect(csvText).toContain('Excluded self-call HTTP_CALLS /health')
  })
})
