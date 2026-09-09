import { Link, useMatchRoute, useParams, useRouterState } from '@tanstack/react-router'
import {
  Archive,
  BarChart3,
  BookOpen,
  CircleX,
  GitBranch,
  History,
  MessageSquare,
  RotateCcw,
  Settings,
  User,
  Users,
} from 'lucide-react'
import { useState } from 'react'

import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Separator } from '@/components/ui/separator'
import { useArchiveChat, useChats, useUnarchiveChat } from '@/hooks/use-chats'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/store/auth-store'

import { WikiSidebarSection } from './wiki-sidebar-section'

const navItems = [
  { icon: MessageSquare, label: 'Ask Kratis', to: '/ask' as const },
  { icon: GitBranch, label: 'Repos', to: '/repos' as const },
  { icon: BarChart3, label: 'Usage', to: '/usage' as const },
]

interface SidebarNavProps {
  className?: string
  onClose?: () => void
  showLabels?: boolean
}

export function SidebarNav({ className, onClose, showLabels = true }: SidebarNavProps) {
  const currentTeamId = useAuthStore((state) => state.currentTeamId)
  const [filter, setFilter] = useState<'all' | 'mine'>('mine')
  const [status, setStatus] = useState<'active' | 'archived'>('active')
  const matchRoute = useMatchRoute()
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-assertion -- strict:false params need narrowing for wiki repoId
  const wikiParams = useParams({ strict: false }) as { repoId?: string }
  const wikiRepoId = wikiParams.repoId
  const onWikiRoute = useRouterState({
    select: (state) => state.location.pathname.startsWith('/wiki/'),
  })

  const { data: chats = [] } = useChats(currentTeamId, filter, status)
  const archiveChat = useArchiveChat(currentTeamId)
  const unarchiveChat = useUnarchiveChat(currentTeamId)

  const handleChatClick = () => {
    onClose?.()
  }

  const toggleFilter = () => {
    setFilter((prev) => (prev === 'mine' ? 'all' : 'mine'))
  }

  return (
    <div className={cn('flex min-h-0 flex-1 flex-col', className)}>
      {!showLabels && onWikiRoute && wikiRepoId && (
        <div className="p-2">
          <Link
            aria-label="Wiki"
            className="bg-secondary text-secondary-foreground flex w-full items-center justify-center rounded-md px-2 py-2 text-sm font-medium transition-colors"
            onClick={onClose}
            params={{ repoId: wikiRepoId }}
            title="Wiki"
            to="/wiki/$repoId"
          >
            <BookOpen className="h-4 w-4 shrink-0" />
          </Link>
        </div>
      )}

      {showLabels && onWikiRoute && (
        <>
          <div className="overflow-hidden p-2">
            <WikiSidebarSection onClose={onClose} />
          </div>
          <Separator className="bg-sidebar-border" />
        </>
      )}

      <nav className="flex min-h-0 flex-1 flex-col space-y-1 overflow-hidden p-2">
        {navItems.map((item) => {
          const isActive = !!matchRoute({ fuzzy: true, to: item.to })
          return (
            <Link
              className={cn(
                'flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
                isActive
                  ? 'bg-secondary text-secondary-foreground'
                  : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground',
                !showLabels && 'justify-center px-2',
              )}
              key={item.to}
              onClick={onClose}
              to={item.to}
            >
              <item.icon className="h-4 w-4 shrink-0" />
              {showLabels && <span>{item.label}</span>}
            </Link>
          )
        })}

        {showLabels && (
          <>
            <Separator className="bg-sidebar-border my-4" />
            <div className="text-muted-foreground flex items-center justify-between px-2 py-1 text-xs">
              <div className="flex items-center gap-2">
                <History className="h-3 w-3" />
                <span>{status === 'active' ? 'Topics' : 'Archived Chats'}</span>
              </div>
              <div className="flex items-center gap-1">
                <Button
                  aria-label={
                    status === 'active' ? 'Switch to archived chats' : 'Switch to active chats'
                  }
                  className={cn(
                    'h-5 w-5 p-0',
                    status === 'archived' && 'bg-secondary text-secondary-foreground',
                  )}
                  onClick={() => setStatus((prev) => (prev === 'active' ? 'archived' : 'active'))}
                  size="icon"
                  title={status === 'active' ? 'View Archived Chats' : 'View Active Chats'}
                  variant="ghost"
                >
                  <Archive className="h-3 w-3" />
                </Button>
                {status === 'active' && (
                  <Button
                    aria-label="Toggle topic filter"
                    className="h-5 w-5 p-0"
                    onClick={toggleFilter}
                    size="icon"
                    variant="ghost"
                  >
                    {filter === 'mine' ? (
                      <User className="h-3 w-3" />
                    ) : (
                      <Users className="h-3 w-3" />
                    )}
                  </Button>
                )}
              </div>
            </div>
            <ScrollArea className="min-h-0 flex-1">
              {[...chats]
                .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
                .slice(0, 20)
                .map((chat) => {
                  const isActive = !!matchRoute({
                    fuzzy: true,
                    params: { id: chat.id },
                    to: '/chats/$id',
                  })
                  return (
                    <div
                      className={cn(
                        'group relative flex w-full items-start rounded-md px-3 py-2 text-sm font-medium transition-colors',
                        isActive
                          ? 'bg-secondary text-secondary-foreground'
                          : 'text-muted-foreground hover:bg-sidebar-accent hover:text-foreground',
                      )}
                      key={chat.id}
                    >
                      <Link
                        className="min-w-0 flex-1 py-0.5 leading-snug break-words whitespace-normal"
                        onClick={handleChatClick}
                        params={{ id: chat.id }}
                        to="/chats/$id"
                      >
                        {chat.title}
                      </Link>
                      <Button
                        aria-label={status === 'active' ? 'Archive chat' : 'Unarchive chat'}
                        className={cn(
                          'pointer-events-none absolute top-1.5 right-1.5 h-6 w-6 shrink-0 opacity-0 transition-opacity group-hover:pointer-events-auto group-hover:opacity-100 focus-visible:pointer-events-auto focus-visible:opacity-100',
                          isActive
                            ? 'text-secondary-foreground/70 hover:bg-secondary-foreground/10 hover:text-secondary-foreground'
                            : 'text-muted-foreground hover:bg-sidebar-accent hover:text-foreground',
                        )}
                        onClick={(e) => {
                          e.preventDefault()
                          e.stopPropagation()
                          if (status === 'active') {
                            archiveChat.mutate(chat.id)
                          } else {
                            unarchiveChat.mutate(chat.id)
                          }
                        }}
                        size="icon"
                        title={status === 'active' ? 'Archive chat' : 'Unarchive chat'}
                        variant="ghost"
                      >
                        {status === 'active' ? (
                          <CircleX className="h-3.5 w-3.5" color={'red'} />
                        ) : (
                          <RotateCcw className="h-3.5 w-3.5" />
                        )}
                      </Button>
                    </div>
                  )
                })}
            </ScrollArea>
          </>
        )}
      </nav>

      {/* Footer */}
      <div className="border-sidebar-border border-t p-2">
        <Link
          className={cn(
            'flex w-full items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
            matchRoute({ to: '/settings' })
              ? 'bg-secondary text-secondary-foreground'
              : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground',
            !showLabels && 'justify-center px-2',
          )}
          onClick={onClose}
          to="/settings"
        >
          <Settings className="h-4 w-4 shrink-0" />
          {showLabels && <span>Settings</span>}
        </Link>
      </div>
    </div>
  )
}
