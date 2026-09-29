import { Check, ChevronDown, FolderGit2 } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useRepositories } from '@/hooks/use-repositories'
import { cn } from '@/lib/utils'

interface RepoSelectSlotProps {
  disabled?: boolean
  onChange: (value: string) => void
  placeholder?: string
  value?: string
}

export function RepoSelectSlot({
  disabled = false,
  onChange,
  placeholder = 'select repository',
  value = '',
}: RepoSelectSlotProps) {
  const { data: repositories, isLoading } = useRepositories()

  const repoList = repositories ?? []
  const hasRepositories = repoList.length > 0
  const selectedRepo = repoList.find((r) => r.name === value || r.id === value)
  const displayText = selectedRepo?.name || value || placeholder

  if (isLoading) {
    return (
      <Badge
        className="text-muted-foreground inline-flex h-7 animate-pulse items-center gap-1 px-2.5 text-xs font-normal"
        variant="secondary"
      >
        <FolderGit2 className="h-3.5 w-3.5" />
        <span>Loading repos...</span>
      </Badge>
    )
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild disabled={disabled}>
        <button
          className={cn(
            'group inline-flex h-7 items-center gap-1.5 rounded-md border px-2.5 py-0.5 text-xs font-medium transition-all',
            'focus-visible:ring-ring focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none',
            value
              ? 'border-primary/40 bg-primary/10 text-primary hover:bg-primary/20'
              : 'border-muted-foreground/50 bg-muted/40 text-muted-foreground hover:border-foreground/50 hover:bg-muted/70 border-dashed',
            disabled && 'cursor-not-allowed opacity-50',
          )}
          type="button"
        >
          <FolderGit2 className="h-3.5 w-3.5 shrink-0" />
          <span className={cn('max-w-[160px] truncate', !value && 'font-normal italic')}>
            {displayText}
          </span>
          <ChevronDown className="h-3 w-3 opacity-60 transition-transform group-hover:opacity-100" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-56">
        <DropdownMenuLabel className="text-muted-foreground text-xs font-semibold">
          Repositories
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {!hasRepositories ? (
          <div className="text-muted-foreground px-2 py-3 text-center text-xs">
            No ingested repositories found.
          </div>
        ) : (
          repoList.map((repo) => {
            const isSelected = value === repo.name || value === repo.id
            return (
              <DropdownMenuItem
                className={cn(
                  'flex cursor-pointer items-center justify-between text-xs',
                  isSelected && 'bg-accent',
                )}
                key={repo.id}
                onSelect={() => onChange(repo.name)}
              >
                <div className="flex items-center gap-2 truncate">
                  <FolderGit2 className="text-muted-foreground h-3.5 w-3.5 shrink-0" />
                  <span className="truncate">{repo.name}</span>
                </div>
                {isSelected && <Check className="text-primary h-3.5 w-3.5 shrink-0" />}
              </DropdownMenuItem>
            )
          })
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
