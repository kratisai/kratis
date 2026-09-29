import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import {
  type ModelProviderWizardState,
  useModelProviderWizard,
} from '@/components/settings/use-model-provider-wizard'

const initialCreate: ModelProviderWizardState = {
  connectionError: null,
  connectionFingerprint: null,
  connectionStatus: 'idle',
  currentStep: 'provider',
  defaults: { embedding: 'keep', embeddingModel: '', ingestion: 'keep', ingestionModel: '' },
  discoveredModels: [],
  selectedModelNames: [],
  selectedProviderType: null,
}

describe('useModelProviderWizard', () => {
  it('starts on the provider step for create mode', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    expect(result.current.state.currentStep).toBe('provider')
    expect(result.current.state.selectedProviderType).toBeNull()
  })

  it('starts on the configure step for edit mode', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.reset(true, 'OPENAI')
    })
    expect(result.current.state.currentStep).toBe('configure')
    expect(result.current.state.selectedProviderType).toBe('OPENAI')
  })

  it('selecting a provider resets connection and discovery state', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.selectProviderType('OPENAI')
      result.current.actions.setConnecting()
      result.current.actions.setConnected(
        [{ kind: 'CHAT', modelName: 'gpt-4' }],
        'fingerprint-1',
      )
      result.current.actions.toggleModel('gpt-4')
    })
    expect(result.current.state.connectionStatus).toBe('connected')
    expect(result.current.state.selectedModelNames).toEqual(['gpt-4'])

    act(() => {
      result.current.actions.selectProviderType('ANTHROPIC')
    })
    expect(result.current.state.connectionStatus).toBe('idle')
    expect(result.current.state.connectionFingerprint).toBeNull()
    expect(result.current.state.discoveredModels).toEqual([])
    expect(result.current.state.selectedModelNames).toEqual([])
    expect(result.current.state.selectedProviderType).toBe('ANTHROPIC')
  })

  it('tracks connection status transitions', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.setConnecting()
    })
    expect(result.current.state.connectionStatus).toBe('connecting')

    act(() => {
      result.current.actions.setConnectionError('boom')
    })
    expect(result.current.state.connectionStatus).toBe('error')
    expect(result.current.state.connectionError).toBe('boom')

    act(() => {
      result.current.actions.setConnected(
        [{ kind: 'CHAT', modelName: 'gpt-4' }],
        'fp',
      )
    })
    expect(result.current.state.connectionStatus).toBe('connected')
    expect(result.current.state.connectionFingerprint).toBe('fp')
    expect(result.current.state.connectionError).toBeNull()
  })

  it('sorts discovered models alphabetically on connect', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.setConnected(
        [
          { kind: 'CHAT', modelName: 'gpt-4o' },
          { kind: 'EMBEDDING', modelName: 'text-embedding-ada-002' },
          { kind: 'CHAT', modelName: 'gpt-4' },
          { kind: 'CHAT', modelName: 'gpt-3.5-turbo' },
        ],
        'fp',
      )
    })
    expect(result.current.state.discoveredModels.map((model) => model.modelName)).toEqual([
      'gpt-3.5-turbo',
      'gpt-4',
      'gpt-4o',
      'text-embedding-ada-002',
    ])
  })

  it('toggles individual models', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.setConnected(
        [
          { kind: 'CHAT', modelName: 'gpt-4' },
          { kind: 'CHAT', modelName: 'gpt-4o' },
        ],
        'fp',
      )
      result.current.actions.toggleModel('gpt-4')
      result.current.actions.toggleModel('gpt-4')
      result.current.actions.toggleModel('gpt-4o')
    })
    expect(result.current.state.selectedModelNames).toEqual(['gpt-4o'])
  })

  it('selects and clears all models of a kind', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.setConnected(
        [
          { kind: 'CHAT', modelName: 'gpt-4' },
          { kind: 'EMBEDDING', modelName: 'embed-1' },
          { kind: 'EMBEDDING', modelName: 'embed-2' },
        ],
        'fp',
      )
      result.current.actions.setAllModels('EMBEDDING', true)
    })
    expect(result.current.state.selectedModelNames).toEqual(['embed-1', 'embed-2'])

    act(() => {
      result.current.actions.setAllModels('EMBEDDING', false)
    })
    expect(result.current.state.selectedModelNames).toEqual([])
  })

  it('sets the selected models wholesale (edit mode prefill)', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.setSelectedModels(['gpt-4', 'gpt-4o'])
    })
    expect(result.current.state.selectedModelNames).toEqual(['gpt-4', 'gpt-4o'])
  })

  it('updates team defaults selection', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.setDefaults({
        embedding: 'use',
        embeddingModel: 'embed-1',
        ingestion: 'keep',
        ingestionModel: '',
      })
    })
    expect(result.current.state.defaults).toEqual({
      embedding: 'use',
      embeddingModel: 'embed-1',
      ingestion: 'keep',
      ingestionModel: '',
    })
  })

  it('navigates between steps', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.goToStep('models')
    })
    expect(result.current.state.currentStep).toBe('models')
    act(() => {
      result.current.actions.goToStep('defaults')
    })
    expect(result.current.state.currentStep).toBe('defaults')
  })

  it('reset restores the initial create state', () => {
    const { result } = renderHook(() => useModelProviderWizard())
    act(() => {
      result.current.actions.selectProviderType('OPENAI')
      result.current.actions.goToStep('models')
    })
    act(() => {
      result.current.actions.reset(false, null)
    })
    expect(result.current.state).toEqual(initialCreate)
  })
})
