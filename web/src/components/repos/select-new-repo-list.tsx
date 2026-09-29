import { ArrowLeft, Check, Code, Loader2, Search, ShieldAlert } from 'lucide-react'
import { useMemo, useState } from 'react'

import type { RemoteRepositoryDto } from '@/types/auth-types'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ScrollArea } from '@/components/ui/scroll-area'

import type { ProviderType } from './repository-form-types'

import { IngestImmediatelyCheckbox } from './ingest-immediately-checkbox'

interface RepoListProps {
  error: Error | null
  ingestImmediately: boolean
  isLoading: boolean
  onBack: () => void
  onBatchOnboard: (selectedRepoNames: Set<string>) => void
  onboardedRepoNames: Set<string>
  onEnterManually: () => void
  onIngestImmediatelyChange: (checked: boolean) => void
  onRefetchRepos?: () => void
  repos: RemoteRepositoryDto[]
  selectedProvider: ProviderType
  totalRepoCount: number
}

export function SelectNewRepoList({
  error,
  ingestImmediately,
  isLoading,
  onBack,
  onBatchOnboard,
  onboardedRepoNames,
  onEnterManually,
  onIngestImmediatelyChange,
  onRefetchRepos,
  repos,
  selectedProvider,
  totalRepoCount,
}: RepoListProps) {
  // Local UI state - search, selection, and GitLab group are only used within this component
  const [repoSearch, setRepoSearch] = useState('')
  const [selectedRepoNames, setSelectedRepoNames] = useState<Set<string>>(new Set())

  const handleToggleRepo = (repoName: string) => {
    setSelectedRepoNames((prev) => {
      const next = new Set(prev)
      if (next.has(repoName)) {
        next.delete(repoName)
      } else {
        next.add(repoName)
      }
      return next
    })
  }

  const handleSelectAll = () => {
    setSelectedRepoNames((prev) => {
      if (prev.size === filteredRepos.length) {
        return new Set()
      }
      return new Set(filteredRepos.map((r) => r.name))
    })
  }

  const handleBatchOnboard = () => {
    if (selectedRepoNames.size === 0) return
    onBatchOnboard(selectedRepoNames)
  }

  const filteredRepos = useMemo(
    () =>
      repos.filter(
        (r) =>
          !onboardedRepoNames.has(r.name) &&
          r.name.toLowerCase().includes(repoSearch.toLowerCase()),
      ),
    [repos, onboardedRepoNames, repoSearch],
  )

  return (
    <div className="flex h-full min-h-0 flex-1 flex-col py-2">
      <div className="mb-3 flex shrink-0 items-center gap-3">
        <div className="relative min-w-0 flex-1">
          <Search className="text-muted-foreground absolute top-3 left-3 h-4 w-4" />
          <Input
            className="pl-9"
            onChange={(e) => setRepoSearch(e.target.value)}
            placeholder="Search remote repositories..."
            value={repoSearch}
          />
        </div>
        {!isLoading && !error && filteredRepos.length > 0 && (
          <IngestImmediatelyCheckbox
            checked={ingestImmediately}
            compact
            onChange={onIngestImmediatelyChange}
          />
        )}
      </div>

      {isLoading ? (
        <div className="flex flex-1 flex-col items-center justify-center space-y-2 py-12">
          <Loader2 className="text-primary h-8 w-8 animate-spin" />
          <p className="text-muted-foreground animate-pulse text-xs">
            Contacting {selectedProvider} host securely...
          </p>
        </div>
      ) : error ? (
        <div className="border-destructive/20 bg-destructive/5 flex flex-1 flex-col justify-center rounded-lg border p-4 text-center">
          <ShieldAlert className="text-destructive mx-auto mb-2 h-8 w-8" />
          <p className="text-destructive text-sm font-semibold">Integration Sync Failed</p>
          <p className="text-muted-foreground mt-1 text-xs">
            {error.message || 'Ensure token/app details are correct'}
          </p>
          {onRefetchRepos && (
            <Button
              className="mt-3 self-center text-xs"
              onClick={onRefetchRepos}
              size="sm"
              variant="outline"
            >
              Retry Connection
            </Button>
          )}
        </div>
      ) : filteredRepos.length === 0 ? (
        <div className="bg-accent/5 flex flex-1 flex-col justify-center rounded-lg border border-dashed p-8 text-center">
          <Code className="text-muted-foreground mx-auto mb-2 h-8 w-8 opacity-50" />
          <p className="text-sm font-medium">
            {totalRepoCount === 0
              ? 'No repositories available for this credential'
              : 'No new repositories found'}
          </p>
          <p className="text-muted-foreground mt-1 text-xs">
            {totalRepoCount === 0
              ? 'Check credentials permissions or verify App installations.'
              : 'All available repos have been onboarded, or try a different credential.'}
          </p>
        </div>
      ) : (
        <div className="mb-3 flex min-h-0 flex-1 flex-col gap-2">
          <div className="flex shrink-0 items-center justify-between">
            <button
              className="text-muted-foreground hover:text-foreground flex items-center gap-1.5 text-xs font-medium"
              onClick={handleSelectAll}
              type="button"
            >
              <Check className="h-3.5 w-3.5" />
              {selectedRepoNames.size === filteredRepos.length ? 'Deselect All' : 'Select All'}
            </button>
            {selectedRepoNames.size > 0 && (
              <span className="text-muted-foreground text-xs">
                {selectedRepoNames.size} selected
              </span>
            )}
          </div>

          <ScrollArea className="bg-accent/25 min-h-[240px] flex-1 rounded-lg border p-2">
            <div className="space-y-1">
              {filteredRepos.map((remote) => {
                const isSelected = selectedRepoNames.has(remote.name)
                return (
                  <button
                    className={`flex w-full min-w-0 items-center justify-between gap-4 rounded-lg border p-2.5 text-left text-sm transition-colors ${
                      isSelected
                        ? 'border-primary bg-primary/5 text-primary'
                        : 'bg-card hover:bg-primary/5 hover:text-primary hover:border-primary/20 border-transparent'
                    }`}
                    key={remote.name}
                    onClick={() => handleToggleRepo(remote.name)}
                    type="button"
                  >
                    <div className="flex min-w-0 flex-1 items-center gap-2">
                      <div
                        className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border ${
                          isSelected ? 'border-primary bg-primary' : 'border-border bg-card'
                        }`}
                      >
                        {isSelected && <Check className="text-primary-foreground h-3 w-3" />}
                      </div>
                      <div className="min-w-0 flex-1">
                        <span className="block truncate text-xs font-semibold sm:text-sm">
                          {remote.name}
                        </span>
                        <span
                          className="text-muted-foreground mt-0.5 block truncate text-[10px] sm:text-xs"
                          title={remote.cloneUrl || remote.sshUrl}
                        >
                          {condenseRepoUrl(remote.cloneUrl || remote.sshUrl)}
                        </span>
                      </div>
                    </div>
                    <Badge
                      className="shrink-0 scale-95 py-0 font-mono text-[10px] font-normal opacity-80"
                      variant="outline"
                    >
                      {remote.defaultBranch || 'main'}
                    </Badge>
                  </button>
                )
              })}
            </div>
          </ScrollArea>
        </div>
      )}

      <div className="mt-auto flex shrink-0 items-center justify-between gap-2 border-t pt-4">
        <Button onClick={onBack} type="button" variant="outline">
          <ArrowLeft className="mr-2 h-4 w-4" /> Back
        </Button>
        <div className="flex items-center gap-2">
          <button
            className="text-primary self-center text-xs font-medium hover:underline"
            onClick={onEnterManually}
            type="button"
          >
            Enter settings manually
          </button>
          {selectedRepoNames.size > 0 && (
            <Button onClick={handleBatchOnboard} type="button">
              Onboard {selectedRepoNames.size} {selectedRepoNames.size === 1 ? 'Repo' : 'Repos'}
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}

function condenseRepoUrl(url: string): string {
  if (!url) return ''
  let clean = url.trim()
  if (clean.endsWith('.git')) {
    clean = clean.slice(0, -4)
  }
  const sshMatch = clean.match(/^[^@]+@[^:]+:(.+)$/)
  if (sshMatch) {
    return sshMatch[1]
  }
  try {
    const parsed = new URL(clean)
    return parsed.pathname.startsWith('/') ? parsed.pathname.slice(1) : parsed.pathname
  } catch {
    const doubleSlashIdx = clean.indexOf('//')
    if (doubleSlashIdx !== -1) {
      const pathPart = clean.slice(doubleSlashIdx + 2)
      const slashIdx = pathPart.indexOf('/')
      if (slashIdx !== -1) {
        return pathPart.slice(slashIdx + 1)
      }
    }
    return clean
  }
}
