import { useMemo, useReducer } from 'react'

import type { ModelEntryDto, ModelKind, ProviderType } from '@/types/auth-types'

export type ConnectionStatus = 'connected' | 'connecting' | 'error' | 'idle'
export interface ModelProviderDefaultsState {
  embedding: 'keep' | 'use'
  embeddingModel: string
  ingestion: 'keep' | 'use'
  ingestionModel: string
}

export type ModelProviderWizardAction =
  | { defaults: ModelProviderDefaultsState; type: 'SET_DEFAULTS' }
  | { discoveredModels: ModelEntryDto[]; fingerprint: string; type: 'SET_CONNECTED' }
  | { edit: boolean; providerType: null | ProviderType; type: 'RESET' }
  | { kind: ModelKind; on: boolean; type: 'SET_ALL_MODELS' }
  | { message: string; type: 'SET_CONNECTION_ERROR' }
  | { modelName: string; type: 'TOGGLE_MODEL' }
  | { modelNames: string[]; type: 'SET_SELECTED_MODELS' }
  | { providerType: ProviderType; type: 'SELECT_PROVIDER_TYPE' }
  | { step: ModelProviderWizardStep; type: 'SET_STEP' }
  | { type: 'SET_CONNECTING' }

export interface ModelProviderWizardState {
  connectionError: null | string
  connectionFingerprint: null | string
  connectionStatus: ConnectionStatus
  currentStep: ModelProviderWizardStep
  defaults: ModelProviderDefaultsState
  discoveredModels: ModelEntryDto[]
  selectedModelNames: string[]
  selectedProviderType: null | ProviderType
}

export type ModelProviderWizardStep = 'configure' | 'defaults' | 'models' | 'provider'

export interface UseModelProviderWizardReturn {
  actions: {
    goToStep: (step: ModelProviderWizardStep) => void
    reset: (edit: boolean, providerType: null | ProviderType) => void
    selectProviderType: (providerType: ProviderType) => void
    setAllModels: (kind: ModelKind, on: boolean) => void
    setConnected: (discoveredModels: ModelEntryDto[], fingerprint: string) => void
    setConnecting: () => void
    setConnectionError: (message: string) => void
    setDefaults: (defaults: ModelProviderDefaultsState) => void
    setSelectedModels: (modelNames: string[]) => void
    toggleModel: (modelName: string) => void
  }
  state: ModelProviderWizardState
}

export function useModelProviderWizard(): UseModelProviderWizardReturn {
  const [state, dispatch] = useReducer(wizardReducer, null, createInitialState)

  const actions = useMemo(
    () => ({
      goToStep: (step: ModelProviderWizardStep) => dispatch({ step, type: 'SET_STEP' }),
      reset: (edit: boolean, providerType: null | ProviderType) =>
        dispatch({ edit, providerType, type: 'RESET' }),
      selectProviderType: (providerType: ProviderType) =>
        dispatch({ providerType, type: 'SELECT_PROVIDER_TYPE' }),
      setAllModels: (kind: ModelKind, on: boolean) =>
        dispatch({ kind, on, type: 'SET_ALL_MODELS' }),
      setConnected: (discoveredModels: ModelEntryDto[], fingerprint: string) =>
        dispatch({ discoveredModels, fingerprint, type: 'SET_CONNECTED' }),
      setConnecting: () => dispatch({ type: 'SET_CONNECTING' }),
      setConnectionError: (message: string) => dispatch({ message, type: 'SET_CONNECTION_ERROR' }),
      setDefaults: (defaults: ModelProviderDefaultsState) =>
        dispatch({ defaults, type: 'SET_DEFAULTS' }),
      setSelectedModels: (modelNames: string[]) =>
        dispatch({ modelNames, type: 'SET_SELECTED_MODELS' }),
      toggleModel: (modelName: string) => dispatch({ modelName, type: 'TOGGLE_MODEL' }),
    }),
    [],
  )

  return { actions, state }
}

function createInitialState(_init: null, _args?: undefined): ModelProviderWizardState {
  return {
    connectionError: null,
    connectionFingerprint: null,
    connectionStatus: 'idle',
    currentStep: 'provider',
    defaults: {
      embedding: 'keep',
      embeddingModel: '',
      ingestion: 'keep',
      ingestionModel: '',
    },
    discoveredModels: [],
    selectedModelNames: [],
    selectedProviderType: null,
  }
}

function wizardReducer(
  state: ModelProviderWizardState,
  action: ModelProviderWizardAction,
): ModelProviderWizardState {
  switch (action.type) {
    case 'RESET':
      return {
        ...createInitialState(null),
        currentStep: action.edit ? 'configure' : 'provider',
        selectedProviderType: action.providerType,
      }
    case 'SELECT_PROVIDER_TYPE':
      return {
        ...state,
        connectionError: null,
        connectionFingerprint: null,
        connectionStatus: 'idle',
        discoveredModels: [],
        selectedModelNames: [],
        selectedProviderType: action.providerType,
      }
    case 'SET_ALL_MODELS': {
      const kindModelNames = state.discoveredModels
        .filter((model) => model.kind === action.kind)
        .map((model) => model.modelName)
      const current = new Set(state.selectedModelNames)
      for (const name of kindModelNames) {
        if (action.on) {
          current.add(name)
        } else {
          current.delete(name)
        }
      }
      return { ...state, selectedModelNames: [...current] }
    }
    case 'SET_CONNECTED':
      return {
        ...state,
        connectionError: null,
        connectionFingerprint: action.fingerprint,
        connectionStatus: 'connected',
        discoveredModels: [...action.discoveredModels].sort((a, b) =>
          a.modelName.localeCompare(b.modelName),
        ),
      }
    case 'SET_CONNECTING':
      return { ...state, connectionError: null, connectionStatus: 'connecting' }
    case 'SET_CONNECTION_ERROR':
      return { ...state, connectionError: action.message, connectionStatus: 'error' }
    case 'SET_DEFAULTS':
      return { ...state, defaults: action.defaults }
    case 'SET_SELECTED_MODELS':
      return { ...state, selectedModelNames: action.modelNames }
    case 'SET_STEP':
      return { ...state, currentStep: action.step }
    case 'TOGGLE_MODEL':
      return {
        ...state,
        selectedModelNames: state.selectedModelNames.includes(action.modelName)
          ? state.selectedModelNames.filter((name) => name !== action.modelName)
          : [...state.selectedModelNames, action.modelName],
      }
  }
}
