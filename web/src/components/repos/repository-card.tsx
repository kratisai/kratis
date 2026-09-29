import { useNavigate } from '@tanstack/react-router'
import { BookOpen, ExternalLink, GitCommit, MessageSquare, Settings, Trash2 } from 'lucide-react'

import type { RepositoryDto } from '@/types/auth-types'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useStartChat } from '@/hooks/use-start-chat'
import { formatRelativeTime } from '@/lib/format'

import { IngestionStatusBadge } from './ingestion-status-badge'
import {
  AzureDevOpsLogo,
  BitbucketLogo,
  CustomGitLogo,
  GitHubLogo,
  GitLabLogo,
} from './provider-logos'

export function RepositoryCard({
  onEdit,
  onNavigate,
  onRequestDelete,
  repo,
}: {
  onEdit: (repo: RepositoryDto) => void
  onNavigate: (repo: RepositoryDto) => void
  onRequestDelete: (repo: RepositoryDto) => void
  repo: RepositoryDto
}) {
  const navigate = useNavigate()
  const { startChat } = useStartChat()
  const provider = getProviderDetails(repo.url)

  return (
    <Card className="hover:bg-accent/50 min-w-0 transition-colors" onClick={() => onNavigate(repo)}>
      <CardHeader className="pb-2">
        <div className="flex min-w-0 flex-col gap-2 md:flex-row md:items-start md:justify-between">
          <div className="min-w-0 flex-1 space-y-1">
            <div className="flex min-w-0 items-center gap-2">
              <div className="bg-secondary border-border/50 flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border">
                {provider.icon}
              </div>
              <CardTitle className="min-w-0 truncate text-base" title={repo.name}>
                {repo.name}
              </CardTitle>
            </div>
            <CardDescription className="truncate" title={repo.url}>
              {repo.url}
            </CardDescription>
          </div>
          <div className="flex justify-end gap-1 md:shrink-0">
            <Button
              aria-label="View Wiki"
              onClick={(e) => {
                e.stopPropagation()
                void navigate({ params: { repoId: repo.id }, to: '/wiki/$repoId' })
              }}
              size="icon"
              variant="ghost"
            >
              <BookOpen className="h-4 w-4" />
            </Button>
            <Button
              aria-label="Ask Kratis"
              onClick={(e) => {
                e.stopPropagation()
                startChat(`Tell me about repository "${repo.name}".`)
              }}
              size="icon"
              variant="ghost"
            >
              <MessageSquare className="h-4 w-4" />
            </Button>
            <Button
              onClick={(e) => {
                e.stopPropagation()
                window.open(repo.url, '_blank')
              }}
              size="icon"
              variant="ghost"
            >
              <ExternalLink className="h-4 w-4" />
            </Button>
            <Button
              onClick={(e) => {
                e.stopPropagation()
                onEdit(repo)
              }}
              size="icon"
              variant="ghost"
            >
              <Settings className="h-4 w-4" />
            </Button>
            <Button
              className="text-destructive hover:text-destructive"
              onClick={(e) => {
                e.stopPropagation()
                onRequestDelete(repo)
              }}
              size="icon"
              variant="ghost"
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent>
        <div className="text-muted-foreground flex flex-wrap items-center gap-4 text-sm">
          <span className="flex items-center gap-1">
            <span className="bg-primary h-2 w-2 rounded-full" />
            {repo.branch}
          </span>
          <IngestionStatusBadge queuePosition={repo.queuePosition} status={repo.ingestionStatus} />
          {repo.commitHash && (
            <span className="text-muted-foreground flex items-center gap-1 text-xs">
              <GitCommit className="h-3 w-3" />
              <code className="bg-muted rounded px-1 py-0.5 font-mono text-[10px]">
                {repo.commitHash.substring(0, 7)}
              </code>
            </span>
          )}
          {repo.lastIngestedAt && (
            <span className="text-muted-foreground text-xs">
              {formatRelativeTime(repo.lastIngestedAt)}
            </span>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

function getProviderDetails(url: string) {
  const lowercaseUrl = url.toLowerCase()
  if (lowercaseUrl.includes('github.com')) {
    return { icon: <GitHubLogo className="text-foreground" size={20} />, name: 'GitHub' }
  }
  if (lowercaseUrl.includes('gitlab.com')) {
    return { icon: <GitLabLogo size={20} />, name: 'GitLab' }
  }
  if (lowercaseUrl.includes('bitbucket.org')) {
    return { icon: <BitbucketLogo size={20} />, name: 'BitBucket' }
  }
  if (lowercaseUrl.includes('dev.azure.com') || lowercaseUrl.includes('visualstudio.com')) {
    return { icon: <AzureDevOpsLogo size={20} />, name: 'Azure DevOps' }
  }
  return { icon: <CustomGitLogo size={20} />, name: 'Git' }
}
