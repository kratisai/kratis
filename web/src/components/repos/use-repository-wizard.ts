import { useMemo, useReducer } from 'react'

import type { ProviderType, RepositoryWizardState, WizardStep } from './repository-form-types'

export interface UseRepositoryWizardReturn {
  actions: {
    goToStep: (step: WizardStep) => void
    reset: () => void
    selectCredential: (id: string) => void
    selectProvider: (provider: ProviderType) => void
    setIngestImmediately: (value: boolean) => void
  }
  state: RepositoryWizardState
}

type WizardAction =
  | { id: string; type: 'SELECT_CREDENTIAL' }
  | { ingestImmediately: boolean; type: 'SET_INGEST_IMMEDIATELY' }
  | { provider: ProviderType; type: 'SELECT_PROVIDER' }
  | { step: WizardStep; type: 'SET_STEP' }
  | { type: 'RESET_WIZARD' }

export function useRepositoryWizard(): UseRepositoryWizardReturn {
  const [state, dispatch] = useReducer(wizardReducer, createInitialState())

  const actions = useMemo(
    () => ({
      goToStep: (step: WizardStep) => dispatch({ step, type: 'SET_STEP' }),
      reset: () => dispatch({ type: 'RESET_WIZARD' }),
      selectCredential: (id: string) => dispatch({ id, type: 'SELECT_CREDENTIAL' }),
      selectProvider: (provider: ProviderType) => dispatch({ provider, type: 'SELECT_PROVIDER' }),
      setIngestImmediately: (ingestImmediately: boolean) =>
        dispatch({ ingestImmediately, type: 'SET_INGEST_IMMEDIATELY' }),
    }),
    [],
  )

  return { actions, state }
}

function createInitialState(): RepositoryWizardState {
  return {
    currentStep: 'provider',
    ingestImmediately: true,
    selectedCredentialId: '',
    selectedProvider: 'github',
  }
}

function wizardReducer(state: RepositoryWizardState, action: WizardAction): RepositoryWizardState {
  switch (action.type) {
    case 'RESET_WIZARD':
      return createInitialState()
    case 'SELECT_CREDENTIAL':
      return { ...state, selectedCredentialId: action.id }
    case 'SELECT_PROVIDER':
      return {
        ...state,
        // Auto-reset auth when changing provider
        selectedCredentialId: '',
        selectedProvider: action.provider,
      }
    case 'SET_INGEST_IMMEDIATELY':
      return { ...state, ingestImmediately: action.ingestImmediately }
    case 'SET_STEP':
      return { ...state, currentStep: action.step }
    default:
      return state
  }
}
