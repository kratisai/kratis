import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { IngestionResult } from '@/types/websocket-types'

import { queryClient } from '@/lib/query-client'
import { useActivityStore } from '@/store/activity-store'
import { useAuthStore } from '@/store/auth-store'
import { useChatStore } from '@/store/chat-store'
import { useExecutionStore } from '@/store/execution-store'
import {
  handleExecutionCompleteResult,
  handleExecutionStatusChangedResult,
  handleIngestionResult,
  resyncActiveExecution,
  teamEntityQueryKey,
} from '@/store/websocket-store'

describe('teamEntityQueryKey', () => {
  it('maps REPOSITORIES to the repositories query key', () => {
    expect(teamEntityQueryKey('REPOSITORIES', 'team-1')).toEqual(['repositories', 'team-1'])
  })

  it('maps CREDENTIALS to the credentials query key', () => {
    expect(teamEntityQueryKey('CREDENTIALS', 'team-1')).toEqual(['credentials', 'team-1'])
  })

  it('maps MODEL_PROVIDERS to the model-providers query key', () => {
    expect(teamEntityQueryKey('MODEL_PROVIDERS', 'team-1')).toEqual(['model-providers', 'team-1'])
  })

  it('maps ENVIRONMENTS to the environments query key', () => {
    expect(teamEntityQueryKey('ENVIRONMENTS', 'team-1')).toEqual(['environments', 'team-1'])
  })

  it('maps CHATS to the chats query key', () => {
    expect(teamEntityQueryKey('CHATS', 'team-1')).toEqual(['chats', 'team-1'])
  })

  it('returns null for unmapped entity types', () => {
    expect(teamEntityQueryKey('SANDBOX_EXECUTIONS', 'team-1')).toBeNull()
    expect(teamEntityQueryKey('UNKNOWN', 'team-1')).toBeNull()
  })
})

describe('resyncActiveExecution', () => {
  beforeEach(() => {
    useChatStore.setState({ currentChatId: null })
    vi.restoreAllMocks()
  })

  afterEach(() => {
    useChatStore.setState({ currentChatId: null })
  })

  it('does nothing when no chat is open', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    resyncActiveExecution()

    expect(invalidateSpy).not.toHaveBeenCalled()
  })

  it('invalidates the chat execution query of the current chat after a reconnect', () => {
    useChatStore.setState({ currentChatId: 'chat-1' })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    resyncActiveExecution()

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['chat-executions', 'chat-1'] })
  })
})

describe('handleExecutionStatusChangedResult', () => {
  beforeEach(() => {
    useAuthStore.setState({ currentTeamId: 'team-1' })
    vi.restoreAllMocks()
  })

  afterEach(() => {
    useAuthStore.setState({ currentTeamId: null })
  })

  it('invalidates the chat execution query when the team matches', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    handleExecutionStatusChangedResult({
      chatId: 'chat-1',
      executionId: 'exec-1',
      teamId: 'team-1',
      type: 'execution_status_changed',
    })

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['chat-executions', 'chat-1'] })
  })

  it('does nothing when the team does not match', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    handleExecutionStatusChangedResult({
      chatId: 'chat-1',
      executionId: 'exec-1',
      teamId: 'team-other',
      type: 'execution_status_changed',
    })

    expect(invalidateSpy).not.toHaveBeenCalled()
  })

  it('does nothing when no team is selected', () => {
    useAuthStore.setState({ currentTeamId: null })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    handleExecutionStatusChangedResult({
      chatId: 'chat-1',
      executionId: 'exec-1',
      teamId: 'team-1',
      type: 'execution_status_changed',
    })

    expect(invalidateSpy).not.toHaveBeenCalled()
  })
})

describe('handleExecutionCompleteResult', () => {
  beforeEach(() => {
    useExecutionStore.setState({ logs: {}, terminalOpen: false })
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    useExecutionStore.setState({ logs: {}, terminalOpen: false })
  })

  it('appends a keyed completion log and updates activities, without an isExecuting flag', () => {
    const activitySpy = vi.spyOn(useActivityStore.getState(), 'handleExecutionComplete')

    handleExecutionCompleteResult({
      executionId: 'exec-1',
      exitCode: 0,
      status: 'COMPLETED',
      type: 'execution_complete',
    })

    expect(useExecutionStore.getState().logs['exec-1']).toEqual([
      '[System] Command completed successfully with exit code 0.',
    ])
    expect(activitySpy).toHaveBeenCalledWith({
      executionId: 'exec-1',
      exitCode: 0,
      status: 'COMPLETED',
      type: 'execution_complete',
    })
    expect(useExecutionStore.getState()).not.toHaveProperty('isExecuting')
  })

  it('does not touch the terminal panel state on completion', () => {
    useExecutionStore.setState({ terminalOpen: true })

    handleExecutionCompleteResult({
      executionId: 'exec-1',
      exitCode: 1,
      status: 'FAILED',
      type: 'execution_complete',
    })

    expect(useExecutionStore.getState().terminalOpen).toBe(true)
  })
})

describe('handleIngestionResult', () => {
  beforeEach(() => {
    useAuthStore.setState({ currentTeamId: 'team-1' })
    vi.restoreAllMocks()
  })

  afterEach(() => {
    useAuthStore.setState({ currentTeamId: null })
  })

  function ingestionResult(overrides: Partial<IngestionResult['event']> = {}): IngestionResult {
    return {
      event: {
        batchId: 'batch-1',
        commitHash: null,
        completedAt: '2026-01-01T00:00:00.000Z',
        repositoryId: 'repo-1',
        status: 'SUCCESS',
        ...overrides,
      },
      type: 'ingestion',
    }
  }

  it('invalidates batch-stats and wiki queries alongside repositories, ingestion-status and batch-history', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    handleIngestionResult(ingestionResult())

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['repositories', 'team-1'] })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ['ingestion-status', 'team-1', 'repo-1'],
    })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['batch-history', 'team-1', 'repo-1'] })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ['batch-stats', 'team-1', 'repo-1', 'batch-1'],
    })
    for (const wikiKey of ['wiki-page', 'wiki-children', 'wiki-pages', 'wiki-tree']) {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: [wikiKey, 'team-1', 'repo-1'] })
    }
  })

  it('does nothing when no team is selected', () => {
    useAuthStore.setState({ currentTeamId: null })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    handleIngestionResult(ingestionResult())

    expect(invalidateSpy).not.toHaveBeenCalled()
  })

  it('skips repo-scoped invalidation when the event has no repositoryId', () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined)

    handleIngestionResult(ingestionResult({ repositoryId: '' }))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['repositories', 'team-1'] })
    expect(invalidateSpy).not.toHaveBeenCalledWith({
      queryKey: ['ingestion-status', 'team-1', ''],
    })
    expect(invalidateSpy).not.toHaveBeenCalledWith({
      queryKey: ['batch-stats', 'team-1', '', 'batch-1'],
    })
    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: ['wiki-page', 'team-1', ''] })
  })
})
