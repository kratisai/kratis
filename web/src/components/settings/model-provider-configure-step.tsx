import type { UseFormReturn } from 'react-hook-form'

import { Check, X } from 'lucide-react'

import type { SupportedProviderType } from '@/types/auth-types'

import {
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'

import type { ConnectionStatus } from './use-model-provider-wizard'

export interface ConfigureFormValues {
  apiKey: string
  baseUrl: string
  displayName: string
}

interface ModelProviderConfigureStepProps {
  connectionError: null | string
  connectionStatus: ConnectionStatus
  currentProviderType: SupportedProviderType | undefined
  form: UseFormReturn<ConfigureFormValues>
  isEdit: boolean
}

export function ModelProviderConfigureStep({
  connectionError,
  connectionStatus,
  currentProviderType,
  form,
  isEdit,
}: ModelProviderConfigureStepProps) {
  const apiKeyRequired = currentProviderType?.requiresApiKey === true
  const baseUrlRequired = currentProviderType?.requiresBaseUrl === true
  const providerType = currentProviderType?.type

  return (
    <div className="h-full min-h-0 flex-1 space-y-4 overflow-y-auto py-2">
      <FormField
        control={form.control}
        name="displayName"
        render={({ field }) => (
          <FormItem>
            <FormLabel>Name</FormLabel>
            <FormControl>
              <Input
                autoComplete="off"
                placeholder={currentProviderType?.displayName ?? 'My provider'}
                {...field}
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
      {apiKeyRequired && (
        <FormField
          control={form.control}
          name="apiKey"
          render={({ field }) => (
            <FormItem>
              <FormLabel>API Key *</FormLabel>
              <FormControl>
                <Input
                  autoComplete="new-password"
                  placeholder={isEdit ? '•••••••••••••••• (leave blank to keep current)' : 'sk-...'}
                  type="password"
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      )}
      <FormField
        control={form.control}
        name="baseUrl"
        render={({ field }) => (
          <FormItem>
            <FormLabel>Base URL{baseUrlRequired ? ' *' : ' (Optional)'}</FormLabel>
            <FormControl>
              <Input
                autoComplete="off"
                placeholder={
                  providerType === 'AZURE_OPENAI'
                    ? 'https://<resource>.openai.azure.com'
                    : currentProviderType?.defaultBaseUrl || 'https://api.example.com'
                }
                {...field}
              />
            </FormControl>
            {currentProviderType?.defaultBaseUrl && !baseUrlRequired && (
              <FormDescription>Default: {currentProviderType.defaultBaseUrl}</FormDescription>
            )}
            {providerType === 'AZURE_OPENAI' && !baseUrlRequired && (
              <FormDescription>
                Your Azure resource endpoint, e.g. https://my-resource.openai.azure.com
              </FormDescription>
            )}
            <FormMessage />
          </FormItem>
        )}
      />
      {providerType === 'OTHER' && (
        <p className="bg-muted text-muted-foreground rounded-md p-3 text-xs">
          Custom endpoints work for planning-agent chat but are not currently routed to sandbox
          agents (ACP executions).
        </p>
      )}
      <ConnectionStatusPanel
        connectionError={connectionError}
        connectionStatus={connectionStatus}
      />
    </div>
  )
}

function ConnectionStatusPanel({
  connectionError,
  connectionStatus,
}: {
  connectionError: null | string
  connectionStatus: ConnectionStatus
}) {
  if (connectionStatus === 'error' && connectionError) {
    return (
      <div
        className="text-destructive border-destructive/30 flex items-start gap-2 rounded-md border p-3 text-sm"
        role="status"
      >
        <X className="mt-0.5 h-4 w-4 shrink-0" />
        <span>{connectionError}</span>
      </div>
    )
  }
  if (connectionStatus === 'connected') {
    return (
      <div
        className="flex items-center gap-2 rounded-md border border-green-500/30 p-3 text-sm text-green-700"
        role="status"
      >
        <Check className="h-4 w-4 shrink-0" />
        <span>Connection successful. Select models to continue.</span>
      </div>
    )
  }
  return null
}
