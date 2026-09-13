import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import * as z from 'zod'

import type {
  CreateModelProviderRequest,
  ModelEntryDto,
  ModelProviderDto,
  ProviderType,
  TeamDto,
  UpdateModelProviderRequest,
} from '@/types/auth-types'

import { Form } from '@/components/ui/form'
import { WizardFooter, WizardShell } from '@/components/wizard/wizard-shell'
import {
  useModelProviders,
  useSupportedProviderTypes,
  useTestConnection,
} from '@/hooks/use-model-providers'
import { getFieldErrors } from '@/lib/auth-api'
import { classifyDiscoveredModels } from '@/lib/model-classification'
import { discoverModels } from '@/lib/model-provider-api'

import {
  type ConfigureFormValues,
  ModelProviderConfigureStep,
} from './model-provider-configure-step'
import { ModelProviderDefaultsStep } from './model-provider-defaults-step'
import { ModelProviderIcon } from './model-provider-logos'
import { ModelProviderModelsStep } from './model-provider-models-step'
import { ModelProviderTypeSelector } from './model-provider-type-selector'
import {
  type ModelProviderDefaultsState,
  type ModelProviderWizardStep,
  useModelProviderWizard,
} from './use-model-provider-wizard'

const configureSchema = z.object({
  apiKey: z.string().optional(),
  baseUrl: z.string().url('Must be a valid URL').optional().or(z.literal('')),
  displayName: z.string().min(1, 'Name is required'),
})

export interface ModelProviderDefaultsPayload {
  embedding?: { model: string }
  ingestion?: { model: string }
}

export type ModelProviderSubmitPayload =
  | {
      data: CreateModelProviderRequest
      defaults?: ModelProviderDefaultsPayload
      mode: 'create'
    }
  | {
      data: UpdateModelProviderRequest
      defaults?: ModelProviderDefaultsPayload
      mode: 'edit'
      providerId: string
    }

interface ModelProviderWizardDialogProps {
  onOpenChange: (open: boolean) => void
  onSubmit: (payload: ModelProviderSubmitPayload, retryDefaultsOnly?: boolean) => Promise<boolean>
  open: boolean
  provider?: ModelProviderDto
  team?: TeamDto
}

export function ModelProviderWizardDialog({
  onOpenChange,
  onSubmit,
  open,
  provider,
  team,
}: ModelProviderWizardDialogProps) {
  const { actions, state } = useModelProviderWizard()
  const { data: providers } = useModelProviders()
  const {
    data: supportedTypes,
    error: supportedTypesError,
    isLoading: loadingTypes,
  } = useSupportedProviderTypes()
  const { mutateAsync: testConnection } = useTestConnection()

  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<null | string>(null)
  const lastPayloadRef = useRef<ModelProviderSubmitPayload | null>(null)
  const modelsInitializedRef = useRef(false)
  const defaultsInitializedRef = useRef(false)

  const form = useForm<ConfigureFormValues>({
    defaultValues: { apiKey: '', baseUrl: '', displayName: '' },
    resolver: zodResolver(configureSchema),
  })

  const isEdit = !!provider
  const selectedProviderType = state.selectedProviderType
  const currentProviderType = supportedTypes?.find((t) => t.type === selectedProviderType)

  const steps = useMemo<{ id: ModelProviderWizardStep; label: string }[]>(
    () =>
      provider
        ? [
            { id: 'configure', label: 'Configure' },
            { id: 'models', label: 'Models' },
            { id: 'defaults', label: 'Defaults' },
          ]
        : [
            { id: 'provider', label: 'Provider' },
            { id: 'configure', label: 'Configure' },
            { id: 'models', label: 'Models' },
            { id: 'defaults', label: 'Defaults' },
          ],
    [provider],
  )

  const stepDescriptions: Record<ModelProviderWizardStep, string> = {
    configure: provider
      ? 'Update connection details. Click Next to rediscover models.'
      : 'Enter your connection details. Click Next to verify the connection.',
    defaults: 'Set the default models for ingestion and embeddings',
    models: 'Choose which models to make available to agents',
    provider: 'Select the model provider you want to connect',
  }

  // Initialize the wizard whenever the dialog opens. Radix does not invoke
  // onOpenChange for programmatic opens, so reset here rather than in the handler.
  useEffect(() => {
    if (!open) return
    actions.reset(isEdit, provider?.providerType ?? null)
    form.reset({
      apiKey: '',
      baseUrl: provider?.baseUrl ?? '',
      displayName: provider?.displayName ?? '',
    })
    modelsInitializedRef.current = false
    defaultsInitializedRef.current = false
    setSaving(false)
    setSaveError(null)
  }, [
    actions,
    form,
    isEdit,
    open,
    provider?.baseUrl,
    provider?.displayName,
    provider?.providerType,
  ])

  const handleOpenChange = (nextOpen: boolean) => {
    onOpenChange(nextOpen)
  }

  const handleSelectProviderType = (providerType: ProviderType) => {
    actions.selectProviderType(providerType)
    const meta = supportedTypes?.find((t) => t.type === providerType)
    form.setValue('displayName', meta?.displayName ?? '', { shouldValidate: true })
    form.setValue('baseUrl', meta?.requiresBaseUrl ? (meta.defaultBaseUrl ?? '') : '', {
      shouldValidate: true,
    })
  }

  const applyServerFieldErrors = (fieldErrors: Record<string, string>): boolean => {
    let applied = false
    for (const field of ['apiKey', 'baseUrl', 'displayName'] as const) {
      const message = fieldErrors[field]
      if (message) {
        form.setError(field, { message })
        applied = true
      }
    }
    return applied
  }

  const serverErrorSummary = (error: unknown, fallback: string): string => {
    const detail = Object.values(getFieldErrors(error)).join(' ')
    if (detail) return detail
    return error instanceof Error ? error.message : fallback
  }

  const handleConnect = async () => {
    const values = form.getValues()
    if (!selectedProviderType) return

    const typeMeta = supportedTypes?.find((t) => t.type === selectedProviderType)
    if (!isEdit && typeMeta?.requiresApiKey && !values.apiKey) {
      form.setError('apiKey', { message: 'API Key is required' })
      return
    }
    if (typeMeta?.requiresBaseUrl && !values.baseUrl) {
      form.setError('baseUrl', { message: 'Base URL is required' })
      return
    }

    const valid = await form.trigger()
    if (!valid) return

    const fingerprint = JSON.stringify({
      apiKey: values.apiKey || null,
      baseUrl: values.baseUrl || null,
      editProviderId: provider?.id ?? null,
      providerType: selectedProviderType,
    })
    if (state.connectionFingerprint === fingerprint && state.discoveredModels.length > 0) {
      actions.goToStep('models')
      return
    }
    actions.setConnecting()
    try {
      let discovered: ModelEntryDto[]
      if (provider && !values.apiKey) {
        discovered = await discoverModels(provider.id)
      } else {
        const result = await testConnection({
          apiKey: values.apiKey || undefined,
          baseUrl: values.baseUrl || undefined,
          providerType: selectedProviderType,
        })
        if (!result.success) {
          actions.setConnectionError(result.error ?? 'Connection failed')
          return
        }
        discovered = classifyDiscoveredModels(result.models ?? [])
      }
      if (discovered.length === 0) {
        actions.setConnectionError('Connected, but no models were discovered')
        return
      }
      actions.setConnected(discovered, fingerprint)
      actions.goToStep('models')
    } catch (error) {
      if (applyServerFieldErrors(getFieldErrors(error))) {
        actions.setConnectionError('Please fix the highlighted fields.')
        return
      }
      actions.setConnectionError(serverErrorSummary(error, 'Connection failed'))
    }
  }

  const goToStep = (step: ModelProviderWizardStep) => {
    actions.goToStep(step)
  }

  // Pre-select stored models once the models step renders (after SET_CONNECTED lands)
  useEffect(() => {
    if (state.currentStep !== 'models' || modelsInitializedRef.current) return
    modelsInitializedRef.current = true
    if (!provider?.models) return
    const discoverable = new Set(state.discoveredModels.map((model) => model.modelName))
    const stored = provider.models
      .filter((model) => discoverable.has(model.modelName))
      .map((model) => model.modelName)
    if (stored.length > 0) {
      actions.setSelectedModels(stored)
    }
  }, [actions, provider?.models, state.currentStep, state.discoveredModels])

  // Pre-select the new provider as team default when the defaults step renders
  useEffect(() => {
    if (state.currentStep !== 'defaults' || defaultsInitializedRef.current) return
    defaultsInitializedRef.current = true
    const chatModel = state.discoveredModels.find((model) => model.kind === 'CHAT')?.modelName
    const embeddingModel = state.discoveredModels.find(
      (model) => model.kind === 'EMBEDDING',
    )?.modelName
    actions.setDefaults({
      embedding: team?.embeddingProvider ? 'keep' : embeddingModel ? 'use' : 'keep',
      embeddingModel: embeddingModel ?? '',
      ingestion: team?.ingestionProvider ? 'keep' : chatModel ? 'use' : 'keep',
      ingestionModel: chatModel ?? '',
    })
  }, [
    actions,
    state.currentStep,
    state.discoveredModels,
    team?.embeddingProvider,
    team?.ingestionProvider,
  ])

  const buildDefaultsPayload = (
    defaults: ModelProviderDefaultsState,
  ): ModelProviderDefaultsPayload | undefined => {
    const payload: ModelProviderDefaultsPayload = {}
    if (defaults.ingestion === 'use' && defaults.ingestionModel) {
      payload.ingestion = { model: defaults.ingestionModel }
    }
    if (defaults.embedding === 'use' && defaults.embeddingModel) {
      payload.embedding = { model: defaults.embeddingModel }
    }
    return payload.ingestion || payload.embedding ? payload : undefined
  }

  const submitWithRetry = async (retryDefaultsOnly = false) => {
    const payload = lastPayloadRef.current
    if (!payload) return
    setSaving(true)
    setSaveError(null)
    try {
      const success = await onSubmit(payload, retryDefaultsOnly)
      if (success) {
        setSaving(false)
        onOpenChange(false)
      } else {
        setSaving(false)
        setSaveError(
          'Provider saved, but updating team defaults failed. You can retry the defaults update.',
        )
      }
    } catch (error) {
      setSaving(false)
      if (applyServerFieldErrors(getFieldErrors(error))) {
        goToStep('configure')
        return
      }
      setSaveError(serverErrorSummary(error, 'Failed to save the provider. Please try again.'))
    }
  }

  const handleFinish = () => {
    const values = form.getValues()
    const selectedModels: ModelEntryDto[] = state.selectedModelNames.map((modelName) => ({
      kind: state.discoveredModels.find((model) => model.modelName === modelName)?.kind ?? 'CHAT',
      modelName,
    }))
    const models = selectedModels.length > 0 ? selectedModels : undefined

    if (!provider) {
      lastPayloadRef.current = {
        data: {
          apiKey: values.apiKey || undefined,
          baseUrl: values.baseUrl || undefined,
          displayName: values.displayName,
          models,
          providerType: selectedProviderType as ProviderType,
        },
        defaults: buildDefaultsPayload(state.defaults),
        mode: 'create',
      }
    } else {
      lastPayloadRef.current = {
        data: {
          apiKey: values.apiKey === '' ? undefined : values.apiKey,
          baseUrl: values.baseUrl || undefined,
          displayName: values.displayName,
          models,
          providerType: provider.providerType,
        },
        defaults: buildDefaultsPayload(state.defaults),
        mode: 'edit',
        providerId: provider.id,
      }
    }
    void submitWithRetry(false)
  }

  const renderFooter = () => {
    switch (state.currentStep) {
      case 'configure':
        return (
          <WizardFooter
            backDisabled={state.connectionStatus === 'connecting'}
            nextDisabled={state.connectionStatus === 'connecting'}
            nextPending={state.connectionStatus === 'connecting'}
            nextPendingLabel="Connecting…"
            onBack={isEdit ? undefined : () => goToStep('provider')}
            onNext={() => void handleConnect()}
          />
        )
      case 'defaults':
        return (
          <WizardFooter
            onBack={() => goToStep('models')}
            onSubmit={handleFinish}
            submitDisabled={saving}
            submitLabel={isEdit ? 'Update' : 'Add Provider'}
            submitPending={saving}
            submitPendingLabel="Saving…"
          />
        )
      case 'models':
        return (
          <WizardFooter
            nextDisabled={state.selectedModelNames.length === 0}
            onBack={() => goToStep('configure')}
            onNext={() => goToStep('defaults')}
          />
        )
      case 'provider':
        return (
          <WizardFooter nextDisabled={!selectedProviderType} onNext={() => goToStep('configure')} />
        )
    }
  }

  return (
    <WizardShell
      currentStepId={state.currentStep}
      description={stepDescriptions[state.currentStep]}
      footer={renderFooter()}
      onOpenChange={handleOpenChange}
      open={open}
      steps={steps}
      title={isEdit ? 'Edit Model Provider' : 'Add Model Provider'}
    >
      {state.currentStep === 'provider' &&
        (loadingTypes ? (
          <p className="text-muted-foreground py-8 text-center text-sm">Loading providers...</p>
        ) : supportedTypesError ? (
          <p className="text-destructive py-8 text-center text-sm">
            Failed to load providers. Please try again.
          </p>
        ) : (
          <ModelProviderTypeSelector
            onSelect={handleSelectProviderType}
            selectedProviderType={selectedProviderType}
            supportedTypes={supportedTypes ?? []}
          />
        ))}

      {state.currentStep === 'configure' && (
        <div className="flex min-h-0 flex-1 flex-col">
          {isEdit && (
            <div className="bg-muted flex items-center gap-2 rounded-md p-2.5 text-sm">
              <ModelProviderIcon providerType={provider.providerType} size={16} />
              <span className="font-medium">{provider.providerType}</span>
              <span className="text-muted-foreground text-xs">Provider type cannot be changed</span>
            </div>
          )}
          <Form {...form}>
            <ModelProviderConfigureStep
              connectionError={state.connectionError}
              connectionStatus={state.connectionStatus}
              currentProviderType={currentProviderType}
              form={form}
              isEdit={isEdit}
            />
          </Form>
        </div>
      )}

      {state.currentStep === 'models' && (
        <ModelProviderModelsStep
          discoveredModels={state.discoveredModels}
          onSetAll={actions.setAllModels}
          onToggle={actions.toggleModel}
          selectedModelNames={state.selectedModelNames}
        />
      )}

      {state.currentStep === 'defaults' && (
        <div className="flex h-full min-h-0 flex-1 flex-col">
          <ModelProviderDefaultsStep
            defaults={state.defaults}
            discoveredModels={state.discoveredModels}
            onDefaultsChange={actions.setDefaults}
            providerDisplayName={form.getValues().displayName || selectedProviderType || ''}
            providers={providers ?? []}
            team={team}
          />
          {saveError && (
            <div
              className="text-destructive border-destructive/30 flex items-center justify-between gap-2 rounded-md border p-3 text-sm"
              role="status"
            >
              <span>{saveError}</span>
              <button
                className="text-destructive hover:underline"
                onClick={() => void submitWithRetry(true)}
                type="button"
              >
                Retry defaults
              </button>
            </div>
          )}
        </div>
      )}
    </WizardShell>
  )
}
