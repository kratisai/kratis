import { beforeEach, describe, expect, it } from 'vitest'

import type { IngestionBatchStatsDto } from '@/types/auth-types'

import { createMockRepository, createMockTeam } from '../support/test-factories'
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

const stats: IngestionBatchStatsDto = {
  architecturePatterns: [],
  batchId: 'batch-1',
  completionTokens: null,
  dimensions: [],
  edgeTypeCounts: {},
  nodeTypeCounts: {},
  promptTokens: null,
  relations: [
    {
      confidence: 'CONFIRMED',
      currentSide: 'UPSTREAM',
      nature: 'CALLS',
      otherRepositoryId: 'repo-2',
      otherRepositoryName: 'billing-service',
    },
    {
      confidence: 'SUGGESTED',
      currentSide: 'DOWNSTREAM',
      nature: 'BUILDS_AGAINST',
      otherRepositoryId: 'repo-3',
      otherRepositoryName: 'shared-lib',
    },
  ],
  surfaces: [
    {
      attributeCount: 2,
      description: 'HTTP API provided by OrderController',
      detectedBy: 'CMM',
      role: 'PROVIDES',
    },
    {
      attributeCount: 1,
      description: 'Messaging dependency',
      detectedBy: 'MODEL',
      role: 'REQUIRES',
    },
  ],
  totalDurationSeconds: 120,
  totalEdges: 4,
  totalNodes: 7,
  totalSpend: 0.02,
  totalTokens: 1000,
  totalToolCalls: 3,
  usageLastUpdatedAt: null,
  wikiPageSlugs: [],
}

describe('Repository stats surfaces and relations', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  it('renders surface tags with hover details and linked relations', async () => {
    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: 'SUCCESS',
        latestBatchId: 'batch-1',
        name: 'orders-service',
      }),
    ])
    addFetchHandler((url) => {
      if (url.includes('/batches/batch-1/stats')) {
        return jsonResponse(stats)
      }
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse([])
      }
      if (url.includes('/batches')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Called by')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: 'Show surfaces (2)' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    expect(screen.queryByText('HTTP API provided by OrderController')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Show surfaces (2)' }))
    const surfaceTag = screen.getByText('HTTP API provided by OrderController')
    expect(surfaceTag).toBeInTheDocument()

    await user.hover(surfaceTag)
    await waitFor(() => {
      expect(screen.getByText('Provides')).toBeInTheDocument()
      expect(screen.getByText('2 attributes')).toBeInTheDocument()
      expect(screen.getByText('Detected by CMM')).toBeInTheDocument()
    })

    expect(screen.getByText('Called by')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'billing-service' })).toHaveAttribute(
      'href',
      '/repos/repo-2',
    )
    expect(screen.getByText('Builds against')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'shared-lib' })).toHaveAttribute(
      'href',
      '/repos/repo-3',
    )
    expect(screen.getByText('Suggested')).toBeInTheDocument()
  })

  it('shows the surfaces toggle when a batch has no relations', async () => {
    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: 'SUCCESS',
        latestBatchId: 'batch-1',
        name: 'orders-service',
      }),
    ])
    addFetchHandler((url) => {
      if (url.includes('/batches/batch-1/stats')) {
        return jsonResponse({ ...stats, relations: [] })
      }
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse([])
      }
      if (url.includes('/batches')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Cross-repository Relations')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: 'Show surfaces (2)' })).toBeInTheDocument()
  })

  it('shows every surface without a second more control', async () => {
    const manySurfaces = Array.from({ length: 10 }, (_, index) => ({
      attributeCount: 1,
      description: `Surface ${index}`,
      detectedBy: 'CMM' as const,
      role: 'PROVIDES' as const,
    }))

    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: 'SUCCESS',
        latestBatchId: 'batch-1',
        name: 'orders-service',
      }),
    ])
    addFetchHandler((url) => {
      if (url.includes('/batches/batch-1/stats')) {
        return jsonResponse({ ...stats, relations: [], surfaces: manySurfaces })
      }
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse([])
      }
      if (url.includes('/batches')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    const { user } = renderWithRouter(['/repos/repo-1'])

    await user.click(await screen.findByRole('button', { name: 'Show surfaces (10)' }))

    for (let index = 0; index < manySurfaces.length; index += 1) {
      expect(screen.getByText(`Surface ${index}`)).toBeInTheDocument()
    }
    expect(screen.queryByRole('button', { name: /more/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'less' })).not.toBeInTheDocument()
  })

  it('tolerates stats responses without surface or relation collections', async () => {
    const legacyStats: Partial<IngestionBatchStatsDto> = { ...stats }
    delete legacyStats.relations
    delete legacyStats.surfaces

    mockListTeams([createMockTeam()])
    mockListRepositories([
      createMockRepository({
        ingestionStatus: 'SUCCESS',
        latestBatchId: 'batch-1',
        name: 'orders-service',
      }),
    ])
    addFetchHandler((url) => {
      if (url.includes('/batches/batch-1/stats')) {
        return jsonResponse(legacyStats)
      }
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse([])
      }
      if (url.includes('/batches')) {
        return jsonResponse([])
      }
      return null
    })

    setAuthenticated({ teamId: 'team-1' })
    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Total Nodes')).toBeInTheDocument()
    })
    expect(screen.queryByText('Surfaces')).not.toBeInTheDocument()
    expect(screen.queryByText('Cross-repository Relations')).not.toBeInTheDocument()
  })
})
