import { ArrowRight } from 'lucide-react'

import { Button } from '@/components/ui/button'

import {
  AzureDevOpsLogo,
  BitbucketLogo,
  CustomGitLogo,
  GitHubLogo,
  GitLabLogo,
  PublicRepoLogo,
} from './provider-logos'

export type ProviderType = 'azure' | 'bitbucket' | 'github' | 'gitlab' | 'public' | 'ssh_key'

interface ProviderSelectorProps {
  onNext: () => void
  onSelect: (provider: ProviderType) => void
  selectedProvider: ProviderType
}

const providers: {
  accentClass?: string
  icon: React.ReactNode
  id: ProviderType
  name: string
  subtitle: string
}[] = [
  {
    accentClass: 'bg-emerald-500/10',
    icon: <PublicRepoLogo className="text-emerald-500" size={28} />,
    id: 'public',
    name: 'Public repository',
    subtitle: 'No authentication needed',
  },
  {
    accentClass: 'bg-primary/10',
    icon: <CustomGitLogo size={28} />,
    id: 'ssh_key',
    name: 'Git with SSH key',
    subtitle: 'Any HTTPS or SSH URL',
  },
  {
    icon: <GitHubLogo className="text-foreground" size={28} />,
    id: 'github',
    name: 'GitHub',
    subtitle: 'App & Access Tokens',
  },
  {
    accentClass: 'bg-orange-500/10',
    icon: <GitLabLogo size={28} />,
    id: 'gitlab',
    name: 'GitLab',
    subtitle: 'SaaS & Self-Hosted',
  },
  {
    accentClass: 'bg-blue-500/10',
    icon: <BitbucketLogo size={28} />,
    id: 'bitbucket',
    name: 'Bitbucket',
    subtitle: 'PAT & SSH Keys',
  },
  {
    accentClass: 'bg-indigo-500/10',
    icon: <AzureDevOpsLogo size={28} />,
    id: 'azure',
    name: 'Azure DevOps',
    subtitle: 'Token Authentication',
  },
]

export function ProviderSelector({ onNext, onSelect, selectedProvider }: ProviderSelectorProps) {
  return (
    <div className="flex h-full min-h-0 flex-1 flex-col py-2">
      <div className="min-h-0 flex-1 overflow-y-auto pr-1">
        <div className="grid grid-cols-2 gap-3 pb-4 sm:grid-cols-3">
          {providers.map((provider) => (
            <button
              className={`hover:border-primary hover:bg-accent/40 group border-border/60 bg-card flex flex-col items-center justify-center rounded-xl border p-5 text-center transition-all duration-200 ${
                selectedProvider === provider.id ? 'border-primary bg-primary/5' : ''
              }`}
              key={provider.id}
              onClick={() => onSelect(provider.id)}
              type="button"
            >
              <div
                className={`mb-3 flex h-12 w-12 items-center justify-center rounded-xl transition-transform group-hover:scale-105 ${provider.accentClass ?? 'bg-foreground/5'}`}
              >
                {provider.icon}
              </div>
              <span className="text-sm font-semibold">{provider.name}</span>
              <span className="text-muted-foreground mt-1 text-[10px] sm:inline">
                {provider.subtitle}
              </span>
            </button>
          ))}
        </div>
      </div>

      <div className="mt-auto flex shrink-0 justify-end gap-2 border-t pt-4">
        <Button onClick={onNext} type="button">
          Next <ArrowRight className="ml-2 h-4 w-4" />
        </Button>
      </div>
    </div>
  )
}
