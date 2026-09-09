import { describe, expect, it } from 'vitest'

import type { JsonRpcRequest } from '@/types/websocket-types'

describe('Protocol Fixtures Validation', () => {
  it('parses valid client auth fixture', () => {
    const fixture: JsonRpcRequest = {
      id: 0,
      jsonrpc: '2.0',
      method: 'auth',
      params: { token: 'valid-jwt-token' },
    }
    expect(fixture.method).toBe('auth')
    expect(fixture.jsonrpc).toBe('2.0')
  })

  it('parses valid chat send fixture', () => {
    const fixture: JsonRpcRequest = {
      id: 1,
      jsonrpc: '2.0',
      method: 'chat.send',
      params: {
        chatId: '123e4567-e89b-12d3-a456-426614174000',
        message: 'Build dashboard',
      },
    }
    expect(fixture.method).toBe('chat.send')
  })

  it('parses valid chat subscribe fixture', () => {
    const fixture: JsonRpcRequest = {
      id: 2,
      jsonrpc: '2.0',
      method: 'chat.subscribe',
      params: {
        chatId: '123e4567-e89b-12d3-a456-426614174000',
      },
    }
    expect(fixture.method).toBe('chat.subscribe')
  })
})
