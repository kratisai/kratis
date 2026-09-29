import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useActivityStore } from '@/store/activity-store'
import { useAuthStore } from '@/store/auth-store'

describe('activity-store - HITL REST calls', () => {
  const fetchMock = vi.fn()
  let capturedRequests: Array<{ init: RequestInit; url: string }> = []

  beforeEach(() => {
    useActivityStore.setState({
      activitiesByExecution: {},
    })
    useAuthStore.setState({ accessToken: null })
    capturedRequests = []
    fetchMock.mockReset()
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      capturedRequests.push({ init: init ?? {}, url })
      return Promise.resolve(new Response(null, { status: 204 }))
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends an approval resolution with bearer token and the selected optionId', async () => {
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600,
    )

    await useActivityStore.getState().resolveHitl('exec-1', 'tc-1', 'approved', 'allow-once')

    expect(capturedRequests).toHaveLength(1)
    const { init, url } = capturedRequests[0]
    expect(url).toBe('/api/v1/hitl/resolve')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ Authorization: 'Bearer test-access-token' })
    expect(JSON.parse(String(init.body))).toEqual({
      executionId: 'exec-1',
      hitlId: 'tc-1',
      optionId: 'allow-once',
      response: 'approved',
    })
  })

  it('sends a decline resolution without an optionId', async () => {
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600,
    )

    await useActivityStore.getState().resolveHitl('exec-1', 'tc-1', 'declined')

    expect(capturedRequests).toHaveLength(1)
    const { init, url } = capturedRequests[0]
    expect(url).toBe('/api/v1/hitl/resolve')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ Authorization: 'Bearer test-access-token' })
    expect(JSON.parse(String(init.body))).toEqual({
      executionId: 'exec-1',
      hitlId: 'tc-1',
      response: 'declined',
    })
  })

  it('sends trimmed feedback with the resolution when provided', async () => {
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600,
    )

    await useActivityStore
      .getState()
      .resolveHitl('exec-1', 'tc-1', 'declined', 'reject-once', undefined, undefined, '  use pnpm instead  ')

    expect(capturedRequests).toHaveLength(1)
    expect(JSON.parse(String(capturedRequests[0].init.body))).toEqual({
      executionId: 'exec-1',
      feedback: 'use pnpm instead',
      hitlId: 'tc-1',
      optionId: 'reject-once',
      response: 'declined',
    })
  })

  it('omits blank feedback from the resolution body', async () => {
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600,
    )

    await useActivityStore.getState().resolveHitl('exec-1', 'tc-1', 'declined', 'reject-once', undefined, undefined, '   ')

    expect(capturedRequests).toHaveLength(1)
    const body = JSON.parse(String(capturedRequests[0].init.body)) as Record<string, unknown>
    expect(body).toEqual({
      executionId: 'exec-1',
      hitlId: 'tc-1',
      optionId: 'reject-once',
      response: 'declined',
    })
    expect('feedback' in body).toBe(false)
  })

  it('sends an answered question resolution with bearer token and content', async () => {
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600,
    )

    await useActivityStore
      .getState()
      .resolveHitl('exec-1', 'el-1', 'answered', undefined, { target: 'staging' })

    expect(capturedRequests).toHaveLength(1)
    const { init, url } = capturedRequests[0]
    expect(url).toBe('/api/v1/hitl/resolve')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ Authorization: 'Bearer test-access-token' })
    expect(JSON.parse(String(init.body))).toEqual({
      content: { target: 'staging' },
      executionId: 'exec-1',
      hitlId: 'el-1',
      response: 'answered',
    })
  })

  it('throws when resolveHitl is not ok', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(new Response(null, { status: 403 })))

    await expect(
      useActivityStore.getState().resolveHitl('exec-1', 'tc-1', 'approved', 'allow-once'),
    ).rejects.toThrow('Failed to resolve HITL: 403')
  })

  it('sends cancelHitl with bearer token from auth store', async () => {
    useAuthStore.getState().login(
      { email: 'test@test.com', id: 'user-1', name: 'Test User' },
      'test-access-token',
      'test-refresh-token',
      3600,
    )

    await useActivityStore.getState().cancelHitl('exec-1', 'tc-1')

    expect(capturedRequests).toHaveLength(1)
    const { init, url } = capturedRequests[0]
    expect(url).toBe('/api/v1/hitl/resolve')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ Authorization: 'Bearer test-access-token' })
    expect(JSON.parse(String(init.body))).toEqual({
      content: null,
      executionId: 'exec-1',
      hitlId: 'tc-1',
      optionId: null,
      response: 'cancelled',
    })
  })

  it('throws when cancelHitl is not ok', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(new Response(null, { status: 404 })))

    await expect(useActivityStore.getState().cancelHitl('exec-1', 'tc-1')).rejects.toThrow(
      'Failed to cancel HITL: 404',
    )
  })
})
