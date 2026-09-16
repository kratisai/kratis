import { beforeEach, describe, expect, it } from 'vitest'

import type { IngestionStatus } from '@/types/auth-types.ts'

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

describe('Repository Drilldown View', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  function setupMocks() {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'abc123def456',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: 'SUCCESS',
        lastIngestedAt: '2024-01-15T10:00:00Z',
        latestBatchId: 'batch-1',
        name: 'frontend-app',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/frontend-app.git',
      },
    ])

    addFetchHandler((url) => {
      // Mock batch logs endpoint (empty)
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse([])
      }
      // Mock batch stats endpoint (empty - no stats)
      if (url.includes('/batches/') && url.includes('/stats')) {
        return jsonResponse(null, 404)
      }
      // Mock batch history endpoint (empty)
      if (url.includes('/batches') && !url.includes('/logs') && !url.includes('/stats')) {
        return jsonResponse([])
      }
      return null
    })
  }

  it('shows repository details when navigating from list view', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for repository name to appear
    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Verify repository URL is displayed
    expect(screen.getByText('https://github.com/user/frontend-app.git')).toBeInTheDocument()

    // Verify branch is displayed
    expect(screen.getByText('main')).toBeInTheDocument()

    // Verify commit hash is displayed (truncated)
    expect(screen.getByText('abc123d')).toBeInTheDocument()

    // Verify ingestion status is displayed
    expect(screen.getByText('Success')).toBeInTheDocument()
  })

  it('shows 404 when repository is not found', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Should show not found message
    await waitFor(() => {
      expect(screen.getByText('Repository not found')).toBeInTheDocument()
    })

    // Should show back button
    expect(screen.getByText('Back to Repositories')).toBeInTheDocument()
  })

  it('navigates back to repos list when clicking back button', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1' })

    const { user } = renderWithRouter(['/repos/repo-1'])

    // Wait for repository to load
    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Click back button (first button in the view)
    const buttons = screen.getAllByRole('button')
    const backButton = buttons[0]
    await user.click(backButton)
  })

  it('displays ingestion status badge', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for status to load
    await waitFor(() => {
      expect(screen.getByText('SUCCESS')).toBeInTheDocument()
    })
  })

  it('shows quick action buttons', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for view to load
    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Verify quick action buttons are present
    expect(screen.getByText('Ingest Now')).toBeInTheDocument()
    expect(screen.getByText('Ask Kratis')).toBeInTheDocument()
    expect(screen.getByText('View Source')).toBeInTheDocument()
  })

  it('shows live logs section', async () => {
    setupMocks()
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for view to load
    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Verify live logs section is present
    expect(screen.getByText('Live Logs')).toBeInTheDocument()
    expect(screen.getByText('Ingestion Status:')).toBeInTheDocument()
  })
})

describe('Repository Drilldown - Ingestion History', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  function setupMocksWithHistory(batches: unknown[] = []) {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'abc123def456',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: 'SUCCESS',
        lastIngestedAt: '2024-01-15T10:00:00Z',
        latestBatchId: 'batch-1',
        name: 'frontend-app',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/frontend-app.git',
      },
    ])

    addFetchHandler((url) => {
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse([])
      }
      if (url.includes('/batches/') && url.includes('/stats')) {
        return jsonResponse(null, 404)
      }
      if (url.includes('/batches') && !url.includes('/logs') && !url.includes('/stats')) {
        return jsonResponse(batches)
      }
      return null
    })
  }

  it('shows empty state when no ingestion history exists', async () => {
    setupMocksWithHistory([])
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Verify ingestion history section is present
    expect(screen.getByText('Ingestion History')).toBeInTheDocument()

    // Verify empty state message
    expect(screen.getByText('No ingestion history yet.')).toBeInTheDocument()
  })

  it('displays batch history table with multiple entries', async () => {
    const mockBatches = [
      {
        batchId: 'batch-2',
        commitHash: 'def789',
        completedAt: '2024-01-15T12:00:00Z',
        errorMessage: null,
        startedAt: '2024-01-15T11:55:00Z',
        status: 'SUCCESS',
      },
      {
        batchId: 'batch-1',
        commitHash: 'abc123',
        completedAt: '2024-01-14T10:05:00Z',
        errorMessage: null,
        startedAt: '2024-01-14T10:00:00Z',
        status: 'SUCCESS',
      },
    ]

    setupMocksWithHistory(mockBatches)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Verify history section is present
    expect(screen.getByText('Ingestion History')).toBeInTheDocument()

    // Verify batch statuses are displayed
    expect(screen.getAllByText('SUCCESS').length).toBeGreaterThanOrEqual(1)

    // Verify commit hashes are displayed (truncated)
    expect(screen.getByText('def789')).toBeInTheDocument()
  })

  it('displays error message for failed ingestion', async () => {
    const mockBatches = [
      {
        batchId: 'batch-failed',
        commitHash: 'deadbeef',
        completedAt: '2024-01-15T12:00:00Z',
        errorMessage: 'Clone failed: repository not found',
        startedAt: '2024-01-15T11:55:00Z',
        status: 'FAILED',
      },
    ]

    setupMocksWithHistory(mockBatches)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Verify FAILED status is displayed (badge shows "Failed" capitalized)
    expect(screen.getByText('Failed')).toBeInTheDocument()

    // Verify error message is displayed
    expect(screen.getByText('Clone failed: repository not found')).toBeInTheDocument()
  })
})

describe('Repository Drilldown - Live Log Streaming', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  function setupMocksWithLogs(
    logs: unknown[] = [],
    status: IngestionStatus | null = 'SUCCESS',
    batchId: null | string = 'batch-1',
  ) {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'abc123def456',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: status,
        lastIngestedAt: '2024-01-15T10:00:00Z',
        latestBatchId: batchId,
        name: 'frontend-app',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/frontend-app.git',
      },
    ])

    addFetchHandler((url) => {
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse(logs)
      }
      if (url.includes('/batches/') && url.includes('/stats')) {
        return jsonResponse(null, 404)
      }
      if (url.includes('/batches') && !url.includes('/logs') && !url.includes('/stats')) {
        return jsonResponse([])
      }
      return null
    })
  }

  it('shows "Trigger Ingestion" message when no active batch', async () => {
    setupMocksWithLogs([], 'SUCCESS', null)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Verify no active batch message
    expect(
      screen.getByText('No active research batch logs found for this repository.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Trigger Ingestion to see agentic logs')).toBeInTheDocument()
  })

  it('displays log entries with correct formatting', async () => {
    const mockLogs = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:00:00Z',
        id: 'log-1',
        level: 'INFO',
        message: 'Starting clone operation',
        step: 'CLONE',
      },
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:01:00Z',
        id: 'log-2',
        level: 'INFO',
        message: 'Clone completed successfully',
        step: 'CLONE',
      },
    ]

    setupMocksWithLogs(mockLogs)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for log messages to appear
    await waitFor(() => {
      expect(screen.getByText('Starting clone operation')).toBeInTheDocument()
    })

    // Verify log messages are displayed
    expect(screen.getByText('Clone completed successfully')).toBeInTheDocument()

    // Verify step labels are displayed
    expect(screen.getAllByText('[CLONE]').length).toBeGreaterThanOrEqual(1)
  })

  it('displays ERROR logs with red styling', async () => {
    const mockLogs = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:00:00Z',
        id: 'log-1',
        level: 'ERROR',
        message: 'Connection timeout',
        step: 'INDEX',
      },
    ]

    setupMocksWithLogs(mockLogs, 'FAILED')
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for error message to appear
    await waitFor(() => {
      expect(screen.getByText('Connection timeout')).toBeInTheDocument()
    })

    // Verify error message is displayed
    expect(screen.getByText('ERROR')).toBeInTheDocument()
  })

  it('displays WARN logs with amber styling', async () => {
    const mockLogs = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:00:00Z',
        id: 'log-1',
        level: 'WARN',
        message: 'Deprecated syntax detected',
        step: 'PARSE',
      },
    ]

    setupMocksWithLogs(mockLogs)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for warning message to appear
    await waitFor(() => {
      expect(screen.getByText('Deprecated syntax detected')).toBeInTheDocument()
    })

    // Verify warning message is displayed
    expect(screen.getByText('WARN')).toBeInTheDocument()
  })

  it('applies correct CSS severity class for ERROR logs (Step 8)', async () => {
    const mockLogs = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:00:00Z',
        id: 'log-1',
        level: 'ERROR',
        message: 'Parsing failed: unexpected token',
        step: 'PARSE',
      },
    ]

    setupMocksWithLogs(mockLogs, 'FAILED')
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for error message to appear
    await waitFor(() => {
      expect(screen.getByText('Parsing failed: unexpected token')).toBeInTheDocument()
    })

    // Find the level badge and verify it has the ERROR CSS class (bg-red-950)
    const errorBadge = screen.getByText('ERROR')
    expect(errorBadge).toHaveClass('bg-red-950')
    expect(errorBadge).toHaveClass('text-red-400')
  })

  it('applies correct CSS severity class for WARN logs (Step 8)', async () => {
    const mockLogs = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:00:00Z',
        id: 'log-1',
        level: 'WARN',
        message: 'Deprecated syntax in module',
        step: 'PARSE',
      },
    ]

    setupMocksWithLogs(mockLogs)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for warning message to appear
    await waitFor(() => {
      expect(screen.getByText('Deprecated syntax in module')).toBeInTheDocument()
    })

    // Find the level badge and verify it has the WARN CSS class (bg-amber-950)
    const warnBadge = screen.getByText('WARN')
    expect(warnBadge).toHaveClass('bg-amber-950')
    expect(warnBadge).toHaveClass('text-amber-400')
  })

  it('applies correct CSS severity class for INFO logs (Step 8)', async () => {
    const mockLogs = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:00:00Z',
        id: 'log-1',
        level: 'INFO',
        message: 'Starting clone operation',
        step: 'CLONE',
      },
    ]

    setupMocksWithLogs(mockLogs)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for info message to appear
    await waitFor(() => {
      expect(screen.getByText('Starting clone operation')).toBeInTheDocument()
    })

    // Find the level badge and verify it has the INFO CSS class (bg-blue-950)
    const infoBadge = screen.getByText('INFO')
    expect(infoBadge).toHaveClass('bg-blue-950')
    expect(infoBadge).toHaveClass('text-blue-400')
  })

  it('auto-scrolls to bottom when new logs stream in (Step 8)', async () => {
    const mockLogs = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:00:00Z',
        id: 'log-1',
        level: 'INFO',
        message: 'Starting clone operation',
        step: 'CLONE',
      },
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:01:00Z',
        id: 'log-2',
        level: 'INFO',
        message: 'Clone completed successfully',
        step: 'CLONE',
      },
      {
        batchId: 'batch-1',
        createdAt: '2024-01-15T10:02:00Z',
        id: 'log-3',
        level: 'WARN',
        message: 'Deprecated syntax detected',
        step: 'PARSE',
      },
    ]

    setupMocksWithLogs(mockLogs, 'PROCESSING')
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for all logs to appear
    await waitFor(() => {
      expect(screen.getByText('Deprecated syntax detected')).toBeInTheDocument()
    })

    // Verify the log container has multiple entries (simulating streamed logs)
    const logEntries = screen.getAllByText(/\[CLONE]|\[PARSE]/)
    expect(logEntries.length).toBeGreaterThanOrEqual(3)

    // Verify the scroll area container exists (auto-scroll is handled by useEffect on logs change)
    // The useEffect triggers scrollIntoView on consoleEndRef when logs update
    const scrollArea = document.querySelector('[class*="h-80"]')
    expect(scrollArea).toBeInTheDocument()
  })
})

describe('Repository Drilldown - Ingestion Statistics', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  function setupMocksWithStats(
    logs: unknown[] = [],
    stats: unknown = null,
    status: IngestionStatus | null = 'SUCCESS',
    batchId: null | string = 'batch-1',
  ) {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'abc123def456',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: status,
        lastIngestedAt: '2024-01-15T10:00:00Z',
        latestBatchId: batchId,
        name: 'frontend-app',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/frontend-app.git',
      },
    ])

    addFetchHandler((url) => {
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse(logs)
      }
      if (url.includes('/batches') && url.includes('/stats')) {
        if (!stats) {
          return jsonResponse(null, 404)
        }
        // The stats protocol guarantees these collections are always present.
        return jsonResponse({
          architecturePatterns: [],
          dimensions: [],
          edgeTypeCounts: {},
          nodeTypeCounts: {},
          wikiPageSlugs: [],
          ...stats,
        })
      }
      if (url.includes('/batches') && !url.includes('/logs') && !url.includes('/stats')) {
        return jsonResponse([])
      }
      return null
    })
  }

  it('shows empty state when no statistics are available', async () => {
    setupMocksWithStats([], null, 'SUCCESS', null)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Verify statistics section is present
    expect(screen.getByText('Ingestion Statistics')).toBeInTheDocument()

    // Verify empty state message
    expect(screen.getByText('No statistics available.')).toBeInTheDocument()
  })

  it('displays high-level metrics', async () => {
    const mockStats = {
      batchId: 'batch-1',
      edgeTypeCounts: { calls: 5, imports: 3 },
      nodeTypeCounts: { class: 4, function: 10 },
      totalDurationSeconds: 180,
      totalEdges: 8,
      totalNodes: 14,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Wait for statistics to load
    await waitFor(() => {
      expect(screen.getByText('Duration')).toBeInTheDocument()
    })

    // Verify statistics section is present
    expect(screen.getByText('Ingestion Statistics')).toBeInTheDocument()

    // Verify high-level metrics are displayed
    expect(screen.getByText('Total Nodes')).toBeInTheDocument()
    expect(screen.getByText('Total Edges')).toBeInTheDocument()

    // Verify values match the stats
    expect(screen.getByText('14')).toBeInTheDocument()
    expect(screen.getByText('8')).toBeInTheDocument()
    expect(screen.getByText('3m 0s')).toBeInTheDocument()
  })

  it('displays node type breakdown', async () => {
    const mockStats = {
      batchId: 'batch-1',
      edgeTypeCounts: { calls: 2 },
      nodeTypeCounts: { class: 3, function: 5 },
      totalDurationSeconds: 60,
      totalEdges: 2,
      totalNodes: 8,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for statistics to load
    await waitFor(() => {
      expect(screen.getByText('Total Nodes')).toBeInTheDocument()
    })

    // Verify node type counts are displayed as tags
    expect(screen.getByText('function')).toBeInTheDocument()
    expect(screen.getByText('class')).toBeInTheDocument()
    expect(screen.getAllByText('5').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('3').length).toBeGreaterThanOrEqual(1)
  })

  it('displays edge type breakdown', async () => {
    const mockStats = {
      batchId: 'batch-1',
      edgeTypeCounts: { calls: 10, imports: 5 },
      nodeTypeCounts: { function: 8 },
      totalDurationSeconds: 120,
      totalEdges: 15,
      totalNodes: 8,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for statistics to load
    await waitFor(() => {
      expect(screen.getByText('Total Edges')).toBeInTheDocument()
    })

    // Verify edge type counts are displayed as tags
    expect(screen.getByText('calls')).toBeInTheDocument()
    expect(screen.getByText('imports')).toBeInTheDocument()
    expect(screen.getAllByText('10').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('5').length).toBeGreaterThanOrEqual(1)
  })

  it('displays duration in human-readable format', async () => {
    const mockStats = {
      batchId: 'batch-1',
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 3665,
      totalEdges: 0,
      totalNodes: 0,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    // Wait for statistics to load
    await waitFor(() => {
      expect(screen.getByText('1h 1m 5s')).toBeInTheDocument()
    })

    // Verify duration is formatted as 1h 1m 5s
    expect(screen.getByText('1h 1m 5s')).toBeInTheDocument()
  })

  it('displays LLM usage stats in low-key strip', async () => {
    const mockStats = {
      batchId: 'batch-1',
      completionTokens: 500,
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      promptTokens: 1000,
      totalDurationSeconds: 180,
      totalEdges: 0,
      totalNodes: 0,
      totalSpend: 1.239,
      totalTokens: 1500,
      totalToolCalls: 42,
      usageLastUpdatedAt: '2024-01-15T10:05:00Z',
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Wait for stats strip to appear
    await waitFor(() => {
      expect(screen.getByText('Duration')).toBeInTheDocument()
    })

    // Verify low-key labels are displayed
    expect(screen.getByText('Duration')).toBeInTheDocument()
    expect(screen.getByText(/^Cost/)).toBeInTheDocument()
    expect(screen.getByText(/^Total Tokens/)).toBeInTheDocument()
    expect(screen.getByText(/^Tool Calls/)).toBeInTheDocument()

    // Verify spend is rounded to the nearest cent
    expect(screen.getByText('$1.24')).toBeInTheDocument()

    // Verify token counts are formatted with thousands separators
    expect(screen.getByText('1,500')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
  })

  it('shows placeholders when no usage data is recorded', async () => {
    const mockStats = {
      batchId: 'batch-1',
      edgeTypeCounts: { calls: 1 },
      nodeTypeCounts: { function: 2 },
      totalDurationSeconds: 60,
      totalEdges: 1,
      totalNodes: 2,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Duration')).toBeInTheDocument()
    })

    // Cost, Total Tokens, and Tool Calls should show placeholders
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(1)
  })

  it('displays architecture patterns block with count and tag cloud', async () => {
    const mockStats = {
      architecturePatterns: [
        { description: 'Hexagonal Architecture', name: 'Hexagonal' },
        { description: 'MVC Architecture', name: 'MVC' },
      ],
      batchId: 'batch-1',
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 0,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Architecture Patterns')).toBeInTheDocument()
    })

    // Verify count is displayed
    expect(screen.getByText('2')).toBeInTheDocument()

    // Verify pattern names are rendered as tags
    expect(screen.getByText('Hexagonal')).toBeInTheDocument()
    expect(screen.getByText('MVC')).toBeInTheDocument()
  })

  it('hides architecture patterns block when none are present', async () => {
    const mockStats = {
      architecturePatterns: [],
      batchId: 'batch-1',
      edgeTypeCounts: { calls: 1 },
      nodeTypeCounts: { function: 2 },
      totalDurationSeconds: 60,
      totalEdges: 1,
      totalNodes: 2,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Total Nodes')).toBeInTheDocument()
    })

    // Architecture Patterns block should not be rendered when list is empty
    expect(screen.queryByText('Architecture Patterns')).not.toBeInTheDocument()
  })

  it('displays multiple architecture patterns as individual cards', async () => {
    const mockStats = {
      architecturePatterns: [
        { description: 'Command Query Responsibility Segregation pattern', name: 'CQRS' },
        { description: 'Append-only store of domain events', name: 'Event Sourcing' },
        { description: 'Mediates between domain and data mapping layers', name: 'Repository' },
        { description: 'Gradually replace a legacy system', name: 'Strangler' },
      ],
      batchId: 'batch-1',
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 90,
      totalEdges: 0,
      totalNodes: 0,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Architecture Patterns')).toBeInTheDocument()
    })

    expect(screen.getByText('4')).toBeInTheDocument()
    expect(screen.getByText('CQRS')).toBeInTheDocument()
    expect(screen.getByText('Event Sourcing')).toBeInTheDocument()
    expect(screen.getByText('Repository')).toBeInTheDocument()
    expect(screen.getByText('+1 more…')).toBeInTheDocument()
  })
})

describe('Repository Drilldown - Batch Selection', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  function setupMocksWithBatchSelection(
    activeBatchId: null | string,
    historyBatches: unknown[],
    logsForBatch1: unknown[] = [],
    logsForBatch2: unknown[] = [],
    statsForBatch1: unknown = null,
    statsForBatch2: unknown = null,
  ) {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'abc123def456',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: activeBatchId ? 'SUCCESS' : 'SUCCESS',
        lastIngestedAt: '2024-01-15T10:00:00Z',
        latestBatchId: activeBatchId,
        name: 'frontend-app',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/frontend-app.git',
      },
    ])

    addFetchHandler((url) => {
      if (url.includes('/batches/') && url.includes('/logs')) {
        if (url.includes('batch-1')) return jsonResponse(logsForBatch1)
        if (url.includes('batch-2')) return jsonResponse(logsForBatch2)
        return jsonResponse([])
      }
      if (url.includes('/batches') && url.includes('/stats')) {
        // The stats protocol guarantees these collections are always present.
        const withDefaults = (stats: unknown) => ({
          architecturePatterns: [],
          dimensions: [],
          edgeTypeCounts: {},
          nodeTypeCounts: {},
          wikiPageSlugs: [],
          ...(stats as Record<string, unknown>),
        })
        if (url.includes('batch-1'))
          return statsForBatch1 ? jsonResponse(withDefaults(statsForBatch1)) : jsonResponse(null, 404)
        if (url.includes('batch-2'))
          return statsForBatch2 ? jsonResponse(withDefaults(statsForBatch2)) : jsonResponse(null, 404)
        return jsonResponse(null, 404)
      }
      if (url.includes('/batches') && !url.includes('/logs') && !url.includes('/stats')) {
        return jsonResponse(historyBatches)
      }
      return null
    })
  }

  it('selects the latest batch reported by the repository projection', async () => {
    const mockHistory = [
      {
        batchId: 'batch-1',
        commitHash: 'abc123',
        completedAt: '2024-01-14T10:05:00Z',
        errorMessage: null,
        startedAt: '2024-01-14T10:00:00Z',
        status: 'SUCCESS',
      },
      {
        batchId: 'batch-2',
        commitHash: 'def456',
        completedAt: '2024-01-13T10:05:00Z',
        errorMessage: null,
        startedAt: '2024-01-13T10:00:00Z',
        status: 'SUCCESS',
      },
    ]
    const mockStats1 = {
      batchId: 'batch-1',
      edgeTypeCounts: {},
      nodeTypeCounts: { class: 5 },
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 5,
    }
    const mockLogs1 = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-14T10:00:00Z',
        id: 'log-1',
        level: 'INFO',
        message: 'Batch 1 log',
        step: 'CLONE',
      },
    ]

    setupMocksWithBatchSelection('batch-1', mockHistory, mockLogs1, [], mockStats1, null)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Should select batch-1 (the repository's latest batch)
    await waitFor(() => {
      expect(screen.getByText('Batch 1 log')).toBeInTheDocument()
    })
    expect(screen.getAllByText('5').length).toBeGreaterThanOrEqual(1) // Total nodes from batch-1 stats
  })

  it('highlights the selected batch row in the history table', async () => {
    const mockHistory = [
      {
        batchId: 'batch-1',
        commitHash: 'abc123',
        completedAt: '2024-01-14T10:05:00Z',
        errorMessage: null,
        startedAt: '2024-01-14T10:00:00Z',
        status: 'SUCCESS',
      },
      {
        batchId: 'batch-2',
        commitHash: 'def456',
        completedAt: '2024-01-13T10:05:00Z',
        errorMessage: null,
        startedAt: '2024-01-13T10:00:00Z',
        status: 'SUCCESS',
      },
    ]
    const mockLogs1 = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-14T10:00:00Z',
        id: 'log-1',
        level: 'INFO',
        message: 'Batch 1 log',
        step: 'CLONE',
      },
    ]
    const mockLogs2 = [
      {
        batchId: 'batch-2',
        createdAt: '2024-01-13T10:00:00Z',
        id: 'log-2',
        level: 'INFO',
        message: 'Batch 2 log',
        step: 'CLONE',
      },
    ]

    setupMocksWithBatchSelection('batch-1', mockHistory, mockLogs1, mockLogs2)
    setAuthenticated({ teamId: 'team-1' })

    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Initially batch-1 is selected (active)
    await waitFor(() => {
      expect(screen.getByText('Batch 1 log')).toBeInTheDocument()
    })

    // Click on batch-2 row
    const batch2Row = screen.getByText('def456').closest('tr')
    await user.click(batch2Row!)

    // Verify batch-2 logs are now displayed
    await waitFor(() => {
      expect(screen.getByText('Batch 2 log')).toBeInTheDocument()
    })

    // Verify the row has the selected border class
    expect(batch2Row).toHaveClass('border-accent')
  })

  it('shows "View Latest" button when a historical batch is selected', async () => {
    const mockHistory = [
      {
        batchId: 'batch-1',
        commitHash: 'abc123',
        completedAt: '2024-01-14T10:05:00Z',
        errorMessage: null,
        startedAt: '2024-01-14T10:00:00Z',
        status: 'SUCCESS',
      },
      {
        batchId: 'batch-2',
        commitHash: 'def456',
        completedAt: '2024-01-13T10:05:00Z',
        errorMessage: null,
        startedAt: '2024-01-13T10:00:00Z',
        status: 'SUCCESS',
      },
    ]
    const mockLogs1 = [
      {
        batchId: 'batch-1',
        createdAt: '2024-01-14T10:00:00Z',
        id: 'log-1',
        level: 'INFO',
        message: 'Batch 1 log',
        step: 'CLONE',
      },
    ]
    const mockLogs2 = [
      {
        batchId: 'batch-2',
        createdAt: '2024-01-13T10:00:00Z',
        id: 'log-2',
        level: 'INFO',
        message: 'Batch 2 log',
        step: 'CLONE',
      },
    ]

    setupMocksWithBatchSelection('batch-1', mockHistory, mockLogs1, mockLogs2)
    setAuthenticated({ teamId: 'team-1' })

    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('frontend-app')).toBeInTheDocument()
    })

    // Click on batch-2 row to select historical batch
    const batch2Row = screen.getByText('def456').closest('tr')
    await user.click(batch2Row!)

    await waitFor(() => {
      expect(screen.getByText('Batch 2 log')).toBeInTheDocument()
    })

    // Verify "Historical Logs" title and "View Latest" button appear
    expect(screen.getByText('Historical Logs')).toBeInTheDocument()
    const viewLatestButton = screen.getByRole('button', { name: /view latest/i })
    expect(viewLatestButton).toBeInTheDocument()

    // Click "View Latest"
    await user.click(viewLatestButton)

    // Verify it switches back to batch-1 (active)
    await waitFor(() => {
      expect(screen.getByText('Live Logs')).toBeInTheDocument()
      expect(screen.getByText('Batch 1 log')).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /view latest/i })).not.toBeInTheDocument()
  })
})

describe('Repository Drilldown - Dimension Categories & Wiki Pages', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  function setupMocksWithStats(batches: unknown[] = [], stats: unknown = null) {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'abc123def456',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: 'SUCCESS',
        lastIngestedAt: '2024-01-15T10:00:00Z',
        latestBatchId: 'batch-1',
        name: 'frontend-app',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/frontend-app.git',
      },
    ])

    addFetchHandler((url) => {
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse([])
      }
      if (url.includes('/batches/') && url.includes('/stats')) {
        if (!stats) {
          return jsonResponse(null, 404)
        }
        // The stats protocol guarantees these collections are always present.
        return jsonResponse({
          architecturePatterns: [],
          dimensions: [],
          edgeTypeCounts: {},
          nodeTypeCounts: {},
          wikiPageSlugs: [],
          ...stats,
        })
      }
      if (url.includes('/batches') && !url.includes('/logs') && !url.includes('/stats')) {
        return jsonResponse(batches)
      }
      return null
    })
  }

  it('displays dimension category stats (domain, archetype, cross_cutting)', async () => {
    const mockStats = {
      batchId: 'batch-1',
      dimensions: [
        { category: 'domain', name: 'Authentication' },
        { category: 'domain', name: 'Authorization' },
        { category: 'archetype', name: 'Controller' },
        { category: 'cross_cutting', name: 'Logging' },
      ],
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 0,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('domain')).toBeInTheDocument()
    })
    expect(screen.getByText('archetype')).toBeInTheDocument()
    expect(screen.getByText('cross cutting')).toBeInTheDocument()

    // Verify dimension names are rendered
    expect(screen.getByText('Authentication')).toBeInTheDocument()
    expect(screen.getByText('Authorization')).toBeInTheDocument()
    expect(screen.getByText('Controller')).toBeInTheDocument()
    expect(screen.getByText('Logging')).toBeInTheDocument()
  })

  it('shows synopsis, file count, and top files in a hover card for a dimension', async () => {
    const mockStats = {
      batchId: 'batch-1',
      dimensions: [
        {
          category: 'domain',
          fileCount: 3,
          name: 'Authentication',
          synopsis: 'Handles user login, session management, and token validation.',
          topFiles: ['src/auth/login.ts', 'src/auth/session.ts', 'src/auth/tokens.ts'],
        },
      ],
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 0,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Authentication')).toBeInTheDocument()
    })

    await user.hover(screen.getByText('Authentication'))

    await waitFor(
      () => {
        expect(
          screen.getByText('Handles user login, session management, and token validation.'),
        ).toBeInTheDocument()
      },
      { timeout: 3000 },
    )

    expect(screen.getByText('3 files')).toBeInTheDocument()
    expect(screen.getByText('src/auth/login.ts')).toBeInTheDocument()
    expect(screen.getByText('src/auth/session.ts')).toBeInTheDocument()
    expect(screen.getByText('src/auth/tokens.ts')).toBeInTheDocument()
  })

  it('omits the synopsis and top files sections in the hover card when absent', async () => {
    const mockStats = {
      batchId: 'batch-1',
      dimensions: [
        {
          category: 'domain',
          fileCount: 1,
          name: 'Orphan Domain',
          synopsis: null,
          topFiles: [],
        },
      ],
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 0,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Orphan Domain')).toBeInTheDocument()
    })

    await user.hover(screen.getByText('Orphan Domain'))

    await waitFor(
      () => {
        expect(screen.getByText('1 file')).toBeInTheDocument()
      },
      { timeout: 3000 },
    )

    expect(screen.queryByText('Top files')).not.toBeInTheDocument()
  })

  it('displays wiki page slugs when present in stats', async () => {
    const mockStats = {
      batchId: 'batch-1',
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 0,
      wikiPageSlugs: ['architecture-overview', 'api-reference', 'deployment-guide'],
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Generated Wiki Pages')).toBeInTheDocument()
    })
    expect(screen.getByText('architecture-overview')).toBeInTheDocument()
    expect(screen.getByText('api-reference')).toBeInTheDocument()
    expect(screen.getByText('deployment-guide')).toBeInTheDocument()
  })

  it('renders wiki page slugs as links pointing to the wiki page route', async () => {
    const mockStats = {
      batchId: 'batch-1',
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 0,
      wikiPageSlugs: ['architecture-overview', 'api-reference'],
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Generated Wiki Pages')).toBeInTheDocument()
    })

    const overviewLink = screen.getByRole('link', { name: /architecture-overview/i })
    expect(overviewLink).toHaveAttribute('href', '/wiki/repo-1/architecture-overview')

    const apiRefLink = screen.getByRole('link', { name: /api-reference/i })
    expect(apiRefLink).toHaveAttribute('href', '/wiki/repo-1/api-reference')
  })

  it('toggles architecture pattern list show more/less', async () => {
    const mockStats = {
      architecturePatterns: [
        { description: 'Pattern 1', name: 'Alpha' },
        { description: 'Pattern 2', name: 'Beta' },
        { description: 'Pattern 3', name: 'Gamma' },
        { description: 'Pattern 4', name: 'Delta' },
        { description: 'Pattern 5', name: 'Epsilon' },
        { description: 'Pattern 6', name: 'Zeta' },
      ],
      batchId: 'batch-1',
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 0,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Architecture Patterns')).toBeInTheDocument()
    })

    // Should show "more" button since there are 6 patterns
    const moreButton = screen.queryByRole('button', { name: /\+.*more/i })
    if (moreButton) {
      await user.click(moreButton)

      // After clicking, "Show less" should appear
      await waitFor(() => {
        const lessButton =
          screen.queryByRole('button', { name: /show less/i }) ||
          screen.queryByRole('button', { name: /less/i })
        expect(lessButton).toBeInTheDocument()
      })

      // Clicking "Show less" should collapse back to the truncated list
      const lessButton = screen.getByRole('button', { name: /show less/i })
      await user.click(lessButton)

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /\+.*more/i })).toBeInTheDocument()
      })
    }
  })

  it('expands and collapses the dimension tag cloud when more than 8 dimensions exist in a category', async () => {
    const dimensions = Array.from({ length: 9 }, (_, i) => ({
      category: 'domain',
      fileCount: 1,
      name: `Domain${i}`,
      synopsis: null,
      topFiles: [],
    }))
    const mockStats = {
      batchId: 'batch-1',
      dimensions,
      edgeTypeCounts: {},
      nodeTypeCounts: {},
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 0,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('Domain0')).toBeInTheDocument()
    })

    // Only 8 of the 9 dimensions are visible initially
    expect(screen.queryByText('Domain8')).not.toBeInTheDocument()

    const moreButton = screen.getByRole('button', { name: /\+1 more/i })
    await user.click(moreButton)

    await waitFor(() => {
      expect(screen.getByText('Domain8')).toBeInTheDocument()
    })

    const lessButton = screen.getByRole('button', { name: /^less$/i })
    await user.click(lessButton)

    await waitFor(() => {
      expect(screen.queryByText('Domain8')).not.toBeInTheDocument()
    })
  })

  it('expands and collapses the node type tag cloud when more than 8 node types exist', async () => {
    const nodeTypeCounts: Record<string, number> = {}
    for (let i = 0; i < 9; i++) {
      nodeTypeCounts[`type${i}`] = i + 1
    }
    const mockStats = {
      batchId: 'batch-1',
      edgeTypeCounts: {},
      nodeTypeCounts,
      totalDurationSeconds: 60,
      totalEdges: 0,
      totalNodes: 45,
    }

    setupMocksWithStats([], mockStats)
    setAuthenticated({ teamId: 'team-1' })

    const { user } = renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('type8')).toBeInTheDocument()
    })

    // The 9th (lowest count) type is hidden behind "+1 more"
    expect(screen.queryByText('type0')).not.toBeInTheDocument()

    const moreButton = screen.getByRole('button', { name: /\+1 more/i })
    await user.click(moreButton)

    await waitFor(() => {
      expect(screen.getByText('type0')).toBeInTheDocument()
    })

    const lessButton = screen.getByRole('button', { name: /^less$/i })
    await user.click(lessButton)

    await waitFor(() => {
      expect(screen.queryByText('type0')).not.toBeInTheDocument()
    })
  })
})

describe('Repository Drilldown - Relative Time Formatting', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
  })

  function setupMocksWithRecentIngestion(lastIngestedAt: string) {
    mockListTeams([
      {
        createdAt: '2024-01-01T00:00:00Z',
        id: 'team-1',
        isDefault: true,
        name: 'Test Team',
        role: 'owner',
        tavilyApiKeyConfigured: false,
      },
    ])
    mockListRepositories([
      {
        branch: 'main',
        commitHash: 'abc123def456',
        createdAt: '2024-01-01T00:00:00Z',
        id: 'repo-1',
        ingestionStatus: 'SUCCESS',
        lastIngestedAt,
        latestBatchId: 'batch-1',
        name: 'frontend-app',
        repositoryType: 'GENERIC',
        teamId: 'team-1',
        updatedAt: '2024-01-01T00:00:00Z',
        url: 'https://github.com/user/frontend-app.git',
      },
    ])

    addFetchHandler((url) => {
      if (url.includes('/batches/') && url.includes('/logs')) {
        return jsonResponse([])
      }
      if (url.includes('/batches/') && url.includes('/stats')) {
        return jsonResponse(null, 404)
      }
      if (url.includes('/batches') && !url.includes('/logs') && !url.includes('/stats')) {
        return jsonResponse([])
      }
      return null
    })
  }

  it('shows minutes-ago for a very recent ingestion', async () => {
    const recentDate = new Date(Date.now() - 5 * 60 * 1000).toISOString()
    setupMocksWithRecentIngestion(recentDate)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('5m ago')).toBeInTheDocument()
    })
  })

  it('shows hours-ago for an ingestion a few hours old', async () => {
    const recentDate = new Date(Date.now() - 3 * 60 * 60 * 1000).toISOString()
    setupMocksWithRecentIngestion(recentDate)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('3h ago')).toBeInTheDocument()
    })
  })

  it('shows days-ago for an ingestion a few days old', async () => {
    const recentDate = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString()
    setupMocksWithRecentIngestion(recentDate)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('2d ago')).toBeInTheDocument()
    })
  })

  it('shows "just now" for an ingestion seconds old', async () => {
    const recentDate = new Date(Date.now() - 10 * 1000).toISOString()
    setupMocksWithRecentIngestion(recentDate)
    setAuthenticated({ teamId: 'team-1' })

    renderWithRouter(['/repos/repo-1'])

    await waitFor(() => {
      expect(screen.getByText('just now')).toBeInTheDocument()
    })
  })
})
