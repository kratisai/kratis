import { Check, ChevronDown } from 'lucide-react'
import { useEffect, useMemo } from 'react'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useModelProviders } from '@/hooks/use-model-providers'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/store/auth-store'
import { useUIStore } from '@/store/ui-store'

export interface ModelSelectorDropdownProps {
  className?: string
  disabled?: boolean
  modelName?: null | string
  onSelectModel?: (providerId: string, modelName: string) => void
  providerId?: null | string
  triggerVariant?: 'compact' | 'form'
}

export function ModelSelectorDropdown({
  className,
  disabled = false,
  modelName: controlledModelName,
  onSelectModel,
  providerId: controlledProviderId,
  triggerVariant = 'compact',
}: ModelSelectorDropdownProps = {}) {
  const { currentTeamId } = useAuthStore()
  const storeSelectedModelName = useUIStore((s) => s.selectedModelName)
  const storeSelectedProviderId = useUIStore((s) => s.selectedProviderId)
  const setSelectedModel = useUIStore((s) => s.setSelectedModel)
  const { data: providers } = useModelProviders()

  const isControlled = Boolean(onSelectModel)
  const activeModelName = isControlled
    ? controlledModelName !== undefined
      ? controlledModelName
      : storeSelectedModelName
    : storeSelectedModelName
  const activeProviderId = isControlled
    ? controlledProviderId !== undefined
      ? controlledProviderId
      : storeSelectedProviderId
    : storeSelectedProviderId

  const chatModelsByProvider = useMemo(() => {
    const map = new Map<string, string[]>()
    for (const provider of providers ?? []) {
      const names = (provider.models ?? []).filter((m) => m.kind === 'CHAT').map((m) => m.modelName)
      map.set(provider.id, names)
    }
    return map
  }, [providers])

  const hasAnyModels = useMemo(() => {
    for (const names of chatModelsByProvider.values()) {
      if (names.length > 0) return true
    }
    return false
  }, [chatModelsByProvider])

  useEffect(() => {
    if (!providers || providers.length === 0) return
    if (isControlled) {
      if (activeProviderId && activeModelName) return
      for (const provider of providers) {
        const names = chatModelsByProvider.get(provider.id) ?? []
        if (names.length > 0) {
          onSelectModel?.(provider.id, names[0])
          return
        }
      }
    } else {
      if (storeSelectedProviderId) return
      for (const provider of providers) {
        const names = chatModelsByProvider.get(provider.id) ?? []
        if (names.length > 0) {
          setSelectedModel(provider.id, names[0])
          return
        }
      }
    }
  }, [
    providers,
    isControlled,
    activeProviderId,
    activeModelName,
    storeSelectedProviderId,
    chatModelsByProvider,
    onSelectModel,
    setSelectedModel,
  ])

  if (!currentTeamId || !providers || providers.length === 0 || !hasAnyModels) {
    if (triggerVariant === 'form') {
      return (
        <button
          className={cn(
            'border-input flex h-9 w-full items-center justify-between rounded-md border bg-transparent px-3 py-2 text-sm shadow-xs disabled:cursor-not-allowed disabled:opacity-50',
            className,
          )}
          disabled
          type="button"
        >
          <span className="text-muted-foreground truncate text-sm">No models</span>
          <ChevronDown className="text-muted-foreground h-4 w-4 opacity-50" />
        </button>
      )
    }
    return (
      <Button className={className} disabled size="sm" variant="ghost">
        <span className="text-xs">No models</span>
        <ChevronDown className="ml-1 h-3 w-3" />
      </Button>
    )
  }

  const displayModelName = activeModelName || 'Select model'

  const handleSelectModel = (providerId: string, modelName: string) => {
    if (isControlled) {
      onSelectModel?.(providerId, modelName)
    } else {
      setSelectedModel(providerId, modelName)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild disabled={disabled}>
        {triggerVariant === 'form' ? (
          <button
            className={cn(
              'border-input focus:ring-ring hover:bg-accent/10 flex h-9 w-full items-center justify-between rounded-md border bg-transparent px-3 py-2 text-sm shadow-xs transition-colors focus:ring-1 focus:outline-none disabled:cursor-not-allowed disabled:opacity-50',
              className,
            )}
            disabled={disabled}
            type="button"
          >
            <span
              className={cn(
                'truncate text-sm',
                activeModelName ? 'text-foreground' : 'text-muted-foreground',
              )}
            >
              {displayModelName}
            </span>
            <ChevronDown className="text-muted-foreground h-4 w-4 opacity-50" />
          </button>
        ) : (
          <button
            className={cn(
              'hover:border-accent inline-flex h-5 w-auto items-center gap-0.5 rounded border border-transparent px-1 py-0 text-xs transition-colors',
              className,
            )}
            disabled={disabled}
            type="button"
          >
            <span className="text-muted-foreground max-w-[100px] truncate text-xs">
              {displayModelName}
            </span>
            <ChevronDown className="text-muted-foreground h-3 w-3" />
          </button>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="start"
        className={
          triggerVariant === 'form'
            ? 'w-(--radix-dropdown-menu-trigger-width) min-w-[var(--radix-dropdown-menu-trigger-width)]'
            : 'w-64'
        }
      >
        {providers.map((provider) => {
          const modelNames = chatModelsByProvider.get(provider.id) ?? []
          if (modelNames.length === 0) return null
          return (
            <div key={provider.id}>
              <DropdownMenuLabel className="text-muted-foreground text-xs font-medium">
                {provider.displayName}
              </DropdownMenuLabel>
              {modelNames.map((modelName) => {
                const isSelected =
                  (activeProviderId ? activeProviderId === provider.id : true) &&
                  activeModelName === modelName
                return (
                  <DropdownMenuItem
                    className={cn('cursor-pointer px-2 py-1', isSelected && 'bg-accent')}
                    key={`${provider.id}-${modelName}`}
                    onSelect={() => handleSelectModel(provider.id, modelName)}
                  >
                    {isSelected && <Check className="mr-2 h-3 w-3" />}
                    <span
                      className={cn(
                        'text-muted-foreground truncate',
                        !isSelected && 'pl-5',
                        isSelected && 'text-foreground',
                      )}
                    >
                      {modelName}
                    </span>
                  </DropdownMenuItem>
                )
              })}
              <DropdownMenuSeparator />
            </div>
          )
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
