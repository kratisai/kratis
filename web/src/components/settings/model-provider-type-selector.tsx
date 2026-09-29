import { Search } from 'lucide-react'
import { useMemo, useState } from 'react'

import type { ProviderType, SupportedProviderType } from '@/types/auth-types'

import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

import { ModelProviderIcon, PROVIDER_BRAND } from './model-provider-logos'

interface ModelProviderTypeSelectorProps {
  onSelect: (providerType: ProviderType) => void
  selectedProviderType: null | ProviderType
  supportedTypes: SupportedProviderType[]
}

export function ModelProviderTypeSelector({
  onSelect,
  selectedProviderType,
  supportedTypes,
}: ModelProviderTypeSelectorProps) {
  const [filter, setFilter] = useState('')

  const filtered = useMemo(() => {
    const query = filter.trim().toLowerCase()
    if (!query) return supportedTypes
    return supportedTypes.filter(
      (type) =>
        type.displayName.toLowerCase().includes(query) ||
        type.description.toLowerCase().includes(query),
    )
  }, [filter, supportedTypes])

  return (
    <div className="flex h-full min-h-0 flex-1 flex-col py-2">
      <div className="relative mb-3">
        <Search className="text-muted-foreground absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2" />
        <Input
          aria-label="Filter providers"
          className="pl-9"
          onChange={(e) => setFilter(e.target.value)}
          placeholder="Search providers..."
          value={filter}
        />
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto pr-1">
        {filtered.length === 0 ? (
          <p className="text-muted-foreground py-8 text-center text-sm">
            No providers match &quot;{filter}&quot;
          </p>
        ) : (
          <div className="grid grid-cols-2 gap-3 pb-4 sm:grid-cols-3">
            {filtered.map((type) => {
              const providerType = type.type as ProviderType
              const isSelected = selectedProviderType === providerType
              const brand = PROVIDER_BRAND[providerType]
              return (
                <button
                  aria-pressed={isSelected}
                  className={cn(
                    'hover:border-primary hover:bg-accent/40 group border-border/60 bg-card flex flex-col items-center justify-center rounded-xl border p-5 text-center transition-all duration-200',
                    isSelected && 'border-primary bg-primary/5',
                  )}
                  key={type.type}
                  onClick={() => onSelect(providerType)}
                  type="button"
                >
                  <div
                    className={cn(
                      'mb-3 flex h-12 w-12 items-center justify-center rounded-xl transition-transform group-hover:scale-105',
                      brand.bgClass,
                    )}
                  >
                    <ModelProviderIcon
                      className={brand.iconClass}
                      providerType={providerType}
                      size={28}
                    />
                  </div>
                  <span className="text-sm font-semibold">{type.displayName}</span>
                  <span className="text-muted-foreground mt-1 text-[10px] sm:inline">
                    {type.description}
                  </span>
                  {providerType === 'OLLAMA' && (
                    <Badge className="mt-2" variant="secondary">
                      Local
                    </Badge>
                  )}
                  {providerType === 'OTHER' && (
                    <Badge className="mt-2" variant="outline">
                      Custom endpoint
                    </Badge>
                  )}
                </button>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
