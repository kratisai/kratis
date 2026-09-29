import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useActivityStore } from '@/store/activity-store'
import { useExecutionStore } from '@/store/execution-store'

const { sendMock } = vi.hoisted(() => ({ sendMock: vi.fn() }))

vi.mock('@/store/websocket-store', () => ({
  useWebSocketStore: { getState: () => ({ send: sendMock }) },
}))

describe('useExecutionStore', () => {
  beforeEach(() => {
    useExecutionStore.setState({
      logs: {},
      replayingExecutionId: null,
      terminalFullscreen: false,
      terminalHeight: 256,
      terminalOpen: false,
    })
    sendMock.mockClear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    useExecutionStore.setState({
      logs: {},
      replayingExecutionId: null,
      terminalFullscreen: false,
      terminalHeight: 256,
      terminalOpen: false,
    })
  })

  it('should have initial state', () => {
    const state = useExecutionStore.getState()
    expect(state.logs).toEqual({})
    expect(state.terminalFullscreen).toBe(false)
    expect(state.terminalHeight).toBe(256)
    expect(state.terminalOpen).toBe(false)
    expect(state.replayingExecutionId).toBeNull()
  })

  it('should add a log keyed by execution ID', () => {
    useExecutionStore.getState().addLog('exec-1', 'Test log')
    expect(useExecutionStore.getState().logs).toEqual({ 'exec-1': ['Test log'] })
  })

  it('should keep logs of different executions independent', () => {
    useExecutionStore.getState().addLog('exec-1', 'line for exec-1')
    useExecutionStore.getState().addLog('exec-2', 'line for exec-2')

    expect(useExecutionStore.getState().logs).toEqual({
      'exec-1': ['line for exec-1'],
      'exec-2': ['line for exec-2'],
    })
  })

  it('should clear logs for a single execution', () => {
    useExecutionStore.getState().addLog('exec-1', 'Test log')
    useExecutionStore.getState().addLog('exec-2', 'Other log')
    useExecutionStore.getState().clearLogs('exec-1')

    const state = useExecutionStore.getState()
    expect(state.logs['exec-1']).toBeUndefined()
    expect(state.logs['exec-2']).toEqual(['Other log'])
  })

  it('should be a no-op for an unknown execution ID', () => {
    useExecutionStore.getState().addLog('exec-1', 'log a')
    const before = useExecutionStore.getState().logs

    useExecutionStore.getState().clearLogs('unknown')

    expect(useExecutionStore.getState().logs).toBe(before)
  })

  it('should set terminalOpen', () => {
    useExecutionStore.getState().setTerminalOpen(true)
    expect(useExecutionStore.getState().terminalOpen).toBe(true)
  })

  it('should set terminalHeight', () => {
    useExecutionStore.getState().setTerminalHeight(480)
    expect(useExecutionStore.getState().terminalHeight).toBe(480)
  })

  it('should set terminalFullscreen', () => {
    useExecutionStore.getState().setTerminalFullscreen(true)
    expect(useExecutionStore.getState().terminalFullscreen).toBe(true)
  })

  it('should allow exiting fullscreen', () => {
    useExecutionStore.setState({ terminalFullscreen: true })
    useExecutionStore.getState().setTerminalFullscreen(false)
    expect(useExecutionStore.getState().terminalFullscreen).toBe(false)
  })

  it('clears stale activities, flags the replay and sends the replay RPC', () => {
    const clearSpy = vi.spyOn(useActivityStore.getState(), 'clearActivities')
    useExecutionStore.getState().replayActivities('exec-1')

    expect(clearSpy).toHaveBeenCalledWith('exec-1')
    expect(useExecutionStore.getState().replayingExecutionId).toBe('exec-1')
    expect(sendMock).toHaveBeenCalledWith('execution.replay_activities', {
      executionId: 'exec-1',
    })
    clearSpy.mockRestore()
  })

  it('clears the replaying flag for the matching execution', () => {
    useExecutionStore.setState({ replayingExecutionId: 'exec-1' })

    useExecutionStore.getState().handleReplayComplete({
      activityCount: 3,
      executionId: 'exec-1',
      type: 'execution_replay_complete',
    })

    expect(useExecutionStore.getState().replayingExecutionId).toBeNull()
  })

  it('ignores completion markers for a different execution', () => {
    useExecutionStore.setState({ replayingExecutionId: 'exec-1' })

    useExecutionStore.getState().handleReplayComplete({
      activityCount: 1,
      executionId: 'exec-2',
      type: 'execution_replay_complete',
    })

    expect(useExecutionStore.getState().replayingExecutionId).toBe('exec-1')
  })
})
