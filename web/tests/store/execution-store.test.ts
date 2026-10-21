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
      subscriberCounts: {},
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
      subscriberCounts: {},
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
    expect(state.subscriberCounts).toEqual({})
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

  it('sends execution.subscribe on the first subscriber only', () => {
    useExecutionStore.getState().subscribeExecution('exec-1')
    useExecutionStore.getState().subscribeExecution('exec-1')

    expect(sendMock).toHaveBeenCalledTimes(1)
    expect(sendMock).toHaveBeenCalledWith('execution.subscribe', { executionId: 'exec-1' })
    expect(useExecutionStore.getState().subscriberCounts['exec-1']).toBe(2)
  })

  it('clears the local activity log before the first subscription', () => {
    const clearSpy = vi.spyOn(useActivityStore.getState(), 'clearActivities')

    useExecutionStore.getState().subscribeExecution('exec-1')
    useExecutionStore.getState().subscribeExecution('exec-1')

    expect(clearSpy).toHaveBeenCalledTimes(1)
    expect(clearSpy).toHaveBeenCalledWith('exec-1')
    clearSpy.mockRestore()
  })

  it('keeps multiple execution subscriptions independent', () => {
    useExecutionStore.getState().subscribeExecution('exec-1')
    useExecutionStore.getState().subscribeExecution('exec-2')

    expect(sendMock).toHaveBeenCalledWith('execution.subscribe', { executionId: 'exec-1' })
    expect(sendMock).toHaveBeenCalledWith('execution.subscribe', { executionId: 'exec-2' })
    expect(useExecutionStore.getState().subscriberCounts).toEqual({ 'exec-1': 1, 'exec-2': 1 })
  })

  it('sends execution.unsubscribe only when the last subscriber leaves', () => {
    useExecutionStore.getState().subscribeExecution('exec-1')
    useExecutionStore.getState().subscribeExecution('exec-1')
    sendMock.mockClear()

    useExecutionStore.getState().unsubscribeExecution('exec-1')
    expect(sendMock).not.toHaveBeenCalled()
    expect(useExecutionStore.getState().subscriberCounts['exec-1']).toBe(1)

    useExecutionStore.getState().unsubscribeExecution('exec-1')
    expect(sendMock).toHaveBeenCalledWith('execution.unsubscribe', { executionId: 'exec-1' })
    expect(useExecutionStore.getState().subscriberCounts['exec-1']).toBeUndefined()
  })

  it('ignores unsubscribe for an execution with no subscribers', () => {
    useExecutionStore.getState().unsubscribeExecution('exec-1')

    expect(sendMock).not.toHaveBeenCalled()
  })

  describe('terminal log catch-up', () => {
    it('sends execution.get_logs for the requested execution', () => {
      useExecutionStore.getState().requestLogs('exec-1')

      expect(sendMock).toHaveBeenCalledWith('execution.get_logs', { executionId: 'exec-1' })
    })

    it('replaces local history with the sidecar tail and keeps lines streamed meanwhile', () => {
      useExecutionStore.setState({ logs: { 'exec-1': ['[Output] stale'] } })
      useExecutionStore.getState().requestLogs('exec-1')
      useExecutionStore.getState().addLog('exec-1', '[Output] live while waiting')

      useExecutionStore.getState().handleLogsResult({
        environmentId: 'env-1',
        executionId: 'exec-1',
        lines: ['one', 'two'],
        status: 'CONNECTED',
        type: 'execution_logs',
      })

      const state = useExecutionStore.getState()
      expect(state.logs['exec-1']).toEqual(['one', 'two', '[Output] live while waiting'])
    })

    it('keeps existing logs when the sidecar returns no lines', () => {
      useExecutionStore.setState({ logs: { 'exec-1': ['[System] done'] } })
      useExecutionStore.getState().requestLogs('exec-1')

      useExecutionStore.getState().handleLogsResult({
        environmentId: 'env-1',
        executionId: 'exec-1',
        lines: [],
        status: 'CONNECTED',
        type: 'execution_logs',
      })

      expect(useExecutionStore.getState().logs['exec-1']).toEqual(['[System] done'])
    })

    it.each(['DISCONNECTED', 'PENDING_CONNECT', 'SLEEPING', 'TERMINATED'] as const)(
      'leaves logs untouched for %s status',
      (status) => {
        useExecutionStore.setState({ logs: { 'exec-1': ['[System] done'] } })

        useExecutionStore.getState().handleLogsResult({
          environmentId: 'env-9',
          executionId: 'exec-1',
          lines: [],
          status,
          type: 'execution_logs',
        })

        expect(useExecutionStore.getState().logs['exec-1']).toEqual(['[System] done'])
      },
    )
  })
})
